QT += core gui network
CONFIG += c++17
INCLUDEPATH += $$PWD
HEADERS += $$PWD/Theme.h $$PWD/EngineClient.h $$PWD/SseParser.h $$PWD/OverlayController.h
SOURCES += $$PWD/Theme.cpp $$PWD/EngineClient.cpp $$PWD/SseParser.cpp $$PWD/OverlayController.cpp
# no pkg-config file ships with layer-shell-qt, only a cmake config
LIBS += -lLayerShellQtInterface
