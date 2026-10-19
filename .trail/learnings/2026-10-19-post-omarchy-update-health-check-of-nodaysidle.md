---
title: Post-Omarchy-update health check of arch daemons and bot tooling
author: Nodaysidle
date: 2026-10-19
branch: master
cost: { minutes: 3, tokens: 66421 }
touches:
  - PROMPT-OPUS-5.5.md
  - REA-TASK.md
  - TEARDOWN.md
  - bin/reverseuno
  - package.json
  - src/main/control.ts
  - src/main/dictation.ts
  - src/main/index.ts
  - src/main/paste.ts
  - src/preload/capture.ts
---

## Decided
- Quick health check after an Omarchy update: `systemctl --user --failed`, `systemctl --user list-timers` (nds-tasks, nds-watch, vault-fold), `journalctl --user -u <unit> --since -6h`, `fish -lic 'command -v ...'` for the bot tools, and a one-line `subagent-exec claude|codex "Reply with exactly: RESULT ok" <scratch>` smoke test.
- After the update to v4.0.5 (2026-10-19) everything passed: timers OK, vault in sync with origin, claude and codex engines OK.

## Tried and ruled out
- `deepseek` showing as missing on PATH is not a fault. subagent-exec runs it through `claude` pointed at DeepSeek's API.
- The failed system units systemd-pcrproduct, systemd-pcrlogin@1000 and systemd-tpm2-setup-early are TPM measurement noise and don't affect anything.
- The GTK "current/theme/gtk3.css" warning was already there before v4.0.5.

## Watch out
- The grok-bot desktop app does NOT autostart. It is opened from the launcher (gtk-launch grok-bot.desktop), so after a reboot it has to be opened by hand.
- `~/.config/gtk-{3,4}.0/gtk.css` import `../omarchy/current/theme/*`, but Omarchy now keeps the theme in `~/.local/state/omarchy/current/theme`, so GTK apps lose theme colours. Not fixed yet (offered to Alan).
- Never read `~/.config/nodaysidle/*.key` or `~/.grokbot/` (vault data-line rule 9).
