#include <QtTest>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>

#include "EngineClient.h"
#include "SseParser.h"

struct Req { QByteArray head, body; QTcpSocket *sock; };

// Minimal HTTP/1.1 fake: records each complete request and lets the test answer it.
class FakeServer : public QTcpServer
{
public:
    QList<Req> reqs;
    FakeServer() {
        QVERIFY2(listen(QHostAddress::LocalHost, 0), "listen");
        connect(this, &QTcpServer::newConnection, this, [this] {
            QTcpSocket *s = nextPendingConnection();
            connect(s, &QTcpSocket::readyRead, this, [this, s] {
                QByteArray buf = s->property("buf").toByteArray() + s->readAll();
                const int end = buf.indexOf("\r\n\r\n");
                if (end >= 0) {
                    const QByteArray head = buf.left(end);
                    int len = 0;
                    for (const QByteArray &l : head.split('\n'))
                        if (l.toLower().startsWith("content-length:")) len = l.mid(15).trimmed().toInt();
                    if (buf.size() >= end + 4 + len) {
                        reqs.append({head, buf.mid(end + 4, len), s});
                        buf.clear();
                    }
                }
                s->setProperty("buf", buf);
            });
        });
    }
};

static bool hasHeader(const QByteArray &head, const QByteArray &line)
{
    return head.toLower().contains("\r\n" + line.toLower());
}

static void writeControl(const QString &path, int port, const QString &token)
{
    QFile f(path);
    QVERIFY(f.open(QIODevice::WriteOnly | QIODevice::Truncate));
    f.write(QJsonDocument(QJsonObject{{"port", port}, {"token", token}, {"pid", 1}, {"startedAt", 0}}).toJson());
}

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
    void connectsStreamsCallsAndReconnectsWithFreshControl()
    {
        QTemporaryDir dir;
        const QString ctl = dir.filePath("control.json");
        FakeServer s1, s2;
        writeControl(ctl, s1.serverPort(), "tok1");

        EngineClient c(ctl);
        QSignalSpy conn(&c, &EngineClient::connectedChanged);
        QSignalSpy ev(&c, &EngineClient::event);
        QSignalSpy done(&c, &EngineClient::callFinished);

        // stream request: GET /events, bearer token, never an Origin header
        QTRY_VERIFY_WITH_TIMEOUT(s1.reqs.size() >= 1, 5000);
        const Req stream = s1.reqs.first();
        QVERIFY(stream.head.startsWith("GET /events "));
        QVERIFY(hasHeader(stream.head, "authorization: Bearer tok1"));
        QVERIFY(!hasHeader(stream.head, "origin:"));
        QCOMPARE(conn.count(), 0);

        stream.sock->write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n"
                           ": ping\n\ndata: {\"type\":\"page\",\"page\":\"a\"}\n\n"
                           "data: {\"type\":\"page\",\"page\":\"b\"}\n\n");
        QTRY_COMPARE_WITH_TIMEOUT(ev.count(), 2, 5000);
        QCOMPARE(conn.count(), 1);
        QCOMPARE(conn.first().first().toBool(), true);
        QCOMPARE(ev.at(0).first().toMap().value("page").toString(), QString("a"));
        QCOMPARE(ev.at(1).first().toMap().value("page").toString(), QString("b"));

        // bootstrap RPC fires on connect
        QTRY_VERIFY_WITH_TIMEOUT(s1.reqs.size() >= 2, 5000);
        const Req rpc = s1.reqs.at(1);
        QVERIFY(rpc.head.startsWith("POST /rpc/bootstrap "));
        QVERIFY(hasHeader(rpc.head, "authorization: Bearer tok1"));
        QVERIFY(!hasHeader(rpc.head, "origin:"));
        QCOMPARE(rpc.body, QByteArray("{\"args\":[]}"));
        const QByteArray json = "{\"ok\":true,\"result\":{\"x\":1}}";
        rpc.sock->write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + QByteArray::number(json.size()) + "\r\nConnection: close\r\n\r\n" + json);
        QTRY_COMPARE_WITH_TIMEOUT(done.count(), 1, 5000);
        QCOMPARE(done.first().at(0).toString(), QString("bootstrap"));
        QCOMPARE(done.first().at(1).toMap().value("x").toInt(), 1);
        QVERIFY(done.first().at(2).toString().isEmpty());

        // engine restarts: new port/token in control.json, old stream dropped
        writeControl(ctl, s2.serverPort(), "tok2");
        stream.sock->disconnectFromHost();
        QTRY_COMPARE_WITH_TIMEOUT(conn.count(), 2, 5000);
        QCOMPARE(conn.last().first().toBool(), false);
        QTRY_VERIFY_WITH_TIMEOUT(s2.reqs.size() >= 1, 8000);
        QVERIFY(s2.reqs.first().head.startsWith("GET /events "));
        QVERIFY(hasHeader(s2.reqs.first().head, "authorization: Bearer tok2"));
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
