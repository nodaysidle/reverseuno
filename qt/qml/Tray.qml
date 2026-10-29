import QtQuick
import Qt.labs.platform as P

// Click opens the Hub; the menu mirrors the old Electron tray.
P.SystemTrayIcon {
    visible: true
    icon.source: "qrc:/tray.png"
    tooltip: "reverseuno — Right Super to dictate"
    onActivated: (reason) => {
        if (reason === P.SystemTrayIcon.Trigger) tray.open()
    }
    menu: P.Menu {
        P.MenuItem { text: tray.stateLabel; enabled: false }
        P.MenuItem { text: tray.toggleLabel; enabled: tray.toggleEnabled; onTriggered: tray.toggle() }
        P.MenuSeparator {}
        P.MenuItem { text: "Open reverseuno"; onTriggered: tray.open() }
        P.MenuItem { text: "Quit"; onTriggered: tray.quit() }
    }
}
