# Native Qt Windows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Electron Hub and Overlay windows with native Qt Quick windows that talk to the unchanged engine over an extended local API.

**Architecture:** The Electron main process stays as a windowless engine (capture, speech-to-text, Formatter, paste, store). `control.ts` grows an RPC route table plus an SSE event stream. A new Qt Quick app in `qt/` reads the same API: Overlay as a layer-shell surface with no keyboard interactivity, Hub as a normal window, tray owned by Qt. The engine launches and supervises the Qt app.

**Tech Stack:** TypeScript/Electron/vitest (engine); C++17, Qt 6.12 (Qt Quick, QtNetwork, QtTest), LayerShellQt 6.8, qmake6 (UI). No `qt6-websockets` is installed, so the stream is SSE over `QNetworkAccessManager`.

**Spec:** `docs/superpowers/specs/2026-10-29-qt-windows-design.md`. Vocabulary: `GLOSSARY.md`.

## Global Constraints

- Autopaste is a hard requirement: hold Right Super in another app, speak, release, text lands at the cursor, Hub closed.
- Push-to-talk goes through `bin/reverseuno` to the engine; the Qt app is never in the paste path.
- Existing CLI commands (`start stop toggle cancel state health show devices device paste lang quit`) keep working unchanged.
- Existing 80 tests keep passing.
- The Overlay never takes keyboard focus.
- Every endpoint including the stream needs `Authorization: Bearer <token>` from `~/.config/reverseuno/control.json` (mode 0600). Requests carrying an `Origin` header are rejected with 403.
- Secrets are write-only: the API reports whether a key is set, never its value.
- Qt reads the Omarchy theme from `~/.local/state/omarchy/current/theme/colors.toml` and watches it; the engine stops sending palettes.
- Hub sections: History, Dictionary, Snippets, Settings. No stats, streak, demo data, or page deep-link events.
- Terms follow `GLOSSARY.md` (Session, Dictation entry, Final text, Formatter, Overlay, Resting pill, Hub).

## Review Focus

- `control.json` missing or stale when the Qt app starts: show the offline state, retry, never crash.
- A request with an `Origin` header (a web page) hits any endpoint or the stream: 403, nothing leaks.
- Stream drops mid-session: the Qt app reconnects and re-bootstraps; the session and paste are unaffected.
- A dictation entry's final text containing markup (`<b>`, `&amp;`, newlines): rendered as plain text in the Hub.
- `settings` patch with an unknown key or wrong type: rejected with 400, stored settings unchanged.

---

## File Structure

- Modify `src/main/control.ts`: Origin guard, RPC route, SSE stream.
- Create `src/main/api.ts`: the RPC method table (`buildApi`) shared by the control server (and, until Task 7, the Electron IPC).
- Modify `src/main/index.ts`: register `buildApi`, publish events, drop windows/tray, supervise Qt app.
- Create `tests/control-api.test.ts`, `tests/control-events.test.ts`.
- Create `qt/` : `reverseuno-ui.pro`, `src/` (C++: `Theme`, `EngineClient`, `HistoryModel`, `main.cpp`), `qml/` (`Overlay.qml`, `Hub.qml`, pages), `tests/` (QtTest).
- Create `packaging/PKGBUILD`, `packaging/reverseuno.desktop`.

---

### Task 1: Origin guard and RPC route

**Files:**
- Create: `src/main/api.ts`
- Modify: `src/main/control.ts:13-24` (handlers), `:122-141` (auth), add `/rpc/<method>`
- Test: `tests/control-api.test.ts`

**Interfaces:**
- Produces: `type ApiTable = Record<string, (...args: unknown[]) => Promise<unknown> | unknown>`; `ControlHandlers.rpc: ApiTable`. Route `POST /rpc/<method>` with body `{ "args": [...] }` answers `{ ok: true, result }`; unknown method 404; handler throw → 400 `{ ok:false, error }`.
- `buildApi(deps): ApiTable` in `src/main/api.ts` with the method names of `ReverseunoApi` (`bootstrap`, `updateSettings`, `setSecret`, `clearSecret`, `testProvider`, `listDevices`, `listModels`, `historyUpdate`, `historyDelete`, `historyClear`, `historyCopy`, `historyInsert`, `historyFlag`, `dictionaryAdd`, `dictionaryAddPack`, `dictionaryUpdate`, `dictionaryDelete`, `snippetAdd`, `snippetUpdate`, `snippetDelete`, `suggestionAccept`, `suggestionDismiss`, `dictationToggle`, `dictationCancel`, `setLogin`, `openExternal`, `exportData`, `quit`). Bodies are moved from the `ipcMain.handle` calls at `index.ts:429-568`, not rewritten.

