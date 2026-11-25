#include "cli.h"

#include <QCoreApplication>
#include <QDBusConnection>
#include <QDBusConnectionInterface>
#include <QDBusMessage>
#include <QDBusPendingCallWatcher>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <QSaveFile>
#include <QStandardPaths>

#include "config.h"
#include "service.h"
#include "stt.h"

namespace {

QString tr(const char *text) { return QCoreApplication::translate("Cli", text); }

QString usage() {
    return QStringLiteral(
        "usage: reverseuno [command]\n"
        "\n"
        "  open | (none)               show the window (starts the app if it is not running)\n"
        "  daemon                      start hidden, for exec-once\n"
        "  start | stop | toggle       control the running instance\n"
        "  cancel | quit               cancel the session | quit the running instance\n"
        "  transcribe FILE [--json]    transcribe an audio file and print the text\n"
        "  skill install [--dir DIR]   install the agent skill (default ~/.claude/skills)\n");
}

// Async, so a service living in this same process (the tests) keeps being served.
bool callInstance(const QString &busName, const QString &method, QString *reply, QString *problem) {
    QDBusConnection bus = QDBusConnection::sessionBus();
    if (!bus.isConnected()) {
        *problem = tr("No D-Bus session bus; is reverseuno running in this session?");
        return false;
    }
    if (!bus.interface()->isServiceRegistered(busName)) {
        *problem = tr("reverseuno is not running. Start it with: reverseuno daemon");
        return false;
    }
    QDBusMessage message = QDBusMessage::createMethodCall(busName, Service::objectPath(), Service::interfaceName(), method);
    QDBusPendingCallWatcher watcher(bus.asyncCall(message, 5000));
    QEventLoop loop;
    QObject::connect(&watcher, &QDBusPendingCallWatcher::finished, &loop, &QEventLoop::quit);
    if (!watcher.isFinished())
        loop.exec();
    const QDBusMessage answer = watcher.reply();
    if (answer.type() == QDBusMessage::ErrorMessage) {
        *problem = answer.errorMessage();
        return false;
    }
    *reply = answer.arguments().value(0).toString();
    return true;
}

int control(const QString &method, const CliOptions &options) {
    QString reply, problem;
    if (!callInstance(options.busName, method, &reply, &problem)) {
        *options.err << problem << "\n";
        return 1;
    }
    if (!reply.isEmpty()) {
        *options.err << reply << "\n";
        return 1;
    }
    return 0;
}

int fail(const CliOptions &options, const QString &message) {
    *options.err << message << "\n";
    return 1;
}

int transcribe(const QStringList &args, const CliOptions &options) {
    QString file;
    bool json = false;
    for (const QString &arg : args) {
        if (arg == "--json")
            json = true;
        else if (file.isEmpty() && !arg.startsWith("--"))
            file = arg;
        else
            return fail(options, tr("Unexpected argument: %1").arg(arg));
    }
    if (file.isEmpty())
        return fail(options, tr("usage: reverseuno transcribe FILE [--json]"));
    if (!QFileInfo(file).isFile())
        return fail(options, tr("No such file: %1").arg(file));

    const QString configPath = options.configPath.isEmpty() ? Config::defaultPath() : options.configPath;
    const Config config = Config::load(configPath);
    const QString key = config.effectiveKey();
    if (key.isEmpty())
        return fail(options, tr("No Deepgram API key: set DEEPGRAM_API_KEY or save one in the reverseuno window."));
    const QString ffmpeg = QStandardPaths::findExecutable("ffmpeg");
    if (ffmpeg.isEmpty())
        return fail(options, tr("ffmpeg is not installed (package ffmpeg)"));

    // Raw samples rather than `-f wav -`: a WAV written to a pipe has no final
    // size in its header, and the length is needed for durationMs anyway.
    QProcess convert;
    convert.start(ffmpeg, {"-nostdin", "-v", "error", "-i", file, "-ar", "16000", "-ac", "1", "-f", "s16le", "-"});
    if (!convert.waitForStarted(3000))
        return fail(options, tr("Could not run ffmpeg: %1").arg(convert.errorString()));
    QByteArray pcm;
    while (convert.state() != QProcess::NotRunning) {
        convert.waitForReadyRead(200);
        pcm += convert.readAllStandardOutput();
    }
    pcm += convert.readAllStandardOutput();
    if (convert.exitStatus() != QProcess::NormalExit || convert.exitCode() != 0)
        return fail(options, tr("ffmpeg could not read %1: %2")
                                 .arg(file, QString::fromUtf8(convert.readAllStandardError()).trimmed().left(300)));
    if (pcm.isEmpty())
        return fail(options, tr("%1 has no audio").arg(file));

    Stt stt(options.sttUrl);
    SttResult result;
    QEventLoop loop;
    QObject::connect(&stt, &Stt::finished, &loop, [&](const SttResult &r) {
        result = r;
        loop.quit();
    });
    stt.transcribe(SttConfig::wav(pcm), key, config.language);
    loop.exec();
    if (!result.ok)
        return fail(options, result.error);

    if (json) {
        const QJsonObject object{{"text", result.text},
                                 {"durationMs", int(pcm.size() / SttConfig::kBytesPerMs)},
                                 {"language", result.language}};
        *options.out << QJsonDocument(object).toJson(QJsonDocument::Compact) << "\n";
    } else {
        *options.out << result.text << "\n";
    }
    return 0;
}

int installSkill(const QStringList &args, const CliOptions &options) {
    QString directory = QDir::homePath() + "/.claude/skills";
    for (int i = 0; i < args.size(); ++i) {
        if (args[i] == "--dir" && i + 1 < args.size())
            directory = args[++i];
        else
            return fail(options, tr("usage: reverseuno skill install [--dir DIR]"));
    }
    QFile bundled(":/skill/SKILL.md");
    if (!bundled.open(QIODevice::ReadOnly))
        return fail(options, tr("The bundled skill is missing from this build"));
    const QString target = directory + "/reverseuno/SKILL.md";
    if (!QDir().mkpath(QFileInfo(target).absolutePath()))
        return fail(options, tr("Could not create %1").arg(QFileInfo(target).absolutePath()));
    QSaveFile file(target);
    if (!file.open(QIODevice::WriteOnly))
        return fail(options, tr("Could not write %1: %2").arg(target, file.errorString()));
    file.write(bundled.readAll());
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner | QFileDevice::ReadGroup | QFileDevice::ReadOther);
    if (!file.commit())
        return fail(options, tr("Could not write %1: %2").arg(target, file.errorString()));
    *options.out << target << "\n";
    return 0;
}

} // namespace

