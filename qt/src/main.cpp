#include <QCommandLineParser>
#include <QCryptographicHash>
#include <QDir>
#include <QGuiApplication>
#include <QLockFile>
#include <QStandardPaths>
#include <QQmlApplicationEngine>
#include <QQmlContext>

#include "EngineClient.h"
#include "HubController.h"
#include "OverlayController.h"
#include "Theme.h"
#include "Tray.h"
#include "Windows.h"

// Same value as ALREADY_RUNNING_EXIT in src/main/supervisor.ts: the engine does not restart on it.
static constexpr int kAlreadyRunningExit = 75;

// One Qt app per engine, so an orphan left by a crashed engine and the new engine's child never
// both draw a tray and an Overlay. Keyed on the control file: an isolated engine (e2e) gets its own.
static QString lockPath()
{
    QString dir = qEnvironmentVariable("XDG_RUNTIME_DIR");
    if (dir.isEmpty()) dir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
    const QString control = QDir::cleanPath(EngineClient::defaultControlFile());
    QString name = "reverseuno-ui";
    if (control != QDir::cleanPath(EngineClient::standardControlFile()))
        name += "-" + QCryptographicHash::hash(control.toUtf8(), QCryptographicHash::Sha1).toHex().left(12);
    return dir + "/" + name + ".lock";
}

int main(int argc, char *argv[])
{
    QLockFile lock(lockPath());
    lock.setStaleLockTime(0); // never stale by age; a dead owner's lock is still taken over
    if (!lock.tryLock()) {
        if (lock.error() == QLockFile::LockFailedError) {
            qInfo().noquote() << "reverseuno-ui already running:" << lock.fileName();
            return kAlreadyRunningExit;
        }
        // Missing or unwritable runtime dir: run unguarded rather than not at all.
        qWarning().noquote() << "reverseuno-ui: cannot create" << lock.fileName() << "; running without the lock";
    }

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
