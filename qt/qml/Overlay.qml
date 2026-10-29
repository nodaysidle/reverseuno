import QtQuick
import QtQuick.Window

// Shown, sized and placed by OverlayController (C++); it starts hidden so the
// layer-shell role is set before the surface is first mapped.
Window {
    id: win
    visible: false
    color: "transparent"
    title: "reverseuno-overlay"

    readonly property bool listening: overlay.state === "listening"
    property int phase: 0

    Binding {
        target: overlay
        property: "contentSize"
        value: Qt.size(Math.ceil(pill.width), Math.ceil(pill.height))
    }

    Timer {
        interval: 70
        repeat: true
        running: win.listening && overlay.visible
        onTriggered: win.phase++
    }

    Rectangle {
        id: pill
        anchors.centerIn: parent
        height: 32
        width: row.implicitWidth + 28
        radius: height / 2
        color: theme.background
        border.width: 1
        border.color: Qt.rgba(theme.foreground.r, theme.foreground.g, theme.foreground.b, 0.1)

        Row {
            id: row
            anchors.centerIn: parent
            spacing: 9

            // Resting mark: five still bars, as the Electron resting pill.
            Row {
                visible: overlay.resting
                anchors.verticalCenter: parent.verticalCenter
                spacing: 2
                Repeater {
                    model: [5, 9, 12, 8, 4]
                    Rectangle {
                        anchors.verticalCenter: parent.verticalCenter
                        width: 2
                        height: modelData
                        radius: 1
                        color: theme.foreground
                        opacity: 0.7
                    }
                }
            }

            // Waveform: level-driven while listening, a flat resting shape otherwise.
            Row {
                visible: !overlay.resting
                anchors.verticalCenter: parent.verticalCenter
                height: 20
                spacing: 2
                Repeater {
                    model: 12
                    Rectangle {
                        readonly property real envelope: Math.sin((index + 1) / 13 * Math.PI)
                        readonly property real ripple: 0.5 + 0.5 * Math.sin(win.phase / 3 + index * 0.62)
                        anchors.verticalCenter: parent.verticalCenter
                        width: 2
                        radius: 1
                        height: Math.max(3, win.listening
                                         ? 4 + overlay.level * 16 * envelope * (0.45 + 0.55 * ripple)
                                         : 3 + envelope * 3)
                        color: overlay.state === "error" ? theme.red : theme.foreground
                        opacity: win.listening ? 0.55 + envelope * 0.45 : 0.4
                        Behavior on height { NumberAnimation { duration: 90 } }
                    }
                }
            }

            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: overlay.word
                textFormat: Text.PlainText
                font.pixelSize: overlay.resting ? 11 : 13
                font.weight: Font.DemiBold
                color: overlay.state === "error" ? theme.red : theme.foreground
                opacity: overlay.resting ? 0.82 : 1
                elide: Text.ElideRight
                width: Math.min(implicitWidth, 320)
            }
        }

        // Cancels the running session; does not move keyboard focus (no keyboard
        // interactivity on the layer surface). The resting pill has no click action.
        MouseArea {
            anchors.fill: parent
            enabled: overlay.active
            cursorShape: enabled ? Qt.PointingHandCursor : Qt.ArrowCursor
            onClicked: overlay.cancel()
        }
    }
}
