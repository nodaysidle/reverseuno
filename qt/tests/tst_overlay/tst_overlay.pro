TEMPLATE = app
TARGET = tst_overlay
QT += testlib
CONFIG += testcase console
include(../../src/core.pri)
SOURCES += ../tst_overlay.cpp
QT += qml quick
DEFINES += QML_DIR=\\\"$$PWD/../../qml\\\"
