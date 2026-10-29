#include <QCommandLineParser>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>

#include "EngineClient.h"
#include "HubController.h"
#include "OverlayController.h"
#include "Theme.h"
#include "Tray.h"
#include "Windows.h"

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);
    app.setQuitOnLastWindowClosed(false); // closing the Hub only hides it
    Theme theme;
    EngineClient engine;
    OverlayController overlay(&engine);
    HubController hub(&engine);
    TrayController tray(&engine, &hub);
    QObject::connect(&tray, &TrayController::quitRequested, &app, &QCoreApplication::quit);

    // The engine passes --page when it started us for `reverseuno show <page>`.
    QCommandLineParser args;
    const QCommandLineOption page("page", "Open the Hub on this page once connected.", "page");
    args.addOption(page);
    args.parse(app.arguments());
    if (args.isSet(page)) hub.openAfterBootstrap(args.value(page));

    QQmlApplicationEngine qml;
    qml.rootContext()->setContextProperty("theme", &theme);
    qml.rootContext()->setContextProperty("engine", &engine);
    qml.rootContext()->setContextProperty("overlay", &overlay);
    qml.rootContext()->setContextProperty("hub", &hub);
    qml.rootContext()->setContextProperty("tray", &tray);
    // Off Wayland (offscreen, X11) a plain frameless window stands in for the layer surface.
    const Windows windows = loadWindows(qml, QCoreApplication::applicationDirPath() + "/qml", overlay, hub,
                                        QGuiApplication::platformName().startsWith("wayland"));
    if (!windows.overlay || !windows.hub) return 1;
    loadTray(qml, QCoreApplication::applicationDirPath() + "/qml/Tray.qml");
    return app.exec();
}
