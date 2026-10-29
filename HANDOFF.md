# HANDOFF

## Goal
reverseuno: the Hub and Overlay are native Qt windows (`qt/`, binary `reverseuno-ui`). A windowless Electron engine runs behind them (capture, speech-to-text, formatting, paste, store). Autopaste must keep working.

## State (2026-10-29)
- PR #4 (the Qt windows) is merged into `master`.
- PR #5 (`nds/audit-fixes`) is merged into `master`. It fixes four audit findings:
  - A history edit that cleaned to empty text was lost while the Hub reported success. The API now returns an error, and the Hub keeps the editor open until the engine confirms.
  - App-rule patterns are validated with the same JS RegExp that `paste.ts` uses.
  - The "1 new words" toast is reworded.
  - The unused `hasCommand` is removed.
- Tests on that branch: 116 engine tests and 33 Qt tests pass, and the typecheck and build are clean. The e2e was not re-run after PR #5.
- The local checkout is on `nds/audit-fixes`. The running engine (started with `setsid`, `electron --hidden .`) and its `qt/reverseuno-ui` were built from that branch.
- The user confirmed the pill, the Hub and Esc-to-close work.

## Fixed in the 2026-10-29 follow-up session (merged with PR #5)
- **The "Process crashed" notifications are fixed.** They came from `xdg-desktop-portal-hyprland`, and the live app never caused them. `tst_tray` started a private D-Bus bus that spawned a second portal, which segfaulted on exit. The test's bus now has no service dirs. Checked with repeated `tst_tray` runs, `make check` and a live `reverseuno-ui` restart: the portal core count stayed at 16 and no new cores appeared. See item 1 below.
- **Speech-to-text is back on Deepgram `nova-3` streaming** (desktop and phone), the fastest path: median 0.24 s after release, against 1.7 s for any OpenRouter one-shot model. OpenRouter `openai/gpt-4o-mini-transcribe` stays the configured one-shot model and the phone's fallback.
- **Speed work (2026-10-29):**
  - Haiku cleanup is skipped for clean utterances of up to 8 words (was 3) at Light cleanup, on both apps.
  - `warmFormatter()` opens the Anthropic connection on key press and on release. No gain was visible in two samples; it is harmless.
  - The phone's streaming provider had been left on xAI with no key, so it silently fell back to OpenRouter. It is now Deepgram, and its cleanup is now Light like the desktop.
  - `scripts/push-keys-to-phone.py` now also copies the Deepgram key.
  - Measured: about 0.4 s for short phrases and 1.4–2.5 s with Haiku.
  - Deepgram can drop an unfamiliar name mid-sentence (Haiku keeps it; checked 6/6). The Dictionary's keyterms are the fix.
- Before that, the same day: OpenRouter `openai/gpt-4o-mini-transcribe` replaced Deepgram `nova-3`. Whisper large-v3 was tried first, then dropped the same day because it is slower (1–4.7 s vs 1.6 s per clip), less accurate on OpenAI's FLEURS numbers, and auto-detected a quiet clip as Russian. The phone already used `gpt-4o-mini-transcribe`, so both are now on the same model. **Cleanup uses Anthropic `claude-haiku-5-5`** (thinking on, effort `low`). These are the new defaults in `src/shared/defaults.ts`, with language `multi` for new installs. The user's live store is set to the same, with `stt.language: "en"`: on auto-detect, Whisper had turned a short, quiet clip into Russian Cyrillic.
- `scripts/test-format.ts` loads the key that matches `format.provider`, so it no longer sends the OpenRouter key to Anthropic.
- Verified live with the user's microphone: 4 English dictations (2 into Velocitty, 2 into omawrite) were transcribed, cleaned up and inserted with no errors or cores. Timings: Whisper 1.0–4.7 s, Haiku 0.75–1.2 s, about 2–3 s from key release to text. 116 engine tests pass, and the typecheck is clean. Real-API checks: both keys pass `testProvider`; Whisper transcribed the e2e captures; Haiku cleaned up the `test-format` samples.
- Keys: OpenRouter comes from `~/.config/whisper-bar/config.env`, Anthropic from the app's `secrets.json` (`plain:`). The engine's own environment has neither key.

