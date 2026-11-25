#include "capture.h"

#include <QAudioDevice>
#include <QAudioFormat>
#include <QCoreApplication>
#include <QMediaDevices>

#include "stt.h"

QString MicCapture::start(const QString &deviceDescription) {
    stop();
    QAudioDevice device = QMediaDevices::defaultAudioInput();
    for (const QAudioDevice &candidate : QMediaDevices::audioInputs())
        if (!deviceDescription.isEmpty() && candidate.description() == deviceDescription)
            device = candidate;
    if (device.isNull())
        return QCoreApplication::translate("MicCapture", "No microphone found");

    QAudioFormat format;
    format.setSampleRate(SttConfig::kSampleRate);
    format.setChannelCount(1);
    format.setSampleFormat(QAudioFormat::Int16);
    if (!device.isFormatSupported(format))
        return QCoreApplication::translate("MicCapture", "The microphone %1 does not offer 16 kHz mono audio")
            .arg(device.description());

    m_source = std::make_unique<QAudioSource>(device, format);
    m_io = m_source->start();
    if (!m_io || m_source->error() != QAudio::NoError) {
        m_source.reset();
        m_io = nullptr;
        return QCoreApplication::translate("MicCapture", "Could not open the microphone %1").arg(device.description());
    }
    connect(m_io, &QIODevice::readyRead, this, [this] {
        const QByteArray frames = m_io->readAll();
        if (!frames.isEmpty())
            emit pcm(frames);
    });
    connect(m_source.get(), &QAudioSource::stateChanged, this, [this](QAudio::State state) {
        if (state == QAudio::StoppedState && m_source && m_source->error() != QAudio::NoError)
            emit failed(QCoreApplication::translate("MicCapture", "The microphone stopped working"));
    });
    return {};
}

void MicCapture::stop() {
    if (!m_source)
        return;
    m_source->disconnect(this);
    m_source->stop();
    m_io = nullptr;
    m_source.reset();
}
