// A tiny, readable paste target for the E2E harness.
// Writes the textarea value to REVERSEUNO_E2E_OUTPUT every 120 ms.
const { app, BrowserWindow } = require('electron')
const fs = require('node:fs')

const output = process.env.REVERSEUNO_E2E_OUTPUT || '/tmp/reverseuno-e2e-target.txt'
// The harness also launches a "decoy" copy that steals focus mid-dictation.
const title = process.env.REVERSEUNO_E2E_TITLE || 'reverseuno-e2e-target'
const keepFocus = process.env.REVERSEUNO_E2E_KEEP_FOCUS !== '0'

app.setName(title)
app.disableHardwareAcceleration()
app.commandLine.appendSwitch('disable-vulkan')

app.whenReady().then(() => {
  const win = new BrowserWindow({
    width: 620,
    height: 240,
    show: true,
    title,
    backgroundColor: '#FBF8F0',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })

  const html = `<!doctype html><html><body style="margin:0;padding:18px;background:#FBF8F0;font-family:monospace">
    <div style="font:11px system-ui;color:#8b8678;letter-spacing:.12em;text-transform:uppercase;margin-bottom:8px">reverseuno e2e target</div>
    <textarea id="t" autofocus style="width:100%;height:150px;box-sizing:border-box;border:1px solid #d8d2c4;border-radius:10px;padding:12px;font:15px monospace;background:#fff;resize:none;outline:none"></textarea>
    <script>document.getElementById('t').focus()</script>
  </body></html>`

  win.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
  win.once('ready-to-show', () => {
    win.show()
    win.focus()
    win.webContents.focus()
  })

  win.webContents.on('did-finish-load', () => {
    win.webContents.focus()
    win.webContents.executeJavaScript("document.getElementById('t').focus()").catch(() => {})
  })

  // Optional: keep grabbing focus. The focus-steal scenario turns this off so the
  // app itself has to put the paste back into the right window.
  if (keepFocus) {
    setInterval(() => {
      if (win.isDestroyed()) return
      win.focus()
      win.webContents.focus()
    }, 500)
  }
  win.on('page-title-updated', (event) => event.preventDefault())

  setInterval(() => {
    if (win.isDestroyed()) return
    win.webContents
      .executeJavaScript("document.getElementById('t') ? document.getElementById('t').value : ''")
      .then((value) => fs.writeFileSync(output, String(value)))
      .catch(() => {})
  }, 120)
})

app.on('window-all-closed', () => app.quit())