## Android app (2026-10-29)
- The dark/white SuperWhisper-style UI and streaming STT lived only on the unpushed branch `feat/android-streaming` and an installed debug build (`com.nodaysidle.reverseuno.debug`). Both are now merged into `master`. The debug app is uninstalled, and the release app `com.nodaysidle.reverseuno` was updated in place with its data kept.
- Redesign on top, at the user's request (antislop dials ENERGY 1 / RHYTHM 1 / MOTION 1):
  - No logo or name bar.
  - Home shows only a status line, search and the dictation list. The stat cards moved to Settings > Usage.
  - No uppercase eyebrows above page titles.
  - The Style tab uses a pen icon.
  - No emoji or em dashes in the UI copy.
- The floating bubble is a rounded square drawn with the logo's five bars, the center bar in the logo's orange-red `#F45B36`, the one accent. It replaced the round pill with a stock mic. The same mark is the in-app record button.
- The bubble's visibility, keyboard anchoring, tap/long-press and edge snapping were already in place on a Wispr Flow model and were left unchanged.
- Verified on the Mi 10T (stock MIUI 14, bootloader locked, verified boot green). A dictation through the bubble into Messages was inserted (OpenRouter `gpt-4o-mini-transcribe` + Anthropic cleanup), with no crashes. The 53 Android unit tests pass.
- The phone and the desktop both stream through Deepgram `nova-3`, with Haiku cleanup at Light. The bubble can cover a field's left-side button (Messages "+"); dragging it to the right edge works around this.
- Cleaned up:
  - the second worktree `reverseuno-android`
  - the stale `/tmp` worktrees and test folders
  - the `.superpowers/` ledger
  - the merged branches
  - the deleted, user-approved branches `feat/phone-provider-palette` (xAI/DeepSeek/phone-provider experiment) and `chore/claude-canary`
  - on the phone: the agent's `/data/local/tmp/reverseuno.apk`, its UI dumps and the dead accessibility entry

## What changed from the legacy version (Electron + React windows, up to `408539a`)
Against `408539a`, the changes under `src qt tests scripts packaging package.json` come to about +5.3k and −7.3k lines across 100 files.

**Removed**
- The React Hub and Overlay (`src/renderer/hub`, `src/renderer/overlay`), `src/preload/index.ts`, and the React, lucide and font dependencies. Only the hidden capture window remains in `src/renderer`.
- Home, the stats cards, the streak, the demo data and the "Show raw transcript" toggle.
- The `rawText` field everywhere (types, demo data, tests, e2e, SPEC).
- The engine's tray and the Electron-side theme palettes. The Qt app owns the tray and reads the Omarchy theme itself.
- The Sound cues toggle. The start/stop sounds are not played by the Qt windows.
- The unused `on*` listeners on `ReverseunoApi`.

**Added**
- `qt/`: a C++17 Qt 6 Quick app (`reverseuno-ui`) with an Overlay (a LayerShellQt pill with a waveform and one state word) and a Hub with History, Dictionary, Snippets and Settings. It has a tray, a single-instance lock, a live Omarchy theme and Esc to hide the Hub. 33 Qt tests.
- A control API extended for the windows: `POST /rpc/<method>`, an SSE stream at `/events` with a snapshot on connect, and token auth. Requests that carry an `Origin` header get a 403, and API keys never appear in responses (`redactSecrets`). Settings patches are shape-checked, including each app rule and its regex.
- A supervisor that starts the Qt app, restarts it at most 5 times a minute and does not restart it on exit code 75.
- `GLOSSARY.md`, the design spec and the plan under `docs/superpowers`, and the agent-skill docs under `docs/agents`.
- Packaging: `packaging/PKGBUILD` and `reverseuno.desktop`; `scripts/install-local.sh` also builds `qt/`; `scripts/install-hyprland.py` has rules for the Qt Hub and a layer rule for the overlay.
- The e2e harness gained `E2E_WITH_UI=1`, `E2E_EXPECT` and an `overlayMapped` check.

