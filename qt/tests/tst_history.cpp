#include <QtTest>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QQmlApplicationEngine>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQuickItem>
#include <QQuickWindow>

#include "EngineClient.h"
#include "HistoryModel.h"
#include "HubController.h"
#include "OverlayController.h"
#include "Theme.h"
#include "Windows.h"

static QVariantMap entry(const QString &id, const QString &text, const QString &appClass, double createdAt,
                         const QString &appTitle = QString())
{
    return {{"id", id}, {"text", text}, {"appClass", appClass}, {"appTitle", appTitle}, {"createdAt", createdAt},
            {"inserted", true}, {"wordCount", 2}};
}

static QQuickItem *findItem(QQuickItem *root, const QString &name)
{
    if (!root) return nullptr;
    if (root->objectName() == name) return root;
    for (QQuickItem *child : root->childItems())
        if (QQuickItem *hit = findItem(child, name)) return hit;
    return nullptr;
}

// Stands in for EngineClient::call's serialisation of QML-built args.
class Probe : public QObject
{
    Q_OBJECT
public:
    QByteArray last;
    Q_INVOKABLE void call(const QString &, const QVariantList &args)
    {
        last = QJsonDocument(QJsonArray::fromVariantList(args)).toJson(QJsonDocument::Compact);
    }
};

class TstHistory : public QObject
{
    Q_OBJECT
private slots:
    void filterMatchesFinalTextAndApp()
    {
        HistoryModel m;
        m.setEntries({entry("a", "Hello World", "kitty", 1), entry("b", "ship it", "firefox", 3, "Pull request"),
                      entry("c", "lunch", "Slack", 2)});
        QCOMPARE(m.rowCount(), 3);
        // newest first
        QCOMPARE(m.data(m.index(0), HistoryModel::IdRole).toString(), QString("b"));
        QCOMPARE(m.data(m.index(2), HistoryModel::IdRole).toString(), QString("a"));

        m.filter("hello");
        QCOMPARE(m.rowCount(), 1);
        QCOMPARE(m.data(m.index(0), HistoryModel::TextRole).toString(), QString("Hello World"));
        m.filter("SLACK");
        QCOMPARE(m.rowCount(), 1);
        QCOMPARE(m.data(m.index(0), HistoryModel::IdRole).toString(), QString("c"));
        m.filter("pull req");
        QCOMPARE(m.rowCount(), 1);
        QCOMPARE(m.data(m.index(0), HistoryModel::IdRole).toString(), QString("b"));
        m.filter("nothing like this");
        QCOMPARE(m.rowCount(), 0);
        // filter survives new entries
        m.filter("lunch");
        m.setEntries({entry("c", "lunch", "Slack", 2), entry("d", "late lunch", "kitty", 9)});
        QCOMPARE(m.rowCount(), 2);
        m.filter("  ");
        QCOMPARE(m.rowCount(), 2);
        QCOMPARE(m.entry("d").value("text").toString(), QString("late lunch"));
        QVERIFY(m.entry("missing").isEmpty());
    }

    void refreshEventReloadsTheModel()
    {
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        QSignalSpy calls(&client, &EngineClient::callFinished);
        emit client.event({{"type", "refresh"}});
        QCOMPARE(calls.count(), 1);
        QCOMPARE(calls.first().at(0).toString(), QString("bootstrap"));

        emit client.callFinished("bootstrap", QVariantMap{{"history", QVariantList{entry("a", "one", "kitty", 1)}}}, QString());
        QCOMPARE(hub.history()->rowCount(), 1);
        emit client.callFinished("bootstrap", QVariantMap{{"history", QVariantList{entry("a", "one", "kitty", 1),
                                                                                    entry("b", "two", "kitty", 2)}}},
                                 QString());
        QCOMPARE(hub.history()->rowCount(), 2);
        // a failed bootstrap (engine went away) keeps what is on screen
        emit client.callFinished("bootstrap", QVariant(), "engine not running");
        QCOMPARE(hub.history()->rowCount(), 2);
    }

    void emptyOrMalformedBootstrapDoesNotCrash()
    {
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        hub.applyBootstrap(QVariant());
        hub.applyBootstrap(QVariant("garbage"));
        hub.applyBootstrap(QVariantMap{{"history", "x"}, {"settings", 3}, {"dictionary", QVariantMap()}});
        hub.applyBootstrap(QVariantMap{{"history", QVariantList{1, "a", QVariantMap(), entry("a", "ok", "", 1)}}});
        QCOMPARE(hub.history()->rowCount(), 1);
        QVERIFY(hub.settings().isEmpty());
        QVERIFY(hub.dictionary().isEmpty());
    }

