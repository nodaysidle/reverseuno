#pragma once

#include <QString>

// The small settings file, ~/.config/reverseuno/config.json (mode 0600).
// The API key lives here too, so the file is never world-readable.
struct Config {
    QString apiKey;
    QString language = "auto";
    QString insertMode = "paste"; // paste | type | copy
    QString microphone;           // device description; empty means the default

    static QString defaultPath();
    // A missing file gives defaults; a corrupt one is moved aside and gives defaults.
    static Config load(const QString &path);
    // Atomic (write beside, then rename) and 0600. Returns an error text, empty on success.
    QString save(const QString &path) const;
    // DEEPGRAM_API_KEY wins over the saved key.
    QString effectiveKey() const;
    static bool keyFromEnvironment();
};
