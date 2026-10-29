import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Segmented choice for a setting with a few values; writes `path` on click.
RowLayout {
    id: choice
    property string path
    property var saved
    property var options: [] // [{ value, label }]
    // Without a `path`, a click emits `picked` instead of writing a setting.
    signal picked(var value)
    spacing: 2
    Repeater {
        model: choice.options
        delegate: Button {
            required property var modelData
            text: modelData.label
            readonly property bool current: choice.saved === modelData.value
            flat: true
            font.weight: current ? Font.DemiBold : Font.Normal
            palette.windowText: current ? theme.foreground : theme.muted
            background: Rectangle { radius: 6; color: parent.current ? win.hairline : "transparent" }
            onClicked: {
                if (current) return
                if (choice.path) hub.set(choice.path, modelData.value)
                else choice.picked(modelData.value)
            }
        }
    }
}