    void pageEventNavigatesAndLegacyNamesMap()
    {
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        QWindow w;
        hub.attachWindow(&w);
        QVERIFY(!w.isVisible());
        QCOMPARE(hub.page(), QString("history"));
        emit client.event({{"type", "page"}, {"page", "settings"}});
        QCOMPARE(hub.page(), QString("settings"));
        QVERIFY(w.isVisible());
        emit client.event({{"type", "page"}, {"page", "home"}});
        QCOMPARE(hub.page(), QString("history"));
        emit client.event({{"type", "page"}, {"page", "style"}});
        QCOMPARE(hub.page(), QString("settings"));
        emit client.event({{"type", "page"}, {"page", "snippets"}});
        QCOMPARE(hub.page(), QString("snippets"));
        emit client.event({{"type", "page"}, {"page", "nonsense"}});
        QCOMPARE(hub.page(), QString("history"));
    }

    void settingsPatchIsNestedFromAPath()
    {
        QCOMPARE(HubController::patchFor("format.cleanup", "light"),
                 (QVariantMap{{"format", QVariantMap{{"cleanup", "light"}}}}));
        QCOMPARE(HubController::patchFor("overlay.showAtRest", true),
                 (QVariantMap{{"overlay", QVariantMap{{"showAtRest", true}}}}));
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        QSignalSpy calls(&client, &EngineClient::callFinished);
        QSignalSpy notices(&hub, &HubController::notice);
        hub.set("format.cleanup", "light");
        QCOMPARE(calls.count(), 1);
        QCOMPARE(calls.first().at(0).toString(), QString("updateSettings"));
        QCOMPARE(notices.count(), 1); // engine down: the failure is said, not swallowed
        QCOMPARE(notices.first().at(1).toBool(), false);
    }

    void qmlObjectArgsSerialiseAsJsonObjects()
    {
        Probe probe;
        QQmlEngine qml;
        qml.rootContext()->setContextProperty("probe", &probe);
        QQmlComponent c(&qml);
        c.setData("import QtQml\nQtObject { Component.onCompleted: probe.call('dictionaryUpdate', ['d1', { starred: true, word: 'x' }]) }", QUrl());
        std::unique_ptr<QObject> o(c.create());
        QVERIFY2(o, qPrintable(c.errorString()));
        QCOMPARE(probe.last, QByteArray(R"(["d1",{"starred":true,"word":"x"}])"));
    }

    void modelFieldKeepsSavedValueWhenListArrives()
    {
        QTest::failOnWarning();
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        QQmlEngine qml;
        qml.rootContext()->setContextProperty("hub", &hub);
        QQmlComponent c(&qml, QUrl::fromLocalFile(QML_DIR "/ModelField.qml"));
        for (const QString &saved : {QString("b"), QString("not-in-list")}) {
            std::unique_ptr<QObject> f(c.createWithInitialProperties({{"saved", saved}, {"model", QStringList()}}));
            QVERIFY2(f, qPrintable(c.errorString()));
            QCOMPARE(f->property("editText").toString(), saved);
            f->setProperty("model", QStringList{"a", "b", "c"});
            QCOMPARE(f->property("editText").toString(), saved);
            QTRY_COMPARE(f->property("editText").toString(), saved); // and after any deferred reset
        }
    }

