#include "stt.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QUrlQuery>
#include <QtEndian>

namespace {
constexpr qsizetype kMaxPendingBytes = 20 * 1024 * 1024;
}

namespace SttConfig {

QString model() { return QStringLiteral("nova-3"); }

QString batchUrl() {
    const QString url = qEnvironmentVariable("REVERSEUNO_STT_URL");
    return url.isEmpty() ? QStringLiteral("https://api.deepgram.com/v1/listen") : url;
}

QString streamUrl() {
    const QString url = qEnvironmentVariable("REVERSEUNO_STT_WS_URL");
    return url.isEmpty() ? QStringLiteral("wss://api.deepgram.com/v1/listen") : url;
}

QUrl buildBatchUrl(const QString &base, const QString &language) {
    QUrl url(base);
    QUrlQuery query;
    query.addQueryItem("model", model());
    query.addQueryItem("smart_format", "true");
    query.addQueryItem("punctuate", "true");
    // Language detection only exists for batch requests, not for the stream.
    if (language.isEmpty() || language == "auto")
        query.addQueryItem("detect_language", "true");
    else
        query.addQueryItem("language", language);
    url.setQuery(query);
    return url;
}

QUrl buildStreamUrl(const QString &base, const QString &language) {
    QUrl url(base);
    QUrlQuery query;
    query.addQueryItem("model", model());
    query.addQueryItem("encoding", "linear16");
    query.addQueryItem("sample_rate", QString::number(kSampleRate));
    query.addQueryItem("channels", "1");
    query.addQueryItem("interim_results", "true");
    query.addQueryItem("smart_format", "true");
    query.addQueryItem("punctuate", "true");
    query.addQueryItem("endpointing", "300");
    query.addQueryItem("utterance_end_ms", "1000");
    query.addQueryItem("language", language.isEmpty() || language == "auto" ? QStringLiteral("multi") : language);
    url.setQuery(query);
    return url;
}

QByteArray wav(const QByteArray &pcm, int sampleRate) {
    QByteArray header(44, '\0');
    auto put32 = [&](int at, quint32 v) { qToLittleEndian(v, reinterpret_cast<uchar *>(header.data()) + at); };
    auto put16 = [&](int at, quint16 v) { qToLittleEndian(v, reinterpret_cast<uchar *>(header.data()) + at); };
    header.replace(0, 4, "RIFF");
    put32(4, quint32(36 + pcm.size()));
    header.replace(8, 8, "WAVEfmt ");
    put32(16, 16);
    put16(20, 1); // PCM
    put16(22, 1); // mono
    put32(24, quint32(sampleRate));
    put32(28, quint32(sampleRate * 2));
    put16(32, 2);
    put16(34, 16);
    header.replace(36, 4, "data");
    put32(40, quint32(pcm.size()));
    return header + pcm;
}

} // namespace SttConfig

// ---- batch ---------------------------------------------------------------

Stt::Stt(const QString &baseUrl, QObject *parent)
    : QObject(parent), m_base(baseUrl.isEmpty() ? SttConfig::batchUrl() : baseUrl) {}

void Stt::abort() {
    if (!m_reply)
        return;
    m_reply->disconnect(this);
    m_reply->abort();
    m_reply->deleteLater();
    m_reply.clear();
}

void Stt::transcribe(const QByteArray &wav, const QString &key, const QString &language) {
    abort();
    QNetworkRequest request(SttConfig::buildBatchUrl(m_base, language));
    request.setRawHeader("Authorization", "Token " + key.toUtf8());
    request.setHeader(QNetworkRequest::ContentTypeHeader, "audio/wav");
    request.setTransferTimeout(30000);
    QNetworkReply *reply = m_network.post(request, wav);
    m_reply = reply;
    connect(reply, &QNetworkReply::finished, this, [this, reply, language] {
        m_reply.clear();
        reply->deleteLater();
        SttResult result;
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray body = reply->readAll();
        if (status == 0) {
            result.error = tr("Network error: %1").arg(reply->errorString());
        } else if (status == 401 || status == 403) {
            result.error = tr("Deepgram rejected the API key (HTTP %1)").arg(status);
        } else if (status < 200 || status >= 300) {
            const QJsonObject object = QJsonDocument::fromJson(body).object();
            QString detail = object.value("err_msg").toString(object.value("message").toString());
            if (detail.isEmpty())
                detail = QString::fromUtf8(body.left(200)).simplified();
            result.error = tr("Deepgram error (HTTP %1): %2").arg(status).arg(detail);
        } else {
            const QJsonObject channel = QJsonDocument::fromJson(body).object()
                                            .value("results").toObject().value("channels").toArray().first().toObject();
            result.ok = true;
            result.text = channel.value("alternatives").toArray().first().toObject().value("transcript").toString().trimmed();
            result.language = channel.value("detected_language").toString(language == "auto" ? QString() : language);
        }
        emit finished(result);
    });
}

