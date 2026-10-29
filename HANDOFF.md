# HANDOFF

## Goal
Rebuild the reverseuno Hub and Overlay as native Qt windows. The Electron engine stays, windowless, behind them. Autopaste must keep working.

## Done
- All 8 plan tasks are complete and reviewed on branch `nds/copy-plural-reverseuno`. It is pushed to origin; the last commit is `3e32547` (Escape hides the Hub). It is not merged and has no PR.
- The new build is running from this repo: engine `electron --hidden .` plus `qt/reverseuno-ui`, replacing the old process. The user confirmed it works, including the pill and the Hub.
- 116 engine tests and 33 Qt tests pass. The e2e (`E2E_WITH_UI=1 E2E_EXPECT=fox,dog node scripts/e2e.mjs <wav>`) passes every check.
- Escape closes the Hub through a `Shortcut` in `qt/qml/Hub.qml`. A `Keys` handler on the content item didn't work, because it needs focus. The user confirmed Esc works.
- Plan: `docs/superpowers/plans/2026-10-29-qt-windows.md`. Spec: `docs/superpowers/specs/2026-10-29-qt-windows-design.md`. Terms: `GLOSSARY.md`. The ledger and reports are in `.superpowers/sdd/2026-10-29-qt-windows/` (git-ignored).

## Left
- Merge to `master` or open a PR, only when the user asks.
- Velocitty garbles `wtype` input and ignores shift-insert and ctrl-shift-v. This predates the branch and affects autopaste there. The user's store has `insert.mode: "type"`.
- Sound cues were not ported to Qt.
- Not verified live: a dictation through the real microphone on the new build, a theme switch, the export dialog, and a package install.
- Run `scripts/install-hyprland.py` to update the window rules.
- The engine was started with `setsid`, so it won't start at login until it is launched from the app launcher or `scripts/install-local.sh` is re-run.
- Small follow-ups: a Host-header check on the control API, an orphaned Qt app after an engine crash, the `format.baseUrl` plus `testProvider` key redirect risk, the hard-coded `paths.cli`, dead overlay dependencies in the engine, and Esc closing the Hub while a dropdown is open.

## Key files
`src/main/{control,api,supervisor,index,dictation}.ts`, `qt/src/*`, `qt/qml/*`, `scripts/{e2e.mjs,install-local.sh,install-hyprland.py}`, `packaging/*`.

## Open questions
Merge or PR? Delete `.superpowers/sdd/...`? A close button in the Hub, or is Esc enough?
