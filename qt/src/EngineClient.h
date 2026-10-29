#pragma once
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariant>
#include <QVariantMap>
#include <functional>

#include "SseParser.h"

class EngineClient : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged)
public:
    // `controlFile` overrides the discovered control.json path (tests).
    explicit EngineClient(const QString &controlFile = QString(), QObject *parent = nullptr);

    bool connected() const { return m_connected; }
    int connectAttempts() const { return m_attempts; }

    using Done = std::function<void(const QVariant &result, const QString &error)>;
    Q_INVOKABLE void call(const QString &method, const QVariantList &args = {});
    // Same, and `done` gets this call's own reply (callFinished carries no args to match on).
    void call(const QString &method, const QVariantList &args, Done done);

signals:
    void connectedChanged(bool connected);
    void event(const QVariantMap &event);
    void callFinished(const QString &method, const QVariant &result, const QString &error);

private:
    struct Control { int port = 0; QString token; };
    bool readControl(Control &out) const;
    QNetworkRequest request(const Control &c, const QString &path) const;
    void connectNow();
    void setConnected(bool on);
    void scheduleReconnect();

    QString m_controlFile;
    QNetworkAccessManager m_net;
    QTimer m_retry;
    QPointer<QNetworkReply> m_stream;
    SseParser m_parser;
    bool m_connected = false;
    int m_attempts = 0;
    int m_backoff = 1000;
};
