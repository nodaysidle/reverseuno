#pragma once

#include <QElapsedTimer>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>
#include <functional>
#include <memory>

#include "capture.h"
#include "config.h"
#include "hypr.h"
#include "paste.h"
#include "stt.h"

// What tests and the screenshot harness replace; the app uses the defaults.
struct BackendOptions {
    QString configPath;       // default ~/.config/reverseuno/config.json
    QString sttUrl, sttWsUrl; // default: Deepgram (or REVERSEUNO_STT_URL / _WS_URL)
    int maxRecordingMs = 5 * 60 * 1000;
    int minRecordingMs = 250;
    int settleMs = 180; // let the push-to-talk key come up before synthetic keys
    Capture *capture = nullptr; // not owned; default: the microphone
    std::function<Paste::Window()> activeWindow;
    std::function<Paste::Result(const QString &text, const QString &mode, const Paste::Window &target)> insert;
};

// One dictation session at a time: idle, listening, transcribing, inserting,
// done or error. Everything the window shows comes from here.
class Backend : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY stateChanged)
    Q_PROPERTY(bool unsaved READ unsaved NOTIFY unsavedChanged)
    Q_PROPERTY(QString interim READ interim NOTIFY interimChanged)
    Q_PROPERTY(QString transcript READ transcript NOTIFY transcriptChanged)
    Q_PROPERTY(QString notice READ notice NOTIFY noticeChanged)
    Q_PROPERTY(QString error READ error NOTIFY errorChanged)
    Q_PROPERTY(QVariantList levels READ levels NOTIFY levelsChanged)
    Q_PROPERTY(QStringList history READ history NOTIFY historyChanged)
    Q_PROPERTY(QStringList problems READ problems NOTIFY problemsChanged)
    Q_PROPERTY(QStringList microphones READ microphones NOTIFY microphonesChanged)
    Q_PROPERTY(QString microphone READ microphone WRITE setMicrophone NOTIFY settingsChanged)
    Q_PROPERTY(QString language READ language WRITE setLanguage NOTIFY settingsChanged)
    Q_PROPERTY(QString insertMode READ insertMode WRITE setInsertMode NOTIFY settingsChanged)
    Q_PROPERTY(bool hasKey READ hasKey NOTIFY keyChanged)
    Q_PROPERTY(bool keyFromEnvironment READ keyFromEnvironment NOTIFY keyChanged)
    Q_PROPERTY(QString version READ version CONSTANT)
    // First-run Setup: what is in place and what is not.
    Q_PROPERTY(bool microphoneFound READ microphoneFound NOTIFY setupChanged)
    Q_PROPERTY(QStringList missingTools READ missingTools NOTIFY setupChanged)
    Q_PROPERTY(QString toolsCommand READ toolsCommand NOTIFY setupChanged)
    Q_PROPERTY(bool hotkeysInstalled READ hotkeysInstalled NOTIFY setupChanged)
    Q_PROPERTY(bool autostartInstalled READ autostartInstalled NOTIFY setupChanged)
    Q_PROPERTY(bool setupBasicsOk READ setupBasicsOk NOTIFY setupChanged)
    Q_PROPERTY(QString hotkeyMessage READ hotkeyMessage NOTIFY setupChanged)
    Q_PROPERTY(bool hotkeyFailed READ hotkeyFailed NOTIFY setupChanged)
    Q_PROPERTY(QString autostartMessage READ autostartMessage NOTIFY setupChanged)
    Q_PROPERTY(bool autostartFailed READ autostartFailed NOTIFY setupChanged)

