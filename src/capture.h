#pragma once

#include <QAudioSource>
#include <QByteArray>
#include <QIODevice>
#include <QObject>
#include <memory>

// Where the audio comes from. The Backend only sees 16 kHz mono int16 frames,
// so tests can feed it without a microphone. Audio stays in memory.
class Capture : public QObject {
    Q_OBJECT
public:
    using QObject::QObject;
    // Returns an error text, empty when capture is running.
    virtual QString start(const QString &deviceDescription) = 0;
    virtual void stop() = 0;

signals:
    void pcm(const QByteArray &frames);
    void failed(const QString &reason);
};

class MicCapture : public Capture {
    Q_OBJECT
public:
    explicit MicCapture(QObject *parent = nullptr) : Capture(parent) {}
    QString start(const QString &deviceDescription) override;
    void stop() override;

private:
    std::unique_ptr<QAudioSource> m_source;
    QIODevice *m_io = nullptr;
};
