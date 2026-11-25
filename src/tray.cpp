#include "tray.h"

#include <QDir>
#include <QFile>
#include <QPainter>
#include <QStandardPaths>
#include <QSvgRenderer>

#include "backend.h"
#include "theme.h"

Tray::Tray(Theme *theme, Backend *backend, const QString &cacheDirectory, QObject *parent)
    : QObject(parent), m_theme(theme), m_backend(backend) {
    m_directory = cacheDirectory;
    if (m_directory.isEmpty()) {
        QString base = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
        if (base.isEmpty())
            base = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
        m_directory = base + "/reverseuno-tray";
    }
    QDir(m_directory).mkpath(".");
    // Fresh each run, so old accents do not pile up.
    for (const QString &old : QDir(m_directory).entryList({"tray-*.png"}, QDir::Files))
        QFile::remove(m_directory + "/" + old);
    connect(theme, &Theme::changed, this, &Tray::update);
    connect(backend, &Backend::stateChanged, this, &Tray::update);
    update();
}

QString Tray::stateFor(const QString &backendState) {
    if (backendState == "listening")
        return "listening";
    if (backendState == "transcribing" || backendState == "inserting")
        return "busy";
    if (backendState == "error")
        return "error";
    return "idle";
}

QImage Tray::render(const QString &accent, const QString &state, int size) {
    QFile file(":/tray.svg");
    if (!file.open(QIODevice::ReadOnly))
        return {};
    QByteArray svg = file.readAll();
    svg.replace("#ffffff", accent.toUtf8());

    QImage image(size, size, QImage::Format_ARGB32_Premultiplied);
    image.fill(Qt::transparent);
    QPainter painter(&image);
    painter.setRenderHint(QPainter::Antialiasing);
    if (state == "busy")
        painter.setOpacity(0.55);
    QSvgRenderer(svg).render(&painter);
    painter.setOpacity(1);

    if (state == "listening" || state == "error") {
        const double centre = size * 0.80, radius = size * 0.20, ring = size * 0.04;
        // A transparent ring first, so the badge reads on top of the N's leg.
        painter.setCompositionMode(QPainter::CompositionMode_Clear);
        painter.setPen(Qt::NoPen);
        painter.drawEllipse(QPointF(centre, centre), radius + ring, radius + ring);
        painter.setCompositionMode(QPainter::CompositionMode_SourceOver);
        painter.setBrush(QColor(state == "error" ? "#ff453a" : "#ffffff"));
        painter.drawEllipse(QPointF(centre, centre), radius, radius);
    }
    return image;
}

void Tray::update() {
    const QString state = stateFor(m_backend->state());
    QString name = m_theme->accent();
    name.remove('#');
    const QString path = QStringLiteral("%1/tray-%2-%3.png").arg(m_directory, state, name);
    if (!QFile::exists(path))
        render(m_theme->accent(), state).save(path);
    const QUrl url = QUrl::fromLocalFile(path);
    if (url != m_url) {
        m_url = url;
        emit iconChanged();
    }
}
