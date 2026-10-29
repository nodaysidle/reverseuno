TEMPLATE = app
TARGET = tst_history
QT += testlib
CONFIG += testcase console
include(../../src/core.pri)
SOURCES += ../tst_history.cpp
QT += qml quick
DEFINES += QML_DIR=\\\"$$PWD/../../qml\\\"
