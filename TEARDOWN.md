# Teardown → rebuild

This project is a **clean-room behaviour rebuild**, not a repackage. The reference is
[`az9713/wispr-flow-rea-teardown`](https://github.com/az9713/wispr-flow-rea-teardown),
a static teardown of **Wispr Flow 1.6.1102 on Windows** (Electron shell +
`Wispr Flow Helper.exe`). The original app was not run for this rebuild, and no
Wispr code, assets, keys or trademarks are included here.

Evidence labels used below:

- **CONFIRMED** — present in the published teardown or its pseudocode.
- **INFERRED** — the names/flow make it likely, but the teardown did not prove it.
- **UNKNOWN** — Wispr's server side or an unopened path.
- **OURS** — a deliberate Linux/Deepgram/OpenRouter replacement, not a Wispr claim.

## What Wispr does, per the evidence

| Stage | Evidence | Status | reverseuno |
|---|---|---|---|
| Global hotkey owned by a native helper | `SetWindowsHookExW`, `LowLevelKeyboardProc`, `KeypressEvent` in the helper; main sends `UpdateShortcuts`/`DictationStart`/`DictationStop` | CONFIRMED | Hyprland `Super_R` press/release → CLI → control server. No native hook. |
| 16 kHz mono microphone | `getUserMedia`, `AudioContext({sampleRate:16000})`, `recorderWorklet.js` | CONFIRMED | `getUserMedia` in a hidden renderer, `AudioContext({sampleRate:16000})`, worklet. |
| 40 ms audio frames | worklet buffers 640 samples per message | CONFIRMED | Same 640-sample worklet buffer. |
| Opus 64 kbps / 20 ms, WAV fallback | WebCodecs `AudioEncoder` config `{codec:"opus", sampleRate:16000, channels:1, bitrate:64000}` | CONFIRMED | **OURS:** linear16 PCM because Deepgram consumes raw PCM directly. No Opus encoder. |
| Silent chunks dropped before first speech | code drops silent chunks before the first real audio | CONFIRMED | Pre-roll of 8 frames, VAD threshold, flush on first speech. |
| Streaming ASR over gRPC | `flow_api.v1.TranscriptionService/TranscribeStream`, `init/context/payload/commit` | CONFIRMED | WebSocket to Deepgram Nova-3 (`linear16`, 16 kHz, interim results, keepalive). |
| Batch fallback | whole recording in one unary gRPC call if the stream fails/heartbeats | CONFIRMED | Whole-clip WAV POST to Deepgram `/v1/listen` if the stream failed or returned empty. |
| Raw vs formatted text | `raw_text`, `formatted_text`, `format`/`external_asr_time`/`external_llm_time` | CONFIRMED | Deepgram transcript = raw; OpenRouter cleanup = formatted; both stored. |
| Server-side style controls | `WritingStyle` (FORMAL/CASUAL/VERY_CASUAL/EXCITED/GENZ), `EditingStrength` (VERBATIM/LIGHT/MEDIUM/HEAVY), dictionary, replacements, snippets | CONFIRMED | Cleanup None/Light/Medium/High, same five styles, dictionary, snippets, custom instructions. |
| Screen/app context sent to server | app name, bundle id, URL, focused text box before/after/selected, AX/OCR words, VS Code names, screenshot field (empty in 1.6.1102) | CONFIRMED | **OURS:** app class + window title only. No screen text, no screenshot, no OCR. |
| Clipboard paste + restore | `PasteText`, `PasteViaClipboard`, `ClipboardSnapshot`, `ClipboardRestore`; Ctrl+V, Shift+Insert for Code/Cursor/Windsurf when a flag is on | CONFIRMED | `wl-copy` + `wtype` Ctrl+V; Shift+Insert for terminals/IDE classes. |
| Key timing | all key-downs, `Sleep(50)`, all key-ups; 50 ms after paste | CONFIRMED | `wtype` with a configurable key delay; 80 ms apply delay; 50 ms settle. |
| Per-app restore delay | 5000 ms: VNC/remote desktop; 3000 ms: Outlook/Word/WhatsApp; 500 ms: everything else | CONFIRMED | Same delays, matched against Hyprland class/title. |
| Skip restore if clipboard changed | “Clipboard changed since paste, skipping restoration” | CONFIRMED | Compare current clipboard to what we set; skip if changed. |
| Edit learning | after paste the helper watches the field; edit distance/correction counts stored in `flow.sqlite` | CONFIRMED | Edit a history entry in the hub → word diff → dictionary suggestion/auto-learn. |
| Hint of a native helper for UIA | IUIAutomationTextPattern/ValuePattern; UIPI checks | CONFIRMED | **Not implemented.** AT-SPI is the Linux analogue; see the handoff backlog. |
| UI windows | `hub`, `status`, `overlay`, `scratchpad`, `meeting_recorder` | CONFIRMED (names) | Hub + overlay. Scratchpad/meeting recorder not rebuilt. |
| Default hotkey | not found as a constant | UNKNOWN | **OURS:** Right ⌘ (`Super_R`) on an Apple Magic Keyboard. |
| Cloud models/prompts | server side | UNKNOWN | **OURS:** Deepgram Nova-3 + OpenRouter `google/gemini-2.5-flash` by default. |
| Streaming language detection | server side | UNKNOWN | Deepgram only supports detection in batch; streaming uses `language=multi` for auto. |
| What the server stores | privacy page says audio deleted after transcription unless cloud storage is on | UNKNOWN | Audio is never written to disk here. |

## UI shape

The published help docs describe the desktop **Hub** as a sidebar (Home, Meetings,
Dictionary, Snippets, Style/Insights depending on plan) with Settings/Help at the
bottom, a history list grouped by date, stats, dictionary corrections and snippets.
The **Flow Bar** is a small floating bubble that shows resting/recording/processing,
can be docked, and can hide for an hour. reverseuno mirrors the structure without
copying the visual assets:

- **Hub:** sidebar → Home / Dictionary / Snippets / Style / Settings; stats cards,
  weekly words, recent dictations grouped by day, detail drawer, edit-to-learn.
- **Overlay:** a compact 500×108 dark capsule, waveform, state, timer, cancel/confirm.
- **Style:** four cleanup levels, five writing styles, custom instructions, app context
  toggle, static before/after preview.
- **Visual language:** warm cream paper, ink, a single vermilion signal colour,
  lavender selection/CTA, Instrument Serif display + Inter UI. These are our choices,
  not Wispr assets.

## Deliberate deviations

| Area | Wispr | reverseuno | Why |
|---|---|---|---|
| Platform | Windows/macOS helper | Linux/Hyprland + `wtype`/`wl-copy` | The native helper is Windows PE; Linux needs a different injection path. |
| Audio codec | Opus 64 kbps | PCM16 16 kHz | Deepgram accepts raw PCM; removes an encoder and a failure mode. |
| ASR | Wispr gRPC + Baseten/Modal | Deepgram Nova-3 | Reproducible with the user's existing key; streaming + batch. |
| Formatter | Wispr server prompts | OpenRouter chat completion | Same raw→formatted split, user-controlled model. |
| Context | UI Automation + OCR + screen | app class/title | Linux AT-SPI work is deferred; privacy default is narrower. |
| Verification | UIA field read | none | `wtype` has no read-back; a failed paste is recorded, not retried. |
| Storage | SQLite + 169 migrations | JSON + schema version | Small local dataset; easy export/inspection. SQLite is a backlog item. |
| Meeting recorder | shipped | not rebuilt | Out of scope for the dictation loop. |

## References

- Teardown report: <https://az9713.github.io/wispr-flow-rea-teardown/wispr-flow-teardown.html>
- Ghidra pass: <https://az9713.github.io/wispr-flow-rea-teardown/ghidra-pass.html>
- Development journey: <https://az9713.github.io/wispr-flow-rea-teardown/DEVELOPMENT-JOURNEY.html>
- Wispr help: Hub/Flow Bar/dictionary/Style docs on `docs.wisprflow.ai`
- Unofficial Linux port (used only as a cross-check for Wayland/XWayland behaviour):
  <https://github.com/wispr-flow-linux/wispr-flow-linux>