**Changed**
- The engine now runs windowless and publishes the merged overlay state plus overlay bounds over SSE. The old preload IPC is gone.
- The Hub lost the dashboard and gained the paste settings (mode, keys, app rules, restore delay, append space, primary selection) and the formatter's custom instructions, app-context switch and base URL.
- An empty OpenRouter base URL now falls back to the default (`openRouterBaseUrl()`).
- The tests went from 80 engine tests in the original design doc to 116 engine plus 33 Qt tests.

**Unchanged on purpose:** audio capture, speech-to-text, formatting, the paste pipeline, the store and the push-to-talk CLI (`bin/reverseuno`).

## What does not work, or is not verified
1. **FIXED (2026-10-29): `xdg-desktop-portal-hyprland` crashes (16 in `coredumpctl`).** The live app never caused them. `tst_tray`'s `trayRegistersWithTheWatcher` started a private `dbus-daemon --session`, and the gtk3 theme activated a second portal on it. That portal segfaulted in its atexit Wayland cleanup when the test killed the bus. The test now gives that bus a config with no service dirs (`qt/tests/tst_tray.cpp`). Checked: 4 full `tst_tray` runs, `make check` and a live `reverseuno-ui` restart added no new cores; the count stays at 16. The user's own portal (PID 1965) never crashed.
2. **Electron SIGTRAP core dump** at 05:13. It came from an early SIGTERM teardown of throwaway engines in my own test runs. Throwaway engines now exit through a graceful `/quit`, and no new cores appeared.
3. **Velocitty garbles pasted input.** The terminal mangles `wtype` typing and ignores `shift-insert` and `ctrl-shift-v` (raw CSI sequences show up). It reproduces on the pre-Qt build `408539a`, so it is not a regression. Confirmed again on 2026-10-29: reverseuno's history held the full text ("trying", "English"), but Velocitty showed "tryg" and "glish". Typed `in`/`an`/`en` sequences get dropped. The same dictation into omawrite came out intact. Adding "Velocitty" to the Dictionary would stop Whisper from writing "velocity". The user's store has `insert.mode: "type"`, so autopaste into Velocitty is unreliable. The fix belongs in Velocitty's input handling, and the user also writes Velocitty.
4. **Sound cues were not ported.** The start/stop sounds need QtMultimedia, and the Hub toggle was removed.
5. **API keys without a keyring** are saved as `plain:<base64>` in `secrets.json` (`src/main/secrets.ts:36-44`). That is encoding, not encryption. The choice between refusing to save and prompting is the user's.
6. **Esc closes the Hub even while a dropdown is open.** Not checked.
7. **Two rapid edits to app rules** (`setAppRule` then `removeAppRule`) can lose one edit (`qt/src/HubController.cpp:227-244`).
8. **Qt app orphaned after a hard engine crash.** A later `show` exits with code 75 and the page is never replayed.
9. **Not verified live:**
   - a dictation through the real microphone on the new build
   - a theme switch
   - the export dialog
   - the package install (`makepkg`, which needs the password and the `electron44` package)
   - a Qt crash when the engine dies, and a stale lock file after a reused process id

## Not done on purpose
- A Host-header check on the control API (impact is limited; see the audit notes).
- The `format.baseUrl` plus `testProvider` key-redirect risk (needs a same-user token holder; it predates the branch).
- `paths.cli` is hard-coded to `~/.local/bin/reverseuno`.
- Dead overlay dependencies in the engine.
- The engine does not start at login until it is launched from the app launcher or `scripts/install-local.sh` is re-run. `scripts/install-hyprland.py` has not been re-run, so the window rules are old.

## Key files
`src/main/{control,api,supervisor,index,dictation,paste,secrets}.ts`, `qt/src/*`, `qt/qml/*`, `qt/tests/*`, `scripts/{e2e.mjs,install-local.sh,install-hyprland.py}`, `packaging/*`, `docs/superpowers/{specs,plans}/2026-10-29-qt-windows*.md`, `GLOSSARY.md`.

## Open questions
- Keys without a keyring: refuse to save, or prompt?
