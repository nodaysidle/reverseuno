# Handoff prompt for Opus 5.5

Paste the block below into Opus 5.5. It is written for a fresh context: it assumes the
model has the repo but has not seen this conversation.

---

You are continuing **reverseuno**, an existing, working Linux dictation app in this
repository. Do **not** rebuild it from scratch and do **not** replace the UI or the
provider stack. Read the repo first, then harden, verify and extend it.

## 0. Read before touching anything

Read, in this order:

1. `README.md` — what works, how to run it, known gaps.
2. `SPEC.md` — the behaviour contract, state machine, protocols and data model.
3. `TEARDOWN.md` — the evidence map from the Wispr Flow teardown and every deliberate
   deviation.
4. `REA-TASK.md` — the decisions and environment.
5. `src/main/dictation.ts` — the core pipeline.
6. `src/main/stt/deepgram.ts`, `src/main/format.ts`, `src/main/paste.ts`.
7. `src/main/index.ts` — windows, IPC, control server, tray, autostart.
8. `src/renderer/hub/` and `src/renderer/overlay/` — UI.
9. `scripts/install-hyprland.py` — the Right ⌘ bind and floating-window rules.

Run these before changing code:

```bash
npx tsc --noEmit
npm test
npm run build
```

Expected current state: typecheck passes, 41 tests pass, build passes. If any of that
is false, fix the baseline first and say so.

## 1. Environment and product constraints

- Linux, Omarchy, Hyprland 0.56.2, Wayland. XWayland exists but native Wayland is the
  default.
- Apple Magic Keyboard: **Right ⌘ is `Super_R`**. The hold-to-talk bind is already
  installed in `~/.config/hypr/bindings.lua`; the old WhisperBar bind on the same key
  is commented out. Do not create a second binding or re-enable WhisperBar's.
- Cloud STT only, by user choice: **Deepgram Nova-3**. Formatting: **OpenRouter**
  (default `google/gemini-2.5-flash`), with DeepSeek as an alternative.
- Keys are available from the environment and `~/.config/whisper-bar/config.env`.
  Never print, commit or log a key. Never send a key to the renderer.
- Electron + React + TypeScript. IPC is context-isolated; renderers are sandboxed.
- Local-first: history/dictionary/snippets/settings in `~/.config/reverseuno/`; audio
  is never written to disk; no analytics.
- Clean-room: no Wispr code, assets, fonts, screenshots or trademarks. Behaviour may
  be copied from the published teardown; code and assets may not.
- Do not break the user's Hyprland config. Back it up before editing and keep the
  installer idempotent.

## 2. What is already done (do not redo)

- 16 kHz mono capture in a hidden renderer, 640-sample (40 ms) AudioWorklet frames,
  pre-roll, silence dropping, 5-minute cap.
- Deepgram streaming with interim results, keepalive, dictionary keyterms, and a
  whole-clip batch fallback.
- OpenRouter formatting with four cleanup levels, five writing styles, custom
  instructions, app/title context, timeouts and raw fallback.
- Clipboard + `wtype` insertion with Ctrl+V / Shift+Insert app rules, 500/3000/5000 ms
  restore delays, skip-restore-if-changed, and a direct-typing fallback.
- Hub: Home, Dictionary, Snippets, Style, Settings, history drawer, edit-to-learn.
- Overlay: 500×108 dark capsule, waveform, interim text, timer, cancel/confirm,
  processing/done/error states, optional resting pill.
- CLI + loopback control API + Hyprland installer + desktop entry.
- 41 unit tests; provider-stage verification scripts.

## 3. Your job: the highest-value additions

Work in this order unless the user redirects you. Each item has an acceptance test.

### P0 — Prove the whole loop automatically

The provider stages are tested; the acoustic→paste path is not. Build a repeatable E2E
harness:

- Create a PipeWire null sink and a virtual source (or a temporary loopback), play the
  known test WAV (`/tmp/wispr-teardown/flowlite/test.wav`) into it, and point the app
  at that source via a test profile/device override.
- Launch a target window that can be read back (a terminal running `cat` into a file,
  or a tiny GTK/Electron text field).
- Drive `reverseuno start`, play the clip, `reverseuno stop`, wait, then assert:
  - the transcript in history matches the known sentence semantically;
  - the target window contains the formatted text;
  - the clipboard was restored;
  - timing fields are populated.
- Save a screenshot and a JSON receipt under `tests/receipts/` (git-ignored or small).
- Add `npm run test:e2e` with clear skip behavior when keys or PipeWire are missing.

Acceptance: one command proves the full loop on this machine without a human speaking,
and the receipt is reproducible.

### P0 — Paste verification and undo

