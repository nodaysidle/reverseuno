#include "paste.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <QRegularExpression>
#include <QStandardPaths>
#include <QThread>

namespace {

struct Run {
    bool started = false;
    int code = -1;
    QByteArray out;
    QString err;
};

// Quiet runs send the tool's output to /dev/null: wl-copy forks a server that
// would otherwise keep our pipes open.
Run run(const QString &program, const QStringList &args, const QByteArray &input = {}, int timeoutMs = 2000,
        bool quiet = false) {
    Run result;
    QProcess process;
    if (quiet) {
        process.setStandardOutputFile(QProcess::nullDevice());
        process.setStandardErrorFile(QProcess::nullDevice());
    }
    process.start(program, args);
    if (!process.waitForStarted(1500)) {
        result.err = process.errorString();
        return result;
    }
    result.started = true;
    if (!input.isEmpty())
        process.write(input);
    process.closeWriteChannel();
    if (!process.waitForFinished(timeoutMs)) {
        process.kill();
        process.waitForFinished(500);
        result.err = QStringLiteral("timeout");
        return result;
    }
    result.code = process.exitStatus() == QProcess::NormalExit ? process.exitCode() : -1;
    if (!quiet) {
        result.out = process.readAllStandardOutput();
        result.err = QString::fromUtf8(process.readAllStandardError()).trimmed();
    }
    return result;
}

bool readText(QString *text) {
    const Run r = run("wl-paste", {"--no-newline"}, {}, 1000);
    if (r.started && r.code == 0)
        *text = QString::fromUtf8(r.out);
    return r.started && r.code == 0;
}

} // namespace

QString Paste::keysFor(const QString &windowClass) {
    // Whole words of the window class, so "code" does not match "barcode".
    static const QRegularExpression terminalOrEditor(
        "(^|[^a-z0-9])(ghostty|alacritty|kitty|foot|wezterm|xterm|konsole|code|cursor|zed|windsurf|jetbrains|idea|pycharm|"
        "webstorm|goland|clion)([^a-z0-9]|$)");
    return terminalOrEditor.match(windowClass.toLower()).hasMatch() ? "shift-insert" : "ctrl-v";
}

QStringList Paste::wtypeArguments(const QString &keys) {
    if (keys == "shift-insert")
        return {"-M", "shift", "-k", "Insert", "-m", "shift"};
    return {"-M", "ctrl", "-k", "v", "-m", "ctrl"};
}

Paste::Window Paste::activeWindow() {
    const Run r = run("hyprctl", {"-j", "activewindow"}, {}, 1200);
    Window window;
    if (!r.started || r.code != 0)
        return window;
    const QJsonObject object = QJsonDocument::fromJson(r.out).object();
    window.address = object.value("address").toString();
    window.windowClass = object.value("class").toString();
    window.title = object.value("title").toString();
    return window;
}

// Hyprland with a Lua config reads dispatch arguments as Lua; older configs use
// the classic dispatcher syntax. Try the classic one, then Lua.
bool Paste::focusWindow(const QString &address) {
    static const QRegularExpression valid("^0x[0-9a-fA-F]+$");
    if (!valid.match(address).hasMatch())
        return false;
    auto ok = [](const Run &r) { return r.started && r.code == 0 && r.out.trimmed().toLower().startsWith("ok"); };
    if (ok(run("hyprctl", {"dispatch", "focuswindow", "address:" + address}, {}, 1200)))
        return true;
    return ok(run("hyprctl", {"dispatch", QStringLiteral("hl.dsp.focus({ window = \"address:%1\" })").arg(address)}, {}, 1200));
}

bool Paste::copyText(const QString &text) {
    const QByteArray data = text.toUtf8();
    for (const QStringList &args : {QStringList{"--type", "text/plain;charset=utf-8"}, QStringList{}}) {
        const Run r = run("wl-copy", args, data, 1500, true);
        // wl-copy may keep a serving process around, so "did it exit cleanly"
        // is not the same question as "did the clipboard change".
        QString current;
        if ((r.started && r.code == 0) || (readText(&current) && current == text))
            return true;
        if (!r.started)
            return false;
    }
    return false;
}

