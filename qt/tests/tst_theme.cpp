#include <QtTest>
#include <QTemporaryDir>

#include "Theme.h"

class TstTheme : public QObject
{
    Q_OBJECT
private slots:
    void parseColorsReadsKeyValueLines()
    {
        const auto c = Theme::parseColors("# c\naccent = \"#112233\"\n  red=\"#ff0000\"\nbad line\n");
        QCOMPARE(c.value("accent"), QString("#112233"));
        QCOMPARE(c.value("red"), QString("#ff0000"));
        QCOMPARE(c.size(), 2);
    }
    void missingColorsTomlYieldsFallbackPalette()
    {
        QTemporaryDir d;
        Theme t(d.path());
        QVERIFY(t.background().isValid());
        QVERIFY(t.foreground().isValid());
        QCOMPARE(t.surface(), t.background());
        QCOMPARE(t.muted(), t.foreground());
        QCOMPARE(t.red(), t.accent());
    }
    void reloadsWhenThemeSwitches()
    {
        QTemporaryDir d;
        Theme t(d.path());
        QSignalSpy spy(&t, &Theme::changed);
        QVERIFY(QDir(d.path()).mkpath("theme"));
        QFile f(d.path() + "/theme/colors.toml");
        QVERIFY(f.open(QIODevice::WriteOnly));
        f.write("background = \"#010203\"\nlighter_background = \"#040506\"\ncolor1 = \"#aa0000\"\n");
        f.close();
        QTRY_COMPARE(t.background(), QColor("#010203"));
        QCOMPARE(t.surface(), QColor("#040506"));
        QCOMPARE(t.red(), QColor("#aa0000"));
        QVERIFY(spy.count() >= 1);
    }
};

QTEST_MAIN(TstTheme)
#include "tst_theme.moc"