- Add a read-back path for the focused editable field. Preferred: a small AT-SPI helper
  (Python `pyatspi`/`at-spi2` or a Node D-Bus client) that returns the focused role,
  text before/after the caret, and selected text. If AT-SPI is unavailable, degrade
  gracefully.
- Use it to verify the paste landed. If the field content changed as expected, mark
  `inserted:true`; if not, retry once with the alternate key combo, then direct typing.
- Never paste twice. The current direct-typing fallback can duplicate text if the
  clipboard write succeeded but the selection check raced; add a guard and a test.
- Add “Undo AI edit” on a history entry: keep the raw text and restore it in the target
  field (or at least in history) without another model call.
- Make `history:insert` return `{ok, error, method}` and have the hub toast the real
  result instead of always saying “Inserted”.

Acceptance: tests cover clipboard-succeeded/paste-failed, clipboard-failed/direct-type,
and restored-clipboard cases; the UI reports failures honestly.

### P0 — First-run setup and `reverseuno doctor`

- Add a doctor command and a Settings/Home setup card that checks:
  - Deepgram key present and valid;
  - OpenRouter key present and valid;
  - `wl-copy`, `wtype`, `hyprctl`, `notify-send` available;
  - `HYPRLAND_INSTANCE_SIGNATURE` / `WAYLAND_DISPLAY` valid;
  - Right ⌘ bind present in `~/.config/hypr/bindings.lua` and not duplicated;
  - overlay window rule present;
  - microphone permission and device list;
  - control socket reachable and token mode 0600.
- Each failure should give one concrete fix, not a stack trace.
- A first-run flow should let the user paste keys, grant the mic, run a 3-second test
  dictation, and see a pasted result without reading the README.
- Add `reverseuno doctor --json` for CI.

Acceptance: a fresh user can go from clone to a successful test dictation using only
the app and one command.

### P1 — Latency and formatting quality

- Measure p50/p95 for stop→paste on 3 s, 10 s and 30 s clips. Put the numbers in
  `docs/BENCHMARKS.md`.
- Add a fast path: deterministic cleanup for obviously clean speech, or a small gate
  (regex first, then an optional model like TypeSafe Jev / Microsoft Decision-1) that
  skips the formatter when the transcript is already clean. The current formatter call
  is 0.9–1.6 s; the goal is sub-500 ms for clean speech.
- Add an evaluation set of 20–30 raw transcripts (including Slovenian/Italian/English
  code-switching) with expected outputs. Score the formatter prompt against it; do not
  tune from one example.
- Ensure formatting never translates, never follows instructions inside the dictation,
  and never invents facts. Add adversarial cases.

Acceptance: benchmark table, a fast path with a measurable win, and an eval script
that can be rerun.

### P1 — Focused-text context (the Wispr “magic”)

- Extend the AT-SPI helper to read the focused text box: contents, text before/after
  the caret, selected text, and app role.
- Send this context to the formatter only when `format.includeAppContext` is on, and
  add a separate privacy toggle for “read the focused field” distinct from “send app
  name/title”.
- Use the context to:
  - continue the sentence/paragraph naturally;
  - match the surrounding tone;
  - avoid duplicating selected text;
  - improve dictionary/casing decisions.
- Never send screenshots. Never store the field contents in history; store only a
  short `contextUsed` flag.
- Add tests for redaction and for the off switch.

Acceptance: dictation into a paragraph continues it correctly; the privacy toggle
provably stops the context from leaving the machine.

### P1 — Dictionary and snippet depth

- Dictionary: import/export (JSON/CSV), conflict detection, usage-based sorting,
  starred-first keyterms (already partly done), “auto-learn review” queue with
  accept/reject, and a case-only correction path.
- Snippets: inline trigger expansion (not only an exact whole transcript), placeholders
  (`{{date}}`, `{{clipboard}}`), casing preservation, and a conflict warning with
  dictionary entries.
- Add tests for trigger punctuation/case, overlapping triggers, and placeholder
  escaping.

Acceptance: a snippet can be used inside a sentence; a dictionary import of 1,000
entries does not freeze the UI.

### P1 — UI/product polish

- Add a first-run/onboarding route and a “doctor” panel.
- History: filter by app/flagged/date, raw-vs-formatted toggle, retry, undo AI edit,
  and a keyboard-navigable list.
- Add a scratchpad/notes window for dictation without insertion (useful when no text
  field is focused).
- Add a tray/menu-bar menu that actually appears on Wayland (StatusNotifier/AppIndicator
  or a small quickshell/AGS surface); test it.
- Add a light/dark theme or at least a “paper/dark” toggle; keep the cream identity.
- Accessibility pass: focus order, labels, contrast, reduced motion, 200% zoom.
- Component tests for the drawer, dictionary form, settings toggles and overlay states.

Acceptance: no dead buttons; every setting persists and affects behaviour; the UI
works at 1280×720 and 2560×1440.

