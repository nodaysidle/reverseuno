import QtQuick
import QtQuick.Controls.Basic

// Whole-number setting. HubController parses and clamps it, so it reaches the engine as a
// JSON number. With `path` it writes that setting; otherwise it emits `committed(text)`
// and the handler returns whether the value was taken. A rejected value reverts.
TextField {
    id: field
    property string path
    property var saved
    property int min: 0
    property int max: 100000
    property string suffix: "ms"
    signal committed(string text)
    implicitWidth: 110
    inputMethodHints: Qt.ImhDigitsOnly
    rightPadding: unit.implicitWidth + 14
    text: saved === undefined ? "" : String(saved)
    function revert() { text = Qt.binding(function () { return saved === undefined ? "" : String(saved) }) }
    onEditingFinished: {
        if (text === String(saved)) return
        if (path) hub.setNumber(path, text, min, max)
        else committed(text)
        revert()
    }
    Label {
        id: unit
        anchors.right: parent.right
        anchors.rightMargin: 10
        anchors.verticalCenter: parent.verticalCenter
        text: field.suffix
        color: theme.muted
        textFormat: Text.PlainText
    }
}
