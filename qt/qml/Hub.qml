import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// The Hub: one quiet window. Created hidden; HubController shows it on a `page`
// event (and the tray later). Closing only hides it: the app keeps running.
ApplicationWindow {
    id: win
    visible: false
    width: 980
    height: 660
    minimumWidth: 760
    minimumHeight: 480
    title: "reverseuno"
    color: theme.background

    // Everything is drawn from the Omarchy theme; derived tones only blend its colours.
    readonly property color hairline: Qt.rgba(theme.foreground.r, theme.foreground.g, theme.foreground.b, 0.08)
    readonly property color soft: Qt.rgba(theme.foreground.r, theme.foreground.g, theme.foreground.b, 0.05)
    palette.window: theme.background
    palette.windowText: theme.foreground
    palette.base: soft
    palette.alternateBase: soft
    palette.text: theme.foreground
    palette.button: Qt.rgba(theme.foreground.r, theme.foreground.g, theme.foreground.b, 0.08)
    palette.buttonText: theme.foreground
    palette.brightText: theme.background
    palette.highlight: theme.accent
    palette.highlightedText: theme.background
    palette.placeholderText: theme.muted
    palette.light: theme.surface
    palette.midlight: Qt.rgba(theme.foreground.r, theme.foreground.g, theme.foreground.b, 0.18)
    palette.mid: hairline
    palette.dark: theme.accent
    palette.shadow: theme.background
    palette.toolTipBase: theme.surface
    palette.toolTipText: theme.foreground
    palette.link: theme.accent

    readonly property var pages: [
        { key: "history", label: "History" },
        { key: "dictionary", label: "Dictionary" },
        { key: "snippets", label: "Snippets" },
        { key: "settings", label: "Settings" }
    ]

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // Slim sidebar
        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 184
            color: win.soft

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 14
                anchors.topMargin: 22
                spacing: 2

                Label {
                    text: "reverseuno"
                    font.weight: Font.DemiBold
                    leftPadding: 10
                    bottomPadding: 18
                    textFormat: Text.PlainText
                }
                Repeater {
                    model: win.pages
                    delegate: ItemDelegate {
                        required property var modelData
                        readonly property bool current: hub.page === modelData.key
                        Layout.fillWidth: true
                        text: modelData.label
                        font.weight: current ? Font.DemiBold : Font.Normal
                        onClicked: hub.page = modelData.key
                        contentItem: Label {
                            text: parent.text
                            font: parent.font
                            color: parent.current ? theme.foreground : theme.muted
                            textFormat: Text.PlainText
                        }
                        background: Rectangle {
                            radius: 6
                            color: parent.current ? win.hairline : "transparent"
                        }
                    }
                }
                Item { Layout.fillHeight: true }
                Label {
                    text: hub.version ? "v" + hub.version : ""
                    color: theme.muted
                    font.pixelSize: 11
                    leftPadding: 10
                    textFormat: Text.PlainText
                }
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0

            // Quiet offline banner; EngineClient keeps retrying on its own.
            Rectangle {
                objectName: "offlineBanner"
                visible: !hub.connected
                Layout.fillWidth: true
                Layout.preferredHeight: 34
                color: win.soft
                Label {
                    anchors.verticalCenter: parent.verticalCenter
                    x: 28
                    text: "reverseuno is not running. Reconnecting…"
                    color: theme.muted
                    textFormat: Text.PlainText
                }
            }

            StackLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                currentIndex: ["history", "dictionary", "snippets", "settings"].indexOf(hub.page)
                HistoryPage {}
                DictionaryPage {}
                SnippetsPage {}
                SettingsPage {}
            }

            // The last notice from an action, then it fades.
            Label {
                id: notice
                Layout.fillWidth: true
                Layout.margins: 12
                Layout.leftMargin: 28
                property bool ok: true
                color: ok ? theme.muted : theme.red
                textFormat: Text.PlainText
                elide: Text.ElideRight
                opacity: text ? 1 : 0
                Timer { id: noticeTimer; interval: 4000; onTriggered: notice.text = "" }
                Connections {
                    target: hub
                    function onNotice(text, ok) {
                        notice.ok = ok
                        notice.text = text
                        noticeTimer.restart()
                    }
                }
            }
        }
    }
}
