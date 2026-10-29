#include "OverlayController.h"

#include <QGuiApplication>
#include <QScreen>
#include <QWindow>
#include <LayerShellQt/window.h>

#include "EngineClient.h"

OverlayController::OverlayController(EngineClient *client, QObject *parent)
    : QObject(parent), m_client(client)
{
    connect(client, &EngineClient::event, this, [this](const QVariantMap &e) {
        const QString type = e.value("type").toString();
        if (type == "state") {
            m_state = e.value("state").toMap();
            if (state() == "idle" && m_level != 0) {
                m_level = 0;
                emit levelChanged();
            }
            emit changed();
        } else if (type == "level") {
            const qreal l = e.value("level").toDouble();
            m_level = l > 0 ? qMin(l, 1.0) : 0; // also maps NaN to 0
            emit levelChanged();
        } else if (type == "overlay") {
            m_visible = e.value("visible").toBool();
            m_target = QRect(e.value("x").toInt(), e.value("y").toInt(), e.value("width").toInt(),
                             e.value("height").toInt());
            apply();
            emit changed();
        }
    });
    // Engine down: the Overlay stays hidden; the snapshot on reconnect re-shows it.
    connect(client, &EngineClient::connectedChanged, this, [this](bool on) {
        if (on || !m_visible) return;
        m_visible = false;
        apply();
        emit changed();
    });
}

// Mirrors statusLabel() in src/renderer/overlay/Overlay.tsx.
QString OverlayController::wordFor(const QVariantMap &s)
{
    const QString state = s.value("state").toString();
    const QString message = s.value("message").toString();
    if (state == "listening") return "Listening";
    if (state == "transcribing") return "Transcribing";
    if (state == "formatting") return "Polishing";
    if (state == "inserting") return "Pasting";
    if (state == "done") {
        if (!message.isEmpty()) return message;
        return s.value("text").toString().isEmpty() ? "Saved" : "Pasted";
    }
    if (state == "skipped") return message.isEmpty() ? "Nothing to paste" : message;
    if (state == "error") return message.isEmpty() ? "Something went wrong" : message;
    if (state == "cancelled") return "Cancelled";
    return "Hold Right Super to talk";
}

bool OverlayController::active() const
{
    const QString s = state();
    return s == "listening" || s == "transcribing" || s == "formatting" || s == "inserting";
}

// The window is only as big as the pill, centred in the engine's (larger) rect, so
// its transparent remainder never eats clicks meant for the window underneath.
QRect OverlayController::geometry() const
{
    if (m_content.isEmpty()) return m_target;
    QRect r(QPoint(), m_content);
    r.moveCenter(m_target.center());
    return r;
}

void OverlayController::setContentSize(const QSize &size)
{
    if (size == m_content) return;
    m_content = size;
    apply();
    emit changed();
}

void OverlayController::attachWindow(QWindow *window, bool layerShell)
{
    m_window = window;
    m_layerShell = layerShell;
    window->setFlags(window->flags() | Qt::FramelessWindowHint | Qt::WindowDoesNotAcceptFocus
                     | Qt::WindowStaysOnTopHint | Qt::Tool);
    if (layerShell) {
        // Must run before the window is first shown.
        auto *ls = LayerShellQt::Window::get(window);
        ls->setLayer(LayerShellQt::Window::LayerOverlay);
        ls->setKeyboardInteractivity(LayerShellQt::Window::KeyboardInteractivityNone);
        ls->setActivateOnShow(false);
        ls->setScope("reverseuno-overlay");
        ls->setAnchors(LayerShellQt::Window::Anchors(LayerShellQt::Window::AnchorTop | LayerShellQt::Window::AnchorLeft));
        // -1: margins count from the output's edge, ignoring bars' exclusive zones,
        // because the engine's coordinates already account for its work area.
        ls->setExclusiveZone(-1);
    }
    apply();
}

void OverlayController::apply()
{
    if (!m_window) return;
    if (!m_visible) {
        m_window->hide();
        return;
    }
    const QRect g = geometry();
    QScreen *screen = QGuiApplication::screenAt(m_target.topLeft());
    if (!screen) screen = QGuiApplication::primaryScreen();
    if (m_layerShell) {
        auto *ls = LayerShellQt::Window::get(m_window);
        // A mapped layer surface cannot change output; remap it on the new one.
        if (ls->screen() != screen) {
            m_window->hide();
            ls->setScreen(screen);
        }
        const QPoint origin = screen->geometry().topLeft();
        ls->setMargins(QMargins(qMax(0, g.x() - origin.x()), qMax(0, g.y() - origin.y()), 0, 0));
        m_window->resize(g.size());
    } else {
        m_window->setGeometry(g);
    }
    m_window->show();
}

void OverlayController::cancel()
{
    if (active()) m_client->call("dictationCancel", {});
}
