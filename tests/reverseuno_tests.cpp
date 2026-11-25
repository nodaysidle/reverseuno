#include <QDBusConnection>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSignalSpy>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QWebSocket>
#include <QWebSocketServer>
#include <QtMath>
#include <QtTest>

#include "backend.h"
#include "cli.h"
#include "config.h"
#include "paste.h"
#include "hypr.h"
#include "service.h"
#include "stt.h"
#include "theme.h"
#include "translator.h"
#include "tray.h"

namespace {

void writeFile(const QString &path, const QByteArray &contents, bool executable = false) {
    QFile file(path);
    QVERIFY(file.open(QIODevice::WriteOnly));
    file.write(contents);
    file.close();
    if (executable)
        QVERIFY(QFile::setPermissions(path, QFile::ReadOwner | QFile::WriteOwner | QFile::ExeOwner));
}

// Mode 0600: owner read and write, nothing for group or others.
bool isPrivate(const QString &path) {
    const auto permissions = QFileInfo(path).permissions();
    const auto others = QFileDevice::ReadGroup | QFileDevice::WriteGroup | QFileDevice::ExeGroup | QFileDevice::ReadOther |
                        QFileDevice::WriteOther | QFileDevice::ExeOther | QFileDevice::ExeOwner;
    return (permissions & QFileDevice::ReadOwner) && (permissions & QFileDevice::WriteOwner) && !(permissions & others);
}

QByteArray readFile(const QString &path) {
    QFile file(path);
    return file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
}

// 16 kHz mono int16 sine wave, loud enough to count as speech.
QByteArray sine(int ms, int amplitude = 8000) {
    const int samples = ms * 16;
    QByteArray pcm(samples * 2, '\0');
    auto *out = reinterpret_cast<qint16 *>(pcm.data());
    for (int i = 0; i < samples; ++i)
        out[i] = qint16(amplitude * qSin(2 * M_PI * 440.0 * i / 16000.0));
    return pcm;
}

// Puts a directory of fake tools first (and only) on PATH, so nothing here can
// reach the real clipboard, compositor or keyboard.
class FakeTools {
public:
    FakeTools() : m_path(qgetenv("PATH")) {
        const QString d = m_dir.path();
        auto script = [&](const QString &name, const QString &body) {
            writeFile(d + "/" + name, "#!/bin/sh\nD='" + d.toUtf8() + "'\n" + body.toUtf8(), true);
        };
        script("hyprctl",
               "echo \"hyprctl $*\" >> \"$D/log\"\n"
               "case \"$1\" in\n"
               " -j) /usr/bin/cat \"$D/active.json\" ;;\n"
               " dispatch) [ -e \"$D/canfocus\" ] && /usr/bin/cp \"$D/target.json\" \"$D/active.json\"; echo ok ;;\n"
               "esac\n");
        script("wl-copy",
               "echo \"wl-copy $*\" >> \"$D/log\"\n"
               "case \"$*\" in *--clear*) /usr/bin/rm -f \"$D/clip\" ;; *) /usr/bin/cat > \"$D/clip\" ;; esac\n");
        script("wl-paste",
               "case \"$*\" in\n"
               " *--list-types*) echo text/plain ;;\n"
               " *) [ -e \"$D/clip\" ] && /usr/bin/cat \"$D/clip\" || exit 1 ;;\n"
               "esac\n");
        script("wtype",
               "echo \"wtype $*\" >> \"$D/log\"\n"
               "[ \"$1\" = \"-\" ] && /usr/bin/cat > \"$D/typed\"\n"
               "exit 0\n");
        setActive("0x1", "firefox");
        qputenv("PATH", d.toUtf8());
    }
    ~FakeTools() { qputenv("PATH", m_path); }

    QString path(const QString &name) const { return m_dir.path() + "/" + name; }
    void setActive(const QString &address, const QString &windowClass) {
        writeFile(path("active.json"), windowJson(address, windowClass));
    }
    void setFocusable(const QString &address, const QString &windowClass) {
        writeFile(path("target.json"), windowJson(address, windowClass));
        writeFile(path("canfocus"), "1");
    }
    QString clip() const { return QString::fromUtf8(readFile(path("clip"))); }
    QString log() const { return QString::fromUtf8(readFile(path("log"))); }

private:
    static QByteArray windowJson(const QString &address, const QString &windowClass) {
        return QJsonDocument(QJsonObject{{"address", address}, {"class", windowClass}, {"title", "t"}}).toJson();
    }
    QTemporaryDir m_dir;
    QByteArray m_path;
};


// A temp Hyprland config folder (REVERSEUNO_HYPR_DIR) and a fake hyprctl that logs
// its calls. configerrors reports an error while the "reject" file exists and the
// F8 bind is in bindings.lua, or always while the "always" file exists.
class HyprRig {
public:
    HyprRig() : m_path(qgetenv("PATH")), m_env(qgetenv("REVERSEUNO_HYPR_DIR")) {
        QVERIFY(QDir(dir()).mkpath("."));
        QVERIFY(QDir(tools()).mkpath("."));
        writeFile(tools() + "/hyprctl",
                  "#!/bin/sh\nH='" + dir().toUtf8() + "'\nD='" + tools().toUtf8() + "'\n"
                  "echo \"$1\" >> \"$D/log\"\n"
                  "if [ \"$1\" = configerrors ]; then\n"
                  "  [ -e \"$D/always\" ] && echo 'Config error in file x.lua at line 1: old problem'\n"
                  "  [ -e \"$D/reject\" ] && /usr/bin/grep -q 'bind_hold(\"F8\"' \"$H/bindings.lua\" && echo 'Config error in file bindings.lua at line 9: bad bind'\n"
                  "fi\nexit 0\n", true);
        qputenv("PATH", tools().toUtf8());
        qputenv("REVERSEUNO_HYPR_DIR", dir().toUtf8());
    }
    ~HyprRig() {
        qputenv("PATH", m_path);
        qputenv("REVERSEUNO_HYPR_DIR", m_env);
    }
    QString dir() const { return m_root.path() + "/hypr"; }
    QString tools() const { return m_root.path() + "/tools"; }
    QString file(const QString &name) const { return dir() + "/" + name; }
    QByteArray read(const QString &name) const { return readFile(file(name)); }
    void write(const QString &name, const QByteArray &data) const { writeFile(file(name), data); }
    int calls(const QString &what) const { return QString::fromUtf8(readFile(tools() + "/log")).split('\n').count(what); }
    int backups(const QString &name) const { return QDir(dir()).entryList({name + ".bak.*"}, QDir::Files).size(); }
    void touch(const QString &marker) const { writeFile(tools() + "/" + marker, "1"); }

private:
    QTemporaryDir m_root;
    QByteArray m_path, m_env;
};

// What the user's bindings.lua looked like before: other binds around an old block.
const QByteArray kPrefix = "-- my binds\no.bind(\"F10\", \"x\", \"y\")\n\n";
const QByteArray kOldBlock =
    "-- >>> reverseuno (managed by scripts/install-hyprland.py) [DORMANT]\n"
    "-- o.bind(\"Super_R\", \"reverseuno hold\", \"/home/arch/.local/bin/reverseuno start\")\n"
    "o.window({ class = \"^reverseuno$\", title = \"^reverseuno$\" }, {\n  float = true,\n  size = { 1180, 820 },\n})\n"
    "-- <<< reverseuno\n";
const QByteArray kSuffix = "\n-- after\no.bind(\"F11\", \"z\", \"w\")\n";

// A one-shot HTTP/1.1 server standing in for Deepgram's batch endpoint.
class HttpFake : public QObject {
public:
    struct Request {
        QString line;
        QMap<QByteArray, QByteArray> headers;
        QByteArray body;
    };
    HttpFake() {
        QVERIFY(server.listen(QHostAddress::LocalHost, 0));
        connect(&server, &QTcpServer::newConnection, this, [this] {
            QTcpSocket *socket = server.nextPendingConnection();
            connect(socket, &QTcpSocket::readyRead, this, [this, socket] { read(socket); });
        });
    }
    QString url() const { return QStringLiteral("http://127.0.0.1:%1/v1/listen").arg(server.serverPort()); }