Paste::Snapshot Paste::snapshot() {
    Snapshot s;
    s.hasText = readText(&s.text);
    const Run types = run("wl-paste", {"--list-types"}, {}, 800);
    if (types.started && types.code == 0 && types.out.contains("image/png")) {
        const Run image = run("wl-paste", {"--type", "image/png"}, {}, 1500);
        if (image.started && image.code == 0 && image.out.size() <= 20 * 1024 * 1024)
            s.image = image.out;
    }
    return s;
}

bool Paste::restore(const Snapshot &snapshot, const QString &written) {
    QString current;
    if (!readText(&current) || current != written)
        return false;
    if (!snapshot.image.isEmpty())
        return run("wl-copy", {"--type", "image/png"}, snapshot.image, 2000, true).code == 0;
    if (snapshot.hasText)
        return copyText(snapshot.text);
    return run("wl-copy", {"--clear"}, {}, 1000, true).code == 0;
}

Paste::Result Paste::insert(const QString &text, const QString &mode, const Window &target) {
    Result r;
    r.written = text;
    if (mode == "copy") {
        r.ok = copyText(text);
        r.onClipboard = r.ok;
        r.note = tr("Copied to the clipboard");
        if (!r.ok)
            r.error = tr("wl-copy could not set the clipboard");
        return r;
    }

    // The overlay of another window, a notification or follow_mouse may have
    // moved focus while the transcript was in flight.
    Window active = activeWindow();
    if (target.valid() && active.address != target.address) {
        focusWindow(target.address);
        QThread::msleep(60);
        active = activeWindow();
        if (active.address != target.address) {
            // Never type into a window the user did not dictate into.
            r.onClipboard = copyText(text);
            r.error = tr("Could not return focus to %1; the text is on your clipboard.")
                          .arg(target.windowClass.isEmpty() ? tr("the original window") : target.windowClass);
            return r;
        }
    }

    if (mode == "type") {
        const Run typed = run("wtype", {"-"}, text.toUtf8(), qMin(30000, 2000 + int(text.size()) * 25));
        r.ok = typed.started && typed.code == 0;
        if (r.ok) {
            r.note = tr("Typed");
        } else {
            r.onClipboard = copyText(text);
            r.error = tr("wtype failed: %1").arg(typed.err.isEmpty() ? tr("not installed or not running") : typed.err);
        }
        return r;
    }

    const QString keys = keysFor(active.windowClass.isEmpty() ? target.windowClass : active.windowClass);
    r.snapshot = snapshot();
    if (!copyText(text)) {
        r.error = tr("wl-copy could not set the clipboard");
        return r;
    }
    QThread::msleep(80);
    const Run pasted = run("wtype", wtypeArguments(keys), {}, 3000);
    r.onClipboard = true;
    if (!pasted.started || pasted.code != 0) {
        r.error = tr("Paste failed (%1); the text is on your clipboard.")
                      .arg(pasted.err.isEmpty() ? tr("wtype is not installed or not running") : pasted.err);
        return r;
    }
    r.ok = true;
    r.note = tr("Pasted");
    r.restoreDelayMs = 500;
    return r;
}

QList<Paste::Missing> Paste::missingTools() {
    QList<Missing> missing;
    const QList<Missing> needed{{"wl-copy", "wl-clipboard"}, {"wl-paste", "wl-clipboard"}, {"wtype", "wtype"},
                                {"hyprctl", "hyprland"},     {"ffmpeg", "ffmpeg"}};
    for (const Missing &tool : needed)
        if (QStandardPaths::findExecutable(tool.tool).isEmpty())
            missing << tool;
    return missing;
}
