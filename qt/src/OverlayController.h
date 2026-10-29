#pragma once
#include <QObject>
#include <QPointer>
#include <QRect>
#include <QSize>
#include <QString>
#include <QVariantMap>

class EngineClient;
class QWindow;

// Turns the engine stream into what the Overlay pill draws, and places its window.
// The engine decides display and show/hide; this only follows the `overlay` event.
class OverlayController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY changed)
    Q_PROPERTY(QString word READ word NOTIFY changed)
    Q_PROPERTY(qreal level READ level NOTIFY levelChanged)
    Q_PROPERTY(bool visible READ visible NOTIFY changed)
    Q_PROPERTY(bool resting READ resting NOTIFY changed)
    Q_PROPERTY(bool active READ active NOTIFY changed)
    Q_PROPERTY(QRect geometry READ geometry NOTIFY changed)
    Q_PROPERTY(QSize contentSize READ contentSize WRITE setContentSize NOTIFY changed)
public:
    explicit OverlayController(EngineClient *client, QObject *parent = nullptr);

    static QString wordFor(const QVariantMap &state);

    QString state() const { return m_state.value("state", "idle").toString(); }
    QString word() const { return wordFor(m_state); }
    qreal level() const { return m_level; }
    bool visible() const { return m_visible; }
    bool resting() const { return m_visible && state() == "idle"; }
    bool active() const;
    QRect geometry() const;
    QSize contentSize() const { return m_content; }
    void setContentSize(const QSize &size);

    // Configures `window` (layer-shell surface when `layerShell`, else a plain
    // frameless window) and shows/hides/places it from now on.
    void attachWindow(QWindow *window, bool layerShell);

    Q_INVOKABLE void cancel();

signals:
    void changed();
    void levelChanged();

private:
    void apply();

    EngineClient *m_client;
    QVariantMap m_state;
    qreal m_level = 0;
    bool m_visible = false;
    QRect m_target; // engine's rect, global logical coordinates
    QSize m_content;
    QPointer<QWindow> m_window;
    bool m_layerShell = false;
};