    int status = 200;
    QByteArray body = R"({"results":{"channels":[{"detected_language":"en","alternatives":[{"transcript":"hello world"}]}]}})";
    bool hold = false;
    QList<Request> requests;

private:
    void read(QTcpSocket *socket) {
        QByteArray &buffer = m_buffers[socket];
        buffer += socket->readAll();
        const int end = buffer.indexOf("\r\n\r\n");
        if (end < 0)
            return;
        const QList<QByteArray> lines = buffer.left(end).split('\n');
        Request request;
        request.line = QString::fromLatin1(lines.value(0)).trimmed();
        for (int i = 1; i < lines.size(); ++i) {
            const int colon = lines[i].indexOf(':');
            if (colon > 0)
                request.headers.insert(lines[i].left(colon).trimmed().toLower(), lines[i].mid(colon + 1).trimmed());
        }
        const int length = request.headers.value("content-length").toInt();
        if (buffer.size() < end + 4 + length)
            return;
        request.body = buffer.mid(end + 4, length);
        m_buffers.remove(socket);
        requests << request;
        if (hold)
            return;
        socket->write("HTTP/1.1 " + QByteArray::number(status) + " X\r\nContent-Type: application/json\r\nContent-Length: " +
                      QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body);
        socket->disconnectFromHost();
    }
    QTcpServer server;
    QHash<QTcpSocket *, QByteArray> m_buffers;
};

// A Deepgram-shaped WebSocket server.
class WsFake : public QObject {
public:
    enum Mode { Normal, Silent, CloseAtOnce };
    WsFake() : server("fake", QWebSocketServer::NonSecureMode) {
        QVERIFY(server.listen(QHostAddress::LocalHost, 0));
        connect(&server, &QWebSocketServer::newConnection, this, [this] {
            QWebSocket *socket = server.nextPendingConnection();
            requests << socket->request();
            if (mode == CloseAtOnce) {
                socket->close();
                return;
            }
            connect(socket, &QWebSocket::binaryMessageReceived, this, [this, socket](const QByteArray &frame) {
                bytes += frame.size();
                if (mode == Normal && !sentInterim) {
                    sentInterim = true;
                    socket->sendTextMessage(result("hel", false));
                }
            });
            connect(socket, &QWebSocket::textMessageReceived, this, [this, socket](const QString &message) {
                texts << message;
                if (message.contains("CloseStream") && mode == Normal) {
                    for (const QString &segment : finals)
                        socket->sendTextMessage(result(segment, true));
                    socket->sendTextMessage(R"({"type":"Metadata"})");
                }
            });
        });
    }
    QString url() const { return QStringLiteral("ws://127.0.0.1:%1/v1/listen").arg(server.serverPort()); }
    static QString result(const QString &text, bool final) {
        return QString::fromUtf8(QJsonDocument(QJsonObject{
            {"type", "Results"},
            {"is_final", final},
            {"channel", QJsonObject{{"alternatives", QJsonArray{QJsonObject{{"transcript", text}}}}}}}).toJson(QJsonDocument::Compact));
    }

    Mode mode = Normal;
    QStringList finals{"hello", "world"};
    QList<QNetworkRequest> requests;
    QStringList texts;
    qint64 bytes = 0;
    bool sentInterim = false;

private:
    QWebSocketServer server;
};

class FakeCapture : public Capture {
public:
    QString start(const QString &) override {
        ++started;
        return problem;
    }
    void stop() override { ++stopped; }
    int started = 0, stopped = 0;
    QString problem;
};

const QString kDeadHttp = QStringLiteral("http://127.0.0.1:1/v1/listen");
const QString kDeadWs = QStringLiteral("ws://127.0.0.1:1/v1/listen");

// A Backend with every outside world faked: capture, Deepgram, window and insert.
struct Rig {
    struct Insert {
        QString text, mode;
        Paste::Window target;
    };
    QTemporaryDir dir;
    HttpFake http;
    WsFake ws;
    FakeCapture capture;
    QList<Insert> inserts;
    std::unique_ptr<Backend> backend;

    explicit Rig(bool wsUp = false, bool httpUp = true, int maxRecordingMs = 5 * 60 * 1000) {
        BackendOptions options;
        options.configPath = dir.path() + "/config/config.json";
        options.sttUrl = httpUp ? http.url() : kDeadHttp;
        options.sttWsUrl = wsUp ? ws.url() : kDeadWs;
        options.settleMs = 0;
        options.maxRecordingMs = maxRecordingMs;
        options.capture = &capture;
        options.activeWindow = [] { return Paste::Window{"0xabc", "kitty", "shell"}; };
        options.insert = [this](const QString &text, const QString &mode, const Paste::Window &target) {
            inserts << Insert{text, mode, target};
            Paste::Result result;
            result.ok = true;
            result.note = "Pasted";
            return result;
        };
        backend = std::make_unique<Backend>(options);
        backend->setApiKey("test-key");
    }
    void speak(int ms) { backend->pushPcm(sine(ms)); }
};

} // namespace

class ReverseunoTests : public QObject {
    Q_OBJECT

private slots:
    void initTestCase() {
        // A real key must never reach a fake server or the network from a test.
        qunsetenv("DEEPGRAM_API_KEY");
        qunsetenv("REVERSEUNO_STT_URL");
        qunsetenv("REVERSEUNO_STT_WS_URL");
        // Nothing here may read or write the real Hyprland config.
        static QTemporaryDir hypr;
        qputenv("REVERSEUNO_HYPR_DIR", hypr.path().toUtf8());
    }

    // ---- theme -------------------------------------------------------------

    void themeFallsBack() {
        QTemporaryDir dir;
        Theme theme(dir.path());
        QCOMPARE(theme.accent(), QString("#FFD60A"));
        QCOMPARE(theme.accentForeground(), QString("black"));
        QCOMPARE(theme.radius(), 0);
    }

    void themeReadsAndRewatchesAccent() {
        QTemporaryDir dir;
        QDir(dir.path()).mkpath("theme");
        const QString colors = dir.path() + "/theme/colors.toml";
        writeFile(colors, "accent = \"#112233\"\n");
        Theme theme(dir.path());
        QCOMPARE(theme.accent(), QString("#112233"));
        QCOMPARE(theme.accentForeground(), QString("white"));

        // A theme switch replaces the file; the watcher must survive two of them.
        for (const char *accent : {"#445566", "#aabbcc"}) {
            writeFile(colors + ".new", QByteArray("accent = \"") + accent + "\"\n");
            QVERIFY(QFile::remove(colors));
            QVERIFY(QFile::rename(colors + ".new", colors));
            QTRY_COMPARE(theme.accent(), QString(accent));
        }
    }

