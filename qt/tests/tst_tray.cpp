#include <QtTest>

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
};

QTEST_GUILESS_MAIN(TstTray)
#include "tst_tray.moc"
