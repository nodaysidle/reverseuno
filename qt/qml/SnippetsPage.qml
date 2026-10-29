import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Snippets: say the trigger, the content is written instead.
Item {
    id: page

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 28
        anchors.bottomMargin: 0
        spacing: 14

        Label {
            text: "Snippets"
            font.pixelSize: 22
            font.weight: Font.DemiBold
            textFormat: Text.PlainText
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            TextField {
                id: trigger
                Layout.preferredWidth: 220
                placeholderText: "Trigger, e.g. my calendar link"
            }
            TextField {
                id: content
                Layout.fillWidth: true
                placeholderText: "Content to insert"
                onAccepted: add.clicked()
            }
            Button {
                id: add
                text: "Add"
                highlighted: true
                enabled: trigger.text.trim() !== "" && content.text.trim() !== ""
                onClicked: {
                    if (!enabled) return
                    hub.run("snippetAdd", [trigger.text.trim(), content.text])
                    trigger.text = ""
                    content.text = ""
                }
            }
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: hub.snippets
            spacing: 2
            ScrollBar.vertical: ScrollBar {}

            delegate: Item {
                id: row
                required property var modelData
                property bool editing: false
                width: ListView.view.width
                height: editing ? 128 : 56

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 4
                    spacing: 16
                    visible: !row.editing
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Label {
                            objectName: "snippetTrigger"
                            text: row.modelData.trigger
                            textFormat: Text.PlainText
                            font.weight: Font.DemiBold
                            elide: Text.ElideRight
                            Layout.fillWidth: true
                        }
                        Label {
                            text: row.modelData.content
                            textFormat: Text.PlainText
                            color: theme.muted
                            elide: Text.ElideRight
                            maximumLineCount: 1
                            Layout.fillWidth: true
                        }
                    }
                    Button {
                        text: "Edit"
                        flat: true
                        onClicked: {
                            editTrigger.text = row.modelData.trigger
                            editContent.text = row.modelData.content
                            row.editing = true
                        }
                    }
                    Button {
                        text: "Delete"
                        flat: true
                        palette.windowText: theme.red
                        onClicked: hub.run("snippetDelete", [row.modelData.id])
                    }
                }

                ColumnLayout {
                    anchors.fill: parent
                    spacing: 6
                    visible: row.editing
                    TextField { id: editTrigger; Layout.fillWidth: true }
                    TextArea {
                        id: editContent
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        textFormat: TextEdit.PlainText
                        wrapMode: TextEdit.Wrap
                    }
                    RowLayout {
                        Button {
                            text: "Save"
                            highlighted: true
                            enabled: editTrigger.text.trim() !== "" && editContent.text.trim() !== ""
                            onClicked: {
                                hub.run("snippetUpdate", [row.modelData.id,
                                         { trigger: editTrigger.text.trim(), content: editContent.text }])
                                row.editing = false
                            }
                        }
                        Button { text: "Cancel"; flat: true; onClicked: row.editing = false }
                    }
                }
            }

            Label {
                anchors.centerIn: parent
                visible: list.count === 0
                text: "Say a trigger while dictating and its content is written instead."
                color: theme.muted
                textFormat: Text.PlainText
            }
        }
    }
}
