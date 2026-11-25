// reverseuno — push-to-talk dictation for Hyprland. Hold a key, speak, release,
// and the text lands in the window you were in. Commands are in cli.cpp.

#include <QDBusConnection>
#include <QDBusConnectionInterface>
#include <QGuiApplication>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QTextStream>
#include <QUrl>

#include "backend.h"
#include "cli.h"
#include "service.h"
#include "theme.h"
#include "tray.h"
#include "translator.h"

#ifndef APP_VERSION
#define APP_VERSION "0"
#endif

int main(int argc, char *argv[]) {
    const QString command = argc > 1 ? QString::fromLocal8Bit(argv[1]) : QStringLiteral("open");
    const bool headless = isHeadlessCommand(command);
    // Commands without a window must not need a display.
    if (headless) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
        // The desktop platform theme would parse the GTK theme and warn on stderr.
        qputenv("QT_QPA_PLATFORMTHEME", "");
    }

    QGuiApplication app(argc, argv);
    app.setApplicationName("reverseuno");
    app.setApplicationVersion(APP_VERSION);
    app.setDesktopFileName("reverseuno");

    // Anything that is not a window command goes to the CLI, which also
    // answers an unknown command with the usage text and exit code 2.
    if (headless || (command != "open" && command != "daemon")) {
        QTextStream out(stdout), err(stderr);
        return runCli(app.arguments().mid(1), {{}, {}, {}, &out, &err});
    }

    // One instance owns the bus name; a second `open` just raises the first.
    QDBusConnection bus = QDBusConnection::sessionBus();
    const bool onBus = bus.isConnected();
    if (onBus && bus.interface()->isServiceRegistered(Service::busName())) {
        if (command == "open")
            showRunningInstance(Service::busName());
        return 0;
    }

    // A window icon from the theme once installed, the bundled one before that.
    app.setWindowIcon(QIcon::fromTheme("reverseuno", QIcon(":/reverseuno.svg")));
    QQuickStyle::setStyle("Material");

    // Installed before anything is translated, and every qsTr() binding is
    // re-evaluated when the Omarchy language changes.
    Translator translator;
    app.installTranslator(&translator);

    Theme theme;
    Backend backend;
    Tray tray(&theme, &backend);
    QObject::connect(&translator, &Translator::changed, &backend, &Backend::refreshProblems);

    Service service(&backend);
    if (onBus) {
        bus.registerObject(Service::objectPath(), &service, QDBusConnection::ExportAllSlots);
        if (!bus.registerService(Service::busName())) {
            // Lost the race to another instance started at the same moment.
            if (command == "open")
                showRunningInstance(Service::busName());
            return 0;
        }
    } else {
        qWarning("No D-Bus session bus: `reverseuno start` and friends cannot reach this instance.");
    }

    // Closing the window only hides it, so the push-to-talk keys keep working.
    app.setQuitOnLastWindowClosed(false);

    QQmlApplicationEngine engine;
    QObject::connect(&translator, &Translator::changed, &engine, &QQmlEngine::retranslate);
    engine.rootContext()->setContextProperty("translator", &translator);
    engine.rootContext()->setContextProperty("theme", &theme);
    engine.rootContext()->setContextProperty("backend", &backend);
    engine.rootContext()->setContextProperty("tray", &tray);
    engine.rootContext()->setContextProperty("startHidden", command == "daemon");
    engine.load(QUrl("qrc:/Main.qml"));
    if (engine.rootObjects().isEmpty())
        return 1;

    return app.exec();
}
