import QtQuick
import QtQuick.Controls.Basic

// Free-text model id with suggestions (from listModels), so new models work without
// an app update. Commits on Enter, on pick, or when focus leaves.
ComboBox {
    id: field
    property string path
    property string saved
    implicitWidth: 300
    editable: true
    function sync() {
        currentIndex = find(saved)
        editText = saved
    }
    function commit(value) {
        const next = value.trim()
        if (!next) editText = saved
        else if (next !== saved) hub.set(path, next)
    }
    onSavedChanged: if (!activeFocus) sync()
    // ComboBox picks item 0 when a model arrives; put the saved id back.
    onCountChanged: sync()
    onModelChanged: sync()
    Component.onCompleted: sync()
    onAccepted: commit(editText)
    onActivated: function (index) { commit(textAt(index)) }
    onActiveFocusChanged: if (!activeFocus) commit(editText)
}
