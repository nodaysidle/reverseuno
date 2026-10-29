TEMPLATE = app
TARGET = tst_tray
QT += testlib
CONFIG += testcase console
include(../../src/core.pri)
SOURCES += ../tst_tray.cpp
DEFINES += UI_BIN=\\\"$$OUT_PWD/../../reverseuno-ui\\\"
