# CLAUDE.md — reverseuno

## 1. Execution Posture (Opus 5.5 Directives)
- Prioritize direct code execution, IPC validation, and tool use over conversational narration.
- Do not provide mid-task status summaries or narrate routine file reads.
- Continue autonomously through multi-step dictation/formatting refactors until tests pass; only pause if blocked by a critical ambiguity.
- The smallest change that fully satisfies the task (YAGNI). Do not add unsolicited state machines, extra middleware, or speculative features.

## 2. Command Matrix
*Execute these exact commands:*

| Command | Purpose |
|---------|---------|
| `npm run dev` | Run Electron development instance (`electron-vite dev`) |
| `npm run build` | Build Electron main, preload, and renderer bundles |
| `npm run typecheck` | Strict TypeScript check (`tsc --noEmit`) |
| `npm test` | Run Vitest unit tests |
| `npm run test:deepgram` | Test live/mock Deepgram streaming integration |
| `npm run test:format` | Test LLM formatting pipeline |
| `npm run test:stream` | Test audio chunk streaming |
| `npm run test:e2e` | End-to-end integration test run |

## 3. Architecture & Dictation State Loop
- **Product**: Linux-native, Wispr-Flow-shaped dictation app (Hold Right Super $\to$ Speak $\to$ Release $\to$ Text inserted at cursor).
- **Core Loop**: `idle` $\to$ `listening` $\to$ `transcribing` $\to$ `formatting` $\to$ `inserting` $\to$ `done`.
- **Stack**: Electron 44, TypeScript 5.9, Vite 8 (`electron-vite`), Vitest, Anthropic SDK, WebSocket.
- **Key Modules (`src/`)**:
  - `src/main/dictation.ts` — Dictation state machine, audio capture trigger, session orchestration.
  - `src/main/stt/` — Streaming transcription clients (Deepgram / OpenRouter).
  - `src/main/format.ts` — Text polishing and punctuation using Anthropic/OpenRouter LLMs.
  - `src/main/paste.ts` — Linux Wayland / X11 active cursor text injection.
  - `src/main/control.ts` — Local IPC/HTTP control API consumed by `reverseuno-ui` (Qt overlay).
  - `src/preload/` — Secure ContextBridge between Electron main and UI.
- **Privacy & Safety Constraints**:
  - No audio stored on disk; stream strictly in-memory.
  - No analytics or remote telemetry.
  - Input grabs (Right Super / hotkeys) must never lock the Wayland compositor or X11 server on failure.

## 4. Negative Constraints (Anti-Defaults)
- Never bypass TypeScript typing; zero `any`.
- Never hardcode API keys or credentials; use local secrets manager (`src/main/secrets.ts`).
- Never leave mock stubs or incomplete handlers in the dictation pipeline.

## 5. Modular Imports
@AGENTS.md
@SPEC.md
@GLOSSARY.md
