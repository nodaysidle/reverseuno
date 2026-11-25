QT += core gui qml quick quickcontrols2 multimedia network dbus svg websockets testlib
CONFIG += c++17 testcase
TARGET = reverseuno_tests
TEMPLATE = app
QMAKE_CXXFLAGS += -Wall -Wextra

INCLUDEPATH += ../src
DEFINES += SRCDIR=\\\"$$PWD/../src\\\" APP_VERSION=\\\"test\\\"

HEADERS += \
    ../src/backend.h \
    ../src/capture.h \
    ../src/cli.h \
    ../src/config.h \
    ../src/hypr.h \
    ../src/paste.h \
    ../src/service.h \
    ../src/stt.h \
    ../src/theme.h \
    ../src/tray.h \
    ../src/translator.h

SOURCES += \
    reverseuno_tests.cpp \
    ../src/backend.cpp \
    ../src/capture.cpp \
    ../src/cli.cpp \
    ../src/config.cpp \
    ../src/hypr.cpp \
    ../src/paste.cpp \
    ../src/service.cpp \
    ../src/stt.cpp \
    ../src/theme.cpp \
    ../src/tray.cpp \
    ../src/translator.cpp

RESOURCES += ../src/resources.qrc
