#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QUrl>

#include "EngineClient.h"
#include "OverlayController.h"
#include "Theme.h"

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);
    Theme theme;
    EngineClient engine;
    OverlayController overlay(&engine);
    QQmlApplicationEngine qml;
    qml.rootContext()->setContextProperty("theme", &theme);
    qml.rootContext()->setContextProperty("engine", &engine);
    qml.rootContext()->setContextProperty("overlay", &overlay);
    const QString dir = QCoreApplication::applicationDirPath() + "/qml/";
    qml.load(QUrl::fromLocalFile(dir + "Main.qml"));
    qml.load(QUrl::fromLocalFile(dir + "Overlay.qml"));
    if (auto *w = qobject_cast<QQuickWindow *>(qml.rootObjects().isEmpty() ? nullptr : qml.rootObjects().constLast()))
        // Off Wayland (offscreen, X11) a plain frameless window stands in for the layer surface.
        overlay.attachWindow(w, QGuiApplication::platformName().startsWith("wayland"));
    return app.exec();
}
