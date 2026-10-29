#include "SseParser.h"

QList<QByteArray> SseParser::feed(const QByteArray &chunk)
{
    m_buf.append(chunk);
    m_buf.replace("\r\n", "\n");
    QList<QByteArray> out;
    int end;
    while ((end = m_buf.indexOf("\n\n")) >= 0) {
        const QByteArray block = m_buf.left(end);
        m_buf.remove(0, end + 2);
        QByteArray data;
        bool has = false;
        for (const QByteArray &line : block.split('\n')) {
            if (!line.startsWith("data:"))
                continue; // comments (": ping") and other fields are ignored
            QByteArray v = line.mid(5);
            if (v.startsWith(' '))
                v.remove(0, 1);
            if (has)
                data.append('\n');
            data.append(v);
            has = true;
        }
        if (has)
            out.append(data);
    }
    return out;
}
