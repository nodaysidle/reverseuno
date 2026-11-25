# reverseuno

Push-to-talk dictation for Hyprland: hold a key, speak, release, and the text lands in the window you were in.

## Language

**Session**:
One live hold-speak-release run, from start until it finishes as done, cancelled or error.
_Avoid_: Dictation (for the run)

**Clip**:
The audio captured during one session. Held in memory only, never written to disk.
_Avoid_: Recording, audio file

**Transcript**:
The text Deepgram returns for a session. It is what gets inserted and shown.
_Avoid_: Final text, raw text, output

**Target window**:
The Hyprland window that was active when the session started. Insertion goes there, and focus is returned to it first.
_Avoid_: Active window (that is whatever is active now)

**Insert mode**:
How the transcript reaches the target window: paste, type, or copy only.
_Avoid_: Paste mode (it is only one of three)

**Stream**:
The live WebSocket to Deepgram while the key is held.

**Batch request**:
One request with the whole clip. The fallback when the stream fails or returns nothing, and what `transcribe` uses.