bool isHeadlessCommand(const QString &command) {
    static const QStringList commands{"start", "stop",   "toggle", "cancel", "quit",
                                      "transcribe", "skill", "help", "--help", "-h", "--version"};
    return commands.contains(command);
}

bool showRunningInstance(const QString &busName) {
    QString reply, problem;
    return callInstance(busName, "Show", &reply, &problem);
}

int runCli(const QStringList &args, const CliOptions &given) {
    CliOptions options = given;
    if (options.busName.isEmpty())
        options.busName = Service::busName();
    const QString command = args.value(0);
    const QStringList rest = args.mid(1);

    if (command == "help" || command == "--help" || command == "-h") {
        *options.out << usage();
        return 0;
    }
    if (command == "--version") {
        *options.out << "reverseuno " << QCoreApplication::applicationVersion() << "\n";
        return 0;
    }
    if (command == "start")
        return control("Start", options);
    if (command == "stop")
        return control("Stop", options);
    if (command == "toggle")
        return control("Toggle", options);
    if (command == "cancel")
        return control("Cancel", options);
    if (command == "quit")
        return control("Quit", options);
    if (command == "transcribe")
        return transcribe(rest, options);
    if (command == "skill" && rest.value(0) == "install")
        return installSkill(rest.mid(1), options);
    *options.err << usage();
    return 2;
}
