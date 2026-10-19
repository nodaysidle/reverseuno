---
title: Hub follows the Omarchy theme
author: Nodaysidle
date: 2026-10-19
branch: master
cost: { minutes: 1, tokens: 0 }
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
- The active palette is `~/.local/state/omarchy/current/theme/colors.toml` (not ~/.config/omarchy/current, which no longer exists). Theme name is in `current/theme.name`.
- `omarchy-theme-set` replaces `current/theme` wholesale, so watch the parent `current/` dir and debounce (src/main/theme.ts).
- Hub gets eleven `--t-*` colors; everything else is derived with color-mix in `src/renderer/styles/base.css` under `:root[data-theme='omarchy']`. No hard-coded colors outside `:root` in hub.css/base.css; keep it that way.
- `--solid`/`--cream` are the fill and text of inverted chips (nav active, toggles, primary button). On dark themes `--solid` = accent, text = best-contrast pick (`onAccent`). Large inverted cards (.rhythm-card, .preview-card, .help-code) use the deep background instead.
- `appearance.theme` = 'omarchy' (default) | 'paper' (original look).

## Tried and ruled out
- Making `--solid` the foreground on dark themes: renders glaring white slabs.
- Scrolling the hub with `wtype -k Next` for screenshots: no effect. Launch electron with `--remote-debugging-port` and use CDP `scrollIntoView` + `Page.captureScreenshot`.

## Watch out
- colors.toml keys include digits (`color1`); the parser regex must allow `[a-z0-9_]`.
- `pkill -f '<electron path> <repo>'` in a Bash call matches the shell's own command line and kills it (exit 144); kill by pid instead.
