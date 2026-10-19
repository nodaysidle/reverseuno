---
title: Push-to-talk paste reliability and OpenRouter STT on Hyprland 0.56 Lua
author: Nodaysidle
date: 2026-10-19
branch: master
cost: { minutes: 23, tokens: 358039 }
touches:
  - PROMPT-OPUS-5.5.md
  - REA-TASK.md
  - README.md
  - SPEC.md
  - TEARDOWN.md
  - bin/reverseuno
  - package.json
  - resources/tray.png
  - resources/tray.svg
  - scripts/install-hyprland.py
---

## Decided
- Push-to-talk stays hold→speak→release=paste on Super_R. Mouse entry points (hub, tray, rest pill) toggle instead, because they have no key release; hub-started sessions save to history (insert=false) since the hub itself has focus.
- The session records the Hyprland window address at key-down; insertText refocuses it before pasting and refuses to type elsewhere (text left on clipboard) if focus can't be returned.
- Overlay is focusable:false, click-through while recording, Hyprland rule no_focus. Cancel is SUPER+Delete (SUPER+Escape is Omarchy's system menu, SUPER+BackSpace is taken).
- Formatter is OpenRouter-only; the DeepSeek/openai-compatible branches were unreachable from the UI and were removed.
- OpenRouter STT: POST /audio/transcriptions with JSON {model, input_audio:{data:base64 wav, format:"wav"}, language?, prompt?}. Models come from /models?output_modalities=transcription (not in the default list).

## Tried and ruled out
- `hyprctl dispatch focuswindow address:…` fails on Hyprland 0.56 with a Lua config: dispatch args are Lua. Use `hl.dsp.focus({ window = "address:0x…" })` and `hl.dsp.window.move({ x, y, window = "address:…" })`; keep the legacy form as a fallback.
- OpenRouter's /models is public, so it can't validate a key; /key returns 401 for bad keys.
- Deepgram TTS WAVs (used to make E2E clips) have 0x7FFF… streaming size fields; OpenRouter STT returns a bare "Provider returned 400" for them. Rewrite the header before using one.

## Watch out
- The E2E target app used to re-grab focus every 500 ms, which hid the focus bug. It's off by default now (E2E_STEAL_FOCUS moves focus to a decoy mid-dictation); keep it that way.
- Settings stored in ~/.config/reverseuno/store.json win over DEFAULT_SETTINGS, so changing a default doesn't change an existing install.
