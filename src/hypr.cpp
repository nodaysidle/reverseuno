#include "hypr.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QProcess>
#include <QSaveFile>
#include <QSet>
#include <QStandardPaths>

namespace {

const QByteArray kStart = "-- >>> reverseuno";
const QByteArray kEnd = "-- <<< reverseuno";

QString luaString(const QString &value) {
    QString escaped = value;
    escaped.replace('\\', "\\\\").replace('"', "\\\"");
    return '"' + escaped + '"';
}

struct Run {
    bool ok = false; // started and exited 0
    QString out;
};

Run hyprctl(const QStringList &args) {
    const QString program = QStandardPaths::findExecutable("hyprctl");
    Run result;
    if (program.isEmpty())
        return result;
    QProcess process;
    process.start(program, args);
    if (!process.waitForFinished(8000)) {
        process.kill();
        process.waitForFinished(500);
        return result;
    }
    result.ok = process.exitStatus() == QProcess::NormalExit && process.exitCode() == 0;
    result.out = QString::fromUtf8(process.readAllStandardOutput()).trimmed();
    return result;
}

QSet<QString> errorLines(const QString &text) {
    QSet<QString> lines;
    for (const QString &line : text.split('\n')) {
        const QString trimmed = line.trimmed();
        if (!trimmed.isEmpty() && trimmed.compare("ok", Qt::CaseInsensitive) != 0 &&
            trimmed.compare("no errors", Qt::CaseInsensitive) != 0)
            lines.insert(trimmed);
    }
    return lines;
}

QByteArray readAll(const QString &path, bool *exists) {
    QFile file(path);
    *exists = file.open(QIODevice::ReadOnly);
    return *exists ? file.readAll() : QByteArray();
}

// Writes through a symlink to its target, so a dotfile manager's link survives.
bool writeAtomic(const QString &path, const QByteArray &data, bool existed) {
    const QString target = existed ? QFileInfo(path).canonicalFilePath() : path;
    const QFileDevice::Permissions permissions = existed ? QFileInfo(target).permissions()
                                                         : QFileDevice::Permissions(0x6644);
    QSaveFile file(target);
    if (!file.open(QIODevice::WriteOnly))
        return false;
    file.write(data);
    file.setPermissions(permissions);
    return file.commit();
}

QString backupPath(const QString &path) {
    const QString base = path + ".bak." + QString::number(QDateTime::currentSecsSinceEpoch());
    QString candidate = base;
    for (int n = 1; QFile::exists(candidate); ++n)
        candidate = base + "-" + QString::number(n);
    return candidate;
}

} // namespace

QString Hypr::dir() {
    const QString overridden = qEnvironmentVariable("REVERSEUNO_HYPR_DIR");
    return overridden.isEmpty() ? QDir::homePath() + "/.config/hypr" : overridden;
}

QString Hypr::binaryPath() {
    if (QFileInfo::exists("/usr/bin/reverseuno"))
        return "/usr/bin/reverseuno";
    const QString onPath = QStandardPaths::findExecutable("reverseuno");
    return onPath.isEmpty() ? QCoreApplication::applicationFilePath() : onPath;
}

QString Hypr::fileName(File file) { return file == Bindings ? "bindings.lua" : "autostart.lua"; }

QString Hypr::block(File file, const QString &binary) {
    const QString note = QStringLiteral(" (managed by reverseuno: Setup in its window rewrites this block)\n");
    if (file == Autostart)
        return QString::fromUtf8(kStart) + note + "o.launch_on_start(" + luaString(binary + " daemon") + ")\n" +
               QString::fromUtf8(kEnd) + "\n";
    const auto cmd = [&](const char *verb) { return luaString(binary + " " + verb); };
    return QString::fromUtf8(kStart) + note +
           "-- Hold F8 to dictate, release to paste. SUPER + U toggles, F12 cancels.\n"
           "o.bind_hold(\"F8\", \"reverseuno: start dictating\", " + cmd("start") +
           ", \"reverseuno: stop dictating\", " + cmd("stop") + ")\n"
           "o.bind(\"SUPER + U\", \"reverseuno: toggle dictation\", " + cmd("toggle") + ")\n"
           "o.bind(\"F12\", \"reverseuno: cancel dictation\", " + cmd("cancel") + ")\n"
           "o.window({ class = \"^reverseuno$\", title = \"^reverseuno$\" }, {\n"
           "  float = true,\n"
           "  center = true,\n"
           "  size = { 980, 660 },\n"
           "})\n" + QString::fromUtf8(kEnd) + "\n";
}

