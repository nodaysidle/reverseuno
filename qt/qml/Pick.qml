import QtQuick
import QtQuick.Controls.Basic

// Drop-down for a setting from a fixed list of { value, label }.
ComboBox {
    id: pick
    property string path
    property var saved
    implicitWidth: 240
    textRole: "label"
    valueRole: "value"
    function sync() { currentIndex = indexOfValue(saved) }
    onSavedChanged: sync()
    onCountChanged: sync()
    Component.onCompleted: sync()
    onActivated: if (currentValue !== saved) hub.set(path, currentValue)
}
