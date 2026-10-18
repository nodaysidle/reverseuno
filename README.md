# reverseuno

**Hold Right ⌘, speak, release. The polished text lands at your cursor.**

A Linux-native, clean-room rebuild of the Wispr Flow dictation loop, shaped around
Omarchy/Hyprland and an Apple Magic Keyboard. Cloud STT (Deepgram Nova-3), cloud
formatting (OpenRouter), a local hub for history/dictionary/snippets/style, and a
compact Flow-Bar-style overlay.

![Hub](docs/screenshots/hub-home.png)

![Overlay](docs/screenshots/overlay-listening.png)

> reverseuno is an independent clean-room project. It is not affiliated with Wispr
> AI, Inc. and ships no Wispr code, assets, keys or trademarks. The behaviour was
> reconstructed from a published teardown and public product documentation.

---

## What works today

- **Right ⌘ push-to-talk.** `Super_R` press starts, release stops. The installer
  comments out the old WhisperBar bind on the same key so the two do not double-fire.
- **16 kHz mono capture** in an Electron renderer with an `AudioWorklet`, 640-sample
  (40 ms) frames — the same shape as the original Wispr recorder.
- **Deepgram Nova-3 streaming** over a WebSocket with interim results, keepalive,
  keyterms from your dictionary, and a whole-clip batch fallback if the socket dies.
- **OpenRouter formatting** with four cleanup levels (None/Light/Medium/High), five
  writing styles, custom instructions, app/title context, a hard timeout, and a raw
  fallback.
- **Wispr-style insertion:** clipboard snapshot → `wl-copy` → `wtype` Ctrl+V or
  Shift+Insert → app-specific restore delay (0.5 s / 3 s / 5 s) → skip restore if the
  clipboard changed. Falls back to direct typing if the clipboard is unavailable.
- **Hub UI:** Home stats/history/search, Dictionary, Snippets, Style, Settings,
  history detail drawer, edit-to-learn dictionary suggestions.
- **Overlay UI:** compact dark pill, live waveform, interim transcript, timer, cancel
  and confirm, processing/done/error states, optional resting pill.
- **Local-first data:** history, dictionary, snippets and settings in
  `~/.config/reverseuno/`; no account, no telemetry; audio is never written to disk.
- **41 unit tests** for text cleanup, snippets, dictionary corrections, edit
  learning, stats, WAV handling, paste rules and the formatter prompt.

## Quick start

### 1. Keys

Use one of these, in priority order:

1. **In the app:** Settings → Providers → paste a Deepgram key and an OpenRouter key.
2. **Environment:** `DEEPGRAM_API_KEY`, `OPENROUTER_API_KEY`.
3. **Existing WhisperBar config:** reverseuno can read
   `~/.config/whisper-bar/config.env` as a fallback. Keys never leave the machine
   except to the provider.

### 2. Run

```bash
npm install
npm run build
npm start
```

`npm start` launches the built app. For development with HMR:

```bash
npm run dev
```

### 3. Install the CLI, desktop entry and Right ⌘ bind

```bash
bash scripts/install-local.sh
```

This:

- symlinks `bin/reverseuno` to `~/.local/bin/reverseuno`;
- writes `~/.local/share/applications/reverseuno.desktop`;
- adds **Apps → reverseuno** to the Omarchy menu (idempotent);
- backs up `~/.config/hypr/bindings.lua` and adds:

```lua
o.bind("Super_R", "reverseuno hold", "/home/arch/.local/bin/reverseuno start")
o.bind("Super_R", "reverseuno release", "/home/arch/.local/bin/reverseuno stop", { release = true })
```

- adds floating-window rules for the 1180×820 hub and the 500×108 overlay;
- comments out the active WhisperBar `Super_R` bind;
- reloads Hyprland.

Then hold **Right ⌘**, speak, and release. The app is also listed under
**Omarchy menu → Apps → reverseuno**.

### CLI

```bash
reverseuno start          # press
reverseuno stop           # release → transcribe → format → paste
reverseuno toggle
reverseuno cancel
reverseuno state
reverseuno show settings
reverseuno paste "text"   # insert text at the focused cursor
reverseuno lang cycle
reverseuno quit
```

