#include <QtTest>
#include <QProcess>
#include <QTemporaryDir>

#include "EngineClient.h"
#include "HubController.h"
#include "Tray.h"

static QVariantMap stateEv(const QString &s) { return {{"type", "state"}, {"state", QVariantMap{{"state", s}}}}; }

class TstTray : public QObject
{
    Q_OBJECT
private slots:
    void stateMapsToMenuLabels()
    {
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        TrayController tray(&client, &hub);
        QSignalSpy changed(&tray, &TrayController::changed);
        // not connected yet: say so, nothing to toggle
        QCOMPARE(tray.stateLabel(), QString("reverseuno is not running"));
        QVERIFY(!tray.toggleEnabled());

        emit client.connectedChanged(true);
        QCOMPARE(tray.stateLabel(), QString("Idle"));
        QCOMPARE(tray.toggleLabel(), QString("Start dictation"));
        QVERIFY(tray.toggleEnabled());

        const QList<std::tuple<QString, QString, QString, bool>> cases = {
            {"listening", "Listening…", "Stop and paste", true},
            {"transcribing", "Working…", "Start dictation", false},
            {"formatting", "Working…", "Start dictation", false},
            {"inserting", "Working…", "Start dictation", false},
            {"done", "Idle", "Start dictation", true},
            {"error", "Idle", "Start dictation", true},
            {"cancelled", "Idle", "Start dictation", true},
            {"idle", "Idle", "Start dictation", true},
        };
        for (const auto &[state, label, toggle, enabled] : cases) {
            QCOMPARE(TrayController::bucketFor(state), label == "Idle" ? QString("idle")
                                                       : label == "Working…" ? QString("working")
                                                                             : QString("listening"));
            emit client.event(stateEv(state));
            QCOMPARE(tray.stateLabel(), label);
            QCOMPARE(tray.toggleLabel(), toggle);
            QCOMPARE(tray.toggleEnabled(), enabled);
        }
        QVERIFY(changed.count() >= 4);

        // engine goes away mid-session: no stale "Listening…"
        emit client.event(stateEv("listening"));
        emit client.connectedChanged(false);
        QCOMPARE(tray.stateLabel(), QString("reverseuno is not running"));
        QVERIFY(!tray.toggleEnabled());
    }
    void toggleAndQuitCallTheRpc()
    {
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        TrayController tray(&client, &hub);
        QSignalSpy calls(&client, &EngineClient::callFinished);
        QSignalSpy quit(&tray, &TrayController::quitRequested);

        tray.toggle();
        QCOMPARE(calls.count(), 1);
        QCOMPARE(calls.at(0).at(0).toString(), QString("dictationToggle"));

        // no engine: the call fails at once, and the Qt app still exits
        tray.quit();
        QCOMPARE(calls.count(), 2);
        QCOMPARE(calls.at(1).at(0).toString(), QString("quit"));
        QCOMPARE(quit.count(), 1);
    }
    void openShowsHistory()
    {
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        TrayController tray(&client, &hub);
        hub.setPage("settings");
        tray.open();
        QCOMPARE(hub.page(), QString("history"));
    }
    void pageArgumentOpensTheHubAfterTheFirstBootstrap()
    {
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        QSignalSpy page(&hub, &HubController::pageChanged);
        hub.openAfterBootstrap("style"); // legacy name, mapped like `show style`
        emit client.callFinished("listModels", QVariantList(), QString());
        emit client.callFinished("bootstrap", QVariant(), "engine went away");
        QCOMPARE(page.count(), 0);
        emit client.callFinished("bootstrap", QVariantMap(), QString());
        QCOMPARE(hub.page(), QString("settings"));
        // only the first one: a reconnect's bootstrap leaves the page alone
        hub.setPage("dictionary");
        emit client.callFinished("bootstrap", QVariantMap(), QString());
        QCOMPARE(hub.page(), QString("dictionary"));
    }
    void secondInstanceExitsWithAlreadyRunning()
    {
        QTemporaryDir runtime; // its own XDG_RUNTIME_DIR: never the user's real lock
        QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
        env.insert("XDG_RUNTIME_DIR", runtime.path());
        env.insert("QT_QPA_PLATFORM", "offscreen");
        env.insert("REVERSEUNO_CONTROL_FILE", runtime.filePath("control.json")); // no engine
        env.insert("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + runtime.filePath("no-bus"));
        env.remove("WAYLAND_DISPLAY");
        env.remove("QT_QPA_PLATFORMTHEME");

        QProcess first, second;
        for (QProcess *p : {&first, &second}) {
            p->setProcessEnvironment(env);
            p->setProcessChannelMode(QProcess::ForwardedErrorChannel);
        }
        first.start(UI_BIN);
        QVERIFY(first.waitForStarted());
        QTRY_VERIFY_WITH_TIMEOUT(!QDir(runtime.path()).entryList({"reverseuno-ui*.lock"}, QDir::Files).isEmpty(), 5000);

        second.start(UI_BIN);
        QVERIFY(second.waitForFinished(5000));
        QCOMPARE(second.exitStatus(), QProcess::NormalExit);
        QCOMPARE(second.exitCode(), 75);
        QCOMPARE(first.state(), QProcess::Running);

        // the lock goes with its owner: a later start takes over
        first.kill();
        QVERIFY(first.waitForFinished(5000));
        QProcess third;
        third.setProcessEnvironment(env);
        third.start(UI_BIN);
        QVERIFY(third.waitForStarted());
        QTest::qWait(500);
        QCOMPARE(third.state(), QProcess::Running);
        third.kill();
        third.waitForFinished(5000);
    }
    void unusableRuntimeDirStillRuns()
    {
        QTemporaryDir tmp;
        QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
        env.insert("XDG_RUNTIME_DIR", tmp.filePath("missing"));
        env.insert("QT_QPA_PLATFORM", "offscreen");
        env.insert("REVERSEUNO_CONTROL_FILE", tmp.filePath("control.json"));
        env.insert("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + tmp.filePath("no-bus"));
        env.remove("WAYLAND_DISPLAY");
        env.remove("QT_QPA_PLATFORMTHEME");
        QProcess ui;
        ui.setProcessEnvironment(env);
        ui.setProcessChannelMode(QProcess::ForwardedErrorChannel);
        ui.start(UI_BIN);
        QVERIFY(ui.waitForStarted());
        QTest::qWait(500);
        QCOMPARE(ui.state(), QProcess::Running);
        ui.kill();
        ui.waitForFinished(5000);
    }
};

QTEST_GUILESS_MAIN(TstTray)
#include "tst_tray.moc"
