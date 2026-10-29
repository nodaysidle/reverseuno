import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// One Settings line: label and hint on the left, the control on the right.
RowLayout {
    id: row
    property string label
    property string hint
    default property alias control: slot.data
    Layout.fillWidth: true
    spacing: 24

    ColumnLayout {
        Layout.fillWidth: true
        spacing: 2
        Label { text: row.label; textFormat: Text.PlainText; Layout.fillWidth: true }
        Label {
            visible: row.hint !== ""
            text: row.hint
            textFormat: Text.PlainText
            color: theme.muted
            font.pixelSize: 12
            wrapMode: Text.Wrap
            Layout.fillWidth: true
        }
    }
    RowLayout {
        id: slot
        spacing: 6
        Layout.alignment: Qt.AlignRight | Qt.AlignVCenter
    }
}