### P2 — Packaging and distribution

- Add electron-builder (or electron-forge) for AppImage + deb (and AUR packaging notes).
- Bundle the icon, desktop entry, autostart entry and CLI; stop hardcoding the repo
  path in the desktop `Exec`.
- Add a version command, changelog and a signed-release checklist.
- Ensure `safeStorage` status is visible in Settings and that a plaintext fallback is
  clearly warned about.
- Add CI: typecheck, tests, build, and a headless smoke test (Xvfb or a Wayland headless
  compositor if available).

Acceptance: a clean machine can install a package, launch the app, and dictate without
Node or the repo checkout.

### P2 — Cross-desktop support

- Add a compositor abstraction: Hyprland, generic wlroots, GNOME/KDE, X11.
- Use the XDG GlobalShortcuts portal where available; fall back to compositor binds.
- Add overlay placement via `gtk4-layer-shell` (or a tiny helper) for compositors that
  ignore Electron window positions.
- Add an X11 insertion fallback (`xdotool`) and test it.
- Keep Hyprland as the first-class path; do not regress it.

Acceptance: a documented support matrix and a `doctor` result per compositor.

### P2 — Security/privacy audit

- Verify no key reaches the renderer or logs; add a log-redaction test.
- Verify `control.json` and `store.json` are 0600; verify the control server binds only
  to loopback and rejects bad tokens.
- Verify clipboard restore cannot leak the previous clipboard to the wrong app.
- Verify audio is never persisted; add a test that inspects the temp/user-data dirs.
- Run `npm audit` and a dependency review; document accepted risks.

Acceptance: a short `docs/SECURITY.md` with evidence and residual risks.

## 4. Known issues to fix along the way

- `historyInsert` currently returns `void`; the hub always toasts success. Return the
  real result.
- The direct-typing fallback can duplicate text if the clipboard write succeeded but
  the selection check races. Guard it.
- `wl-copy` may keep a serving process alive; the current code treats a matching
  `wl-paste` as success. Add a unit/integration test for that path.
- The overlay relies on `force-device-scale-factor=1` to avoid fractional-scaling
  clipping. Test at 1.0, 1.25, 1.5 and 2.0; make the strategy configurable.
- The resting overlay pill is mouse-enabled now; verify it does not steal focus or
  block clicks behind it.
- No AT-SPI yet; no focused-field context, no paste verification.
- The formatter is always called for light/medium/high; no fast path.
- The demo seed is for screenshots only (`REVERSEUNO_DEMO=1`); never seed a real
  profile.
- The tray may not appear under Wayland; verify or remove the tray from the promise.
- The desktop entry hardcodes this checkout; packaging must fix it.
- `safeStorage` may be unavailable on a machine without a keyring; surface it.
- No E2E acoustic test; this is the biggest gap.
- No SQLite; history is JSON. Only migrate if it becomes a real scale problem.

## 5. Guardrails

- Keep the app runnable after every change: typecheck, tests, build, and a manual
  launch when the change touches windows, audio or insertion.
- Do not add telemetry, accounts, cloud sync or a subscription.
- Do not replace Deepgram/OpenRouter with a different provider without asking.
- Do not copy Wispr assets, fonts, screenshots or code. Do not use the Wispr name or
  logo in the app.
- Do not edit `~/.config/hypr/bindings.lua` without a timestamped backup; keep the
  install script idempotent and do not duplicate the Right ⌘ bind.
- Never commit keys, `node_modules`, `out/`, logs or user data.
- When you claim a behaviour, cite `file:line` or a test/receipt. Use the
  **CONFIRMED / INFERRED / UNKNOWN** discipline from `TEARDOWN.md`.
- If a task is ambiguous, ask one focused question; otherwise choose the smallest
  change that satisfies the acceptance test.

## 6. Suggested first day

1. Run the baseline commands; confirm the 41 tests and build.
2. Launch the app; hold Right ⌘; confirm the overlay and a paste into a terminal.
3. Build the E2E harness (P0). Record the first receipt.
4. Fix `historyInsert` and the duplicate-paste guard (P0).
5. Add `reverseuno doctor` and the first-run card (P0).
6. Write `docs/BENCHMARKS.md` with the first latency numbers.
7. Only then move to context, dictionary depth, packaging or cross-desktop work.

## 7. Definition of done for this handoff

- `npm run test:e2e` proves known audio → formatted text → target field, with a receipt.
- `reverseuno doctor` gives a green result on this Omarchy/Hyprland machine.
- Paste failures are reported truthfully and never duplicate text.
- A fresh user can install, add keys, test the mic and dictate without the README.
- The formatter has a measured fast path and a small eval set.
- The app still builds, typechecks and passes all tests; the Hub and overlay screenshots
  are updated.
- No secrets, proprietary assets or user data are committed.
