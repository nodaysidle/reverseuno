import QtQuick
import QtQuick.Controls.Basic

// Switch bound to a setting. With `path` it writes updateSettings; otherwise it emits
// `flipped`. The binding is restored after each click so the engine's value wins.
Switch {
    id: toggle
    property string path
    property bool saved
    signal flipped(bool on)
    checked: saved
    onToggled: {
        if (path) hub.set(path, checked)
        else flipped(checked)
        checked = Qt.binding(function () { return toggle.saved })
    }
}
