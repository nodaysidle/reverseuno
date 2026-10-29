# reverseuno: native Qt windows, design

Date: 2026-10-29. Status: draft, awaiting approval. Vocabulary follows `GLOSSARY.md`.

## Intent

The Hub and the Overlay look clunky and out of place on Omarchy: a dense dashboard
and a wide capsule in an Electron/HTML shell. The goal is windows that feel native
next to Omawrite and Omacut, with Superwhisper as the product reference: quiet,
flat, minimal, following the Omarchy theme live.

The engine already works and stays as it is. Only the two windows are rebuilt.

## Goals

- Overlay: a small pill that shows only what matters while a session runs.
- Hub: the essentials only. History, Dictionary, Snippets, and settings including
  the Formatter's model choice (provider, model, cleanup level, writing style) and
  the speech-to-text model.
- Native Qt Quick, themed live from Omarchy's current theme.
- A tray icon (menubar) that opens the Hub.
- Packaged so it appears in the app launcher.

## Non-goals

- Rewriting the engine in C++. Audio capture, speech-to-text, formatting, paste,
  and the store all stay in the current TypeScript/Electron main process.
- New features. No new providers, no account, no telemetry.
- Android (`android/`) is a separate project.

## Anti-goals (a failure even if it technically works)

- **Autopaste breaks or gets slower.** Pasting a finished session into the focused
  app is a hard requirement. Release-to-paste latency must not regress.
- Push-to-talk (Right Super press and release via the existing CLI) stops working.
- The Qt app and the engine drift: a setting changed in one is not seen by the other.
- Home turns back into a dashboard. No stat-card grids, streaks, or rhythm charts.

## Constraints

- Engine keeps running Electron; its Hub and Overlay windows are removed and it runs
  without windows. The hidden capture window stays (it owns the microphone).
- Qt Quick, C++, built with `qmake6` into one binary, following the `omarchy-app`
  conventions (theme-following, installable package with a `.desktop` entry).
- The Qt app talks to the engine over the local control API (see Bridge). The engine
  remains the single owner of settings, history, dictionary, snippets and secrets.

## Bridge (decided by me, not yet by you)

The engine already serves a token-protected HTTP control API on `127.0.0.1`
(`control.ts`; port and token in `~/.config/reverseuno/control.json`), used by
`bin/reverseuno`. It is extended into the full surface the windows need, and the
existing CLI commands keep working unchanged. The surface mirrors what the Electron
preload offers today (`ReverseunoApi` in `src/shared/types.ts`):

- Live: session state events, audio level, theme changes, "refresh" nudges.
  Delivered as a stream (server-sent events or WebSocket; the builder picks).
- Reads: bootstrap (settings, history, dictionary, snippets, suggestions, secret
  status), model lists, audio devices.
- Writes: settings patch, secrets, history edit/delete/copy/insert/flag, dictionary
  and snippet CRUD, suggestion accept/dismiss, toggle/cancel session, quit.

## The windows

**Overlay.** A small pill. Shows a waveform and one state word (Listening,
Polishing, Pasted, error). No buttons or hints while speaking; click or key to
cancel. The **Resting pill** (idle form) stays optional via the existing setting.
Appears near the cursor's display as it does today. *(My guess on content; the
exact look is left to the builder against the Superwhisper reference.)*

**Hub.** A single calm window, opened from the tray or `reverseuno show`.
Sections: History (search, open an entry to read, edit, copy, insert, delete, flag;
learned-edit suggestions), Dictionary, Snippets, Settings. Settings holds speech
provider and model, Formatter provider and model, cleanup level, writing style,
API keys with a test button, microphone, language, overlay options, start at login.
No dashboard, no stats. *(Dropping stats and the streak is my reading of "minimal";
the engine keeps computing them.)*

## Verification

- Existing 80 tests still pass; the engine API gets tests for each new endpoint.
- A manual script, run before this ships: hold Right Super in another app, speak,
  release, and confirm the final text lands at the cursor, with the Hub closed.
- Theme check: switch the Omarchy theme; both windows recolour without restart.

## Calls I made (from the builder check; correct any you disagree with)

- **Overlay focus.** The Overlay never takes keyboard focus: a layer-shell surface
  with keyboard interactivity off, so the paste still lands in the previously active
  window. Cancel is by click on the pill (does not move focus), the existing CLI
  `cancel`, or the tray. There is no key-to-cancel on the pill itself.
- **Placement.** The engine keeps deciding which display and when to show or hide;
  the stream carries that. Qt only draws.
- **Tray and lifecycle.** The Qt app owns the tray; the engine's tray is removed.
  The engine starts the Qt app and restarts it if it exits. `reverseuno show` opens
  the Hub (launching the Qt app first if it is not running). Start at login starts
  the engine only.
- **Security.** The bearer token in `control.json` (file mode 0600) guards every
  endpoint including the stream. Requests carrying an `Origin` header are rejected,
  so web pages cannot reach it. Secrets are write-only: the API reports whether a
  key is set, never its value.
- **Hub scope.** History replaces Home. Style folds into Settings. Dictionary and
  Snippets stay. Kept: clear history, export, add developer word pack (in Settings
  or Dictionary), open external links. Dropped: demo data, stats, streak, deep-link
  page events (the Hub opens at History, or at the page `show <page>` names).
- **Theme.** The Qt app reads the Omarchy theme itself and watches it, so it
  recolours even if the engine is down. The engine no longer sends palettes.
- **Engine down.** The Hub shows an offline state and retries; the Overlay stays
  hidden. Nothing in the push-to-talk path depends on the Qt app.
- **Latency.** The Overlay is outside the paste path (key press and release go
  through the CLI to the engine as today), so no timing test is added; the pipeline
  is left untouched.
- **Packaging.** One package installs the engine and the Qt app, with the `.desktop`
  entry launching the engine, which brings up the Qt app.

## Left to the builder

Qt module choices, QML structure and file layout, stream transport, how the
tray is implemented under Wayland, fonts and exact sizes, packaging details, and
how engine and Qt app are started together (one launcher or two units).
