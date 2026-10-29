#pragma once
#include <QAbstractListModel>
#include <QVariantList>
#include <QVariantMap>

// Dictation entries for the Hub's History list, newest first, with a search filter.
class HistoryModel : public QAbstractListModel
{
    Q_OBJECT
    Q_PROPERTY(int count READ count NOTIFY countChanged)
public:
    enum Role {
        IdRole = Qt::UserRole + 1,
        TextRole,
        AppClassRole,
        AppTitleRole,
        CreatedAtRole,
        InsertedRole,
        ErrorRole,
        FlaggedRole,
        WordCountRole,
    };
    using QAbstractListModel::QAbstractListModel;

    int rowCount(const QModelIndex &parent = QModelIndex()) const override;
    QVariant data(const QModelIndex &index, int role) const override;
    QHash<int, QByteArray> roleNames() const override;

    int count() const { return int(m_rows.size()); }
    // Non-map items and entries without an id are dropped.
    void setEntries(const QVariantList &entries);
    // Case-insensitive match on final text, app class and app title; blank shows all.
    Q_INVOKABLE void filter(const QString &query);
    // The whole entry (also filtered-out ones), or an empty map.
    Q_INVOKABLE QVariantMap entry(const QString &id) const;

signals:
    void countChanged();

private:
    void rebuild();

    QList<QVariantMap> m_all;
    QList<QVariantMap> m_rows;
    QString m_query;
};
