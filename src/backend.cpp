#include "backend.h"

#include <QCoreApplication>
#include <QMediaDevices>
#include <QThread>
#include <cmath>

#ifndef APP_VERSION
#define APP_VERSION "0"
#endif

namespace {
// A frame whose RMS (0..1) stays under this is treated as silence. Room noise
// on a quiet desk sits well below it; speech well above.
constexpr double kSpeechRms = 0.003;
constexpr int kLevelBars = 48;
constexpr int kHistoryLimit = 50;
}

Backend::Backend(const BackendOptions &options, QObject *parent)
    : QObject(parent), m_options(options), m_stt(options.sttUrl, this) {
    if (m_options.configPath.isEmpty())
        m_options.configPath = Config::defaultPath();
    if (!m_options.activeWindow)
        m_options.activeWindow = [] { return Paste::activeWindow(); };
    if (!m_options.insert)
        m_options.insert = [](const QString &text, const QString &mode, const Paste::Window &target) {
            return Paste::insert(text, mode, target);
        };
    m_config = Config::load(m_options.configPath);
    m_levels = QVariantList(kLevelBars, 0.0);
    refreshSetup();

    if (m_options.capture) {
        m_capture = m_options.capture;
    } else {
        m_mic = std::make_unique<MicCapture>(this);
        m_capture = m_mic.get();
        auto *devices = new QMediaDevices(this);
        connect(devices, &QMediaDevices::audioInputsChanged, this, [this] {
            emit microphonesChanged();
            refreshProblems();
        });
    }
    connect(m_capture, &Capture::pcm, this, &Backend::pushPcm);
    connect(m_capture, &Capture::failed, this, [this](const QString &reason) {
        if (m_state != "listening")
            return;
        endListening();
        discardStream();
        m_pcm.clear();
        fail(reason);
    });
    connect(&m_stt, &Stt::finished, this, &Backend::onSttResult);

    m_limit.setSingleShot(true);
    connect(&m_limit, &QTimer::timeout, this, [this] { stop(); });
    m_restoreTimer.setSingleShot(true);
    connect(&m_restoreTimer, &QTimer::timeout, this, &Backend::flushRestore);
}

Backend::~Backend() {
    cancel();
    flushRestore();
}

QString Backend::version() const { return QStringLiteral(APP_VERSION); }

QString Backend::keyProblem() const { return tr("No Deepgram API key: add one in settings (S) or set DEEPGRAM_API_KEY"); }

QStringList Backend::problems() const {
    QStringList list;
    if (!hasKey())
        list << keyProblem();
    for (const Paste::Missing &tool : Paste::missingTools())
        list << tr("%1 is not installed (package %2)").arg(tool.tool, tool.package);
    if (!m_options.capture && QMediaDevices::audioInputs().isEmpty())
        list << tr("No microphone found");
    return list;
}

bool Backend::microphoneFound() const { return m_options.capture || !QMediaDevices::audioInputs().isEmpty(); }

QStringList Backend::missingTools() const {
    QStringList names;
    for (const Paste::Missing &tool : Paste::missingTools())
        names << tool.tool;
    return names;
}

QString Backend::toolsCommand() const {
    QStringList packages;
    for (const Paste::Missing &tool : Paste::missingTools())
        if (!packages.contains(tool.package))
            packages << tool.package;
    return packages.isEmpty() ? QString() : "omarchy pkg add " + packages.join(' ');
}

void Backend::refreshSetup() {
    const QString binary = Hypr::binaryPath();
    m_hotkeysInstalled = Hypr::installed(Hypr::Bindings, binary);
    m_autostartInstalled = Hypr::installed(Hypr::Autostart, binary);
    emit setupChanged();
}

void Backend::installHotkeys() {
    const Hypr::Result result = Hypr::install(Hypr::Bindings, Hypr::binaryPath());
    m_hotkeyMessage = result.message;
    m_hotkeyFailed = !result.ok;
    refreshSetup();
}