public:
    explicit Backend(const BackendOptions &options = {}, QObject *parent = nullptr);
    ~Backend() override;

    QString state() const { return m_state; }
    bool busy() const { return m_state == "listening" || m_state == "transcribing" || m_state == "inserting"; }
    bool unsaved() const { return busy() || (!m_transcript.isEmpty() && !m_delivered); }
    QString interim() const { return m_interim; }
    QString transcript() const { return m_transcript; }
    QString notice() const { return m_notice; }
    QString error() const { return m_error; }
    QVariantList levels() const { return m_levels; }
    QStringList history() const { return m_history; }
    QStringList problems() const;
    QStringList microphones() const;
    QString microphone() const { return m_config.microphone; }
    QString language() const { return m_config.language; }
    QString insertMode() const { return m_config.insertMode; }
    bool hasKey() const { return !m_config.effectiveKey().isEmpty(); }
    bool keyFromEnvironment() const { return Config::keyFromEnvironment(); }
    QString version() const;
    bool microphoneFound() const;
    QStringList missingTools() const;
    QString toolsCommand() const;
    bool hotkeysInstalled() const { return m_hotkeysInstalled; }
    bool autostartInstalled() const { return m_autostartInstalled; }
    bool setupBasicsOk() const { return hasKey() && microphoneFound() && missingTools().isEmpty(); }
    QString hotkeyMessage() const { return m_hotkeyMessage; }
    bool hotkeyFailed() const { return m_hotkeyFailed; }
    QString autostartMessage() const { return m_autostartMessage; }
    bool autostartFailed() const { return m_autostartFailed; }
    // Why the last start() or stop() was refused; empty after a success.
    QString failureReason() const { return m_failure; }

    void setMicrophone(const QString &description);
    void setLanguage(const QString &code);
    void setInsertMode(const QString &mode);

    // Starts a session. False when one is already running ("busy"), the key or
    // the microphone is missing; failureReason() says which.
    Q_INVOKABLE bool start();
    Q_INVOKABLE bool stop();
    Q_INVOKABLE bool toggle();
    // Aborts whatever is running. False when nothing was.
    Q_INVOKABLE bool cancel();
    Q_INVOKABLE void copyTranscript();
    Q_INVOKABLE void copyText(const QString &text);
    // Write-only: the key is never read back by the window.
    Q_INVOKABLE void setApiKey(const QString &key);
    Q_INVOKABLE void show() {
        refreshSetup();
        emit showRequested();
    }
    Q_INVOKABLE void showSetup() {
        show();
        emit setupRequested();
    }
    // Re-reads the Hyprland files; the rest of the setup state is read live.
    Q_INVOKABLE void refreshSetup();
    // Write the managed blocks into ~/.config/hypr (see Hypr).
    Q_INVOKABLE void installHotkeys();
    Q_INVOKABLE void installAutostart();
    Q_INVOKABLE void quit();

    // Audio in: the capture feeds this; tests call it directly.
    void pushPcm(const QByteArray &frames);

public slots:
    void refreshProblems() {
        emit problemsChanged();
        emit setupChanged();
    }

signals:
    void stateChanged();
    void unsavedChanged();
    void interimChanged();
    void transcriptChanged();
    void noticeChanged();
    void errorChanged();
    void levelsChanged();
    void historyChanged();
    void problemsChanged();
    void microphonesChanged();
    void settingsChanged();
    void keyChanged();
    void showRequested();
    void setupRequested();
    void setupChanged();

private:
    void setState(const QString &state);
    void setNotice(const QString &notice);
    void fail(const QString &message);
    void endListening();
    void discardStream();
    void runBatch();
    void deliver(const QString &text);
    void onSttResult(const SttResult &result);
    void flushRestore();
    void saveConfig();
    QString keyProblem() const;

    BackendOptions m_options;
    Config m_config;
    std::unique_ptr<MicCapture> m_mic;
    Capture *m_capture = nullptr;
    Stt m_stt;
    QPointer<SttStream> m_stream;
    QTimer m_limit, m_restoreTimer;
    QElapsedTimer m_stopped;

    QString m_state = "idle", m_interim, m_transcript, m_notice, m_error, m_failure;
    QStringList m_history;
    QVariantList m_levels;
    QByteArray m_pcm;
    bool m_hasSpeech = false, m_delivered = true, m_streamUsable = false;
    Paste::Window m_target;
    Paste::Result m_restore;
    bool m_hotkeysInstalled = false, m_autostartInstalled = false;
    bool m_hotkeyFailed = false, m_autostartFailed = false;
    QString m_hotkeyMessage, m_autostartMessage;
};
