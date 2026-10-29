#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QUrl>

#include "EngineClient.h"
#include "Theme.h"

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);
    Theme theme;
    EngineClient engine;
    QQmlApplicationEngine qml;
    qml.rootContext()->setContextProperty("theme", &theme);
    qml.rootContext()->setContextProperty("engine", &engine);
    qml.load(QUrl::fromLocalFile(QCoreApplication::applicationDirPath() + "/qml/Main.qml"));
    return app.exec();
}
