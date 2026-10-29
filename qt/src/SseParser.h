#pragma once
#include <QByteArray>
#include <QList>

// Incremental Server-Sent Events parser: feed raw network reads, get complete event payloads.
class SseParser
{
public:
    QList<QByteArray> feed(const QByteArray &chunk);

private:
    QByteArray m_buf;
};