The CLI talks to `127.0.0.1:47100` (or the next free port) with a per-run bearer
token stored in `~/.config/reverseuno/control.json` (mode 0600).

## Testing

```bash
npm test                 # 41 unit tests
npx tsc --noEmit         # typecheck
npm run build            # main + preload + three renderer pages

# Provider checks (reads env or ~/.config/whisper-bar/config.env)
npx tsx scripts/test-deepgram.ts
npx tsx scripts/test-format.ts
npx tsx scripts/test-stream.ts   # real-time 40 ms frames over the WebSocket
```

Verified on this machine:

- Deepgram batch: `Let's meet at 6PM on Thursday to review the quarterly report.`
- Deepgram streaming: same final transcript, interim results visible.
- OpenRouter: `um so i think...` → `I think we should move the review to Thursday at 6:00 and send the numbers before that.`
- Overlay and hub render correctly on Hyprland at 1:1 scale.

## Architecture

```text
Right ⌘ (Hyprland bind)
        │
        ▼
reverseuno CLI ──HTTP──▶ Electron main (DictationEngine)
                              │
        ┌─────────────────────┼────────────────────────┐
        ▼                     ▼                        ▼
  capture renderer      Deepgram WS               formatter (OpenRouter)
  getUserMedia          linear16 16 kHz           cleanup + style + dictionary
  AudioWorklet          interim + final           timeout + raw fallback
  640 samples           batch fallback
        │
        ▼
  clipboard + wtype Ctrl+V / Shift+Insert
  restore after 500/3000/5000 ms
        │
        ▼
  history (JSON), dictionary, snippets, stats
```

Key files:

| Path | Responsibility |
|---|---|
| `src/main/dictation.ts` | State machine, streaming, fallback, formatting, insertion, history |
| `src/main/stt/deepgram.ts` | WebSocket + batch Deepgram client |
| `src/main/format.ts` | Formatter prompt and OpenRouter/DeepSeek call |
| `src/main/paste.ts` | Hyprland window lookup, clipboard snapshot/restore, `wtype` rules |
| `src/main/index.ts` | Windows, IPC, control server, tray, autostart |
| `src/renderer/hub/` | Hub React UI |
| `src/renderer/overlay/` | Flow-Bar overlay |
| `src/renderer/capture/` | Hidden mic renderer + AudioWorklet |
| `src/shared/` | Types, defaults, text cleanup, stats, WAV |
| `scripts/install-hyprland.py` | Idempotent Hyprland bind + window rules |
| `PROMPT-OPUS-5.5.md` | Handoff prompt for the next model |

## Known gaps

- The **acoustic end-to-end path** (known audio → STT → format → paste) has not been
  proven automatically. The provider stages and the UI are tested; a virtual-source
  E2E harness is the next step.
- No **paste verification** (Wispr uses Windows UI Automation; Linux would need
  AT-SPI). A failed paste is reported in history but not retried.
- No **focused-text-box context** (before/after cursor, selected text). Only app class
  and window title are sent to the formatter.
- **Cloud only** by design: no offline Whisper, no local model.
- **Hyprland only.** No X11/GNOME/KDE global-shortcut or overlay path.
- No packaging (AppImage/deb/AUR), auto-update or signed releases. The desktop entry
  points at this checkout.
- `safeStorage` can fall back to a plainly marked value on systems without a keyring.
- No meeting recorder, notepad/scratchpad, insights or mobile sync.
- The hub uses JSON storage; Wispr uses SQLite with migrations.

See [`SPEC.md`](SPEC.md) for the full behaviour contract and [`TEARDOWN.md`](TEARDOWN.md)
for the evidence behind each copied behaviour.

## Privacy

- Audio is streamed to Deepgram and held in memory for the batch fallback only.
- The cleaned transcript is sent to OpenRouter (or DeepSeek) when formatting is on.
- App class/window title can be sent as tone context; disable it in Style.
- History, dictionary, snippets and settings stay in `~/.config/reverseuno/`.
- No analytics, no account, no telemetry.

## License

MIT. See [`LICENSE`](LICENSE).
