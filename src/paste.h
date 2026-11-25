#pragma once

#include <QByteArray>
#include <QCoreApplication>
#include <QString>
#include <QStringList>

// Getting the text into the window the user dictated into: wl-copy and wtype
// on Wayland, hyprctl to find and refocus that window. All blocking and short.
struct Paste {
    Q_DECLARE_TR_FUNCTIONS(Paste)
public:
    struct Window {
        QString address, windowClass, title;
        bool valid() const { return !address.isEmpty(); }
    };
    struct Snapshot {
        QString text;
        QByteArray image;
        bool hasText = false;
    };
    struct Result {
        bool ok = false;
        QString error;
        QString note;          // shown in the window when ok
        bool onClipboard = false; // the text is on the clipboard for the user
        int restoreDelayMs = 0; // > 0: call restore() after this long
        Snapshot snapshot;
        QString written;
    };
    struct Missing {
        QString tool, package;
    };

    // "shift-insert" for terminals and code editors, "ctrl-v" for everything else.
    static QString keysFor(const QString &windowClass);
    static QStringList wtypeArguments(const QString &keys);

    static Window activeWindow();
    static bool focusWindow(const QString &address);
    static bool copyText(const QString &text);
    static Snapshot snapshot();
    // Puts the old clipboard back, but only if it still holds what we wrote.
    static bool restore(const Snapshot &snapshot, const QString &written);
    // mode: paste | type | copy
    static Result insert(const QString &text, const QString &mode, const Window &target);
    static QList<Missing> missingTools();
};
