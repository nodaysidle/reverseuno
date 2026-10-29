#include <QtTest>

#include "EngineClient.h"
#include "SseParser.h"

class TstEngineClient : public QObject
{
    Q_OBJECT
private slots:
    void noControlFileReportsDisconnectedAndKeepsRetrying()
    {
        EngineClient c("/nonexistent/control.json");
        QSignalSpy conn(&c, &EngineClient::connectedChanged);
        QVERIFY(!c.connected());
        QTRY_VERIFY_WITH_TIMEOUT(c.connectAttempts() >= 2, 3000);
        QVERIFY(!c.connected());
        QCOMPARE(conn.count(), 0);
    }
    void callWithoutEngineReportsError()
    {
        EngineClient c("/nonexistent/control.json");
        QSignalSpy done(&c, &EngineClient::callFinished);
        c.call("bootstrap");
        QCOMPARE(done.count(), 1);
        QVERIFY(!done.first().at(2).toString().isEmpty());
    }
    void sseChunkSplitAcrossTwoReads()
    {
        SseParser p;
        QVERIFY(p.feed(": ping\n\ndata: {\"type\":\"le").isEmpty());
        QVERIFY(p.feed("vel\",\"level\":0.5}\n").isEmpty());
        const auto out = p.feed("\ndata: {\"type\":\"refresh\"}\r\n\r\n");
        QCOMPARE(out.size(), 2);
        QCOMPARE(out.at(0), QByteArray("{\"type\":\"level\",\"level\":0.5}"));
        QCOMPARE(out.at(1), QByteArray("{\"type\":\"refresh\"}"));
    }
};

QTEST_MAIN(TstEngineClient)
#include "tst_engineclient.moc"
