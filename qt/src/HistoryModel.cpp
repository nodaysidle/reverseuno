#include "HistoryModel.h"

#include <algorithm>

int HistoryModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : count();
}

QVariant HistoryModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= count()) return {};
    const QVariantMap &e = m_rows.at(index.row());
    switch (role) {
    case IdRole: return e.value("id").toString();
    case TextRole: return e.value("text").toString();
    case AppClassRole: return e.value("appClass").toString();
    case AppTitleRole: return e.value("appTitle").toString();
    case CreatedAtRole: return e.value("createdAt").toDouble();
    case InsertedRole: return e.value("inserted").toBool();
    case ErrorRole: return e.value("error").toString();
    case FlaggedRole: return e.value("flagged").toBool();
    case WordCountRole: return e.value("wordCount").toInt();
    }
    return {};
}

QHash<int, QByteArray> HistoryModel::roleNames() const
{
    return {{IdRole, "id"},           {TextRole, "text"},         {AppClassRole, "appClass"},
            {AppTitleRole, "appTitle"}, {CreatedAtRole, "createdAt"}, {InsertedRole, "inserted"},
            {ErrorRole, "error"},     {FlaggedRole, "flagged"},   {WordCountRole, "wordCount"}};
}

void HistoryModel::setEntries(const QVariantList &entries)
{
    m_all.clear();
    for (const QVariant &v : entries) {
        const QVariantMap e = v.toMap();
        if (!e.value("id").toString().isEmpty()) m_all.append(e);
    }
    std::stable_sort(m_all.begin(), m_all.end(), [](const QVariantMap &a, const QVariantMap &b) {
        return a.value("createdAt").toDouble() > b.value("createdAt").toDouble();
    });
    rebuild();
}

void HistoryModel::filter(const QString &query)
{
    m_query = query.trimmed();
    rebuild();
}

QVariantMap HistoryModel::entry(const QString &id) const
{
    for (const QVariantMap &e : m_all)
        if (e.value("id").toString() == id) return e;
    return {};
}

void HistoryModel::rebuild()
{
    beginResetModel();
    m_rows.clear();
    for (const QVariantMap &e : m_all) {
        const QString hay = e.value("text").toString() + '\n' + e.value("appClass").toString() + '\n'
                            + e.value("appTitle").toString();
        if (m_query.isEmpty() || hay.contains(m_query, Qt::CaseInsensitive)) m_rows.append(e);
    }
    endResetModel();
    emit countChanged();
}