void Backend::installAutostart() {
    const Hypr::Result result = Hypr::install(Hypr::Autostart, Hypr::binaryPath());
    m_autostartMessage = result.message;
    m_autostartFailed = !result.ok;
    refreshSetup();
}

QStringList Backend::microphones() const {
    QStringList names;
    if (!m_options.capture)
        for (const QAudioDevice &device : QMediaDevices::audioInputs())
            names << device.description();
    return names;
}

void Backend::setState(const QString &state) {
    if (state == m_state)
        return;
    m_state = state;
    emit stateChanged();
    emit unsavedChanged();
}

void Backend::setNotice(const QString &notice) {
    if (notice == m_notice)
        return;
    m_notice = notice;
    emit noticeChanged();
}

// Errors stay in the window until the next session starts.
void Backend::fail(const QString &message) {
    m_failure = message;
    m_error = message;
    emit errorChanged();
    setNotice({});
    setState("error");
}

void Backend::saveConfig() {
    const QString problem = m_config.save(m_options.configPath);
    if (!problem.isEmpty()) {
        m_error = tr("Could not save settings: %1").arg(problem);
        emit errorChanged();
    }
}

void Backend::setMicrophone(const QString &description) {
    if (description == m_config.microphone)
        return;
    m_config.microphone = description;
    saveConfig();
    emit settingsChanged();
}

void Backend::setLanguage(const QString &code) {
    if (code.isEmpty() || code == m_config.language)
        return;
    m_config.language = code;
    saveConfig();
    emit settingsChanged();
}

void Backend::setInsertMode(const QString &mode) {
    if ((mode != "paste" && mode != "type" && mode != "copy") || mode == m_config.insertMode)
        return;
    m_config.insertMode = mode;
    saveConfig();
    emit settingsChanged();
}

void Backend::setApiKey(const QString &key) {
    m_config.apiKey = key.trimmed();
    saveConfig();
    emit keyChanged();
    refreshProblems();
}

bool Backend::start() {
    m_failure.clear();
    if (busy()) {
        m_failure = QStringLiteral("busy");
        return false;
    }
    m_error.clear();
    emit errorChanged();
    setNotice({});
    if (!hasKey()) {
        fail(keyProblem());
        return false;
    }

    m_pcm.clear();
    m_hasSpeech = false;
    m_levels = QVariantList(kLevelBars, 0.0);
    emit levelsChanged();
    m_interim.clear();
    emit interimChanged();

    // Recorded now, at key down: this is where the text has to land.
    m_target = m_options.activeWindow();
    const QString problem = m_capture->start(m_config.microphone);
    if (!problem.isEmpty()) {
        fail(problem);
        return false;
    }

    discardStream();
    m_stream = new SttStream(m_options.sttWsUrl, this);
    connect(m_stream, &SttStream::interim, this, [this](const QString &text) {
        m_interim = text;
        emit interimChanged();
    });
    m_stream->start(m_config.effectiveKey(), m_config.language);
    m_limit.start(m_options.maxRecordingMs);
    setState("listening");
    return true;
}

void Backend::pushPcm(const QByteArray &frames) {
    if (m_state != "listening")
        return;
    m_pcm.append(frames);
    if (m_stream)
        m_stream->sendPcm(frames);

    const qsizetype count = frames.size() / 2;
    if (count == 0)
        return;
    const auto *samples = reinterpret_cast<const qint16 *>(frames.constData());
    double sum = 0;
    for (qsizetype i = 0; i < count; ++i)
        sum += double(samples[i]) * samples[i];
    const double rms = std::sqrt(sum / double(count)) / 32768.0;
    if (rms >= kSpeechRms)
        m_hasSpeech = true;
    m_levels.removeFirst();
    m_levels.append(qMin(1.0, std::sqrt(rms) * 2.0));
    emit levelsChanged();
}