    void themeFollowsHyprlandRounding() {
        QTemporaryDir dir;
        const QString response = dir.filePath("response.json"), command = dir.filePath("hyprctl");
        writeFile(command, "#!/bin/sh\n/usr/bin/cat '" + response.toUtf8() + "'\n", true);
        writeFile(response, "{\"int\":0}");
        Theme theme(dir.path(), nullptr, command);
        QCOMPARE(theme.radius(), 0);
        writeFile(response, "{\"int\":6}");
        QTRY_COMPARE(theme.radius(), 6);
    }

    // ---- translator ----------------------------------------------------------

    void translatorFallsBackToEnglish() {
        QTemporaryDir dir;
        Translator translator(dir.path() + "/language", dir.path());
        QCOMPARE(translator.language(), QString("en"));
        QVERIFY(translator.translate("Main", "Save").isNull());
    }

    void translatorFallsBackFromRegionToLanguageToEnglish() {
        QTemporaryDir dir;
        writeFile(dir.path() + "/language", "pt-BR\n");
        writeFile(dir.path() + "/pt.json", R"({"Save": "Guardar", "Open": "Abrir"})");
        writeFile(dir.path() + "/pt-BR.json", R"({"Save": "Salvar"})");
        Translator translator(dir.path() + "/language", dir.path());
        QCOMPARE(translator.language(), QString("pt-BR"));
        QCOMPARE(translator.translate("Main", "Save"), QString("Salvar")); // pt-BR
        QCOMPARE(translator.translate("Main", "Open"), QString("Abrir"));  // pt
        QVERIFY(translator.translate("Main", "Quit").isNull());            // English
    }

    void translatorPicksPluralForms() {
        QTemporaryDir dir;
        writeFile(dir.path() + "/language", "ru\n");
        writeFile(dir.path() + "/ru.json", R"({"%n clips": {"one": "%n клип", "few": "%n клипа", "many": "%n клипов", "other": "%n клипа"}})");
        Translator translator(dir.path() + "/language", dir.path());
        QCOMPARE(translator.translate("Main", "%n clips", nullptr, 21), QString("%n клип"));
        QCOMPARE(translator.translate("Main", "%n clips", nullptr, 25), QString("%n клипов"));
    }

