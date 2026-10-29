# reverseuno

Push-to-talk dictation for the desktop: hold a key, speak, release, and polished text lands in the focused app.

## Language

### The run

**Session**:
One live hold-speak-release run, from start until it finishes as done, cancelled or error.
_Avoid_: Dictation (for the run)

**Clip**:
The audio captured during one session. Held in memory only, never written to disk.
_Avoid_: Recording, audio file

**Dictation entry**:
The saved history record of a finished session.
_Avoid_: Dictation (alone), history item

**Final text**:
The text a session produces, after snippets, the formatter and the dictionary have been applied. It is what gets inserted and saved.
_Avoid_: Raw text, transcript, output

### Polishing

**Formatter**:
The model-backed stage that rewrites a session's text. "Polish" is friendly UI wording for the same stage.
_Avoid_: Cleaner, refiner

**Cleanup level**:
How heavily the formatter edits: none, light, medium or high. Formatting can also be turned off entirely by a separate master switch; "formatting off" means either one.
_Avoid_: Format level, polish level

**Writing style**:
The register the formatter aims for: formal, casual, very casual, excited or genz.
_Avoid_: Tone

### Windows

**Overlay**:
The bubble that appears while speech-to-text is active, showing the session's state.
_Avoid_: HUD, popup

**Resting pill**:
The idle form of the overlay, shown when no session is running. Optional.
_Avoid_: Idle bar

**Hub**:
The main window, opened from the tray, for history, dictionary, snippets and settings.
_Avoid_: Dashboard, main window

### Dictionary

**Dictionary entry**:
A personal word the app should get right. Comes in two kinds: a spelling or a correction.
_Avoid_: Vocabulary, boost term

**Spelling**:
A dictionary entry with only a word: the spelling to enforce.

**Correction**:
A dictionary entry that maps something you say (a mis-hearing) to what should be written.
_Avoid_: Replacement

**Learned edit**:
A change you make to a dictation entry's final text that the app turns into a correction, or offers as a suggestion when auto-learn is off.
_Avoid_: Learned correction

**Keyterms**:
The ranked subset of dictionary entries sent to speech-to-text to bias recognition.
_Avoid_: Preferred spellings, boost terms
