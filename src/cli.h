#pragma once

#include <QString>
#include <QStringList>
#include <QTextStream>

// The commands that need no window: start, stop, toggle, cancel and quit talk
// to the running instance over D-Bus; transcribe and skill install work alone.
struct CliOptions {
    QString busName;    // default io.github.nodaysidle.Reverseuno
    QString sttUrl;     // default Deepgram (or REVERSEUNO_STT_URL)
    QString configPath; // default ~/.config/reverseuno/config.json
    QTextStream *out = nullptr;
    QTextStream *err = nullptr;
};

bool isHeadlessCommand(const QString &command);
// args: everything after the program name. Returns the exit code, 0 or 1 (2 for a bad command line).
int runCli(const QStringList &args, const CliOptions &options);
// Tells a running instance to show its window; false when none runs.
bool showRunningInstance(const QString &busName);
