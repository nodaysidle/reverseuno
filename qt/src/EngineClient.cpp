#include "EngineClient.h"

#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>
#include <QtGlobal>
#include <chrono>

namespace {
QString defaultControlFile()
{
    const QString env = qEnvironmentVariable("REVERSEUNO_CONTROL_FILE");
    if (!env.isEmpty())
        return env;
    QString base = qEnvironmentVariable("XDG_CONFIG_HOME");
    if (base.isEmpty())
        base = QDir::homePath() + "/.config";
    return base + "/reverseuno/control.json";
}
}

EngineClient::EngineClient(const QString &controlFile, QObject *parent)
    : QObject(parent), m_controlFile(controlFile.isEmpty() ? defaultControlFile() : controlFile)
{
    m_retry.setSingleShot(true);
    connect(&m_retry, &QTimer::timeout, this, &EngineClient::connectNow);
    QTimer::singleShot(0, this, &EngineClient::connectNow);
}

bool EngineClient::readControl(Control &out) const
{
    QFile f(m_controlFile);
    if (!f.open(QIODevice::ReadOnly))
        return false;
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    out.port = o.value("port").toInt();
    out.token = o.value("token").toString();
    return out.port > 0 && !out.token.isEmpty();
}

// No Origin header is ever set: the engine rejects requests that carry one.
QNetworkRequest EngineClient::request(const Control &c, const QString &path) const
{
    QNetworkRequest r(QUrl(QStringLiteral("http://127.0.0.1:%1%2").arg(c.port).arg(path)));
    r.setRawHeader("Authorization", "Bearer " + c.token.toUtf8());
    return r;
}

void EngineClient::setConnected(bool on)
{
    if (m_connected == on)
        return;
    m_connected = on;
    emit connectedChanged(on);
}

void EngineClient::scheduleReconnect()
{
    m_retry.start(m_backoff);
    m_backoff = qMin(m_backoff * 2, 5000);
}

void EngineClient::connectNow()
{
    ++m_attempts;
    Control c;
    if (!readControl(c)) {
        setConnected(false);
        scheduleReconnect();
        return;
    }
    m_parser = SseParser();
    QNetworkRequest r = request(c, "/events");
    r.setTransferTimeout(std::chrono::milliseconds(0)); // pings arrive every 15 s; stream is long-lived
    QNetworkReply *reply = m_net.get(r);
    m_stream = reply;
    connect(reply, &QNetworkReply::metaDataChanged, this, [this, reply] {
        if (reply != m_stream || m_connected)
            return;
        if (reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() != 200)
            return;
        m_backoff = 1000;
        setConnected(true);
        call("bootstrap");
    });
    connect(reply, &QNetworkReply::readyRead, this, [this, reply] {
        const QByteArray chunk = reply->readAll();
        if (reply != m_stream)
            return;
        for (const QByteArray &payload : m_parser.feed(chunk)) {
            const QJsonDocument d = QJsonDocument::fromJson(payload);
            if (d.isObject())
                emit event(d.object().toVariantMap());
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply] {
        const bool current = reply == m_stream;
        reply->deleteLater();
        if (!current)
            return;
        setConnected(false);
        scheduleReconnect();
    });
}

void EngineClient::call(const QString &method, const QVariantList &args)
{
    call(method, args, Done());
}

void EngineClient::call(const QString &method, const QVariantList &args, Done done)
{
    Control c;
    if (!readControl(c)) {
        const QString error = QStringLiteral("engine not running");
        if (done) done(QVariant(), error);
        emit callFinished(method, QVariant(), error);
        return;
    }
    QNetworkRequest r = request(c, "/rpc/" + method);
    r.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    const QByteArray body = QJsonDocument(QJsonObject{{"args", QJsonArray::fromVariantList(args)}}).toJson(QJsonDocument::Compact);
    QNetworkReply *reply = m_net.post(r, body);
    connect(reply, &QNetworkReply::finished, this, [this, reply, method, done] {
        reply->deleteLater();
        const QJsonObject o = QJsonDocument::fromJson(reply->readAll()).object();
        const bool ok = o.value("ok").toBool();
        const QVariant result = ok ? o.value("result").toVariant() : QVariant();
        const QString error = ok ? QString() : o.value("error").toString(reply->errorString());
        if (done) done(result, error);
        emit callFinished(method, result, error);
    });
}
