QT += core gui network qml quick
CONFIG += c++17
INCLUDEPATH += $$PWD
HEADERS += $$PWD/Theme.h $$PWD/EngineClient.h $$PWD/SseParser.h $$PWD/OverlayController.h \
           $$PWD/HistoryModel.h $$PWD/HubController.h $$PWD/Windows.h $$PWD/Tray.h
SOURCES += $$PWD/Theme.cpp $$PWD/EngineClient.cpp $$PWD/SseParser.cpp $$PWD/OverlayController.cpp \
           $$PWD/HistoryModel.cpp $$PWD/HubController.cpp $$PWD/Windows.cpp $$PWD/Tray.cpp
# no pkg-config file ships with layer-shell-qt, only a cmake config
LIBS += -lLayerShellQtInterface
