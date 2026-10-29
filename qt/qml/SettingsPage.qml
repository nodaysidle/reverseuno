import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts

// Settings: only keys the engine has (Settings in src/shared/types.ts). Every control
// writes a small nested patch through hub.set(); secrets are write-only.
Item {
    id: page

    readonly property var stt: hub.settings.stt || ({})
    readonly property var fmt: hub.settings.format || ({})
    readonly property var audio: hub.settings.audio || ({})
    readonly property var ovl: hub.settings.overlay || ({})
    readonly property var hist: hub.settings.history || ({})
    readonly property var sys: hub.settings.system || ({})
    property bool confirmClear: false

    // Mirrors LANGUAGE_OPTIONS and DEEPGRAM_MODELS in src/shared/defaults.ts.
    readonly property var languages: [
        { value: "auto", label: "Auto-detect" }, { value: "multi", label: "Multilingual" },
        { value: "en", label: "English" }, { value: "sl", label: "Slovenian" }, { value: "it", label: "Italian" },
        { value: "de", label: "German" }, { value: "es", label: "Spanish" }, { value: "fr", label: "French" },
        { value: "pt", label: "Portuguese" }, { value: "nl", label: "Dutch" }, { value: "ja", label: "Japanese" },
        { value: "ko", label: "Korean" }, { value: "zh", label: "Chinese" }
    ]
    readonly property var deepgramModels: ["nova-3", "nova-2", "enhanced", "base"].map(function (m) {
        return { value: m, label: m }
    })
    readonly property var microphones: [{ value: "", label: "System default" }].concat(
        hub.devices.filter(function (d) { return !!d.id }).map(function (d) {
            return { value: d.id, label: d.label || "Unnamed input" }
        }))
    readonly property var keys: [
        { name: "deepgram", label: "Deepgram", url: "https://console.deepgram.com/" },
        { name: "openrouter", label: "OpenRouter", url: "https://openrouter.ai/keys" },
        { name: "anthropic", label: "Anthropic", url: "https://console.anthropic.com/settings/keys" }
    ]

    function loadLists() { if (visible && hub.connected) hub.loadLists() }
    onVisibleChanged: loadLists()
    Connections { target: hub; function onConnectedChanged() { page.loadLists() } }

    component Heading: Label {
        Layout.topMargin: 22
        font.weight: Font.DemiBold
        textFormat: Text.PlainText
    }

    ScrollView {
        id: scroll
        anchors.fill: parent
        contentWidth: availableWidth

        ColumnLayout {
            width: scroll.availableWidth - 56
            x: 28
            spacing: 14

            Label {
                Layout.topMargin: 28
                text: "Settings"
                font.pixelSize: 22
                font.weight: Font.DemiBold
                textFormat: Text.PlainText
            }

            Heading { text: "Speech" }
            SettingRow {
                label: "Speech-to-text"
                hint: page.stt.provider === "openrouter" ? "One request on release; no live preview."
                                                         : "Streams while you speak."
                Choice {
                    path: "stt.provider"
                    saved: page.stt.provider
                    options: [{ value: "deepgram", label: "Deepgram" }, { value: "openrouter", label: "OpenRouter" }]
                }
            }
            SettingRow {
                label: "Speech model"
                Pick {
                    visible: page.stt.provider !== "openrouter"
                    path: "stt.model"
                    saved: page.stt.model
                    model: page.deepgramModels
                }
                ModelField {
                    visible: page.stt.provider === "openrouter"
                    path: "stt.openrouterModel"
                    saved: page.stt.openrouterModel || ""
                    model: hub.sttModels
                }
            }
            SettingRow {
                label: "Language"
                Pick { path: "stt.language"; saved: page.stt.language; model: page.languages }
            }
            SettingRow {
                label: "Microphone"
                Pick { path: "audio.inputDevice"; saved: page.audio.inputDevice; model: page.microphones }
            }

            Heading { text: "Formatter" }
            SettingRow {
                label: "Polish with the Formatter"
                hint: "Off inserts the transcript as heard."
                Toggle { path: "format.enabled"; saved: !!page.fmt.enabled }
            }
            SettingRow {
                label: "Provider"
                Choice {
                    path: "format.provider"
                    saved: page.fmt.provider
                    options: [{ value: "openrouter", label: "OpenRouter" }, { value: "anthropic", label: "Anthropic" }]
                }
            }
            SettingRow {
                label: "Model"
                ModelField {
                    visible: page.fmt.provider !== "anthropic"
                    path: "format.model"
                    saved: page.fmt.model || ""
                    model: hub.formatModels
                }
                ModelField {
                    visible: page.fmt.provider === "anthropic"
                    path: "format.anthropicModel"
                    saved: page.fmt.anthropicModel || ""
                    model: []
                }
            }
            SettingRow {
                label: "Cleanup level"
                hint: "How heavily the Formatter edits."
                Choice {
                    path: "format.cleanup"
                    saved: page.fmt.cleanup
                    options: [{ value: "none", label: "None" }, { value: "light", label: "Light" },
                              { value: "medium", label: "Medium" }, { value: "high", label: "High" }]
                }
            }
            SettingRow {
                label: "Writing style"
                Pick {
                    path: "format.style"
                    saved: page.fmt.style
                    model: [{ value: "formal", label: "Formal" }, { value: "casual", label: "Casual" },
                            { value: "very-casual", label: "Very casual" }, { value: "excited", label: "Excited" },
                            { value: "genz", label: "Gen Z" }]
                }
            }

            Heading { text: "API keys" }
            Label {
                text: "Keys are stored by reverseuno and never shown again."
                color: theme.muted
                font.pixelSize: 12
                textFormat: Text.PlainText
            }
            Repeater {
                model: page.keys
                delegate: SettingRow {
                    id: keyRow
                    required property var modelData
                    readonly property bool present: !!hub.secrets[modelData.name]
                    label: modelData.label
                    hint: present ? "Set" : "Not set"
                    TextField {
                        id: secret
                        implicitWidth: 220
                        echoMode: TextInput.Password
                        placeholderText: keyRow.present ? "Replace key" : "Paste key"
                        onAccepted: save.clicked()
                    }
                    Button {
                        id: save
                        text: "Save"
                        enabled: secret.text.trim() !== ""
                        onClicked: {
                            if (!enabled) return
                            hub.run("setSecret", [keyRow.modelData.name, secret.text.trim()], keyRow.modelData.label + " key saved")
                            secret.text = ""
                        }
                    }
                    Button {
                        text: hub.testing === keyRow.modelData.name ? "Testing…" : "Test"
                        enabled: keyRow.present && hub.testing === ""
                        onClicked: hub.testProvider(keyRow.modelData.name)
                    }
                    Button {
                        text: "Clear"
                        flat: true
                        enabled: keyRow.present
                        onClicked: hub.run("clearSecret", [keyRow.modelData.name], keyRow.modelData.label + " key cleared")
                    }
                    Button {
                        text: "Get a key"
                        flat: true
                        palette.windowText: theme.accent
                        onClicked: hub.run("openExternal", [keyRow.modelData.url])
                    }
                }
            }

            Heading { text: "Overlay" }
            SettingRow {
                label: "Resting pill"
                hint: "A small pill stays on screen when no Session is running."
                Toggle { path: "overlay.showAtRest"; saved: !!page.ovl.showAtRest }
            }
            SettingRow {
                label: "Position"
                Choice {
                    path: "overlay.position"
                    saved: page.ovl.position
                    options: [{ value: "bottom", label: "Bottom" }, { value: "top", label: "Top" }]
                }
            }
            SettingRow {
                label: "Sound cues"
                hint: "A soft cue when recording starts and stops."
                Toggle { path: "overlay.sounds"; saved: !!page.ovl.sounds }
            }

            Heading { text: "History" }
            SettingRow {
                label: "Learn from edits"
                hint: "Editing a Dictation entry's Final text adds the changed word as a Correction. Off, it is offered as a suggestion."
                Toggle { path: "history.autoLearn"; saved: !!page.hist.autoLearn }
            }
            SettingRow {
                label: "Export"
                hint: "History, Dictionary, Snippets and settings as JSON."
                Button { text: "Export…"; onClicked: hub.run("exportData", [], "Exported to %1") }
            }
            SettingRow {
                label: "Clear history"
                hint: page.confirmClear ? "Delete every Dictation entry? Dictionary and Snippets are kept." : "Dictionary and Snippets are kept."
                Button {
                    visible: !page.confirmClear
                    text: "Clear history…"
                    flat: true
                    palette.windowText: theme.red
                    onClicked: page.confirmClear = true
                }
                Button { visible: page.confirmClear; text: "Cancel"; flat: true; onClicked: page.confirmClear = false }
                Button {
                    visible: page.confirmClear
                    text: "Delete all"
                    palette.button: theme.red
                    palette.buttonText: theme.background
                    onClicked: {
                        hub.run("historyClear", [], "History cleared")
                        page.confirmClear = false
                    }
                }
            }

            Heading { text: "System" }
            SettingRow {
                label: "Start at login"
                Toggle { saved: !!page.sys.launchAtLogin; onFlipped: function (on) { hub.run("setLogin", [on]) } }
            }
            Item { Layout.preferredHeight: 28 }
        }
    }
}
