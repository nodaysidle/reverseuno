# reverseuno behaviour spec

The contract the code follows. Where code and this file disagree, fix one of them deliberately.

## Scope

Hold a key, speak, release: the text is inserted into the window that was active when the key went
down. One native binary (C++, Qt Quick). Deepgram `nova-3` is the only speech engine. No formatter,
dictionary, snippets, stats or saved history: the window lists this run's transcripts, in memory.

## Session

`idle -> listening -> transcribing -> inserting -> done`, or `error`. `start`, `stop`, `cancel` and
`toggle` act on one session at a time.

- `start` while a session runs is refused ("busy"). Without a key or a microphone it ends in `error`.
- A clip under 250 ms, or with no frame above the speech threshold, produces no text ("Too short" /
  "No speech heard") and returns to `idle`.
- Recording stops itself at 5 minutes.
- `cancel` aborts capture, the socket and any request, and returns to `idle`. `stop` outside `listening` is refused.
- An error keeps its reason in the window until the next `start`.

## Audio

`QAudioSource`, 16 kHz mono int16, in memory only (never on disk). Frames feed the stream live and are kept
for the single-request fallback.

## Speech to text (model `nova-3` everywhere)

- Stream: `wss://api.deepgram.com/v1/listen?model=nova-3&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&smart_format=true&punctuate=true&endpointing=300&utterance_end_ms=1000&language=<code|multi>`,
  header `Authorization: Token <key>`, `{"type":"KeepAlive"}` every 8 s. Frames recorded while the socket
  connects are queued and flushed on open. `is_final` segments accumulate; interim text is shown. On stop
  send `{"type":"CloseStream"}` and wait for `Metadata` or 2.5 s.
- Batch: `POST https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&punctuate=true&language=<code>`
  (`detect_language=true` for auto) with the WAV clip. Used when the stream failed or gave no text, and by `reverseuno transcribe`.
- `REVERSEUNO_STT_URL` and `REVERSEUNO_STT_WS_URL` override the endpoints (tests).
- A 401 or 403 reads "Deepgram rejected the API key"; the key is never logged or echoed.

## Insertion

Modes: `paste` (default), `type`, `copy`. The active Hyprland window is read at key down
(`hyprctl -j activewindow`) and refocused if it changed (`hyprctl dispatch focuswindow address:<addr>`, then the
Lua form). If focus cannot be restored, or paste fails, the text stays on the clipboard and an error is reported.

Paste: snapshot the clipboard, `wl-copy` the text, Ctrl+V via `wtype` (Shift+Insert when the window class is a
terminal or code editor: ghostty, alacritty, kitty, foot, wezterm, xterm, konsole, code, cursor, zed, windsurf,
jetbrains and its IDEs), then after 500 ms restore the snapshot only if the clipboard still equals what was written.
`type` pipes the text to `wtype -`. The text waits until 180 ms after the key came up.

## Settings and key

`~/.config/reverseuno/config.json`, mode 0600, written atomically; a corrupt file is moved aside. Key:
`DEEPGRAM_API_KEY`, else the saved key. The window never reads the key back.

## Setup, hotkeys and autostart

A Setup view lists: key (write-only field), microphone (picker), tools (`wl-copy`, `wl-paste`, `wtype`, `hyprctl`,
`ffmpeg`, with the `omarchy pkg add ...` command for the missing ones), hotkeys, autostart. It opens by itself
when the key, microphone or a tool is missing, and from the Setup button or the tray menu.

Hotkeys (keysym names, so they work on every layout): hold `F8` = start on press, stop on release; `SUPER + U` toggles;
`F12` cancels. `Install hotkeys` and `Install autostart` each maintain one block between `-- >>> reverseuno` and
`-- <<< reverseuno` in `bindings.lua` (`o.bind_hold`, `o.bind`, and an `o.window` rule: float, centre, 980x660) and
`autostart.lua` (`o.launch_on_start("<binary> daemon")`), using the absolute binary path (`/usr/bin/reverseuno`, else
`PATH`, else the running binary). An existing marked block, including an old one, is replaced in place and nothing
outside the markers changes. Before a change the file is copied to `<file>.bak.<epoch>`, written atomically (through a
symlink if it is one), then `hyprctl reload` and `hyprctl configerrors` run; a new error restores the old file and
reloads again, and the error is shown in the window. A second install changes nothing. `REVERSEUNO_HYPR_DIR`
overrides `~/.config/hypr`. Right Super is never bound.

## Tray

A StatusNotifierItem (`Qt.labs.platform` `SystemTrayIcon`) shows `src/tray.svg` recoloured to the theme accent and
redrawn when the accent changes; a white badge while listening, a dimmed N while transcribing, a red badge on error.
The tooltip is the state word. Left click shows or hides the window; the menu has Start/Stop dictation, Open reverseuno,
Setup and Quit.

## Control

Single instance on the session bus, `io.github.nodaysidle.Reverseuno`, object `/`, methods `Start`, `Stop`,
`Toggle`, `Cancel`, `Show`, `Quit`. CLI: see the README. Non-window commands run with `QT_QPA_PLATFORM=offscreen`.

## Window

One `ApplicationWindow` (title and app id `reverseuno`), Material Dark, accent and corner radius from the Omarchy
theme and Hyprland, strings through the Omarchy language. Shows the state, a level meter, interim and last
transcript, this run's history, an error banner for missing tools, key, microphone or network, and settings.
Keys: Space, Ctrl+C or Enter, S or Ctrl+comma, `?`, Q or Esc. Closing the window hides it.

## Acceptance

`bin/build` has no compiler or QML warnings. `bin/test` passes. A real microphone and real paste can only
be checked by hand on a Hyprland session.
