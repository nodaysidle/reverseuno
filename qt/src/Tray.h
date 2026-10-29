#pragma once
#include <QObject>
#include <QString>

class EngineClient;
class HubController;
class QQmlEngine;

// What the tray menu shows and does. Tray.qml (Qt Labs Platform SystemTrayIcon) draws it.
class TrayController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString stateLabel READ stateLabel NOTIFY changed)
    Q_PROPERTY(QString toggleLabel READ toggleLabel NOTIFY changed)
    Q_PROPERTY(bool toggleEnabled READ toggleEnabled NOTIFY changed)
public:
    TrayController(EngineClient *client, HubController *hub, QObject *parent = nullptr);

    // stream state → idle | listening | working
    static QString bucketFor(const QString &state);

    QString stateLabel() const;
    QString toggleLabel() const;
    bool toggleEnabled() const;

    Q_INVOKABLE void toggle();
    Q_INVOKABLE void open();
    // Asks the engine to quit, then quitRequested (the engine also stops the supervisor).
    Q_INVOKABLE void quit();

signals:
    void changed();
    void quitRequested();

private:
    EngineClient *m_client;
    HubController *m_hub;
    bool m_connected = false;
    QString m_bucket = "idle";
};

// Creates Tray.qml. A load error is reported; the app runs on without a tray.
void loadTray(QQmlEngine &qml, const QString &file);
