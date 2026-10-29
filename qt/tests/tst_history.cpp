#include <QtTest>
#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
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
                entry("c", "<b>x</b> & literal markup", "firefox", 1761720000000, "GitHub")}},
                {"settings", QVariantMap{
                    {"stt", QVariantMap{{"provider", "deepgram"}, {"model", "nova-3"}, {"language", "en"}}},
                    {"format", QVariantMap{{"enabled", true}, {"provider", "openrouter"}, {"model", "google/gemini-2.5-flash"},
                                           {"cleanup", "medium"}, {"style", "casual"}}},
                    {"audio", QVariantMap{{"inputDevice", ""}}},
                    {"overlay", QVariantMap{{"showAtRest", true}, {"position", "bottom"}, {"sounds", false}}}}},
                {"dictionary", QVariantList{QVariantMap{{"id", "d1"}, {"word", "nodaysidle"}, {"starred", true}},
                                            QVariantMap{{"id", "d2"}, {"word", "hyper land"}, {"correction", "Hyprland"}}}},
                {"snippets", QVariantList{QVariantMap{{"id", "s1"}, {"trigger", "my calendar link"},
                                                      {"content", "https://cal.example/me"}}}},
                {"suggestions", QVariantList{QVariantMap{{"id", "g1"}, {"from", "omar key"}, {"to", "Omarchy"}}}},
                {"secrets", QVariantList{QVariantMap{{"name", "deepgram"}, {"present", true}}}},
                {"version", "0.9.0"}});
            for (const QString &p : {"history", "dictionary", "snippets", "settings"}) {
                hub.setPage(p);
                if (p == QLatin1String("history")) {
                    QMetaObject::invokeMethod(findItem(wins.hub->contentItem(), "entryText")->parentItem()->parentItem(), "clicked");
                }
                QTest::qWait(200);
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
