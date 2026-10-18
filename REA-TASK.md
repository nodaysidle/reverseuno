# REA-TASK.md

## Step 1 — Ask (answered)

**Target:** the Wispr Flow dictation loop, reconstructed on Linux. The reference is the
published Windows teardown [`az9713/wispr-flow-rea-teardown`](https://github.com/az9713/wispr-flow-rea-teardown),
not a local copy of the proprietary app. No Wispr binary is installed on this machine.

**Location:** reference evidence in the teardown repo (`wispr-flow-teardown.html`,
`ghidra/pseudocode/*`, `flowlite/flowlite.py`); target app in this repo.

**Feature:** hold-to-talk dictation: global hotkey → 16 kHz capture → streaming cloud
STT → formatting → insertion at the cursor → history/dictionary learning.
Secondary: the hub/overlay UI.

**Goal:** C — our own working version, plus an evidence report and spec.

**Platform decisions (user):**

- Cloud STT only.
- Electron + React, Wispr-faithful.
- Right ⌘ (`Super_R`) on an Apple Magic Keyboard.
- Linux / Omarchy / Hyprland / Wayland.

**Deliverables:**

1. Working app in this repo.
2. `TEARDOWN.md` — CONFIRMED / INFERRED / UNKNOWN evidence map.
3. `SPEC.md` — behaviour contract.
4. `README.md` — quick start and limitations.
5. `PROMPT-OPUS-5.5.md` — handoff prompt for the next model.

## Step 2 — Setup (done)

- Node 26.8.1, npm 11, Electron 44.9.0, electron-vite 6, React 19.3.1.
- Deepgram and OpenRouter keys available from the environment and
  `~/.config/whisper-bar/config.env`.
- Hyprland 0.56.2, Wayland, XWayland enabled, `wtype`/`wl-copy`/`grim` installed.
- Existing WhisperBar daemon and Right ⌘ bind discovered; the installer comments the
  old bind and adds reverseuno's.

## Step 3 — Route (done)

Electron. The original is Electron + a native Windows helper. The JavaScript half
needs no disassembler; the native helper's paste path is documented in the teardown's
Ghidra section and was reimplemented with `wtype`/`wl-copy`. No need to run
`analyze-javascript-application` on a 133 MB ASAR we do not have.

## Step 4 — Trace (done)

```
Right ⌘ press
  → Hyprland o.bind → reverseuno CLI → loopback HTTP
  → DictationEngine.start
  → capture renderer getUserMedia → AudioWorklet 640 samples → IPC
  → Deepgram WS linear16 16 kHz → interim/final
Right ⌘ release
  → capture stop → CloseStream → final or batch WAV fallback
  → sanitize / snippet / dictionary → OpenRouter formatting
  → clipboard + wtype Ctrl+V / Shift+Insert → restore
  → history JSON + stats
```

## Step 5 — Report

`TEARDOWN.md` records the evidence and every deliberate deviation. The app itself is
the proof of the mechanism; provider calls and the streaming path were exercised with
the teardown's `flowlite/test.wav`:

- batch: `Let's meet at 6PM on Thursday to review the quarterly report.`
- streaming: same final transcript with interim results.
- formatter: fillers removed and punctuation restored.

**Still UNKNOWN / unproven:** Wispr's server-side models and prompts, the Windows
keyboard hook, the OCR engine, the live feature-flag values, and an automated
acoustic→paste end-to-end run on this machine. These are listed in the handoff.

## Step 6 — Build (done for v0.1)

- App: `src/main`, `src/preload`, `src/renderer` (hub, overlay, capture).
- Tests: 41 passing; typecheck and production build pass.
- CLI, install script, Hyprland binds and window rules.
- Reference screenshots in `docs/screenshots/`.

## Step 7 — Repo

Local git repository. Commit the app, docs, tests and screenshots. Do not commit keys,
`node_modules`, `out`, logs, or the demo user-data directory.

## Next step

Give `PROMPT-OPUS-5.5.md` to the next model. It is scoped to hardening, end-to-end
proof, onboarding, context and packaging — not a rewrite.