    void insertionAndFormatterPatches()
    {
        auto json = [](const QVariantMap &m) { return QJsonDocument(QJsonObject::fromVariantMap(m)).toJson(QJsonDocument::Compact); };
        QCOMPARE(json(HubController::patchFor("insert.mode", "paste")), QByteArray(R"({"insert":{"mode":"paste"}})"));
        QCOMPARE(json(HubController::patchFor("insert.defaultKeys", "shift-insert")),
                 QByteArray(R"({"insert":{"defaultKeys":"shift-insert"}})"));
        // numbers typed in a field go out as JSON numbers, clamped
        QCOMPARE(json(HubController::patchFor("insert.restoreDelayMs", HubController::number("150", 100, 10000))),
                 QByteArray(R"({"insert":{"restoreDelayMs":150}})"));
        QCOMPARE(HubController::number(" 42.6 ", 0, 100), QVariant(43));
        QCOMPARE(HubController::number("5", 100, 10000), QVariant(100));
        QCOMPARE(HubController::number("1e9", 100, 10000), QVariant(10000));
        QVERIFY(!HubController::number("abc", 100, 10000).isValid());
        QVERIFY(!HubController::number("", 100, 10000).isValid());
        QCOMPARE(json(HubController::patchFor("format.baseUrl", "https://proxy.example/v1")),
                 QByteArray("{\"format\":{\"baseUrl\":\"https:/" "/proxy.example/v1\"}}"));
        QCOMPARE(json(HubController::patchFor("format.customInstructions", "Short sentences.")),
                 QByteArray(R"({"format":{"customInstructions":"Short sentences."}})"));
        QVERIFY(HubController::validBaseUrl(""));
        QVERIFY(HubController::validBaseUrl("http://localhost:8080/v1"));
        QVERIFY(!HubController::validBaseUrl("openrouter.ai/api/v1"));
        QVERIFY(!HubController::validBaseUrl("ftp://x.example"));
        QVERIFY(!HubController::validBaseUrl("https://a b"));

        // appRules: edited in place, sent as the whole array
        QVariantList rules{QVariantMap{{"pattern", "ghostty|kitty"}, {"keys", "shift-insert"}},
                           QVariantMap{{"pattern", "libreoffice"}, {"keys", "ctrl-v"}, {"restoreDelayMs", 3000}}};
        QVERIFY(HubController::changeRule(rules, 0, "pattern", "ghostty|kitty|velocitty"));
        QVERIFY(HubController::changeRule(rules, 1, "restoreDelayMs", "250"));
        QVERIFY(HubController::changeRule(rules, 1, "keys", "shift-insert"));
        QVERIFY(!HubController::changeRule(rules, 0, "pattern", "(unclosed"));
        QVERIFY(!HubController::changeRule(rules, 0, "keys", "alt-v"));
        QVERIFY(!HubController::changeRule(rules, 0, "restoreDelayMs", "soon"));
        QVERIFY(!HubController::changeRule(rules, 5, "keys", "ctrl-v"));
        QVERIFY(!HubController::changeRule(rules, 0, "other", "x"));
        QCOMPARE(json(HubController::patchFor("insert.appRules", rules)),
                 QByteArray(R"({"insert":{"appRules":[{"keys":"shift-insert","pattern":"ghostty|kitty|velocitty"},)"
                            R"({"keys":"shift-insert","pattern":"libreoffice","restoreDelayMs":250}]}})"));

        // rejected input sends nothing and says why; accepted input is one updateSettings
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        QSignalSpy calls(&client, &EngineClient::callFinished);
        QSignalSpy notices(&hub, &HubController::notice);
        QVERIFY(!hub.setNumber("insert.restoreDelayMs", "abc", 100, 10000));
        QVERIFY(!hub.setBaseUrl("not a url"));
        hub.applyBootstrap(QVariantMap{{"settings", QVariantMap{{"insert", QVariantMap{{"appRules", rules}}}}}});
        QVERIFY(!hub.setAppRule(0, "pattern", "(unclosed"));
        QCOMPARE(calls.count(), 0);
        QCOMPARE(notices.count(), 3);
        QVERIFY(hub.setNumber("insert.restoreDelayMs", "150", 100, 10000));
        QVERIFY(hub.setBaseUrl(""));
        QVERIFY(hub.setAppRule(0, "keys", "ctrl-v"));
        hub.addAppRule();
        hub.removeAppRule(0);
        QCOMPARE(calls.count(), 5);
        for (const QList<QVariant> &c : calls) QCOMPARE(c.at(0).toString(), QString("updateSettings"));
    }

