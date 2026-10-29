#pragma once
#include <QColor>
#include <QFileSystemWatcher>
#include <QHash>
#include <QObject>
#include <QTimer>

class Theme : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QColor background READ background NOTIFY changed)
    Q_PROPERTY(QColor foreground READ foreground NOTIFY changed)
    Q_PROPERTY(QColor accent READ accent NOTIFY changed)
    Q_PROPERTY(QColor muted READ muted NOTIFY changed)
    Q_PROPERTY(QColor surface READ surface NOTIFY changed)
    Q_PROPERTY(QColor red READ red NOTIFY changed)
    Q_PROPERTY(QColor green READ green NOTIFY changed)
public:
    // `current` is the Omarchy dir holding theme/colors.toml; overridable for tests.
    explicit Theme(const QString &current = QString(), QObject *parent = nullptr);

    static QHash<QString, QString> parseColors(const QString &source);

    QColor background() const { return m_background; }
    QColor foreground() const { return m_foreground; }
    QColor accent() const { return m_accent; }
    QColor muted() const { return m_muted; }
    QColor surface() const { return m_surface; }
    QColor red() const { return m_red; }
    QColor green() const { return m_green; }

    void reload();

signals:
    void changed();

private:
    QString m_current;
    QFileSystemWatcher m_watcher;
    QTimer m_settle;
    QColor m_background, m_foreground, m_accent, m_muted, m_surface, m_red, m_green;
};
