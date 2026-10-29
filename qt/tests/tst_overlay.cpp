#include <QtTest>
#include <QGuiApplication>
#include <QScreen>
#include <QWindow>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <LayerShellQt/window.h>

#include "EngineClient.h"
#include "OverlayController.h"
#include "Theme.h"

static QVariantMap stateEv(const QVariantMap &s) { return {{"type", "state"}, {"state", s}}; }
static QVariantMap overlayEv(bool visible, int x, int y, int w, int h)
{
    return {{"type", "overlay"}, {"visible", visible}, {"x", x}, {"y", y}, {"width", w}, {"height", h}};
}

class TstOverlay : public QObject
{
    Q_OBJECT
private slots:
    void controllerMapsStateEventsToTheRightWord()
    {
        const QList<QPair<QVariantMap, QString>> cases = {
            {{{"state", "idle"}}, "Hold Right Super to talk"},
            {{{"state", "listening"}}, "Listening"},
            {{{"state", "transcribing"}}, "Transcribing"},
            {{{"state", "formatting"}}, "Polishing"},
            {{{"state", "inserting"}}, "Pasting"},
            {{{"state", "done"}, {"message", "Pasted"}, {"text", "hi"}}, "Pasted"},
            {{{"state", "done"}, {"message", "Saved to history"}, {"text", "hi"}}, "Saved to history"},
            {{{"state", "done"}, {"text", "hi"}}, "Pasted"},
            {{{"state", "done"}}, "Saved"},
            {{{"state", "skipped"}}, "Nothing to paste"},
            {{{"state", "error"}, {"message", "Mic unavailable"}}, "Mic unavailable"},
            {{{"state", "error"}}, "Something went wrong"},
            {{{"state", "cancelled"}}, "Cancelled"},
        };
        EngineClient client("/nonexistent/control.json");
        OverlayController c(&client);
        for (const auto &[s, word] : cases) {
            QCOMPARE(OverlayController::wordFor(s), word);
            emit client.event(stateEv(s));
            QCOMPARE(c.word(), word);
            QCOMPARE(c.state(), s.value("state").toString());
        }
    }
    void controllerHidesOnOverlayFalseAndShowsOnTrueWithGeometry()
    {
        EngineClient client("/nonexistent/control.json");
        OverlayController c(&client);
        QWindow w;
        c.attachWindow(&w, false);
        c.setContentSize({200, 36});
        emit client.event(stateEv({{"state", "listening"}}));
        const QRect screen = QGuiApplication::primaryScreen()->geometry();
        emit client.event(overlayEv(true, screen.x() + 100, screen.y() + 40, 500, 108));
        QVERIFY(c.visible());
        QVERIFY(w.isVisible());
        // pill-sized window centred in the engine's rect
        const QRect want(screen.x() + 250, screen.y() + 76, 200, 36);
        QCOMPARE(c.geometry(), want);
        QCOMPARE(w.geometry(), want);

        emit client.event(overlayEv(false, 0, 0, 0, 0));
        QVERIFY(!c.visible());
        QVERIFY(!w.isVisible());

        emit client.event(overlayEv(true, screen.x() + 100, screen.y() + 40, 500, 108));
        QVERIFY(w.isVisible());
        emit client.connectedChanged(false); // engine down: stay hidden
        QVERIFY(!c.visible());
        QVERIFY(!w.isVisible());
    }
    void restingPillWhenIdleAndVisible()
    {
        EngineClient client("/nonexistent/control.json");
        OverlayController c(&client);
        emit client.event(stateEv({{"state", "idle"}}));
        QVERIFY(!c.resting());
        emit client.event(overlayEv(true, 0, 0, 500, 108));
        QVERIFY(c.resting());
        QVERIFY(!c.active());
        emit client.event(stateEv({{"state", "listening"}}));
        QVERIFY(!c.resting());
        QVERIFY(c.active());
    }
    void levelIsClampedToZeroOne()
    {
        EngineClient client("/nonexistent/control.json");
        OverlayController c(&client);
        emit client.event(stateEv({{"state", "listening"}}));
        emit client.event({{"type", "level"}, {"level", 0.4}});
        QCOMPARE(c.level(), 0.4);
        emit client.event({{"type", "level"}, {"level", 3.0}});
        QCOMPARE(c.level(), 1.0);
        emit client.event({{"type", "level"}, {"level", -1.0}});
        QCOMPARE(c.level(), 0.0);
        emit client.event({{"type", "level"}, {"level", 0.7}});
        emit client.event(stateEv({{"state", "idle"}}));
        QCOMPARE(c.level(), 0.0);
    }
    void cancelCallsDictationCancelThroughTheClient()
    {
        // No engine: EngineClient reports each call at once through callFinished.
        EngineClient client("/nonexistent/control.json");
        OverlayController c(&client);
        QSignalSpy calls(&client, &EngineClient::callFinished);
        emit client.event(overlayEv(true, 0, 0, 500, 108));
        c.cancel(); // resting pill: no click action
        QCOMPARE(calls.count(), 0);
        emit client.event(stateEv({{"state", "listening"}}));
        c.cancel();
        QCOMPARE(calls.count(), 1);
        QCOMPARE(calls.first().at(0).toString(), QString("dictationCancel"));
    }
    void layerShellNeverTakesKeyboardFocus()
    {
        EngineClient client("/nonexistent/control.json");
        OverlayController c(&client);
        QWindow w;
        c.attachWindow(&w, true);
        auto *ls = LayerShellQt::Window::get(&w);
        if (!ls) QSKIP("LayerShellQt::Window::get returned null on this platform");
        QCOMPARE(ls->keyboardInteractivity(), LayerShellQt::Window::KeyboardInteractivityNone);
        QCOMPARE(ls->layer(), LayerShellQt::Window::LayerOverlay);
        QCOMPARE(ls->scope(), QString("reverseuno-overlay"));
        QCOMPARE(ls->anchors(), LayerShellQt::Window::Anchors(LayerShellQt::Window::AnchorTop | LayerShellQt::Window::AnchorLeft));

        c.setContentSize({200, 36});
        const QRect screen = QGuiApplication::primaryScreen()->geometry();
        emit client.event(overlayEv(true, screen.x() + 100, screen.y() + 40, 500, 108));
        QCOMPARE(ls->margins(), QMargins(250, 76, 0, 0));
    }
    void overlayQmlLoadsAndReportsPillSize()
    {
        QTest::failOnWarning();
        Theme theme("/nonexistent");
        EngineClient client("/nonexistent/control.json");
        OverlayController c(&client);
        QQmlApplicationEngine qml;
        qml.rootContext()->setContextProperty("theme", &theme);
        qml.rootContext()->setContextProperty("overlay", &c);
        qml.load(QUrl::fromLocalFile(QML_DIR "/Overlay.qml"));
        auto *w = qobject_cast<QQuickWindow *>(qml.rootObjects().value(0));
        QVERIFY(w);
        c.attachWindow(w, false);
        QVERIFY(!w->isVisible());
        emit client.event(stateEv({{"state", "listening"}}));
        emit client.event({{"type", "level"}, {"level", 0.5}});
        emit client.event(overlayEv(true, 0, 0, 500, 108));
        QVERIFY(w->isVisible());
        QVERIFY(c.contentSize().width() > 0);
        QCOMPARE(c.contentSize().height(), 32);
        QCOMPARE(w->size(), c.contentSize());
    }
};

int main(int argc, char *argv[])
{
    qputenv("QT_QPA_PLATFORM", "offscreen"); // never put windows on the live desktop
    QGuiApplication app(argc, argv);
    TstOverlay t;
    return QTest::qExec(&t, argc, argv);
}
#include "tst_overlay.moc"
