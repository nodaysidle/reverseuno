#include "Theme.h"

#include <QDir>
#include <QFile>
#include <QRegularExpression>

namespace {
const char *const FALLBACK = "#11111b";

QColor pick(const QHash<QString, QString> &c, std::initializer_list<const char *> keys, const QColor &fallback)
{
    static const QRegularExpression hex("^#[0-9a-fA-F]{6}$");
    for (const char *k : keys) {
        const QString v = c.value(QLatin1String(k));
        if (hex.match(v).hasMatch())
            return QColor(v);
    }
    return fallback;
}
}

Theme::Theme(const QString &current, QObject *parent)
    : QObject(parent),
      m_current(current.isEmpty() ? QDir::homePath() + "/.local/state/omarchy/current" : current)
{
    // theme switches are bursts of renames; settle before reading
    m_settle.setSingleShot(true);
    m_settle.setInterval(150);
    connect(&m_settle, &QTimer::timeout, this, &Theme::reload);
    connect(&m_watcher, &QFileSystemWatcher::directoryChanged, this, [this] { m_settle.start(); });
    if (QDir(m_current).exists())
        m_watcher.addPath(m_current);
    reload();
}

QHash<QString, QString> Theme::parseColors(const QString &source)
{
    static const QRegularExpression line("^\\s*([a-z0-9_]+)\\s*=\\s*\"([^\"]*)\"");
    QHash<QString, QString> out;
    for (const QString &l : source.split('\n')) {
        const auto m = line.match(l);
        if (m.hasMatch())
            out.insert(m.captured(1), m.captured(2));
    }
    return out;
}

void Theme::reload()
{
    QHash<QString, QString> c;
    QFile f(m_current + "/theme/colors.toml");
    if (f.open(QIODevice::ReadOnly))
        c = parseColors(QString::fromUtf8(f.readAll()));
    // the watched dir may have been replaced; re-arm
    if (m_watcher.directories().isEmpty() && QDir(m_current).exists())
        m_watcher.addPath(m_current);

    const QColor bg = pick(c, {"background"}, QColor(FALLBACK));
    const QColor fg = pick(c, {"foreground"}, QColor("#cdd6f4"));
    const QColor ac = pick(c, {"accent", "blue"}, QColor("#89b4fa"));
    m_background = bg;
    m_foreground = fg;
    m_accent = ac;
    m_muted = pick(c, {"muted", "dark_foreground", "bright_black", "color8"}, fg);
    m_surface = pick(c, {"lighter_background"}, bg);
    m_red = pick(c, {"red", "color1"}, ac);
    m_green = pick(c, {"green", "color2"}, ac);
    emit changed();
}
