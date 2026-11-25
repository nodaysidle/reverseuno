QT += core gui qml quick quickcontrols2 multimedia network dbus svg websockets

CONFIG += c++17 release
TARGET = reverseuno
TEMPLATE = app
VERSION = 0.3.0
DEFINES += APP_VERSION=\\\"$$VERSION\\\"
QMAKE_CXXFLAGS += -Wall -Wextra

HEADERS += \
    src/backend.h \
    src/capture.h \
    src/cli.h \
    src/config.h \
    src/hypr.h \
    src/paste.h \
    src/service.h \
    src/stt.h \
    src/theme.h \
    src/tray.h \
    src/translator.h

SOURCES += \
    src/main.cpp \
    src/backend.cpp \
    src/capture.cpp \
    src/cli.cpp \
    src/config.cpp \
    src/hypr.cpp \
    src/paste.cpp \
    src/service.cpp \
    src/stt.cpp \
    src/theme.cpp \
    src/tray.cpp \
    src/translator.cpp

RESOURCES += src/resources.qrc