    void comboPopupItemsArePlainText()
    {
        QTest::failOnWarning();
        Theme theme("/nonexistent");
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        QQmlEngine qml;
        qml.rootContext()->setContextProperty("theme", &theme);
        qml.rootContext()->setContextProperty("hub", &hub);
        QQmlComponent c(&qml);
        c.setData("import QtQuick\nWindow {\n width: 400; height: 400\n"
                  " property alias pick: pick\n property alias field: field\n"
                  " Pick { id: pick; model: [{ value: 'm', label: '<i>m</i>' }] }\n"
                  " ModelField { id: field; y: 60; model: ['<b>model</b>'] }\n}",
                  QUrl::fromLocalFile(QML_DIR "/probe.qml"));
        std::unique_ptr<QObject> root(c.create());
        QVERIFY2(root, qPrintable(c.errorString()));
        auto *win = qobject_cast<QQuickWindow *>(root.get());
        win->show();
        QVERIFY(QTest::qWaitForWindowExposed(win));
        for (const char *name : {"pick", "field"}) {
            QObject *combo = root->property(name).value<QObject *>();
            QObject *popup = combo->property("popup").value<QObject *>();
            QVERIFY(QMetaObject::invokeMethod(popup, "open"));
            QQuickItem *label = nullptr;
            QTRY_VERIFY((label = findItem(popup->property("contentItem").value<QQuickItem *>(), "choiceLabel")));
            QVERIFY(label->property("text").toString().startsWith('<'));
            QCOMPARE(label->property("textFormat").toInt(), 0); // Text.PlainText
            QMetaObject::invokeMethod(popup, "close");
        }
    }

    void markupInTextStaysLiteral()
    {
        QTest::failOnWarning();
        Theme theme("/nonexistent");
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        OverlayController overlay(&client);
        hub.applyBootstrap(QVariantMap{{"history", QVariantList{entry("a", "<b>x</b> &amp;", "<i>app</i>", 1)}}});
        QQmlApplicationEngine qml;
        qml.rootContext()->setContextProperty("theme", &theme);
        qml.rootContext()->setContextProperty("hub", &hub);
        qml.rootContext()->setContextProperty("overlay", &overlay);
        const Windows wins = loadWindows(qml, QML_DIR, overlay, hub, false);
        QVERIFY(wins.hub);
        wins.hub->show();
        QVERIFY(QTest::qWaitForWindowExposed(wins.hub));
        QQuickItem *text = nullptr;
        QTRY_VERIFY((text = findItem(wins.hub->contentItem(), "entryText")));
        QCOMPARE(text->property("text").toString(), QString("<b>x</b> &amp;"));
        QCOMPARE(text->property("textFormat").toInt(), 0); // Text.PlainText
        QQuickItem *app = findItem(wins.hub->contentItem(), "entryApp");
        QVERIFY(app);
        QCOMPARE(app->property("textFormat").toInt(), 0);
        if (qEnvironmentVariableIsSet("HUB_SNAPSHOT")) {
            hub.applyBootstrap(QVariantMap{{"history", QVariantList{
                entry("a", "Ship the Qt hub today and keep the Overlay small.", "kitty", 1761700000000, "~/dev"),
                entry("b", "Can we move the review to Thursday afternoon?", "Slack", 1761710000000),
                entry("c", "<b>x</b> & literal markup", "firefox", 1761690000000, "GitHub")}},
                {"settings", QVariantMap{
                    {"stt", QVariantMap{{"provider", "deepgram"}, {"model", "nova-3"}, {"language", "en"}}},
                    {"format", QVariantMap{{"enabled", true}, {"provider", "openrouter"}, {"model", "google/gemini-2.5-flash"},
                                           {"cleanup", "medium"}, {"style", "casual"}}},
                    {"audio", QVariantMap{{"inputDevice", ""}}},
                    {"insert", QVariantMap{{"mode", "paste"}, {"defaultKeys", "ctrl-v"}, {"restoreDelayMs", 500},
                                           {"appendSpace", true}, {"copyToPrimary", false},
                                           {"appRules", QVariantList{
                                               QVariantMap{{"pattern", "ghostty|kitty|velocitty"}, {"keys", "shift-insert"}},
                                               QVariantMap{{"pattern", "libreoffice|<b>x</b>"}, {"keys", "ctrl-v"},
                                                           {"restoreDelayMs", 3000}}}}}},
                    {"overlay", QVariantMap{{"showAtRest", true}, {"position", "bottom"}}}}},
                {"dictionary", QVariantList{QVariantMap{{"id", "d1"}, {"word", "nodaysidle"}, {"starred", true}},
                                            QVariantMap{{"id", "d2"}, {"word", "hyper land"}, {"correction", "Hyprland"}}}},
                {"snippets", QVariantList{QVariantMap{{"id", "s1"}, {"trigger", "my calendar link"},
                                                      {"content", "https://cal.example/me"}}}},
                {"suggestions", QVariantList{QVariantMap{{"id", "g1"}, {"from", "omar key"}, {"to", "Omarchy"}}}},
                {"secrets", QVariantList{QVariantMap{{"name", "deepgram"}, {"present", true}}}},
                {"version", "0.1.0"}});
            emit client.connectedChanged(true); // no offline banner in the README pictures
            for (const QString &p : {"history", "dictionary", "snippets", "settings"}) {
                hub.setPage(p);
                if (p == QLatin1String("history")) {
                    QMetaObject::invokeMethod(findItem(wins.hub->contentItem(), "entryText")->parentItem()->parentItem(), "clicked");
                }
                if (p == QLatin1String("settings")) wins.hub->resize(980, 2300); // the whole page in one picture
                // Settings' list calls fail with no engine; let that notice fade (4 s) before the picture.
                QTest::qWait(p == QLatin1String("settings") ? 4300 : 200);
                wins.hub->grabWindow().save(qEnvironmentVariable("HUB_SNAPSHOT") + "/hub-" + p + ".png");
            }
        }
    }

