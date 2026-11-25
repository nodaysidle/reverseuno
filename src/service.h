#pragma once

#include <QObject>
#include <QString>

class Backend;

// The D-Bus face of the running instance. `reverseuno start` and friends call
// these. A returned string is the reason a call was refused; empty means done.
class Service : public QObject {
    Q_OBJECT
    Q_CLASSINFO("D-Bus Interface", "io.github.nodaysidle.Reverseuno")

public:
    static QString busName() { return QStringLiteral("io.github.nodaysidle.Reverseuno"); }
    static QString objectPath() { return QStringLiteral("/"); }
    static QString interfaceName() { return busName(); }

    explicit Service(Backend *backend, QObject *parent = nullptr) : QObject(parent), m_backend(backend) {}

public slots:
    QString Start();
    QString Stop();
    QString Toggle();
    QString Cancel();
    void Show();
    void Quit();

private:
    Backend *m_backend;
};
