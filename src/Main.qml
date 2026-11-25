import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import Qt.labs.platform as Platform

ApplicationWindow {
    id: win
    width: 980
    height: 660
    minimumWidth: 400
    minimumHeight: 460
    visible: !startHidden
    title: "reverseuno"
    color: "#0e0e10"

    Material.theme: Material.Dark
    Material.accent: theme.accent
    Material.roundedScale: theme.radius

    readonly property color card: "#17171a"
    readonly property color line: "#2a2a2f"
    readonly property color dim: "#a4a4ae"
    readonly property color danger: "#ff6b6b"

    property bool helpVisible: false
    property bool settingsVisible: false
    property bool setupVisible: false
    // Plain letters must not fire shortcuts while the user types a key or confirms a dialog.
    readonly property bool typing: keyField.activeFocus || setupKeyField.activeFocus
    readonly property bool plain: !typing && !quitDialog.visible
    readonly property bool narrow: width < 720
    readonly property bool listening: backend.state === "listening"

    readonly property var languages: [
        { code: "auto", text: qsTr("Auto-detect") },
        { code: "en", text: "English" }, { code: "de", text: "Deutsch" },
        { code: "es", text: "Español" }, { code: "fr", text: "Français" },
        { code: "it", text: "Italiano" }, { code: "nl", text: "Nederlands" },
        { code: "pt", text: "Português" }, { code: "sl", text: "Slovenščina" },
        { code: "ja", text: "日本語" }
    ]
    readonly property var modes: [
        { code: "paste", text: qsTr("Paste") },
        { code: "type", text: qsTr("Type directly") },
        { code: "copy", text: qsTr("Copy only") }
    ]

    function stateWord(state) {
        switch (state) {
        case "listening": return qsTr("Listening")
        case "transcribing": return qsTr("Transcribing")
        case "inserting": return qsTr("Inserting")
        case "done": return qsTr("Done")
        case "error": return qsTr("Error")
        default: return qsTr("Ready")
        }
    }

    function indexOfCode(list, code) {
        for (var i = 0; i < list.length; ++i)
            if (list[i].code === code)
                return i
        return 0
    }

    function requestQuit() {
        if (backend.unsaved)
            quitDialog.open()
        else
            backend.quit()
    }

    function openSettings(open) {
        settingsVisible = open
        if (open)
            setupVisible = false
        else
            keyField.focus = false
    }

    function openSetup(open) {
        setupVisible = open
        if (open)
            settingsVisible = false
        else
            setupKeyField.focus = false
    }

    function showWindow() {
        // Wayland offers no raise(); activating is what brings it forward.
        win.show()
        win.requestActivate()
    }

    // Left click on the tray icon: show the window, or hide it when it is up.
    function toggleWindow() {
        if (win.visible)
            win.hide()
        else
            showWindow()
    }

    // Quit from the tray: the confirmation lives in the window, so bring it up first.
    function quitFromTray() {
        if (backend.unsaved) {
            showWindow()
            quitDialog.open()
        } else {
            backend.quit()
        }
    }

    onClosing: function (close) {
        // Closing only hides the window; the push-to-talk keys keep working.
        close.accepted = false
        win.hide()
    }

    Connections {
        target: backend
        function onShowRequested() {
            win.showWindow()
            if (!backend.setupBasicsOk)
                win.openSetup(true)
        }
        function onSetupRequested() { win.openSetup(true) }
    }

    Component.onCompleted: {
        // Something basic is missing: start on the Setup view.
        if (!backend.setupBasicsOk)
            openSetup(true)
    }

    Shortcut { sequence: "?"; context: Qt.ApplicationShortcut; enabled: win.plain; onActivated: win.helpVisible = !win.helpVisible }
    Shortcut { sequence: "Q"; context: Qt.ApplicationShortcut; enabled: win.plain; onActivated: win.requestQuit() }
    Shortcut { sequence: "Space"; context: Qt.ApplicationShortcut; enabled: win.plain && !win.settingsVisible; onActivated: backend.toggle() }
    Shortcut { sequence: "S"; context: Qt.ApplicationShortcut; enabled: win.plain; onActivated: win.openSettings(!win.settingsVisible) }
    Shortcut { sequence: "Ctrl+,"; context: Qt.ApplicationShortcut; enabled: !quitDialog.visible; onActivated: win.openSettings(!win.settingsVisible) }
    Shortcut {
        sequences: ["Ctrl+C", "Return", "Enter"]
        context: Qt.ApplicationShortcut
        // With a selection in the transcript, Ctrl+C copies just that.
        enabled: win.plain && !win.settingsVisible && transcriptText.selectedText.length === 0
        onActivated: backend.copyTranscript()
    }
    Shortcut {
        sequence: "Escape"
        context: Qt.ApplicationShortcut
        enabled: !quitDialog.visible
        onActivated: {
            if (win.helpVisible)
                win.helpVisible = false
            else if (win.setupVisible)
                win.openSetup(false)
            else if (win.settingsVisible)
                win.openSettings(false)
            else
                win.requestQuit()
        }
    }

    // A visible, outlined action button (Material's default sits flat on the dark panel).
    component ActionButton: Button {
        id: action
        background: Rectangle {
            implicitHeight: 40
            radius: Math.min(6, theme.radius)
            color: action.down ? "#33333a" : "#26262b"
            border.color: action.enabled ? "#4a4a52" : win.line
        }
    }

    component SetupRow: Rectangle {
        id: row
        property string title
        property bool ok: false
        property string detail
        default property alias content: slot.data
        Layout.fillWidth: true
        implicitHeight: rowColumn.implicitHeight + 20
        radius: Math.min(8, theme.radius)
        color: win.card
        border.color: win.line

        ColumnLayout {
            id: rowColumn
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 10
            spacing: 6
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Rectangle {
                    Layout.preferredWidth: 12
                    Layout.preferredHeight: 12
                    radius: 6
                    color: row.ok ? "#4cd964" : win.danger
                }
                Label { text: row.title; font.bold: true; Layout.fillWidth: true; elide: Text.ElideRight }
                Label {
                    text: row.ok ? qsTr("Ready") : qsTr("Needs attention")
                    color: row.ok ? "#7ee08a" : "#ff9a9a"
                    font.pixelSize: 13
                }
            }
            Label {
                Layout.fillWidth: true
                visible: row.detail.length > 0
                text: row.detail
                wrapMode: Text.WordWrap
                color: win.dim
            }
            ColumnLayout { id: slot; Layout.fillWidth: true; spacing: 6 }
        }
    }

    // The icon in the bar's tray (StatusNotifierItem). The icon file is redrawn
    // in the theme accent by the Tray class.
    Platform.SystemTrayIcon {
        visible: true
        icon.source: tray.iconUrl
        tooltip: "reverseuno: " + win.stateWord(backend.state)
        onActivated: function (reason) {
            if (reason === Platform.SystemTrayIcon.Trigger)
                win.toggleWindow()
        }
        menu: Platform.Menu {
            Platform.MenuItem {
                text: win.listening ? qsTr("Stop dictation") : qsTr("Start dictation")
                onTriggered: backend.toggle()
            }
            Platform.MenuItem { text: qsTr("Open reverseuno"); onTriggered: backend.show() }
            Platform.MenuItem { text: qsTr("Setup"); onTriggered: backend.showSetup() }
            Platform.MenuSeparator {}
            Platform.MenuItem { text: qsTr("Quit"); onTriggered: win.quitFromTray() }
        }
    }

    // Right-to-left languages mirror the window; the Translator sets the
    // application's layout direction.
    Item {
        id: root
        anchors.fill: parent
        LayoutMirroring.enabled: Qt.application.layoutDirection === Qt.RightToLeft
        LayoutMirroring.childrenInherit: true

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 14

            RowLayout {
                Layout.fillWidth: true
                spacing: 12

                Image {
                    source: "qrc:/reverseuno.svg"
                    sourceSize.width: 36
                    sourceSize.height: 36
                    Layout.preferredWidth: 36
                    Layout.preferredHeight: 36
                }
                Label {
                    visible: !win.narrow
                    text: "reverseuno"
                    font.pixelSize: 22
                    font.bold: true
                }
                Item { Layout.fillWidth: true }
                Rectangle {
                    implicitWidth: stateLabel.implicitWidth + 28
                    implicitHeight: 32
                    radius: Math.min(16, theme.radius)
                    color: backend.state === "error" ? "#3a1d1d" : (win.listening ? theme.accent : win.card)
                    border.color: backend.state === "error" ? win.danger : (win.listening ? theme.accent : win.line)
                    Label {
                        id: stateLabel
                        anchors.centerIn: parent
                        text: win.stateWord(backend.state)
                        font.bold: true
                        color: win.listening ? theme.accentForeground : (backend.state === "error" ? win.danger : "white")
                    }
                }
                Button {
                    text: qsTr("Setup")
                    flat: true
                    onClicked: win.openSetup(!win.setupVisible)
                }
                Button {
                    text: qsTr("Settings")
                    flat: true
                    onClicked: win.openSettings(!win.settingsVisible)
                }
            }

            // Missing tools, key or microphone, and the last error.
            Rectangle {
                id: banner
                Layout.fillWidth: true
                visible: backend.problems.length > 0 || backend.error.length > 0
                implicitHeight: bannerColumn.implicitHeight + 24
                radius: Math.min(8, theme.radius)
                color: "#2a1717"
                border.color: win.danger
                Column {
                    id: bannerColumn
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: 12
                    spacing: 6
                    Label {
                        width: parent.width
                        visible: backend.error.length > 0 && backend.problems.indexOf(backend.error) < 0
                        text: backend.error
                        wrapMode: Text.WordWrap
                        font.bold: true
                        color: "#ffb4b4"
                    }
                    Repeater {
                        model: backend.problems
                        Label {
                            width: bannerColumn.width
                            text: modelData
                            wrapMode: Text.WordWrap
                            color: "#ffb4b4"
                        }
                    }
                }
            }

            GridLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                columns: win.narrow ? 1 : 2
                columnSpacing: 16
                rowSpacing: 14

                // Dictation: waveform, live text, last transcript.
                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Layout.preferredWidth: 600
                    spacing: 12

                    Rectangle {
                        id: waveform
                        Layout.fillWidth: true
                        implicitHeight: 110
                        radius: theme.radius
                        color: win.card
                        border.color: win.line

                        Row {
                            id: bars
                            anchors.centerIn: parent
                            spacing: 3
                            visible: win.listening
                            Repeater {
                                model: backend.levels
                                Rectangle {
                                    width: Math.max(2, (waveform.width - 28 - 47 * 3) / 48)
                                    height: Math.max(4, modelData * 84)
                                    radius: Math.min(3, theme.radius)
                                    color: theme.accent
                                    anchors.verticalCenter: parent.verticalCenter
                                }
                            }
                        }
                        Label {
                            anchors.centerIn: parent
                            width: parent.width - 28
                            horizontalAlignment: Text.AlignHCenter
                            visible: !win.listening
                            wrapMode: Text.WordWrap
                            color: win.dim
                            text: backend.state === "transcribing" ? qsTr("Transcribing…")
                                : backend.notice.length > 0 ? backend.notice
                                : qsTr("Press Space, or hold your push-to-talk key, and speak")
                        }
                    }

                    Label {
                        Layout.fillWidth: true
                        visible: backend.interim.length > 0
                        text: backend.interim
                        wrapMode: Text.WordWrap
                        maximumLineCount: 3
                        elide: Text.ElideRight
                        font.italic: true
                        color: win.dim
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        Layout.minimumHeight: 120
                        radius: theme.radius
                        color: win.card
                        border.color: win.line

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.margins: 14
                            spacing: 8
                            Label {
                                text: qsTr("Last transcript")
                                color: win.dim
                                font.pixelSize: 13
                            }
                            ScrollView {
                                id: transcriptScroll
                                Layout.fillWidth: true
                                Layout.fillHeight: true
                                clip: true
                                TextEdit {
                                    id: transcriptText
                                    width: transcriptScroll.availableWidth
                                    readOnly: true
                                    selectByMouse: true
                                    textFormat: TextEdit.PlainText
                                    wrapMode: TextEdit.Wrap
                                    color: "white"
                                    selectionColor: theme.accent
                                    selectedTextColor: theme.accentForeground
                                    font.pixelSize: 17
                                    text: backend.transcript.length > 0 ? backend.transcript : qsTr("Nothing yet")
                                    opacity: backend.transcript.length > 0 ? 1 : 0.5
                                }
                            }
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        Button {
                            id: primary
                            text: win.listening ? qsTr("Stop") : qsTr("Start dictation")
                            enabled: backend.state !== "transcribing" && backend.state !== "inserting"
                            onClicked: backend.toggle()
                            background: Rectangle {
                                implicitHeight: 40
                                radius: Math.min(8, theme.radius)
                                color: primary.enabled ? theme.accent : "#2a2a2f"
                                opacity: primary.down ? 0.8 : 1
                            }
                            contentItem: Label {
                                text: primary.text
                                font.bold: true
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                                color: primary.enabled ? theme.accentForeground : win.dim
                            }
                        }
                        Button {
                            text: qsTr("Cancel")
                            flat: true
                            visible: backend.busy
                            onClicked: backend.cancel()
                        }
                        Item { Layout.fillWidth: true }
                        Button {
                            text: qsTr("Copy")
                            flat: true
                            enabled: backend.transcript.length > 0
                            onClicked: backend.copyTranscript()
                        }
                    }
                }

                // This run's transcripts, never saved.
                Rectangle {
                    Layout.fillWidth: win.narrow
                    Layout.fillHeight: true
                    Layout.preferredWidth: win.narrow ? -1 : 300
                    Layout.minimumHeight: win.narrow ? 150 : 0
                    radius: theme.radius
                    color: win.card
                    border.color: win.line

                    ColumnLayout {
                        anchors.fill: parent
                        anchors.margins: 14
                        spacing: 8
                        Label {
                            text: backend.history.length > 0
                                ? qsTr("%n transcripts this run", "", backend.history.length)
                                : qsTr("This run")
                            color: win.dim
                            font.pixelSize: 13
                        }
                        Label {
                            Layout.fillWidth: true
                            visible: backend.history.length === 0
                            wrapMode: Text.WordWrap
                            color: win.dim
                            text: qsTr("Transcripts from this run appear here. Nothing is saved to disk.")
                        }
                        ListView {
                            Layout.fillWidth: true
                            Layout.fillHeight: true
                            clip: true
                            spacing: 6
                            model: backend.history
                            delegate: Rectangle {
                                width: ListView.view.width
                                height: entry.implicitHeight + 16
                                radius: Math.min(6, theme.radius)
                                color: hover.hovered ? "#202025" : "transparent"
                                border.color: win.line
                                HoverHandler { id: hover }
                                TapHandler { onTapped: backend.copyText(modelData) }
                                Label {
                                    id: entry
                                    anchors.fill: parent
                                    anchors.margins: 8
                                    text: modelData
                                    wrapMode: Text.WordWrap
                                    maximumLineCount: 3
                                    elide: Text.ElideRight
                                }
                            }
                        }
                    }
                }
            }

            Label {
                Layout.fillWidth: true
                text: qsTr("Press ? for keyboard shortcuts")
                color: win.dim
                font.pixelSize: 12
            }
        }

        // Settings slide in over the right edge.
        Rectangle {
            id: settingsPanel
            visible: win.settingsVisible
            z: 5
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            anchors.right: parent.right
            width: Math.min(parent.width, 380)
            color: "#131316"
            border.color: win.line

            Flickable {
                anchors.fill: parent
                anchors.margins: 20
                contentHeight: settingsColumn.implicitHeight
                clip: true

                ColumnLayout {
                    id: settingsColumn
                    width: parent.width
                    spacing: 16

                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: qsTr("Settings"); font.pixelSize: 20; font.bold: true }
                        Item { Layout.fillWidth: true }
                        Button { text: qsTr("Close"); flat: true; onClicked: win.openSettings(false) }
                    }

                    TextField {
                        id: keyField
                        Layout.fillWidth: true
                        placeholderText: qsTr("Deepgram API key")
                        echoMode: TextInput.Password
                        selectByMouse: true
                        onAccepted: saveKey.clicked()
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        Button {
                            id: saveKey
                            text: qsTr("Save key")
                            enabled: keyField.text.length > 0
                            onClicked: {
                                backend.setApiKey(keyField.text)
                                keyField.clear()
                                keyField.focus = false
                            }
                        }
                        Button {
                            text: qsTr("Remove key")
                            flat: true
                            enabled: backend.hasKey && !backend.keyFromEnvironment
                            onClicked: backend.setApiKey("")
                        }
                    }
                    Label {
                        Layout.fillWidth: true
                        wrapMode: Text.WordWrap
                        color: win.dim
                        text: backend.keyFromEnvironment ? qsTr("Using DEEPGRAM_API_KEY from the environment")
                            : backend.hasKey ? qsTr("A key is saved. It is never shown again.")
                            : qsTr("No key saved")
                    }

                    ComboBox {
                        Layout.fillWidth: true
                        model: win.languages
                        textRole: "text"
                        currentIndex: win.indexOfCode(win.languages, backend.language)
                        onActivated: function (index) { backend.language = win.languages[index].code }
                    }
                    ComboBox {
                        Layout.fillWidth: true
                        model: win.modes
                        textRole: "text"
                        currentIndex: win.indexOfCode(win.modes, backend.insertMode)
                        onActivated: function (index) { backend.insertMode = win.modes[index].code }
                    }
                    ComboBox {
                        Layout.fillWidth: true
                        model: [qsTr("Default microphone")].concat(backend.microphones)
                        currentIndex: Math.max(0, backend.microphones.indexOf(backend.microphone) + 1)
                        onActivated: function (index) { backend.microphone = index === 0 ? "" : backend.microphones[index - 1] }
                    }
                    Label {
                        Layout.fillWidth: true
                        wrapMode: Text.WordWrap
                        color: win.dim
                        text: qsTr("Audio stays in memory and is sent to Deepgram only while you dictate.")
                    }
                }
            }
        }

        // First-run Setup: one row per thing reverseuno needs, each with its fix.
        Rectangle {
            id: setupPanel
            visible: win.setupVisible
            z: 6
            anchors.top: parent.top
            anchors.bottom: parent.bottom
            anchors.right: parent.right
            width: Math.min(parent.width, 600)
            color: "#131316"
            border.color: win.line

            Flickable {
                objectName: "setupFlick"
                anchors.fill: parent
                anchors.margins: 16
                contentHeight: setupColumn.implicitHeight
                clip: true
                boundsBehavior: Flickable.StopAtBounds

                ColumnLayout {
                    id: setupColumn
                    width: parent.width
                    spacing: 8

                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: qsTr("Setup"); font.pixelSize: 20; font.bold: true }
                        Item { Layout.fillWidth: true }
                        Button { text: qsTr("Close"); flat: true; onClicked: win.openSetup(false) }
                    }

                    SetupRow {
                        title: qsTr("Deepgram API key")
                        ok: backend.hasKey
                        detail: backend.keyFromEnvironment ? qsTr("DEEPGRAM_API_KEY is set in the environment")
                            : backend.hasKey ? qsTr("A key is saved. It is never shown again.")
                            : qsTr("Paste your key. It is stored in a private file and never shown again.")
                        RowLayout {
                            Layout.fillWidth: true
                            TextField {
                                id: setupKeyField
                                Layout.fillWidth: true
                                placeholderText: qsTr("Deepgram API key")
                                echoMode: TextInput.Password
                                selectByMouse: true
                                onAccepted: setupSaveKey.clicked()
                            }
                            ActionButton {
                                id: setupSaveKey
                                text: qsTr("Save key")
                                enabled: setupKeyField.text.length > 0
                                onClicked: {
                                    backend.setApiKey(setupKeyField.text)
                                    setupKeyField.clear()
                                    setupKeyField.focus = false
                                }
                            }
                        }
                    }

                    SetupRow {
                        title: qsTr("Microphone")
                        ok: backend.microphoneFound
                        detail: backend.microphoneFound ? qsTr("Pick which microphone to use.")
                            : qsTr("No microphone found. Plug one in and check the PipeWire settings.")
                        ComboBox {
                            Layout.fillWidth: true
                            visible: backend.microphones.length > 0
                            model: [qsTr("Default microphone")].concat(backend.microphones)
                            currentIndex: Math.max(0, backend.microphones.indexOf(backend.microphone) + 1)
                            onActivated: function (index) { backend.microphone = index === 0 ? "" : backend.microphones[index - 1] }
                        }
                    }

                    SetupRow {
                        title: qsTr("Tools")
                        ok: backend.missingTools.length === 0
                        detail: backend.missingTools.length === 0 ? qsTr("wl-copy, wtype, hyprctl and ffmpeg are installed")
                            : qsTr("Missing: %1").arg(backend.missingTools.join(", "))
                        RowLayout {
                            Layout.fillWidth: true
                            visible: backend.missingTools.length > 0
                            Rectangle {
                                Layout.fillWidth: true
                                implicitHeight: command.implicitHeight + 16
                                radius: Math.min(6, theme.radius)
                                color: "#0e0e10"
                                border.color: win.line
                                TextEdit {
                                    id: command
                                    anchors.fill: parent
                                    anchors.margins: 8
                                    readOnly: true
                                    selectByMouse: true
                                    textFormat: TextEdit.PlainText
                                    wrapMode: TextEdit.Wrap
                                    color: "white"
                                    selectionColor: theme.accent
                                    selectedTextColor: theme.accentForeground
                                    font.family: "monospace"
                                    text: backend.toolsCommand
                                }
                            }
                            ActionButton { text: qsTr("Copy command"); onClicked: backend.copyText(backend.toolsCommand) }
                        }
                    }

                    SetupRow {
                        title: qsTr("Hotkeys")
                        ok: backend.hotkeysInstalled
                        detail: qsTr("Hold F8 to dictate and release to paste. SUPER + U toggles dictation. F12 cancels.")
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ActionButton {
                                text: backend.hotkeysInstalled ? qsTr("Reinstall hotkeys") : qsTr("Install hotkeys")
                                onClicked: backend.installHotkeys()
                            }
                            Label {
                                Layout.fillWidth: true
                                visible: backend.hotkeyMessage.length > 0
                                text: backend.hotkeyMessage
                                wrapMode: Text.WrapAnywhere
                                color: backend.hotkeyFailed ? "#ffb4b4" : win.dim
                                font.pixelSize: 13
                            }
                        }
                    }

                    SetupRow {
                        title: qsTr("Start at login")
                        ok: backend.autostartInstalled
                        detail: qsTr("Starts reverseuno hidden when Hyprland starts, so the keys work right after login.")
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 12
                            ActionButton {
                                text: backend.autostartInstalled ? qsTr("Reinstall autostart") : qsTr("Install autostart")
                                onClicked: backend.installAutostart()
                            }
                            Label {
                                Layout.fillWidth: true
                                visible: backend.autostartMessage.length > 0
                                text: backend.autostartMessage
                                wrapMode: Text.WrapAnywhere
                                color: backend.autostartFailed ? "#ffb4b4" : win.dim
                                font.pixelSize: 13
                            }
                        }
                    }

                    Label {
                        Layout.fillWidth: true
                        wrapMode: Text.WordWrap
                        color: win.dim
                        font.pixelSize: 13
                        text: qsTr("Hyprland changes go into a marked block, keep a .bak copy next to the file, and are checked with hyprctl; a change Hyprland rejects is undone.")
                    }
                }
            }
        }

        // Every shortcut at a glance.
        Rectangle {
            anchors.fill: parent
            z: 10
            visible: win.helpVisible
            color: "#cc000000"
            MouseArea { anchors.fill: parent; onClicked: win.helpVisible = false }
            Rectangle {
                anchors.centerIn: parent
                width: Math.min(parent.width - 40, 440)
                height: helpGrid.implicitHeight + 48
                radius: theme.radius
                color: win.card
                border.color: win.line
                MouseArea { anchors.fill: parent }
                GridLayout {
                    id: helpGrid
                    anchors.fill: parent
                    anchors.margins: 24
                    columns: 2
                    columnSpacing: 20
                    rowSpacing: 10
                    Label { text: qsTr("Keyboard shortcuts"); font.bold: true; font.pixelSize: 18; Layout.columnSpan: 2 }
                    Label { text: "Space"; color: theme.accent; font.bold: true }
                    Label { text: qsTr("Start or stop dictating"); Layout.fillWidth: true; wrapMode: Text.WordWrap }
                    Label { text: "Ctrl+C / Enter"; color: theme.accent; font.bold: true }
                    Label { text: qsTr("Copy the last transcript"); Layout.fillWidth: true; wrapMode: Text.WordWrap }
                    Label { text: "S / Ctrl+,"; color: theme.accent; font.bold: true }
                    Label { text: qsTr("Open or close settings"); Layout.fillWidth: true; wrapMode: Text.WordWrap }
                    Label { text: "?"; color: theme.accent; font.bold: true }
                    Label { text: qsTr("Show or hide this help"); Layout.fillWidth: true; wrapMode: Text.WordWrap }
                    Label { text: "Q / Esc"; color: theme.accent; font.bold: true }
                    Label { text: qsTr("Quit reverseuno"); Layout.fillWidth: true; wrapMode: Text.WordWrap }
                }
            }
        }
    }

    Dialog {
        id: quitDialog
        modal: true
        anchors.centerIn: Overlay.overlay
        width: Math.min(win.width - 40, 420)
        title: qsTr("Quit reverseuno?")
        standardButtons: Dialog.Yes | Dialog.Cancel
        onAccepted: backend.quit()
        contentItem: Label {
            wrapMode: Text.WordWrap
            text: backend.busy ? qsTr("A dictation is in progress. Quitting discards it.")
                : qsTr("The last transcript was neither inserted nor copied.")
        }
    }
}