// ---- streaming -----------------------------------------------------------

SttStream::SttStream(const QString &baseUrl, QObject *parent)
    : QObject(parent), m_base(baseUrl.isEmpty() ? SttConfig::streamUrl() : baseUrl) {
    m_keepAlive.setInterval(8000);
    m_connectTimeout.setSingleShot(true);
    m_connectTimeout.setInterval(6000);
    m_finishTimeout.setSingleShot(true);
    m_finishTimeout.setInterval(2500);
    connect(&m_keepAlive, &QTimer::timeout, this, [this] {
        if (m_open)
            m_socket.sendTextMessage(R"({"type":"KeepAlive"})");
    });
    connect(&m_connectTimeout, &QTimer::timeout, this, &SttStream::fail);
    connect(&m_finishTimeout, &QTimer::timeout, this, &SttStream::complete);
    connect(&m_socket, &QWebSocket::connected, this, [this] {
        m_open = true;
        m_connectTimeout.stop();
        m_keepAlive.start();
        // Audio spoken while the socket was connecting goes first, in order.
        for (const QByteArray &frame : std::as_const(m_pending))
            m_socket.sendBinaryMessage(frame);
        m_pending.clear();
        m_pendingBytes = 0;
    });
    connect(&m_socket, &QWebSocket::textMessageReceived, this, &SttStream::onMessage);
    connect(&m_socket, &QWebSocket::errorOccurred, this, &SttStream::fail);
    connect(&m_socket, &QWebSocket::disconnected, this, [this] {
        m_open = false;
        fail();
    });
}

void SttStream::start(const QString &key, const QString &language) {
    m_language = language;
    QNetworkRequest request(SttConfig::buildStreamUrl(m_base, language));
    request.setRawHeader("Authorization", "Token " + key.toUtf8());
    m_connectTimeout.start();
    m_socket.open(request);
}

void SttStream::sendPcm(const QByteArray &pcm) {
    if (m_failed || m_done || m_finishing)
        return;
    if (m_open) {
        m_socket.sendBinaryMessage(pcm);
    } else if (m_pendingBytes < kMaxPendingBytes) {
        m_pending.append(pcm);
        m_pendingBytes += pcm.size();
    }
}

QString SttStream::text() const {
    QStringList parts = m_finals;
    if (!m_interim.isEmpty())
        parts << m_interim;
    return parts.join(' ').trimmed();
}

void SttStream::onMessage(const QString &message) {
    const QJsonObject object = QJsonDocument::fromJson(message.toUtf8()).object();
    const QString type = object.value("type").toString();
    if (type == "Results") {
        const QString transcript = object.value("channel").toObject().value("alternatives").toArray()
                                       .first().toObject().value("transcript").toString().trimmed();
        if (object.value("is_final").toBool()) {
            if (!transcript.isEmpty())
                m_finals << transcript;
            m_interim.clear();
        } else if (!transcript.isEmpty()) {
            m_interim = transcript;
        }
        emit interim(text());
    } else if (type == "Metadata") {
        if (m_finishing)
            complete();
    } else if (type == "Error" || type == "error") {
        fail();
    }
}

void SttStream::finish() {
    if (m_done || m_finishing)
        return;
    m_finishing = true;
    // Not connected yet (or already broken): there is nothing to wait for.
    if (!m_open) {
        complete();
        return;
    }
    m_socket.sendTextMessage(R"({"type":"CloseStream"})");
    m_finishTimeout.start();
}

// A failure while recording only marks the stream unusable (the batch path
// takes over at stop); a failure while finishing ends the wait.
void SttStream::fail() {
    if (m_done)
        return;
    m_failed = true;
    m_connectTimeout.stop();
    m_keepAlive.stop();
    if (m_finishing)
        complete();
}

void SttStream::complete() {
    if (m_done)
        return;
    m_done = true;
    m_connectTimeout.stop();
    m_keepAlive.stop();
    m_finishTimeout.stop();
    m_socket.disconnect(this);
    m_socket.abort();
    SttResult result;
    result.text = text();
    result.ok = !result.text.isEmpty();
    result.language = m_language;
    emit finished(result);
}

void SttStream::abort() {
    m_done = true;
    m_connectTimeout.stop();
    m_keepAlive.stop();
    m_finishTimeout.stop();
    m_socket.disconnect(this);
    m_socket.abort();
}
