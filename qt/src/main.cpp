#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>

#include "EngineClient.h"
#include "HubController.h"
#include "OverlayController.h"
#include "Theme.h"
#include "Windows.h"

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);
    app.setQuitOnLastWindowClosed(false); // closing the Hub only hides it
    Theme theme;
    EngineClient engine;
    OverlayController overlay(&engine);
    HubController hub(&engine);
    QQmlApplicationEngine qml;
    qml.rootContext()->setContextProperty("theme", &theme);
    qml.rootContext()->setContextProperty("engine", &engine);
    qml.rootContext()->setContextProperty("overlay", &overlay);
    qml.rootContext()->setContextProperty("hub", &hub);
    // Off Wayland (offscreen, X11) a plain frameless window stands in for the layer surface.
    const Windows windows = loadWindows(qml, QCoreApplication::applicationDirPath() + "/qml", overlay, hub,
                                        QGuiApplication::platformName().startsWith("wayland"));
    if (!windows.overlay || !windows.hub) return 1;
    return app.exec();
}