    void offlineBannerShownWhenDisconnected()
    {
        QTest::failOnWarning();
        Theme theme("/nonexistent");
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        OverlayController overlay(&client);
        QQmlApplicationEngine qml;
        qml.rootContext()->setContextProperty("theme", &theme);
        qml.rootContext()->setContextProperty("hub", &hub);
        qml.rootContext()->setContextProperty("overlay", &overlay);
        const Windows wins = loadWindows(qml, QML_DIR, overlay, hub, false);
        QVERIFY(wins.hub);
        wins.hub->show();
        QVERIFY(QTest::qWaitForWindowExposed(wins.hub));
        QQuickItem *banner = findItem(wins.hub->contentItem(), "offlineBanner");
        QVERIFY(banner);
        QVERIFY(banner->isVisible());
        emit client.connectedChanged(true);
        QVERIFY(hub.connected());
        QVERIFY(!banner->isVisible());
        emit client.connectedChanged(false);
        QVERIFY(banner->isVisible());
    }

    void hubWindowIsNeverTheOverlay()
    {
        QTest::failOnWarning();
        Theme theme("/nonexistent");
        EngineClient client("/nonexistent/control.json");
        HubController hub(&client);
        OverlayController overlay(&client);
        QQmlApplicationEngine qml;
        qml.rootContext()->setContextProperty("theme", &theme);
        qml.rootContext()->setContextProperty("hub", &hub);
        qml.rootContext()->setContextProperty("overlay", &overlay);
        const Windows wins = loadWindows(qml, QML_DIR, overlay, hub, false);
        QVERIFY(wins.hub);
        QVERIFY(wins.overlay);
        QVERIFY(wins.hub != wins.overlay);
        QCOMPARE(wins.overlay->title(), QString("reverseuno-overlay"));
        // what OverlayController::attachWindow sets; the Hub stays an ordinary window
        QCOMPARE(wins.overlay->flags() & Qt::WindowType_Mask, Qt::Tool);
        QVERIFY(wins.overlay->flags().testFlags(Qt::FramelessWindowHint | Qt::WindowDoesNotAcceptFocus));
        QCOMPARE(wins.hub->flags() & Qt::WindowType_Mask, Qt::Window);
        QVERIFY(!wins.hub->flags().testAnyFlags(Qt::FramelessWindowHint | Qt::WindowDoesNotAcceptFocus
                                                | Qt::WindowStaysOnTopHint));
        // both start hidden: the Hub waits for a `page` event (or the tray)
        QVERIFY(!wins.hub->isVisible());
        QVERIFY(!wins.overlay->isVisible());
        QTest::ignoreMessage(QtWarningMsg, "This plugin does not support raise()"); // offscreen only
        emit client.event({{"type", "page"}, {"page", "dictionary"}});
        QVERIFY(wins.hub->isVisible());
        QVERIFY(!wins.overlay->isVisible());
    }
};

int main(int argc, char *argv[])
{
    qputenv("QT_QPA_PLATFORM", "offscreen"); // never put windows on the live desktop
    QGuiApplication app(argc, argv);
    TstHistory t;
    return QTest::qExec(&t, argc, argv);
}
#include "tst_history.moc"
