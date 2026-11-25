#pragma once

#include <QByteArray>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QUrl>
#include <QWebSocket>

// Speech to text with Deepgram, model nova-3. Two paths share the result type:
// live streaming over a WebSocket while the key is held, and one batch POST of
// the whole clip (the fallback, and what `reverseuno transcribe` uses).

struct SttResult {
    bool ok = false;
    QString text;
    QString language;
    QString error;
};

namespace SttConfig {
constexpr int kSampleRate = 16000;
constexpr int kBytesPerMs = kSampleRate * 2 / 1000; // mono int16
QString model();
QString batchUrl();  // REVERSEUNO_STT_URL overrides the Deepgram endpoint (tests)
QString streamUrl(); // REVERSEUNO_STT_WS_URL likewise
QUrl buildBatchUrl(const QString &base, const QString &language);
QUrl buildStreamUrl(const QString &base, const QString &language);
QByteArray wav(const QByteArray &pcm, int sampleRate = kSampleRate);
}

// One POST of a complete WAV.
class Stt : public QObject {
    Q_OBJECT
public:
    explicit Stt(const QString &baseUrl = {}, QObject *parent = nullptr);
    void transcribe(const QByteArray &wav, const QString &key, const QString &language);
    void abort(); // finished() is not emitted for an aborted request
    bool busy() const { return !m_reply.isNull(); }

signals:
    void finished(const SttResult &result);

private:
    QString m_base;
    QNetworkAccessManager m_network;
    QPointer<QNetworkReply> m_reply;
};

// A live session: frames are queued until the socket opens, then flushed.
class SttStream : public QObject {
    Q_OBJECT
public:
    explicit SttStream(const QString &baseUrl = {}, QObject *parent = nullptr);
    void start(const QString &key, const QString &language);
    void sendPcm(const QByteArray &pcm);
    // Sends CloseStream and waits for Metadata, at most 2.5 s; finished() always follows.
    void finish();
    void abort(); // finished() is not emitted after this
    // False once the socket failed or closed early; the caller then uses the batch path.
    bool usable() const { return !m_failed; }
    QString text() const;

signals:
    void interim(const QString &text);
    void finished(const SttResult &result);

private:
    void fail();
    void complete();
    void onMessage(const QString &message);

    QString m_base;
    QWebSocket m_socket;
    QTimer m_keepAlive, m_connectTimeout, m_finishTimeout;
    QList<QByteArray> m_pending;
    qsizetype m_pendingBytes = 0;
    QStringList m_finals;
    QString m_interim, m_language;
    bool m_open = false, m_failed = false, m_finishing = false, m_done = false;
};
