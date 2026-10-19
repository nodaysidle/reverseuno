---
title: Right Super release bind needs the SUPER-held variant
author: Nodaysidle
date: 2026-10-19
branch: master
cost: { minutes: 10, tokens: 81890 }
touches:
  - PROMPT-OPUS-5.5.md
  - REA-TASK.md
  - TEARDOWN.md
  - resources/tray.png
  - resources/tray.svg
  - scripts/install-hyprland.py
  - src/main/control.ts
  - README.md
  - SPEC.md
  - src/main/index.ts
---

## Decided
- Push-to-talk binds: `Super_R` press -> start, and release bound twice: `Super_R` (modmask 0) and `SUPER + Super_R` (modmask 64), both -> stop. A duplicate stop is ignored (state is no longer listening).
- Every POST to the control server logs `[control] request {path}`, so app.log shows whether the key binds fired.

## Tried and ruled out
- Only the mod-less release bind: start fired but stop never did on a real Apple Magic Keyboard (Right Cmd = Super_R); the recording ran until stopped from the GUI. After adding the SUPER-held variant the owner confirmed it works, with exactly one /stop per release.
- wtype -P Super_R does not trigger Hyprland binds, so the key path can't be tested from a script; ask the user to press the key.

## Watch out
- Dictionary entries without a correction now enforce their exact spelling (applyDictionary); don't add everyday English words to the developer pack.