void Backend::endListening() {
    m_limit.stop();
    m_capture->stop();
    m_stopped.start();
}

void Backend::discardStream() {
    if (!m_stream)
        return;
    m_stream->abort();
    m_stream->deleteLater();
    m_stream.clear();
}

bool Backend::stop() {
    m_failure.clear();
    if (m_state != "listening") {
        m_failure = tr("Not listening");
        return false;
    }
    endListening();

    const qint64 durationMs = m_pcm.size() / SttConfig::kBytesPerMs;
    if (durationMs < m_options.minRecordingMs || !m_hasSpeech) {
        discardStream();
        m_pcm.clear();
        setNotice(durationMs < m_options.minRecordingMs ? tr("Too short, nothing was sent") : tr("No speech heard"));
        setState("idle");
        return true;
    }

    setState("transcribing");
    if (m_stream && m_stream->usable()) {
        connect(m_stream, &SttStream::finished, this, [this](const SttResult &result) {
            m_stream->deleteLater();
            m_stream.clear();
            // No text from the socket (it failed, or heard nothing): the whole
            // clip is still in memory, so send it in one request.
            if (result.ok)
                deliver(result.text);
            else
                runBatch();
        });
        m_stream->finish();
    } else {
        discardStream();
        runBatch();
    }
    return true;
}

void Backend::runBatch() { m_stt.transcribe(SttConfig::wav(m_pcm), m_config.effectiveKey(), m_config.language); }

void Backend::onSttResult(const SttResult &result) {
    if (m_state != "transcribing")
        return;
    if (!result.ok) {
        m_pcm.clear();
        fail(result.error);
    } else if (result.text.isEmpty()) {
        m_pcm.clear();
        m_interim.clear();
        emit interimChanged();
        setNotice(tr("No speech heard"));
        setState("idle");
    } else {
        deliver(result.text);
    }
}

void Backend::deliver(const QString &text) {
    m_pcm.clear();
    m_interim.clear();
    emit interimChanged();
    m_transcript = text;
    m_history.prepend(text);
    while (m_history.size() > kHistoryLimit)
        m_history.removeLast();
    m_delivered = false;
    emit transcriptChanged();
    emit historyChanged();
    setState("inserting");

    if (m_stopped.isValid()) {
        const qint64 wait = m_options.settleMs - m_stopped.elapsed();
        if (wait > 0)
            QThread::msleep(ulong(wait));
    }
    const Paste::Result result = m_options.insert(text, m_config.insertMode, m_target);
    m_delivered = result.ok || result.onClipboard;
    if (result.ok) {
        setNotice(result.note);
        setState("done");
        if (result.restoreDelayMs > 0) {
            flushRestore();
            m_restore = result;
            m_restoreTimer.start(result.restoreDelayMs);
        }
    } else {
        fail(result.error);
    }
    emit unsavedChanged();
}

void Backend::flushRestore() {
    if (!m_restoreTimer.isActive())
        return;
    m_restoreTimer.stop();
    Paste::restore(m_restore.snapshot, m_restore.written);
}

bool Backend::toggle() { return m_state == "listening" ? stop() : start(); }

bool Backend::cancel() {
    if (!busy())
        return false;
    endListening();
    discardStream();
    m_stt.abort();
    m_pcm.clear();
    m_interim.clear();
    emit interimChanged();
    setNotice(tr("Cancelled"));
    setState("idle");
    return true;
}

void Backend::copyTranscript() { copyText(m_transcript); }

void Backend::copyText(const QString &text) {
    if (text.isEmpty())
        return;
    if (Paste::copyText(text)) {
        if (text == m_transcript) {
            m_delivered = true;
            emit unsavedChanged();
        }
        setNotice(tr("Copied to the clipboard"));
    } else {
        m_error = tr("wl-copy could not set the clipboard");
        emit errorChanged();
    }
}

void Backend::quit() {
    cancel();
    flushRestore();
    QCoreApplication::quit();
}
