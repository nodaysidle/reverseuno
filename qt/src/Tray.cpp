#include "Tray.h"

#include <QQmlComponent>
#include <QQmlEngine>
#include <QUrl>

#include "EngineClient.h"
#include "HubController.h"

TrayController::TrayController(EngineClient *client, HubController *hub, QObject *parent)
    : QObject(parent), m_client(client), m_hub(hub), m_connected(client->connected())
{
    connect(client, &EngineClient::event, this, [this](const QVariantMap &e) {
        if (e.value("type").toString() != "state") return;
        const QString bucket = bucketFor(e.value("state").toMap().value("state").toString());
        if (bucket == m_bucket) return;
        m_bucket = bucket;
        emit changed();
    });
    connect(client, &EngineClient::connectedChanged, this, [this](bool on) {
        m_connected = on;
        m_bucket = "idle"; // the connect snapshot brings the real state
        emit changed();
    });
}

QString TrayController::bucketFor(const QString &state)
{
    if (state == "listening") return "listening";
    if (state == "transcribing" || state == "formatting" || state == "inserting") return "working";
    return "idle";
}

QString TrayController::stateLabel() const
{
    if (!m_connected) return "reverseuno is not running";
    if (m_bucket == "listening") return QStringLiteral("Listening…");
    if (m_bucket == "working") return QStringLiteral("Working…");
    return "Idle";
}

QString TrayController::toggleLabel() const
{
    return m_bucket == "listening" ? "Stop and paste" : "Start dictation";
}

bool TrayController::toggleEnabled() const
{
    return m_connected && m_bucket != "working";
}

// Mouse-started sessions have no key release, so this item starts and stops.
void TrayController::toggle()
{
    m_client->call("dictationToggle");
}

void TrayController::open()
{
    m_hub->show("history");
}

void TrayController::quit()
{
    // Exit only once the request is answered (or failed), or it may never leave.
    QPointer<TrayController> self(this);
    m_client->call("quit", {}, [self](const QVariant &, const QString &) {
        if (self) emit self->quitRequested();
    });
}

// On Wayland Qt always makes a D-Bus StatusNotifierItem and (re)registers it whenever a
// watcher (Waybar) appears, so a late or restarted tray host is handled by Qt itself.
// It registers under its unique bus name (":1.N"): `busctl list` shows no StatusNotifierItem
// name for it; the watcher's RegisteredStatusNotifierItems property lists it.
// No tray host at all: the icon just never shows; nothing else depends on it.
void loadTray(QQmlEngine &qml, const QString &file)
{
    QQmlComponent component(&qml, QUrl::fromLocalFile(file));
    QObject *tray = component.create();
    if (!tray) {
        for (const QQmlError &e : component.errors()) qWarning().noquote() << e.toString();
        return;
    }
    tray->setParent(&qml);
}
