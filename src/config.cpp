#include "config.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

QString Config::defaultPath() { return QDir::homePath() + "/.config/reverseuno/config.json"; }

bool Config::keyFromEnvironment() { return !qEnvironmentVariable("DEEPGRAM_API_KEY").trimmed().isEmpty(); }

QString Config::effectiveKey() const {
    const QString env = qEnvironmentVariable("DEEPGRAM_API_KEY").trimmed();
    return env.isEmpty() ? apiKey : env;
}

Config Config::load(const QString &path) {
    Config config;
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly))
        return config;
    const QByteArray data = file.readAll();
    file.close();
    QJsonParseError error;
    const QJsonDocument document = QJsonDocument::fromJson(data, &error);
    if (error.error != QJsonParseError::NoError || !document.isObject()) {
        // Keep what was there; the next save would otherwise overwrite it.
        QFile::rename(path, path + ".corrupt-" + QString::number(QDateTime::currentSecsSinceEpoch()));
        return config;
    }
    const QJsonObject object = document.object();
    config.apiKey = object.value("apiKey").toString().trimmed();
    config.language = object.value("language").toString(config.language);
    config.insertMode = object.value("insertMode").toString(config.insertMode);
    config.microphone = object.value("microphone").toString();
    if (config.insertMode != "paste" && config.insertMode != "type" && config.insertMode != "copy")
        config.insertMode = "paste";
    return config;
}

QString Config::save(const QString &path) const {
    if (!QDir().mkpath(QFileInfo(path).absolutePath()))
        return QStringLiteral("Could not create %1").arg(QFileInfo(path).absolutePath());
    QJsonObject object{{"language", language}, {"insertMode", insertMode}};
    if (!apiKey.isEmpty())
        object.insert("apiKey", apiKey);
    if (!microphone.isEmpty())
        object.insert("microphone", microphone);

    QSaveFile file(path);
    if (!file.open(QIODevice::WriteOnly))
        return file.errorString();
    file.write(QJsonDocument(object).toJson());
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    return file.commit() ? QString() : file.errorString();
}