- [ ] **Step 1: Write failing tests** in `tests/control-api.test.ts`, starting a `ControlServer` on a temp dir with a fake `rpc` table: `rejects a request with an Origin header (403)`, `rejects a missing token (401)`, `calls a method with args and returns result`, `returns 404 for an unknown method`, `returns 400 when the method throws`, `never returns secret values (bootstrap fake returns only {set:boolean})`.
- [ ] **Step 2: Run** `npx vitest run tests/control-api.test.ts` → FAIL.
- [ ] **Step 3: Implement** the Origin check before auth (all paths except none) and the `/rpc/` branch in `control.ts`; `api.ts` moves handler bodies out of `index.ts` and `index.ts` registers them via `buildApi` for both IPC (loop over the table) and `ControlServer`.
- [ ] **Step 4: Run** `npx vitest run` → all pass (80 + new). `npm run typecheck` clean.
- [ ] **Step 5: Commit** `feat(engine): rpc route and origin guard on the control API`.

### Task 2: Settings validation

**Files:** Modify `src/main/api.ts` (`updateSettings`); Test `tests/control-api.test.ts`.
**Interfaces:** Consumes `buildApi` from Task 1. Produces: `updateSettings` throws `Error('invalid settings')` for unknown keys or wrong types (so the route returns 400).

