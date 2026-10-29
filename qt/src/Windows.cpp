#include "Windows.h"

#include <QQmlComponent>
#include <QQmlEngine>
#include <QQuickWindow>
#include <QUrl>

#include "HubController.h"
#include "OverlayController.h"

static QQuickWindow *create(QQmlEngine &qml, const QString &file)
{
    QQmlComponent component(&qml, QUrl::fromLocalFile(file));
    QObject *object = component.create();
    if (component.isError()) {
        for (const QQmlError &e : component.errors()) qWarning().noquote() << e.toString();
    }
    auto *window = qobject_cast<QQuickWindow *>(object);
    if (!window) {
        delete object;
        qWarning().noquote() << file << "did not create a window";
        return nullptr;
    }
    object->setParent(&qml); // destroyed with the engine, before its context goes away
    return window;
}

Windows loadWindows(QQmlEngine &qml, const QString &dir, OverlayController &overlay, HubController &hub,
                    bool layerShell)
{
    Windows w;
    w.overlay = create(qml, dir + "/Overlay.qml");
    w.hub = create(qml, dir + "/Hub.qml");
    if (w.overlay) overlay.attachWindow(w.overlay, layerShell);
    if (w.hub) hub.attachWindow(w.hub);
    return w;
}