QByteArray Hypr::splice(const QByteArray &text, const QByteArray &block, bool *ok) {
    *ok = true;
    // A marker counts only at the start of a line.
    auto findLine = [&](const QByteArray &marker, qsizetype from) -> qsizetype {
        for (qsizetype at = text.indexOf(marker, from); at >= 0; at = text.indexOf(marker, at + 1))
            if (at == 0 || text[at - 1] == '\n')
                return at;
        return -1;
    };
    const qsizetype start = findLine(kStart, 0);
    if (start < 0) {
        QByteArray out = text;
        if (!out.isEmpty() && !out.endsWith('\n'))
            out += '\n';
        if (!out.isEmpty())
            out += '\n';
        return out + block;
    }
    const qsizetype end = findLine(kEnd, start);
    if (end < 0) {
        *ok = false;
        return text;
    }
    qsizetype after = text.indexOf('\n', end);
    QByteArray replacement = block;
    if (after < 0) {
        after = text.size();
        replacement.chop(1); // the old end marker had no newline either
    } else {
        ++after;
    }
    return text.left(start) + replacement + text.mid(after);
}

bool Hypr::installed(File file, const QString &binary) {
    bool exists = false;
    const QByteArray text = readAll(dir() + "/" + fileName(file), &exists);
    if (!exists)
        return false;
    bool ok = false;
    const QByteArray spliced = splice(text, block(file, binary).toUtf8(), &ok);
    return ok && spliced == text;
}

Hypr::Result Hypr::install(File file, const QString &binary) {
    Result result;
    if (QStandardPaths::findExecutable("hyprctl").isEmpty()) {
        result.message = tr("hyprctl is not installed (package hyprland)");
        return result;
    }
    if (!QDir(dir()).exists()) {
        result.message = tr("No Hyprland config folder at %1").arg(dir());
        return result;
    }
    const QString path = dir() + "/" + fileName(file);
    bool existed = false;
    const QByteArray old = readAll(path, &existed);
    bool spliceOk = false;
    const QByteArray updated = splice(old, block(file, binary).toUtf8(), &spliceOk);
    if (!spliceOk) {
        result.message = tr("%1 has a reverseuno start marker without an end marker; fix it by hand").arg(path);
        return result;
    }
    if (existed && updated == old) {
        result.ok = true;
        result.message = tr("Already installed");
        return result;
    }

    const QSet<QString> before = errorLines(hyprctl({"configerrors"}).out);
    QString backup;
    if (existed) {
        backup = backupPath(path);
        if (!QFile::copy(path, backup)) {
            result.message = tr("Could not back up %1").arg(path);
            return result;
        }
    }
    if (!writeAtomic(path, updated, existed)) {
        result.message = tr("Could not write %1").arg(path);
        return result;
    }
    result.changed = true;

    if (!hyprctl({"reload"}).ok) {
        // Not running (or not answering): keep the change, but say it was not checked.
        result.ok = true;
        result.message = tr("Written to %1. Hyprland did not answer, so it was not validated.").arg(path);
        return result;
    }
    QStringList added;
    for (const QString &line : errorLines(hyprctl({"configerrors"}).out))
        if (!before.contains(line))
            added << line;
    if (!added.isEmpty()) {
        if (existed)
            writeAtomic(path, old, true);
        else
            QFile::remove(path);
        hyprctl({"reload"});
        result.changed = false;
        result.message = tr("Hyprland rejected the change, so it was undone: %1").arg(added.join(' '));
        return result;
    }
    result.ok = true;
    result.message = existed ? tr("Installed in %1 (backup: %2)").arg(path, backup) : tr("Installed in %1").arg(path);
    return result;
}
