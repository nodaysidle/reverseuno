# CLAUDE.md — reverseuno

## 1. Execution Posture
- Prioritize direct code execution and tool use over conversational narration.
- Do not provide mid-task status summaries or narrate routine file reads.
- Continue autonomously through multi-step work until the checks pass; only pause if blocked by a critical ambiguity.
- The smallest change that fully satisfies the task (YAGNI). No unsolicited state machines, middleware or speculative features.

## 2. Command Matrix
*Execute these exact commands:*

| Command | Purpose |
|---------|---------|
| `bin/build` | `qmake6` + `make` into `build/reverseuno` |
| `bin/test` | Build and run the Qt Test binary offscreen (starts a private D-Bus session when there is none) |
| `bin/install` | `makepkg -fsi` in `pkgbuild/`, building this working tree (uncommitted changes included) |
| `build/reverseuno` | Run the app; `reverseuno daemon` starts it hidden |

## 3. Architecture
- **Product**: Linux-native, Omarchy-shaped dictation app (hold a key, speak, release, text inserted into the window you were in).
- **Core loop**: `idle` -> `listening` -> `transcribing` -> `inserting` -> `done` (or `error`).
- **Stack**: C++17, Qt 6 Quick (qmake6, Material Dark), Qt Multimedia, Qt WebSockets, Qt D-Bus. One binary, no CMake.
- **Key modules (`src/`)**:
  - `backend.*` — session state machine and everything the window shows.
  - `stt.*` — Deepgram `nova-3`: live WebSocket stream and the single-request fallback.
  - `capture.*` — `QAudioSource` 16 kHz mono int16, in memory only.
  - `paste.*` — `hyprctl`, `wl-copy`/`wl-paste`, `wtype`: window refocus, paste, clipboard restore.
  - `service.*`, `cli.*`, `main.cpp` — D-Bus single instance (`io.github.nodaysidle.Reverseuno`) and the headless commands.
  - `config.*` — `~/.config/reverseuno/config.json` (0600, atomic), key from `DEEPGRAM_API_KEY` first.
  - `hypr.*` — the managed blocks in `~/.config/hypr/{bindings,autostart}.lua` (backup, atomic write, `hyprctl reload` + `configerrors`, undo).
  - `tray.*` — renders the tray icon (`src/tray.svg`) in the theme accent; the `SystemTrayIcon` itself is in `Main.qml`.
  - `theme.*`, `translator.*` — Omarchy accent, Hyprland rounding and language, followed live.
  - `Main.qml` — the one window; `i18n/en.json` lists every visible string.
- **Privacy and safety**:
  - No audio on disk; frames stay in memory. No analytics or telemetry.
  - The API key is never logged, echoed to the window or sent anywhere but Deepgram.
  - Hyprland config is only edited by the Setup buttons, inside the marked blocks. Tests use fake `wl-copy`, `wtype` and `hyprctl` on `PATH`, temp `REVERSEUNO_HYPR_DIR` and config folders, and fake Deepgram servers.

## 4. Negative Constraints
- Every visible string is `qsTr`/`tr` with a key in `src/i18n/en.json` (a test enforces it).
- No file dialogs; if one is ever needed use `org.freedesktop.portal.FileChooser`, never `QFileDialog`.
- No stubs or incomplete handlers in the dictation pipeline. The model is `nova-3` everywhere.

## 5. Modular Imports
@AGENTS.md
@SPEC.md
@GLOSSARY.md
