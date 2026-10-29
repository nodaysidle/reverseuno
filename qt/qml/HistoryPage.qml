import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Dictation entries, newest first, with search; the selected one opens on the right.
Item {
    id: page

    // Keyed by id, re-read after every refresh (each write and dictation resets the model).
    property string selectedId: ""
    property var current: ({})
    property bool editing: false

    function select(id) {
        selectedId = id
        editing = false
        current = hub.history.entry(id)
    }
    function when(ms) {
        const d = new Date(ms)
        const today = new Date().toDateString() === d.toDateString()
        return today ? Qt.formatTime(d, "HH:mm") : Qt.formatDateTime(d, "d MMM, HH:mm")
    }

    // Sized to its label (Basic buttons default to a 100 px minimum).
    component Act: Button {
        leftPadding: 14
        rightPadding: 14
        implicitWidth: implicitContentWidth + leftPadding + rightPadding
    }

    Connections {
        target: hub.history
        function onModelReset() {
            page.current = hub.history.entry(page.selectedId)
            if (!page.current.id) page.selectedId = ""
        }
    }

    RowLayout {
        anchors.fill: parent
        anchors.margins: 28
        anchors.bottomMargin: 0
        spacing: 28

        ColumnLayout {
            Layout.fillHeight: true
            Layout.fillWidth: true
            Layout.preferredWidth: 1
            spacing: 14

            Label {
                text: "History"
                font.pixelSize: 22
                font.weight: Font.DemiBold
                textFormat: Text.PlainText
            }
            TextField {
                Layout.fillWidth: true
                placeholderText: "Search dictations and apps"
                onTextChanged: hub.history.filter(text)
            }

            // Learned edits waiting for a yes or no
            Repeater {
                model: hub.suggestions
                delegate: RowLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 8
                    Label {
                        Layout.fillWidth: true
                        text: modelData.from + " → " + modelData.to
                        textFormat: Text.PlainText
                        elide: Text.ElideRight
                    }
                    Act {
                        text: "Add"
                        flat: true
                        onClicked: hub.run("suggestionAccept", [modelData.id], "Added to Dictionary")
                    }
                    Act {
                        text: "Dismiss"
                        flat: true
                        onClicked: hub.run("suggestionDismiss", [modelData.id])
                    }
                }
            }

            ListView {
                id: list
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                model: hub.history
                spacing: 2
                ScrollBar.vertical: ScrollBar {}

                delegate: ItemDelegate {
                    id: row
                    required property var model
                    readonly property string entryId: model.id
                    width: ListView.view.width
                    padding: 12
                    onClicked: page.select(entryId)
                    background: Rectangle {
                        radius: 8
                        color: {
                            if (row.entryId === page.selectedId) return win.hairline
                            if (row.hovered) return win.soft
                            return "transparent"
                        }
                    }
                    contentItem: ColumnLayout {
                        spacing: 4
                        Label {
                            objectName: "entryText"
                            Layout.fillWidth: true
                            text: row.model.text
                            textFormat: Text.PlainText
                            wrapMode: Text.Wrap
                            maximumLineCount: 2
                            elide: Text.ElideRight
                        }
                        RowLayout {
                            spacing: 10
                            Label {
                                objectName: "entryApp"
                                text: row.model.appClass || row.model.appTitle || "Unknown app"
                                textFormat: Text.PlainText
                                color: theme.muted
                                font.pixelSize: 12
                            }
                            Label {
                                text: page.when(row.model.createdAt)
                                textFormat: Text.PlainText
                                color: theme.muted
                                font.pixelSize: 12
                            }
                            Label {
                                visible: row.model.error !== ""
                                text: "Error"
                                color: theme.red
                                font.pixelSize: 12
                            }
                            Label {
                                visible: !row.model.inserted && row.model.error === ""
                                text: "Not inserted"
                                color: theme.muted
                                font.pixelSize: 12
                            }
                            Label {
                                visible: row.model.flagged
                                text: "Flagged"
                                color: theme.accent
                                font.pixelSize: 12
                            }
                        }
                    }
                }

                Label {
                    anchors.centerIn: parent
                    visible: list.count === 0
                    text: hub.history.count === 0 && hub.connected ? "Hold Right Super and speak. Your Dictation entries land here." : "Nothing here"
                    color: theme.muted
                    textFormat: Text.PlainText
                }
            }
        }

        // Detail pane
        ColumnLayout {
            Layout.fillHeight: true
            Layout.fillWidth: true
            Layout.preferredWidth: 1
            Layout.bottomMargin: 8
            spacing: 14
            visible: page.selectedId !== ""

            Label {
                text: page.current.createdAt ? page.when(page.current.createdAt) + "  ·  "
                      + (page.current.appClass || page.current.appTitle || "Unknown app")
                      + "  ·  " + (page.current.wordCount || 0) + " words" : ""
                textFormat: Text.PlainText
                color: theme.muted
                Layout.topMargin: 6
                Layout.fillWidth: true
                elide: Text.ElideRight
            }

            ScrollView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                visible: !page.editing
                TextEdit {
                    objectName: "detailText"
                    width: parent.width
                    text: page.current.text || ""
                    textFormat: TextEdit.PlainText
                    readOnly: true
                    selectByMouse: true
                    wrapMode: TextEdit.Wrap
                    color: theme.foreground
                    selectionColor: theme.accent
                    selectedTextColor: theme.background
                    font.pixelSize: 15
                }
            }
            ScrollView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                visible: page.editing
                TextArea {
                    id: editor
                    textFormat: TextEdit.PlainText
                    wrapMode: TextEdit.Wrap
                    font.pixelSize: 15
                }
            }

            Label {
                visible: !!page.current.error
                Layout.fillWidth: true
                text: "Paste failed: " + (page.current.error || "")
                textFormat: Text.PlainText
                color: theme.red
                wrapMode: Text.Wrap
            }

            Flow {
                Layout.fillWidth: true
                spacing: 6
                visible: !page.editing
                Act { text: "Copy"; onClicked: hub.run("historyCopy", [page.selectedId], "Copied") }
                Act { text: "Insert"; onClicked: hub.run("historyInsert", [page.selectedId], "Inserted at your cursor") }
                Act {
                    text: "Edit"
                    onClicked: {
                        editor.text = page.current.text || "" // copied once: a refresh must not wipe the edit
                        page.editing = true
                        editor.forceActiveFocus()
                    }
                }
                Act {
                    text: page.current.flagged ? "Unflag" : "Flag"
                    flat: true
                    onClicked: hub.run("historyFlag", [page.selectedId, !page.current.flagged])
                }
                Act {
                    text: "Delete"
                    flat: true
                    palette.windowText: theme.red
                    onClicked: {
                        hub.run("historyDelete", [page.selectedId], "Dictation entry deleted")
                        page.selectedId = ""
                    }
                }
            }
            RowLayout {
                spacing: 6
                visible: page.editing
                Act {
                    text: "Save"
                    highlighted: true
                    enabled: hub.connected && editor.text.trim() !== ""
                    onClicked: {
                        if (editor.text !== page.current.text)
                            hub.run("historyUpdate", [page.selectedId, editor.text], "Final text updated")
                        page.editing = false
                    }
                }
                Act { text: "Cancel"; flat: true; onClicked: page.editing = false }
            }
        }
        Item {
            visible: page.selectedId === ""
            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.preferredWidth: 1
            Label {
                anchors.centerIn: parent
                text: hub.history.count ? "Select a Dictation entry" : ""
                color: theme.muted
                textFormat: Text.PlainText
            }
        }
    }
}
