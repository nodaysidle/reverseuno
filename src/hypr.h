#pragma once

#include <QByteArray>
#include <QCoreApplication>
#include <QString>

// The two managed blocks reverseuno keeps in the user's Hyprland Lua config:
// the hotkeys and window rule in bindings.lua, and the autostart in
// autostart.lua. Everything outside the markers is left byte for byte alone.
struct Hypr {
    Q_DECLARE_TR_FUNCTIONS(Hypr)
public:
    enum File { Bindings, Autostart };
    struct Result {
        bool ok = false;
        bool changed = false;
        QString message;
    };

    // REVERSEUNO_HYPR_DIR, else ~/.config/hypr.
    static QString dir();
    // /usr/bin/reverseuno when installed, else the one on PATH, else this binary.
    static QString binaryPath();
    static QString fileName(File file);
    static QString block(File file, const QString &binary);
    // Puts the block in place of an existing marked block, or appends it.
    // ok is false when a start marker has no end marker.
    static QByteArray splice(const QByteArray &text, const QByteArray &block, bool *ok);
    static bool installed(File file, const QString &binary);
    // Backs the file up, writes the block atomically, then reloads Hyprland and
    // asks it for config errors. New errors undo the change.
    static Result install(File file, const QString &binary);
};
