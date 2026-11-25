#include "service.h"

#include "backend.h"

QString Service::Start() { return m_backend->start() ? QString() : m_backend->failureReason(); }
QString Service::Stop() { return m_backend->stop() ? QString() : m_backend->failureReason(); }
QString Service::Toggle() { return m_backend->toggle() ? QString() : m_backend->failureReason(); }
QString Service::Cancel() {
    m_backend->cancel();
    return {};
}
void Service::Show() { m_backend->show(); }
// Queued, so the reply to the caller goes out before the app exits.
void Service::Quit() { QMetaObject::invokeMethod(m_backend, &Backend::quit, Qt::QueuedConnection); }
