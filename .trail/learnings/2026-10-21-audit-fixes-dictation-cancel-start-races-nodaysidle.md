---
title: "Audit fixes: dictation cancel/start races, skipped state, warm capture"
author: Nodaysidle
date: 2026-10-21
branch: master
cost: { minutes: 17, tokens: 207340 }
touches:
  - package-lock.json
  - package.json
  - src/main/control.ts
  - src/main/dictation.ts
  - src/main/format.ts
  - src/main/index.ts
  - src/main/paste.ts
  - src/main/store.ts
  - src/main/stt/deepgram.ts
  - src/preload/index.ts
---

## Decided
- Every await in DictationEngine.process()/stop() re-checks `this.session === session`; cancel() only nulls the session, so a stale pipeline must bail instead of pasting. start() is also guarded by `this.starting` because `session` is only assigned after the first await.
- 'done'/'skipped'/'error'/'cancelled' are SETTLED_STATES: a new press (and engine.paste) must not wait for the overlay hide timer. 'too short' and 'no speech' use state 'skipped', not 'done' (no green check).
- Light-cleanup LLM pass is skipped for <=3 words with no fillers (format.ts needsFormatting); insertText counts the 180 ms modifier settle from key-up (options.releasedAt) and reuses a clipboard snapshot started in stop().
- Capture keeps the AudioContext/worklet warm between presses (suspended when idle) and flushes the worklet's partial frame on stop; main waits TAIL_MS=120 after key-up.
- Tray menu state is driven from sendHub('state'), the only path every transition takes (sendOverlay is skipped when the overlay hides).

## Tried and ruled out
- Debounced async store writes: sync write of the store is about a millisecond, not worth the flush-on-quit risk.
- Removing the duplicate `SUPER + Super_R` release bind: it is deliberate (Hyprland may still count Super as held).

## Watch out
- Stored settings win over DEFAULT_SETTINGS, so changing a default (format.timeoutMs 4500) does not change an existing install.
- MEASURED on this machine: wl-copy exits in ~6-11 ms but its stdio `close` never fires (the forked server keeps the pipes open), so waiting on close cost the full 900 ms timeout on every paste. runCommand now takes settleOnExit for wl-copy.
- e2e (`npm run test:e2e`) needs ghostty and /tmp/wispr-teardown/flowlite/test.wav; neither exists here, so it has not been run for these changes.
- Store v2 migrates format.timeoutMs 4500 -> 2500 (default lowered); a user who chose 4500 deliberately is migrated too.
- Vitest: a mockImplementationOnce that is never consumed leaks into the next test; wait for the mock call before cancelling.
