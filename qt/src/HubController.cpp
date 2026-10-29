#include "HubController.h"

#include <QWindow>

#include "EngineClient.h"

HubController::HubController(EngineClient *client, QObject *parent)
    : QObject(parent), m_client(client)
{
    connect(client, &EngineClient::event, this, [this](const QVariantMap &e) {
        const QString type = e.value("type").toString();
        if (type == "refresh") reload();
        else if (type == "page") show(e.value("page").toString());
    });
    connect(client, &EngineClient::connectedChanged, this, [this](bool on) {
        if (m_connected == on) return;
        m_connected = on;
        emit connectedChanged();
    });
    // EngineClient itself bootstraps on every (re)connect; catch that reply too.
    connect(client, &EngineClient::callFinished, this,
            [this](const QString &method, const QVariant &result, const QString &error) {
                // A failed bootstrap (engine went away) keeps what is on screen.
                if (method == "bootstrap" && error.isEmpty()) applyBootstrap(result);
            });
}

QString HubController::pageFor(const QString &name)
{
    if (name == "style") return "settings";
    if (name == "dictionary" || name == "snippets" || name == "settings") return name;
    return "history";
}

QVariantMap HubController::patchFor(const QString &path, const QVariant &value)
{
    const QStringList keys = path.split('.');
    QVariant patch = value;
    for (auto it = keys.crbegin(); it != keys.crend(); ++it) patch = QVariantMap{{*it, patch}};
    return patch.toMap();
}

void HubController::setPage(const QString &page)
{
    const QString p = pageFor(page);
    if (p == m_page) return;
    m_page = p;
    emit pageChanged();
}

void HubController::attachWindow(QWindow *window)
{
    m_window = window;
}

void HubController::show(const QString &page)
{
    if (!page.isEmpty()) setPage(page);
    if (!m_window) return;
    m_window->show();
    m_window->raise();
    m_window->requestActivate();
}

void HubController::applyBootstrap(const QVariant &bootstrap)
{
    const QVariantMap b = bootstrap.toMap();
    m_history.setEntries(b.value("history").toList());
    m_settings = b.value("settings").toMap();
    m_dictionary = b.value("dictionary").toList();
    m_snippets = b.value("snippets").toList();
    m_suggestions = b.value("suggestions").toList();
    m_version = b.value("version").toString();
    m_secrets.clear();
    for (const QVariant &s : b.value("secrets").toList()) {
        const QVariantMap m = s.toMap();
        if (!m.value("name").toString().isEmpty()) m_secrets.insert(m.value("name").toString(), m.value("present").toBool());
    }
    emit dataChanged();
}

void HubController::request(const QString &method, const QVariantList &args, Done done)
{
    QPointer<HubController> self(this);
    m_client->call(method, args, [self, method, done](const QVariant &result, const QString &error) {
        if (!self) return;
        if (!error.isEmpty()) emit self->notice(error, false);
        else if (done) done(result);
    });
}

void HubController::reload()
{
    m_client->call("bootstrap");
}

void HubController::set(const QString &path, const QVariant &value)
{
    request("updateSettings", {patchFor(path, value)}, [this](const QVariant &settings) {
        m_settings = settings.toMap();
        emit dataChanged();
    });
}

static QStringList ids(const QVariant &models)
{
    QStringList out;
    for (const QVariant &m : models.toList())
        if (!m.toMap().value("id").toString().isEmpty()) out << m.toMap().value("id").toString();
    return out;
}

void HubController::loadLists()
{
    request("listModels", {"format"}, [this](const QVariant &r) { m_formatModels = ids(r); emit listsChanged(); });
    request("listModels", {"stt"}, [this](const QVariant &r) { m_sttModels = ids(r); emit listsChanged(); });
    request("listDevices", {}, [this](const QVariant &r) { m_devices = r.toList(); emit listsChanged(); });
}

void HubController::testProvider(const QString &name)
{
    if (!m_testing.isEmpty()) return;
    m_testing = name;
    emit testingChanged();
    QPointer<HubController> self(this);
    m_client->call("testProvider", {name}, [self](const QVariant &result, const QString &error) {
        if (!self) return;
        self->m_testing.clear();
        emit self->testingChanged();
        const QVariantMap r = result.toMap();
        if (!error.isEmpty()) emit self->notice(error, false);
        else emit self->notice(r.value("message").toString(), r.value("ok").toBool());
    });
}

void HubController::run(const QString &method, const QVariantList &args, const QString &okNotice)
{
    request(method, args, [this, okNotice](const QVariant &result) {
        // ActionResult-shaped replies ({ok, error}) report their own failure.
        const QVariantMap r = result.toMap();
        if (r.contains("ok") && !r.value("ok").toBool()) {
            emit notice(r.value("error").toString(), false);
            return;
        }
        if (okNotice.isEmpty() || (okNotice.contains("%1") && result.isNull())) return;
        emit notice(okNotice.contains("%1") ? okNotice.arg(result.toString()) : okNotice, true);
    });
}