    void englishCatalogCoversEveryString() {
        static const QRegularExpression direct("(?<![A-Za-z_])(?:qsTr|tr)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        static const QRegularExpression translate("translate\\(\\s*\"[^\"]*\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        QSet<QString> used;
        QStringList files = QDir(SRCDIR).entryList({"*.cpp", "Main.qml"}, QDir::Files);
        QVERIFY(files.size() > 5);
        for (const QString &name : std::as_const(files)) {
            const QString text = QString::fromUtf8(readFile(QString(SRCDIR) + "/" + name));
            for (const auto &re : {direct, translate})
                for (auto it = re.globalMatch(text); it.hasNext();)
                    used.insert(QJsonDocument::fromJson("[\"" + it.next().captured(1).toUtf8() + "\"]").array().first().toString());
        }
        QVERIFY(used.size() > 40);
        const QJsonObject catalog = QJsonDocument::fromJson(readFile(":/i18n/en.json")).object();
        for (const QString &key : std::as_const(used)) {
            QVERIFY2(catalog.contains(key), qPrintable("missing from en.json: " + key));
            if (key.contains("%n"))
                QVERIFY(catalog.value(key).toObject().contains("one") && catalog.value(key).toObject().contains("other"));
            else
                QCOMPARE(catalog.value(key).toString(), key);
        }
        for (const QString &key : catalog.keys())
            QVERIFY2(used.contains(key), qPrintable("stale in en.json: " + key));
    }

    // ---- paste rules and insertion -------------------------------------------

    void pasteRuleSelection_data() {
        QTest::addColumn<QString>("windowClass");
        QTest::addColumn<QString>("keys");
        for (const char *c : {"ghostty", "com.mitchellh.ghostty", "Alacritty", "kitty", "foot", "org.wezfurlong.wezterm",
                              "XTerm", "org.kde.konsole", "Code", "code-oss", "Cursor", "dev.zed.Zed", "jetbrains-idea"})
            QTest::newRow(c) << c << "shift-insert";
        for (const char *c : {"firefox", "chromium", "barcode-scanner", "org.gnome.Nautilus", "", "telegramdesktop"})
            QTest::newRow(c[0] ? c : "empty") << c << "ctrl-v";
    }
    void pasteRuleSelection() {
        QFETCH(QString, windowClass);
        QFETCH(QString, keys);
        QCOMPARE(Paste::keysFor(windowClass), keys);
    }

    void pastePressesCtrlVAndRestoresTheClipboard() {
        FakeTools tools;
        writeFile(tools.path("clip"), "old");
        const Paste::Window target{"0x1", "firefox", "t"};
        const Paste::Result result = Paste::insert("hello", "paste", target);
        QVERIFY2(result.ok, qPrintable(result.error));
        QCOMPARE(tools.clip(), QString("hello"));
        QVERIFY(tools.log().contains("wtype -M ctrl -k v -m ctrl"));
        QVERIFY(result.restoreDelayMs > 0);
        QVERIFY(Paste::restore(result.snapshot, result.written));
        QCOMPARE(tools.clip(), QString("old"));
    }

    void pasteDoesNotRestoreOverANewerClipboard() {
        FakeTools tools;
        writeFile(tools.path("clip"), "old");
        const Paste::Result result = Paste::insert("hello", "paste", {"0x1", "firefox", "t"});
        QVERIFY(result.ok);
        writeFile(tools.path("clip"), "something the user copied meanwhile");
        QVERIFY(!Paste::restore(result.snapshot, result.written));
        QCOMPARE(tools.clip(), QString("something the user copied meanwhile"));
    }

    void pasteUsesShiftInsertInTerminals() {
        FakeTools tools;
        tools.setActive("0x1", "kitty");
        QVERIFY(Paste::insert("hi", "paste", {"0x1", "kitty", "t"}).ok);
        QVERIFY(tools.log().contains("wtype -M shift -k Insert -m shift"));
        QVERIFY(!tools.log().contains("-M ctrl"));
    }

    void pasteRefocusesTheOriginalWindow() {
        FakeTools tools;
        tools.setActive("0x2", "firefox");
        tools.setFocusable("0x1", "kitty");
        const Paste::Result result = Paste::insert("hi", "paste", {"0x1", "kitty", "t"});
        QVERIFY2(result.ok, qPrintable(result.error));
        QVERIFY(tools.log().contains("dispatch focuswindow address:0x1"));
        QVERIFY(tools.log().contains("wtype -M shift -k Insert -m shift")); // the refocused window is a terminal
    }

    void pasteLeavesTextOnTheClipboardWhenFocusIsLost() {
        FakeTools tools;
        tools.setActive("0x2", "firefox"); // and no way back to 0x1
        const Paste::Result result = Paste::insert("precious words", "paste", {"0x1", "kitty", "t"});
        QVERIFY(!result.ok);
        QVERIFY(result.onClipboard);
        QVERIFY2(result.error.contains("Could not return focus to kitty"), qPrintable(result.error));
        QCOMPARE(tools.clip(), QString("precious words"));
        QVERIFY(!tools.log().contains("wtype"));
    }

    void pasteTypeAndCopyModes() {
        FakeTools tools;
        QVERIFY(Paste::insert("typed text", "type", {"0x1", "firefox", "t"}).ok);
        QCOMPARE(QString::fromUtf8(readFile(tools.path("typed"))), QString("typed text"));
        QVERIFY(Paste::insert("copied text", "copy", {}).ok);
        QCOMPARE(tools.clip(), QString("copied text"));
    }

    void missingToolsAreReported() {
        QTemporaryDir empty;
        const QByteArray saved = qgetenv("PATH");
        qputenv("PATH", empty.path().toUtf8());
        QCOMPARE(Paste::missingTools().size(), 5);
        qputenv("PATH", saved);
        FakeTools tools;
        QStringList missing;
        for (const auto &tool : Paste::missingTools())
            missing << tool.tool;
        QCOMPARE(missing, QStringList{"ffmpeg"});
    }


    // ---- Hyprland blocks ---------------------------------------------------------

    void hyprBlocksUseTheGivenBinaryAndCurrentHelpers() {
        const QString bindings = Hypr::block(Hypr::Bindings, "/usr/bin/reverseuno");
        QVERIFY(bindings.startsWith("-- >>> reverseuno"));
        QVERIFY(bindings.endsWith("-- <<< reverseuno\n"));
        QVERIFY(bindings.contains("o.bind_hold(\"F8\", \"reverseuno: start dictating\", \"/usr/bin/reverseuno start\", "
                                  "\"reverseuno: stop dictating\", \"/usr/bin/reverseuno stop\")"));
        QVERIFY(bindings.contains("o.bind(\"SUPER + U\", \"reverseuno: toggle dictation\", \"/usr/bin/reverseuno toggle\")"));
        QVERIFY(bindings.contains("o.bind(\"F12\", \"reverseuno: cancel dictation\", \"/usr/bin/reverseuno cancel\")"));
        QVERIFY(bindings.contains("size = { 980, 660 }"));
        QVERIFY(!bindings.contains("Super_R"));
        QVERIFY(Hypr::block(Hypr::Autostart, "/opt/a \"b\"/reverseuno").contains("o.launch_on_start(\"/opt/a \\\"b\\\"/reverseuno daemon\")"));
    }

    void hyprReplacesTheOldBlockAndKeepsEverythingElse() {
        HyprRig rig;
        rig.write("bindings.lua", kPrefix + kOldBlock + kSuffix);
        const Hypr::Result result = Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno");
        QVERIFY2(result.ok, qPrintable(result.message));
        QVERIFY(result.changed);
        const QByteArray expected = kPrefix + Hypr::block(Hypr::Bindings, "/usr/bin/reverseuno").toUtf8() + kSuffix;
        QCOMPARE(rig.read("bindings.lua"), expected); // outside the markers: byte for byte
        QVERIFY(!rig.read("bindings.lua").contains("Super_R"));
        QVERIFY(!rig.read("bindings.lua").contains("1180"));
        QCOMPARE(rig.backups("bindings.lua"), 1);
        const QString backup = QDir(rig.dir()).entryList({"bindings.lua.bak.*"}).first();
        QCOMPARE(rig.read(backup), kPrefix + kOldBlock + kSuffix);
        QCOMPARE(rig.calls("reload"), 1);
        QVERIFY(rig.calls("configerrors") >= 2); // before and after
    }

    void hyprInstallIsIdempotent() {
        HyprRig rig;
        rig.write("bindings.lua", kPrefix + kOldBlock + kSuffix);
        QVERIFY(Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno").changed);
        const QByteArray once = rig.read("bindings.lua");
        QVERIFY(Hypr::installed(Hypr::Bindings, "/usr/bin/reverseuno"));
        const Hypr::Result again = Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno");
        QVERIFY(again.ok);
        QVERIFY(!again.changed);
        QCOMPARE(rig.read("bindings.lua"), once);
        QCOMPARE(rig.backups("bindings.lua"), 1); // no second backup
        QCOMPARE(rig.calls("reload"), 1);          // and no second reload
        QVERIFY(!Hypr::installed(Hypr::Bindings, "/somewhere/else/reverseuno"));
    }

    void hyprAppendsWhenThereIsNoBlockAndCreatesAMissingFile() {
        HyprRig rig;
        rig.write("bindings.lua", "o.bind(\"F10\", \"x\", \"y\")"); // no trailing newline
        QVERIFY(Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno").ok);
        QVERIFY(rig.read("bindings.lua").startsWith("o.bind(\"F10\", \"x\", \"y\")\n\n-- >>> reverseuno"));

        QVERIFY(!QFile::exists(rig.file("autostart.lua")));
        QVERIFY(Hypr::install(Hypr::Autostart, "/usr/bin/reverseuno").ok);
        QCOMPARE(rig.read("autostart.lua"), Hypr::block(Hypr::Autostart, "/usr/bin/reverseuno").toUtf8());
        QCOMPARE(rig.backups("autostart.lua"), 0); // nothing to back up
        QVERIFY(Hypr::installed(Hypr::Autostart, "/usr/bin/reverseuno"));
    }

    void hyprRefusesAnUnterminatedBlock() {
        HyprRig rig;
        const QByteArray broken = "-- >>> reverseuno\no.bind(\"F8\", \"x\", \"y\")\n";
        rig.write("bindings.lua", broken);
        const Hypr::Result result = Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno");
        QVERIFY(!result.ok);
        QVERIFY(result.message.contains("end marker"));
        QCOMPARE(rig.read("bindings.lua"), broken);
        QCOMPARE(rig.backups("bindings.lua"), 0);
        QCOMPARE(rig.calls("reload"), 0);
    }

    void hyprUndoesAChangeThatHyprlandRejects() {
        HyprRig rig;
        rig.touch("reject");
        const QByteArray original = kPrefix + kOldBlock + kSuffix;
        rig.write("bindings.lua", original);
        const Hypr::Result result = Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno");
        QVERIFY(!result.ok);
        QVERIFY2(result.message.contains("rejected") && result.message.contains("bad bind"), qPrintable(result.message));
        QCOMPARE(rig.read("bindings.lua"), original); // restored
        QCOMPARE(rig.backups("bindings.lua"), 1);
        QCOMPARE(rig.calls("reload"), 2); // the change, then the undo
        QVERIFY(!Hypr::installed(Hypr::Bindings, "/usr/bin/reverseuno"));

        // A brand new file that is rejected is removed again.
        QVERIFY(QFile::remove(rig.file("bindings.lua")));
        QVERIFY(!Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno").ok);
        QVERIFY(!QFile::exists(rig.file("bindings.lua")));
    }

    void hyprDoesNotBlameItselfForErrorsThatWereAlreadyThere() {
        HyprRig rig;
        rig.touch("always");
        rig.write("bindings.lua", kPrefix);
        const Hypr::Result result = Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno");
        QVERIFY2(result.ok, qPrintable(result.message));
        QVERIFY(Hypr::installed(Hypr::Bindings, "/usr/bin/reverseuno"));
    }

    void hyprWritesThroughASymlink() {
        HyprRig rig;
        QTemporaryDir other;
        writeFile(other.path() + "/real.lua", kPrefix);
        QVERIFY(QFile::link(other.path() + "/real.lua", rig.file("bindings.lua")));
        QVERIFY(Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno").ok);
        QVERIFY(QFileInfo(rig.file("bindings.lua")).isSymLink());
        QVERIFY(readFile(other.path() + "/real.lua").contains("-- >>> reverseuno"));
    }

    void hyprNeedsHyprctlAndAConfigFolder() {
        HyprRig rig;
        rig.write("bindings.lua", kPrefix);
        {
            QTemporaryDir empty;
            qputenv("PATH", empty.path().toUtf8()); // no hyprctl
            const Hypr::Result result = Hypr::install(Hypr::Bindings, "/usr/bin/reverseuno");
            QVERIFY(!result.ok);
            QVERIFY(result.message.contains("hyprctl"));
            QCOMPARE(rig.read("bindings.lua"), kPrefix);
            qputenv("PATH", rig.tools().toUtf8());
        }
        QVERIFY(QDir(rig.dir()).removeRecursively());
        QVERIFY(Hypr::install(Hypr::Autostart, "/usr/bin/reverseuno").message.contains("No Hyprland config folder"));
    }

    void backendSetupReportsMissingToolsAndInstallsHotkeys() {
        {
            QTemporaryDir empty;
            const QByteArray saved = qgetenv("PATH");
            qputenv("PATH", empty.path().toUtf8());
            Rig rig;
            QCOMPARE(rig.backend->missingTools(), (QStringList{"wl-copy", "wl-paste", "wtype", "hyprctl", "ffmpeg"}));
            QCOMPARE(rig.backend->toolsCommand(), QString("omarchy pkg add wl-clipboard wtype hyprland ffmpeg"));
            QVERIFY(!rig.backend->setupBasicsOk());
            qputenv("PATH", saved);
        }
        HyprRig hypr;
        FakeTools tools; // puts its own tools first on PATH
        writeFile(tools.path("ffmpeg"), "#!/bin/sh\n", true);
        writeFile(tools.path("hyprctl"), "#!/bin/sh\nexit 0\n", true);
        Rig rig;
        QVERIFY(rig.backend->missingTools().isEmpty());
        QVERIFY(rig.backend->toolsCommand().isEmpty());
        QVERIFY(rig.backend->setupBasicsOk()); // key, microphone (injected) and tools
        QVERIFY(!rig.backend->hotkeysInstalled());
        QVERIFY(!rig.backend->autostartInstalled());

        rig.backend->installHotkeys();
        QVERIFY2(!rig.backend->hotkeyFailed(), qPrintable(rig.backend->hotkeyMessage()));
        QVERIFY(rig.backend->hotkeysInstalled());
        rig.backend->installAutostart();
        QVERIFY(rig.backend->autostartInstalled());
        QVERIFY(hypr.read("autostart.lua").contains("daemon"));
        rig.backend->setApiKey({});
        QVERIFY(!rig.backend->setupBasicsOk());
    }

    // ---- tray icon ------------------------------------------------------------------

    void trayIconIsRecolouredPerAccentAndState() {
        const QImage red = Tray::render("#ff0000", "idle", 128);
        const QImage green = Tray::render("#00ff00", "idle", 128);
        QCOMPARE(red.pixelColor(30, 64), QColor("#ff0000")); // the N's left stem
        QCOMPARE(green.pixelColor(30, 64), QColor("#00ff00"));
        QCOMPARE(red.pixelColor(2, 2).alpha(), 0); // no background
        // The badge sits over the right leg: nothing in idle, white while listening, red-ish on error.
        QCOMPARE(red.pixelColor(102, 102), QColor("#ff0000"));
        QCOMPARE(Tray::render("#ff0000", "listening", 128).pixelColor(102, 102), QColor("#ffffff"));
        QCOMPARE(Tray::render("#ff0000", "error", 128).pixelColor(102, 102), QColor("#ff453a"));
        QVERIFY(Tray::render("#ff0000", "busy", 128).pixelColor(30, 64).alpha() < 200); // dimmed
        QCOMPARE(Tray::stateFor("transcribing"), QString("busy"));
        QCOMPARE(Tray::stateFor("done"), QString("idle"));
    }

    void trayIconFollowsTheThemeAccentAndTheSessionState() {
        QTemporaryDir dir, cache;
        QDir(dir.path()).mkpath("theme");
        const QString colors = dir.path() + "/theme/colors.toml";
        writeFile(colors, "accent = \"#112233\"\n");
        Theme theme(dir.path());
        Rig rig;
        Tray tray(&theme, rig.backend.get(), cache.path());
        QSignalSpy changed(&tray, &Tray::iconChanged);
        const QUrl first = tray.iconUrl();
        QVERIFY(QFile::exists(first.toLocalFile()));
        QCOMPARE(QImage(first.toLocalFile()).pixelColor(30, 64), QColor("#112233"));

        writeFile(colors + ".new", "accent = \"#ccaa00\"\n");
        QVERIFY(QFile::remove(colors));
        QVERIFY(QFile::rename(colors + ".new", colors));
        QTRY_VERIFY(tray.iconUrl() != first);
        QCOMPARE(QImage(tray.iconUrl().toLocalFile()).pixelColor(30, 64), QColor("#ccaa00"));

        const QUrl idle = tray.iconUrl();
        QVERIFY(rig.backend->start());
        QVERIFY(tray.iconUrl() != idle); // listening badge
        QCOMPARE(QImage(tray.iconUrl().toLocalFile()).pixelColor(102, 102), QColor("#ffffff"));
        QVERIFY(changed.count() >= 2);
    }

    // ---- config ----------------------------------------------------------------

    void configIsPrivateAndCorruptFilesAreKept() {
        QTemporaryDir dir;
        const QString path = dir.path() + "/c/config.json";
        Config config;
        config.apiKey = "secret";
        config.language = "sl";
        QCOMPARE(config.save(path), QString());
        QVERIFY(isPrivate(path));
        QCOMPARE(Config::load(path).apiKey, QString("secret"));
        QCOMPARE(Config::load(path).language, QString("sl"));

        writeFile(path, "{not json");
        QCOMPARE(Config::load(path).language, QString("auto"));
        QVERIFY(!QFile::exists(path));
        QCOMPARE(QDir(dir.path() + "/c").entryList({"config.json.corrupt-*"}).size(), 1);
    }

    void environmentKeyWinsOverTheSavedKey() {
        Config config;
        config.apiKey = "saved";
        QCOMPARE(config.effectiveKey(), QString("saved"));
        qputenv("DEEPGRAM_API_KEY", "from-env");
        QCOMPARE(config.effectiveKey(), QString("from-env"));
        qunsetenv("DEEPGRAM_API_KEY");
    }

    // ---- STT urls and the batch path ---------------------------------------------

    void bothDeepgramUrlsUseNova3() {
        QCOMPARE(SttConfig::model(), QString("nova-3"));
        const QString batch = SttConfig::buildBatchUrl("https://api.deepgram.com/v1/listen", "auto").toString();
        QVERIFY(batch.contains("model=nova-3"));
        QVERIFY(batch.contains("detect_language=true"));
        QVERIFY(batch.contains("smart_format=true") && batch.contains("punctuate=true"));
        QVERIFY(SttConfig::buildBatchUrl("https://x/v1/listen", "sl").toString().contains("language=sl"));
        const QString stream = SttConfig::buildStreamUrl("wss://api.deepgram.com/v1/listen", "auto").toString();
        for (const char *part : {"model=nova-3", "encoding=linear16", "sample_rate=16000", "channels=1", "interim_results=true",
                                 "smart_format=true", "punctuate=true", "endpointing=300", "utterance_end_ms=1000", "language=multi"})
            QVERIFY2(stream.contains(part), part);
        QVERIFY(!stream.contains("detect_language"));
        QVERIFY(SttConfig::buildStreamUrl("wss://x/v1/listen", "de").toString().contains("language=de"));
    }

    void wavHeaderIsCorrect() {
        const QByteArray wav = SttConfig::wav(sine(250));
        QCOMPARE(wav.size(), 44 + 8000);
        QCOMPARE(wav.left(4), QByteArray("RIFF"));
        QCOMPARE(wav.mid(8, 4), QByteArray("WAVE"));
        QCOMPARE(qFromLittleEndian<quint32>(wav.constData() + 24), quint32(16000));
        QCOMPARE(qFromLittleEndian<quint32>(wav.constData() + 40), quint32(8000));
    }

    void batchSendsTheClipAndParsesTheTranscript() {
        HttpFake http;
        Stt stt(http.url());
        QSignalSpy spy(&stt, &Stt::finished);
        stt.transcribe(SttConfig::wav(sine(500)), "k1", "auto");
        QTRY_COMPARE(spy.count(), 1);
        const SttResult result = spy.first().first().value<SttResult>();
        QVERIFY(result.ok);
        QCOMPARE(result.text, QString("hello world"));
        QCOMPARE(result.language, QString("en"));
        QCOMPARE(http.requests.size(), 1);
        QCOMPARE(http.requests[0].headers.value("authorization"), QByteArray("Token k1"));
        QCOMPARE(http.requests[0].headers.value("content-type"), QByteArray("audio/wav"));
        QVERIFY(http.requests[0].line.startsWith("POST /v1/listen?"));
        QCOMPARE(http.requests[0].body.size(), 44 + 16000);
    }

    void batchReportsAnUnauthorizedKey() {
        HttpFake http;
        http.status = 401;
        http.body = R"({"err_code":"INVALID_AUTH","err_msg":"Invalid credentials."})";
        Stt stt(http.url());
        QSignalSpy spy(&stt, &Stt::finished);
        stt.transcribe(SttConfig::wav(sine(300)), "bad-key-123", "en");
        QTRY_COMPARE(spy.count(), 1);
        const SttResult result = spy.first().first().value<SttResult>();
        QVERIFY(!result.ok);
        QCOMPARE(result.error, QString("Deepgram rejected the API key (HTTP 401)"));
        QVERIFY(!result.error.contains("bad-key-123"));
    }

    void batchReportsServerAndNetworkErrors() {
        HttpFake http;
        http.status = 500;
        http.body = R"({"err_msg":"boom"})";
        {
            Stt stt(http.url());
            QSignalSpy spy(&stt, &Stt::finished);
            stt.transcribe("x", "k", "en");
            QTRY_COMPARE(spy.count(), 1);
            QCOMPARE(spy.first().first().value<SttResult>().error, QString("Deepgram error (HTTP 500): boom"));
        }
        Stt dead(kDeadHttp);
        QSignalSpy spy(&dead, &Stt::finished);
        dead.transcribe("x", "k", "en");
        QTRY_COMPARE(spy.count(), 1);
        QVERIFY(spy.first().first().value<SttResult>().error.startsWith("Network error"));
    }

    // ---- streaming -----------------------------------------------------------------

    void streamFlushesQueuedFramesAndJoinsFinals() {
        WsFake ws;
        SttStream stream(ws.url());
        QSignalSpy finished(&stream, &SttStream::finished);
        QSignalSpy interim(&stream, &SttStream::interim);
        stream.start("k2", "auto");
        stream.sendPcm(QByteArray(1000, 'a')); // before the socket is open
        QTRY_COMPARE(ws.requests.size(), 1);
        stream.sendPcm(QByteArray(500, 'b'));
        QTRY_VERIFY(interim.count() > 0);
        stream.finish();
        QTRY_COMPARE(finished.count(), 1);
        const SttResult result = finished.first().first().value<SttResult>();
        QVERIFY(result.ok);
        QCOMPARE(result.text, QString("hello world"));
        QCOMPARE(ws.bytes, qint64(1500));
        QCOMPARE(ws.requests[0].rawHeader("Authorization"), QByteArray("Token k2"));
        const QString url = ws.requests[0].url().toString();
        QVERIFY2(url.contains("model=nova-3") && url.contains("language=multi") && url.contains("sample_rate=16000"), qPrintable(url));
        QVERIFY(ws.texts.contains(R"({"type":"CloseStream"})"));
    }

    void streamGivesUpWaitingForMetadataAfterTwoAndAHalfSeconds() {
        WsFake ws;
        ws.mode = WsFake::Silent;
        SttStream stream(ws.url());
        QSignalSpy finished(&stream, &SttStream::finished);
        stream.start("k", "en");
        QTRY_COMPARE(ws.requests.size(), 1);
        stream.sendPcm(QByteArray(640, 'a'));
        QElapsedTimer timer;
        timer.start();
        stream.finish();
        QTRY_COMPARE_WITH_TIMEOUT(finished.count(), 1, 6000);
        QVERIFY(timer.elapsed() >= 2400);
        QVERIFY(!finished.first().first().value<SttResult>().ok);
    }

    void streamThatCannotConnectIsUnusable() {
        SttStream stream(kDeadWs);
        QSignalSpy finished(&stream, &SttStream::finished);
        stream.start("k", "en");
        QTRY_VERIFY(!stream.usable());
        stream.finish();
        QCOMPARE(finished.count(), 1);
        QVERIFY(!finished.first().first().value<SttResult>().ok);
    }

    // ---- backend session -------------------------------------------------------------

    void startNeedsAKeyAndTheKeyIsStoredPrivately() {
        Rig rig;
        rig.backend->setApiKey({});
        QVERIFY(!rig.backend->start());
        QCOMPARE(rig.backend->state(), QString("error"));
        QVERIFY(rig.backend->error().contains("API key"));
        QVERIFY(!rig.backend->hasKey());

        rig.backend->setApiKey("  test-key  ");
        QVERIFY(rig.backend->hasKey());
        const QString path = rig.dir.path() + "/config/config.json";
        QVERIFY(isPrivate(path));
        QCOMPARE(QJsonDocument::fromJson(readFile(path)).object().value("apiKey").toString(), QString("test-key"));
        QVERIFY(rig.backend->start());
        QCOMPARE(rig.backend->state(), QString("listening"));
        QVERIFY(rig.backend->error().isEmpty());
    }

    void onlyOneSessionAtATime() {
        Rig rig;
        QVERIFY(rig.backend->start());
        QVERIFY(!rig.backend->start());
        QCOMPARE(rig.backend->failureReason(), QString("busy"));
        QCOMPARE(rig.backend->state(), QString("listening"));
        QCOMPARE(rig.capture.started, 1);
    }

    void aMissingMicrophoneIsReported() {
        Rig rig;
        rig.capture.problem = "No microphone found";
        QVERIFY(!rig.backend->start());
        QCOMPARE(rig.backend->state(), QString("error"));
        QCOMPARE(rig.backend->error(), QString("No microphone found"));
    }

    void tapsShorterThan250msProduceNothing() {
        Rig rig;
        QVERIFY(rig.backend->start());
        rig.speak(100);
        QVERIFY(rig.backend->stop());
        QCOMPARE(rig.backend->state(), QString("idle"));
        QVERIFY(rig.backend->notice().contains("Too short"));
        QVERIFY(rig.http.requests.isEmpty());
        QVERIFY(rig.inserts.isEmpty());
        QVERIFY(rig.backend->transcript().isEmpty());
    }

    void silentClipsProduceNothing() {
        Rig rig;
        QVERIFY(rig.backend->start());
        rig.backend->pushPcm(QByteArray(32000, '\0'));
        QVERIFY(rig.backend->stop());
        QCOMPARE(rig.backend->state(), QString("idle"));
        QCOMPARE(rig.backend->notice(), QString("No speech heard"));
        QVERIFY(rig.http.requests.isEmpty());
        QVERIFY(rig.inserts.isEmpty());
    }

    void stopOutsideASessionIsRefused() {
        Rig rig;
        QVERIFY(!rig.backend->stop());
        QVERIFY(!rig.backend->cancel());
        QVERIFY(!rig.backend->failureReason().isEmpty());
    }

    void cancelWhileListening() {
        Rig rig;
        QVERIFY(rig.backend->start());
        rig.speak(500);
        QVERIFY(rig.backend->cancel());
        QCOMPARE(rig.backend->state(), QString("idle"));
        QCOMPARE(rig.capture.stopped, 1);
        QVERIFY(rig.http.requests.isEmpty());
        QVERIFY(rig.backend->start()); // and a new session may follow
    }

    void cancelWhileTranscribingDropsTheResult() {
        Rig rig;
        rig.http.hold = true;
        QVERIFY(rig.backend->start());
        rig.speak(1000);
        QVERIFY(rig.backend->stop());
        QCOMPARE(rig.backend->state(), QString("transcribing"));
        QVERIFY(!rig.backend->start()); // busy while transcribing
        QTRY_COMPARE(rig.http.requests.size(), 1);
        QVERIFY(rig.backend->cancel());
        QCOMPARE(rig.backend->state(), QString("idle"));
        QTest::qWait(200);
        QCOMPARE(rig.backend->state(), QString("idle"));
        QVERIFY(rig.inserts.isEmpty());
    }

    void recordingStopsItselfAtTheLimit() {
        Rig rig(false, true, 150);
        QVERIFY(rig.backend->start());
        rig.speak(1000);
        QTRY_VERIFY(rig.backend->state() != "listening");
        QTRY_COMPARE_WITH_TIMEOUT(rig.backend->state(), QString("done"), 8000);
        QCOMPARE(rig.inserts.size(), 1);
    }

    void batchPathTranscribesAndInserts() {
        Rig rig; // no WebSocket server: the stream cannot connect
        rig.backend->setLanguage("sl");
        QVERIFY(rig.backend->start());
        rig.speak(1000);
        QVERIFY(rig.backend->stop());
        QTRY_COMPARE_WITH_TIMEOUT(rig.backend->state(), QString("done"), 8000);
        QCOMPARE(rig.inserts.size(), 1);
        QCOMPARE(rig.inserts[0].text, QString("hello world"));
        QCOMPARE(rig.inserts[0].mode, QString("paste"));
        QCOMPARE(rig.inserts[0].target.address, QString("0xabc")); // the window at key down
        QCOMPARE(rig.backend->transcript(), QString("hello world"));
        QCOMPARE(rig.backend->history(), QStringList{"hello world"});
        QVERIFY(!rig.backend->unsaved());
        QCOMPARE(rig.http.requests.size(), 1);
        QVERIFY(rig.http.requests[0].line.contains("model=nova-3"));
        QVERIFY(rig.http.requests[0].line.contains("language=sl"));
        QCOMPARE(rig.http.requests[0].headers.value("authorization"), QByteArray("Token test-key"));
        QCOMPARE(rig.http.requests[0].body.size(), 44 + 32000);
    }

    void streamingPathNeedsNoBatchRequest() {
        Rig rig(true);
        QVERIFY(rig.backend->start());
        rig.speak(1000); // pushed while the socket is still connecting
        QTRY_VERIFY(!rig.backend->interim().isEmpty());
        QVERIFY(rig.backend->stop());
        QTRY_COMPARE_WITH_TIMEOUT(rig.backend->state(), QString("done"), 8000);
        QCOMPARE(rig.inserts.size(), 1);
        QCOMPARE(rig.inserts[0].text, QString("hello world"));
        QVERIFY(rig.http.requests.isEmpty());
        QCOMPARE(rig.ws.bytes, qint64(32000));
        QCOMPARE(rig.ws.requests[0].rawHeader("Authorization"), QByteArray("Token test-key"));
        QVERIFY(rig.backend->interim().isEmpty());
    }

    void aStreamThatYieldsNothingFallsBackToTheBatchRequest() {
        Rig rig(true);
        rig.ws.mode = WsFake::CloseAtOnce;
        QVERIFY(rig.backend->start());
        rig.speak(1000);
        QVERIFY(rig.backend->stop());
        QTRY_COMPARE_WITH_TIMEOUT(rig.backend->state(), QString("done"), 8000);
        QCOMPARE(rig.http.requests.size(), 1);
        QCOMPARE(rig.http.requests[0].body.size(), 44 + 32000); // the whole clip
        QCOMPARE(rig.inserts[0].text, QString("hello world"));
    }

    void aSilentStreamAlsoFallsBack() {
        Rig rig(true);
        rig.ws.mode = WsFake::Silent;
        QVERIFY(rig.backend->start());
        rig.speak(1000);
        QVERIFY(rig.backend->stop());
        QTRY_COMPARE_WITH_TIMEOUT(rig.backend->state(), QString("done"), 10000);
        QCOMPARE(rig.http.requests.size(), 1);
    }

    void aRejectedKeyLeavesAnErrorInTheWindow() {
        Rig rig;
        rig.http.status = 401;
        rig.http.body = R"({"err_code":"INVALID_AUTH"})";
        QVERIFY(rig.backend->start());
        rig.speak(1000);
        QVERIFY(rig.backend->stop());
        QTRY_COMPARE_WITH_TIMEOUT(rig.backend->state(), QString("error"), 8000);
        QCOMPARE(rig.backend->error(), QString("Deepgram rejected the API key (HTTP 401)"));
        QVERIFY(rig.inserts.isEmpty());
        QVERIFY(rig.backend->start()); // errors do not block the next session
        QVERIFY(rig.backend->error().isEmpty());
    }

    void environmentKeyIsUsedForRequests() {
        qputenv("DEEPGRAM_API_KEY", "env-key");
        Rig rig;
        QVERIFY(rig.backend->keyFromEnvironment());
        QVERIFY(rig.backend->start());
        rig.speak(1000);
        QVERIFY(rig.backend->stop());
        QTRY_COMPARE_WITH_TIMEOUT(rig.backend->state(), QString("done"), 8000);
        QCOMPARE(rig.http.requests[0].headers.value("authorization"), QByteArray("Token env-key"));
        qunsetenv("DEEPGRAM_API_KEY");
    }

    void settingsPersistAcrossInstances() {
        Rig rig;
        rig.backend->setLanguage("de");
        rig.backend->setInsertMode("type");
        rig.backend->setMicrophone("Desk mic");
        BackendOptions options;
        options.configPath = rig.dir.path() + "/config/config.json";
        FakeCapture capture;
        options.capture = &capture;
        Backend again(options);
        QCOMPARE(again.language(), QString("de"));
        QCOMPARE(again.insertMode(), QString("type"));
        QCOMPARE(again.microphone(), QString("Desk mic"));
        QVERIFY(again.hasKey());
        again.setInsertMode("nonsense");
        QCOMPARE(again.insertMode(), QString("type"));
    }

    void problemsListMissingToolsAndKey() {
        QTemporaryDir empty;
        const QByteArray saved = qgetenv("PATH");
        qputenv("PATH", empty.path().toUtf8());
        Rig rig;
        rig.backend->setApiKey({});
        const QString all = rig.backend->problems().join("\n");
        qputenv("PATH", saved);
        for (const char *part : {"API key", "wl-copy", "wtype", "hyprctl", "ffmpeg"})
            QVERIFY2(all.contains(part), part);
    }

    // ---- CLI ------------------------------------------------------------------------------

    void transcribeJsonAndPlain() {
        QTemporaryDir dir;
        HttpFake http;
        writeFile(dir.path() + "/tone.wav", SttConfig::wav(sine(1000)));
        Config config;
        config.apiKey = "cli-key";
        QCOMPARE(config.save(dir.path() + "/config.json"), QString());

        QString out, err;
        QTextStream outStream(&out), errStream(&err);
        CliOptions options{{}, http.url(), dir.path() + "/config.json", &outStream, &errStream};
        QCOMPARE(runCli({"transcribe", dir.path() + "/tone.wav", "--json"}, options), 0);
        outStream.flush();
        const QJsonObject json = QJsonDocument::fromJson(out.toUtf8()).object();
        QCOMPARE(json.value("text").toString(), QString("hello world"));
        QCOMPARE(json.value("language").toString(), QString("en"));
        QVERIFY(qAbs(json.value("durationMs").toInt() - 1000) < 30);
        QVERIFY(err.isEmpty());
        QCOMPARE(http.requests[0].headers.value("authorization"), QByteArray("Token cli-key"));
        QVERIFY(http.requests[0].line.contains("model=nova-3"));

        out.clear();
        QCOMPARE(runCli({"transcribe", dir.path() + "/tone.wav"}, options), 0);
        outStream.flush();
        QCOMPARE(out, QString("hello world\n"));
    }

    void transcribeFailuresExitWithOne() {
        QTemporaryDir dir;
        HttpFake http;
        writeFile(dir.path() + "/tone.wav", SttConfig::wav(sine(500)));
        writeFile(dir.path() + "/junk.bin", "this is not audio at all");
        Config config;
        config.apiKey = "k";
        QCOMPARE(config.save(dir.path() + "/config.json"), QString());
        auto run = [&](const QStringList &args, const QString &configPath, QString *err) {
            QString out;
            QTextStream outStream(&out), errStream(err);
            const int code = runCli(args, CliOptions{{}, http.url(), configPath, &outStream, &errStream});
            errStream.flush();
            outStream.flush();
            return out.isEmpty() ? code : -99; // failures print nothing on stdout
        };
        QString err;
        QCOMPARE(run({"transcribe", dir.path() + "/missing.wav", "--json"}, dir.path() + "/config.json", &err), 1);
        QVERIFY(err.contains("No such file"));
        err.clear();
        QCOMPARE(run({"transcribe", dir.path() + "/junk.bin"}, dir.path() + "/config.json", &err), 1);
        QVERIFY2(err.contains("ffmpeg could not read"), qPrintable(err));
        err.clear();
        QCOMPARE(run({"transcribe", dir.path() + "/tone.wav"}, dir.path() + "/nokey.json", &err), 1);
        QVERIFY(err.contains("No Deepgram API key"));
        err.clear();
        http.status = 401;
        QCOMPARE(run({"transcribe", dir.path() + "/tone.wav", "--json"}, dir.path() + "/config.json", &err), 1);
        QVERIFY2(err.contains("rejected the API key"), qPrintable(err));
        err.clear();
        QCOMPARE(run({"transcribe"}, dir.path() + "/config.json", &err), 1);
        QVERIFY(err.contains("usage"));
    }

    void skillInstallWritesTheBundledSkill() {
        QTemporaryDir dir;
        QString out, err;
        QTextStream outStream(&out), errStream(&err);
        CliOptions options{{}, {}, {}, &outStream, &errStream};
        QCOMPARE(runCli({"skill", "install", "--dir", dir.path()}, options), 0);
        const QString target = dir.path() + "/reverseuno/SKILL.md";
        const QString text = QString::fromUtf8(readFile(target));
        QVERIFY(text.startsWith("---\nname: reverseuno"));
        QVERIFY(text.contains("reverseuno transcribe"));
        QVERIFY(text.contains("nova-3"));
        outStream.flush();
        QCOMPARE(out.trimmed(), target);
        QCOMPARE(runCli({"skill", "install", "--bogus"}, options), 1);
        QCOMPARE(runCli({"frobnicate"}, options), 2);
    }

    void controlCommandsFailWithoutARunningInstance() {
        const QString bus = "io.github.nodaysidle.ReverseunoTest" + QString::number(QCoreApplication::applicationPid());
        for (const char *command : {"start", "stop", "toggle", "cancel", "quit"}) {
            QString out, err;
            QTextStream outStream(&out), errStream(&err);
            QCOMPARE(runCli({command}, CliOptions{bus, {}, {}, &outStream, &errStream}), 1);
            errStream.flush();
            QVERIFY2(!err.isEmpty(), command);
        }
        QVERIFY(!showRunningInstance(bus));
    }

    void controlCommandsReachTheRunningInstance() {
        if (!QDBusConnection::sessionBus().isConnected())
            QSKIP("no D-Bus session bus (bin/test starts a private one)");
        const QString name = "io.github.nodaysidle.ReverseunoTest" + QString::number(QCoreApplication::applicationPid());
        Rig rig;
        Service service(rig.backend.get());
        QDBusConnection bus = QDBusConnection::sessionBus();
        QVERIFY(bus.registerObject(Service::objectPath(), &service, QDBusConnection::ExportAllSlots));
        QVERIFY(bus.registerService(name));

        auto call = [&](const char *command, QString *err) {
            QString out;
            QTextStream outStream(&out), errStream(err);
            const int code = runCli({command}, CliOptions{name, {}, {}, &outStream, &errStream});
            errStream.flush();
            return code;
        };
        QString err;
        QCOMPARE(call("start", &err), 0);
        QCOMPARE(rig.backend->state(), QString("listening"));
        QCOMPARE(call("start", &err), 1); // busy
        QVERIFY2(err.contains("busy"), qPrintable(err));
        QCOMPARE(call("cancel", &err), 0);
        QCOMPARE(rig.backend->state(), QString("idle"));
        QCOMPARE(call("toggle", &err), 0);
        QCOMPARE(rig.backend->state(), QString("listening"));
        QCOMPARE(call("stop", &err), 0); // nothing was said: a silent clip, no text
        QCOMPARE(rig.backend->state(), QString("idle"));
        err.clear();
        QCOMPARE(call("stop", &err), 1);
        QVERIFY(err.contains("Not listening"));

        QSignalSpy shown(rig.backend.get(), &Backend::showRequested);
        QVERIFY(showRunningInstance(name));
        QCOMPARE(shown.count(), 1);
        bus.unregisterService(name);
        bus.unregisterObject(Service::objectPath());
    }
};

Q_DECLARE_METATYPE(SttResult)

QTEST_MAIN(ReverseunoTests)
#include "reverseuno_tests.moc"
