#pragma once
#include <QObject>
#include <QPointer>
#include <QStringList>
#include <QVariantList>
#include <QVariantMap>

#include "HistoryModel.h"

class EngineClient;
class QWindow;

// Everything the Hub shows, from the engine's bootstrap, and the calls it makes.
// The engine stays the owner: after each write it sends `refresh` and we re-read.
class HubController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged)
    Q_PROPERTY(QString page READ page WRITE setPage NOTIFY pageChanged)
    Q_PROPERTY(HistoryModel *history READ history CONSTANT)
    Q_PROPERTY(QVariantMap settings READ settings NOTIFY dataChanged)
    Q_PROPERTY(QVariantList dictionary READ dictionary NOTIFY dataChanged)
    Q_PROPERTY(QVariantList snippets READ snippets NOTIFY dataChanged)
    Q_PROPERTY(QVariantList suggestions READ suggestions NOTIFY dataChanged)
    Q_PROPERTY(QVariantMap secrets READ secrets NOTIFY dataChanged)
    Q_PROPERTY(QString version READ version NOTIFY dataChanged)
    Q_PROPERTY(QStringList formatModels READ formatModels NOTIFY listsChanged)
    Q_PROPERTY(QStringList sttModels READ sttModels NOTIFY listsChanged)
    Q_PROPERTY(QVariantList devices READ devices NOTIFY listsChanged)
    Q_PROPERTY(QString testing READ testing NOTIFY testingChanged)
public:
    explicit HubController(EngineClient *client, QObject *parent = nullptr);

    // history|dictionary|snippets|settings; legacy home→history, style→settings.
    static QString pageFor(const QString &name);
    // "format.cleanup", "light" → {format: {cleanup: "light"}}
    static QVariantMap patchFor(const QString &path, const QVariant &value);

    // A whole number typed in a field: null when it is not one, else clamped to [min, max].
    // Settings numbers must reach the engine as JSON numbers (it checks typeof).
    static QVariant number(const QString &text, int min, int max);
    // Edits one field of a per-app paste rule (Settings.insert.appRules) in place.
    // False, and `rules` untouched, for a bad index, regex, key or delay.
    static bool changeRule(QVariantList &rules, int index, const QString &field, const QVariant &value);
    static bool validBaseUrl(const QString &url);

    bool connected() const { return m_connected; }
    QString page() const { return m_page; }
    void setPage(const QString &page);
    HistoryModel *history() { return &m_history; }
    QVariantMap settings() const { return m_settings; }
    QVariantList dictionary() const { return m_dictionary; }
    QVariantList snippets() const { return m_snippets; }
    QVariantList suggestions() const { return m_suggestions; }
    // name → present (write-only secrets: never a value)
    QVariantMap secrets() const { return m_secrets; }
    QString version() const { return m_version; }
    QStringList formatModels() const { return m_formatModels; }
    QStringList sttModels() const { return m_sttModels; }
    QVariantList devices() const { return m_devices; }
    QString testing() const { return m_testing; }

    // Shown and raised on a `page` event (and by the tray later).
    void attachWindow(QWindow *window);
    void applyBootstrap(const QVariant &bootstrap);
    // `--page`: the engine started us to show the Hub; do it once the first bootstrap lands.
    void openAfterBootstrap(const QString &page) { m_openPage = page; }

    Q_INVOKABLE void show(const QString &page = QString());
    Q_INVOKABLE void reload();
    // One write path for every Settings control: a small nested updateSettings patch.
    Q_INVOKABLE void set(const QString &path, const QVariant &value);
    // False (nothing sent, a notice said why) when the value is rejected; the field reverts.
    Q_INVOKABLE bool setNumber(const QString &path, const QString &text, int min, int max);
    Q_INVOKABLE bool setBaseUrl(const QString &url);
    // appRules is written as a whole array; the engine replaces it.
    Q_INVOKABLE void addAppRule();
    Q_INVOKABLE void removeAppRule(int index);
    Q_INVOKABLE bool setAppRule(int index, const QString &field, const QVariant &value);
    Q_INVOKABLE void loadLists();
    Q_INVOKABLE void testProvider(const QString &name);
    // Any other RPC. A failure always becomes a notice; `okNotice` ("%1" = result) on success.
    Q_INVOKABLE void run(const QString &method, const QVariantList &args = {}, const QString &okNotice = QString());

signals:
    void connectedChanged();
    void pageChanged();
    void dataChanged();
    void listsChanged();
    void testingChanged();
    void notice(const QString &text, bool ok);

private:
    using Done = std::function<void(const QVariant &result)>;
    QVariantList appRules() const;
    void request(const QString &method, const QVariantList &args, Done done);

    EngineClient *m_client;
    QPointer<QWindow> m_window;
    HistoryModel m_history;
    bool m_connected = false;
    QString m_page = "history";
    QVariantMap m_settings;
    QVariantList m_dictionary, m_snippets, m_suggestions, m_devices;
    QVariantMap m_secrets;
    QString m_version, m_testing, m_openPage;
    QStringList m_formatModels, m_sttModels;
};
