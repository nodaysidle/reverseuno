#pragma once

#include <QImage>
#include <QObject>
#include <QUrl>

class Backend;
class Theme;

// The icon for the bar's tray: the N in the theme accent, redrawn when the
// accent changes, with a small badge for the session state.
class Tray : public QObject {
    Q_OBJECT
    Q_PROPERTY(QUrl iconUrl READ iconUrl NOTIFY iconChanged)

public:
    explicit Tray(Theme *theme, Backend *backend, const QString &cacheDirectory = {}, QObject *parent = nullptr);

    QUrl iconUrl() const { return m_url; }

    // state: idle, listening (white badge), busy (dimmed N) or error (red badge)
    static QImage render(const QString &accent, const QString &state, int size = 128);
    static QString stateFor(const QString &backendState);

signals:
    void iconChanged();

private:
    void update();

    Theme *m_theme;
    Backend *m_backend;
    QString m_directory;
    QUrl m_url;
};