- [ ] **Step 1: Failing tests:** `settings patch with an unknown key is rejected and leaves settings unchanged`, `settings patch with a wrong type is rejected`, `a valid nested patch (format.cleanup: "light") is applied`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** validation against the shape of `defaultSettings` (`src/shared/defaults.ts`): recursive key and `typeof` check.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(engine): validate settings patches`.

### Task 3: Event stream

**Files:** Modify `src/main/control.ts` (add `GET /events`, `ControlServer.publish(event: ApiEvent)`), `src/main/index.ts` (publish from `sendHub`, `sendOverlay`, `sendLevel`, `showHub`); Test `tests/control-events.test.ts`.

**Interfaces:**
- Produces: `type ApiEvent = { type: 'state'; state: StateEvent } | { type: 'level'; level: number } | { type: 'refresh' } | { type: 'page'; page: string } | { type: 'overlay'; visible: boolean; x: number; y: number; width: number; height: number }`. Wire format: SSE, one `data: <json>` per event, a `: ping` comment every 15 s. `overlay` carries the placement the engine already computes (`index.ts:162-183`); Qt only draws.

- [ ] **Step 1: Failing tests:** `GET /events without token is 401`, `with Origin is 403`, `a client receives published events in order`, `a disconnected client is dropped without affecting others`, `ping is sent`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** SSE with a `Set<ServerResponse>`; wire `publish` calls. **Step 4: Run** all → PASS. **Step 5: Commit** `feat(engine): SSE event stream`.

### Task 4: Qt scaffold, theme, engine client

**Files:** Create `qt/reverseuno-ui.pro`, `qt/src/Theme.{h,cpp}`, `qt/src/EngineClient.{h,cpp}`, `qt/src/main.cpp`, `qt/tests/tst_theme.cpp`, `qt/tests/tst_engineclient.cpp`.

**Interfaces:**
- `class Theme : QObject` with `Q_PROPERTY` colours `background foreground accent muted surface red green` (QColor), `static QHash<QString,QString> parseColors(const QString&)`; reloads on change of `~/.local/state/omarchy/current/theme/colors.toml` (watch the parent dir, as `theme.ts` does).
- `class EngineClient : QObject`: `Q_INVOKABLE void call(const QString& method, const QVariantList& args)`; signals `connectedChanged(bool)`, `event(QVariantMap)`, `callFinished(QString method, QVariant result, QString error)`; reads `control.json` at each (re)connect; reconnects with backoff 1 s → 5 s; on connect calls `bootstrap`.

- [ ] **Step 1: Failing QtTest:** `parseColors reads key = "value" lines`, `missing colors.toml yields fallback palette`, `EngineClient with no control.json reports disconnected and keeps retrying`, `EngineClient parses an SSE chunk split across two reads`.
- [ ] **Step 2: Run** `cd qt && qmake6 && make check` → FAIL. **Step 3: Implement** per the interfaces. **Step 4: Run** → PASS. **Step 5: Commit** `feat(qt): scaffold, theme and engine client`.

### Task 5: Overlay

**Files:** Create `qt/qml/Overlay.qml`, `qt/src/OverlayController.{h,cpp}`; Test `qt/tests/tst_overlay.cpp`.

**Interfaces:** Consumes `EngineClient.event`. Layer-shell surface (LayerShellQt, layer overlay, keyboard interactivity none), positioned from the `overlay` event. Shows a waveform from `level` and one state word (Listening, Polishing, Pasted, error text). Click calls `dictationCancel` through `EngineClient.call`. Resting pill shown when the `overlay` event says so.

- [ ] **Step 1: Failing test:** `controller maps state events to the right word`, `controller hides on overlay.visible=false`, `click emits dictationCancel`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Manual check:** run engine + Qt app, hold Right Super in a text editor, confirm the editor keeps focus (cursor keeps blinking) and the text pastes.
- [ ] **Step 6: Commit** `feat(qt): overlay`.

### Task 6: Hub

**Files:** Create `qt/qml/Hub.qml`, `qt/qml/{History,Dictionary,Snippets,Settings}Page.qml`, `qt/src/HistoryModel.{h,cpp}`; Test `qt/tests/tst_history.cpp`.

**Interfaces:** Consumes `EngineClient.call` with the Task 1 method names. `HistoryModel : QAbstractListModel` roles `id text appClass createdAt inserted error flagged`; `filter(QString)` matches `text`, `appClass`, `appTitle`. Settings page covers: speech provider/model, Formatter provider/model, cleanup level, writing style, API keys with test button, microphone, language, Overlay options (including Resting pill), start at login, export, clear history, add developer word pack. All entry text uses `Text.PlainText`. Offline state shown when `connectedChanged(false)`.

- [ ] **Step 1: Failing tests:** `filter matches final text and app`, `refresh event reloads the model`, `markup in text stays literal`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** the model, then pages one by one (History, Dictionary, Snippets, Settings). **Step 4: Run** → PASS.
- [ ] **Step 5: Manual check:** change the Formatter model, dictate, confirm the new model is used (engine log). Switch the Omarchy theme; Hub recolours.
- [ ] **Step 6: Commit** `feat(qt): hub`.

### Task 7: Tray and lifecycle

**Files:** Create `qt/src/Tray.{h,cpp}`; Modify `src/main/index.ts` (remove `new BrowserWindow` for hub/overlay, `ensureTray`, `updateTrayMenu`, hub IPC, `window:*`; keep `capture`), `src/preload/index.ts` (delete), `src/renderer/hub|overlay` (delete), `electron.vite.config.ts`; Test `tests/lifecycle.test.ts`.

**Interfaces:** Engine spawns the Qt binary (`reverseuno-ui`) at startup and respawns on exit (cap: 5 restarts per minute). `/show` handler: publish `page`, and start the Qt app if it is not running. Tray (Qt): click opens the Hub; menu mirrors Start/Stop, Quit.

- [ ] **Step 1: Failing tests:** `supervisor restarts the child after an exit`, `supervisor stops restarting after 5 exits within a minute`, `show starts the child if not running`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** a small `src/main/supervisor.ts` and the removals. **Step 4: Run** `npm run typecheck && npx vitest run` → PASS.
- [ ] **Step 5: Manual check (autopaste, the hard requirement):** with the Hub closed, hold Right Super in another app, speak, release: text lands at the cursor. Repeat 5 times. Kill the Qt app mid-session: the paste still completes.
- [ ] **Step 6: Commit** `refactor: engine runs windowless, Qt owns tray`.

### Task 8: Packaging and docs

**Files:** Create `packaging/PKGBUILD`, `packaging/reverseuno.desktop`; Modify `README.md`, `SPEC.md` §10 (windows), `docs/screenshots/` (new Hub and Overlay shots).

- [ ] **Step 1:** PKGBUILD builds the engine and `qt/` and installs both; the `.desktop` entry launches the engine.
- [ ] **Step 2:** `makepkg -f` builds and `pacman -U` installs; the app appears in the launcher.
- [ ] **Step 3:** Update README/SPEC to the new windows; replace the screenshots; update the test-count badge if it changed.
- [ ] **Step 4: Commit** `docs: Qt windows, packaging`.

---

## Self-review notes

- Spec coverage: Overlay (T5), Hub (T6), tray and lifecycle (T7), bridge and security (T1–T3), theme (T4), autopaste check (T7 step 5), packaging (T8). Builder-check calls (focus, placement, tray, security, scope, theme, offline, latency, packaging) each map to a task.
- Names match across tasks: `buildApi`, `ApiEvent`, `EngineClient.call`, `dictationCancel`.
- Left to the implementer: QML styling against the Superwhisper reference, exact sizes and fonts.
