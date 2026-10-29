#pragma once
#include <QString>

class HubController;
class OverlayController;
class QQmlEngine;
class QQuickWindow;

struct Windows {
    QQuickWindow *overlay = nullptr;
    QQuickWindow *hub = nullptr;
};

// Creates Overlay.qml and Hub.qml from `dir`, each through its own component, so the
// Overlay's layer-shell setup can only ever land on the Overlay's window. A file that
// fails to load is reported and left null.
Windows loadWindows(QQmlEngine &qml, const QString &dir, OverlayController &overlay, HubController &hub,
                    bool layerShell);
