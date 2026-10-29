import QtQuick
import QtQuick.Controls.Basic

// Popup row for Pick and ModelField. Device names and model ids come from outside
// the app, so they are shown as plain text (the stock delegate would render markup).
ItemDelegate {
    id: item
    property Item combo
    property string label
    property int row
    width: ListView.view ? ListView.view.width : implicitWidth
    highlighted: combo.highlightedIndex === row
    hoverEnabled: combo.hoverEnabled
    contentItem: Label {
        objectName: "choiceLabel"
        text: item.label
        textFormat: Text.PlainText
        elide: Text.ElideRight
        font.weight: item.combo.currentIndex === item.row ? Font.DemiBold : Font.Normal
        color: item.highlighted ? item.palette.highlightedText : item.palette.text
    }
}
