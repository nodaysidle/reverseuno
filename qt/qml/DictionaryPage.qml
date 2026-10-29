import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Dictionary entries: a Spelling (word only) or a Correction (what you say → what is written).
Item {
    id: page

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 28
        anchors.bottomMargin: 0
        spacing: 14

        RowLayout {
            Layout.fillWidth: true
            Label {
                Layout.fillWidth: true
                text: "Dictionary"
                font.pixelSize: 22
                font.weight: Font.DemiBold
                textFormat: Text.PlainText
            }
            Button {
                text: "Add developer word pack"
                flat: true
                onClicked: hub.run("dictionaryAddPack", [], "Developer words added: %1")
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            TextField {
                id: word
                Layout.fillWidth: true
                placeholderText: "Word, or what you say"
                onAccepted: add.clicked()
            }
            TextField {
                id: correction
                Layout.fillWidth: true
                placeholderText: "Written as (optional, makes a Correction)"
                onAccepted: add.clicked()
            }
            Button {
                id: add
                text: "Add"
                highlighted: true
                enabled: hub.connected && word.text.trim() !== ""
                onClicked: {
                    if (!enabled) return
                    const args = correction.text.trim() ? [word.text.trim(), correction.text.trim()] : [word.text.trim()]
                    hub.run("dictionaryAdd", args)
                    word.text = ""
                    correction.text = ""
                }
            }
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            model: hub.dictionary
            spacing: 2
            ScrollBar.vertical: ScrollBar {}

            delegate: Item {
                id: row
                required property var modelData
                property bool editing: false
                readonly property bool isCorrection: !!modelData.correction
                width: ListView.view.width
                height: 44

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 4
                    spacing: 10
                    visible: !row.editing

                    Button {
                        flat: true
                        implicitWidth: 36
                        text: row.modelData.starred ? "★" : "☆"
                        palette.windowText: row.modelData.starred ? theme.accent : theme.muted
                        onClicked: hub.run("dictionaryUpdate", [row.modelData.id, { starred: !row.modelData.starred }])
                    }
                    Label {
                        objectName: "dictionaryWord"
                        text: row.modelData.word
                        textFormat: Text.PlainText
                        elide: Text.ElideRight
                        Layout.maximumWidth: row.width * 0.32
                    }
                    Label {
                        visible: row.isCorrection
                        text: "→  " + (row.modelData.correction || "")
                        textFormat: Text.PlainText
                        elide: Text.ElideRight
                        Layout.maximumWidth: row.width * 0.32
                    }
                    Label {
                        text: row.isCorrection ? "Correction" : "Spelling"
                        color: theme.muted
                        font.pixelSize: 12
                    }
                    Item { Layout.fillWidth: true }
                    Button {
                        text: "Edit"
                        flat: true
                        onClicked: {
                            editWord.text = row.modelData.word
                            editCorrection.text = row.modelData.correction || ""
                            row.editing = true
                        }
                    }
                    Button {
                        text: "Delete"
                        flat: true
                        palette.windowText: theme.red
                        onClicked: hub.run("dictionaryDelete", [row.modelData.id])
                    }
                }

                RowLayout {
                    anchors.fill: parent
                    spacing: 8
                    visible: row.editing
                    TextField { id: editWord; Layout.fillWidth: true }
                    TextField { id: editCorrection; Layout.fillWidth: true; placeholderText: "No correction (Spelling)" }
                    Button {
                        text: "Save"
                        highlighted: true
                        enabled: hub.connected && editWord.text.trim() !== ""
                        // An empty correction clears it, turning the entry into a Spelling.
                        onClicked: {
                            hub.run("dictionaryUpdate", [row.modelData.id,
                                     { word: editWord.text.trim(), correction: editCorrection.text.trim() }])
                            row.editing = false
                        }
                    }
                    Button { text: "Cancel"; flat: true; onClicked: row.editing = false }
                }
            }

            Label {
                anchors.centerIn: parent
                visible: list.count === 0
                text: "Add names, jargon and words speech-to-text gets wrong."
                color: theme.muted
                textFormat: Text.PlainText
            }
        }
    }
}
