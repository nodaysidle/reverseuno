# AGENTS.md

<!-- delegation:v1 -->
## Delegation — Sonnet builds, Haiku swarms, Opus reviews

Launch with `claude --model sonnet --advisor opus`. Sonnet 5.5 runs the session, coordinates, writes the code and applies fixes.

- **Delegate every repo search and test run to the Haiku 5.5 subagents** to keep the main context small:
  - `search`: maps the repo (read-only).
  - `grep`: finds call sites and occurrences in its own context.
  - `audit`: checks the diff and runs the tests in a throwaway worktree. The worktree branches from the default branch, so put the `git diff` output in the audit prompt.
- **Consult the Opus advisor only at three moments:** before a plan locks, when the same error shows up twice, and before calling it done (it signs off on the synthesized diff).
- **Micro-forks** (which file, which tool, retry or stop) need no thinker. Decide them directly, without spawning agents or calling the advisor. When app code here needs routing like this at runtime, use TypeSafe Jev (the `typesafe` skill).

## Agent skills

### Issue tracker

Issues live in GitHub Issues for `nodaysidle/reverseuno` (via `gh`). See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: one `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
