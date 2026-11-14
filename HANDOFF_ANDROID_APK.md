# HANDOFF: Android APK

## What this is
The Android app of reverseuno, in `android/` (Kotlin, Jetpack Compose, minSdk 31, targetSdk 36, package `com.nodaysidle.reverseuno`, debug builds `.debug`). A floating pill (an accessibility overlay) records, transcribes and inserts into the focused field. The companion app has History, Dictionary, Snippets, Style and Settings.

**Version: 0.2.0, versionCode 2** (`android/app/build.gradle.kts`).

The Linux desktop app in `src/` and `qt/` is a separate thing with its own version (`package.json` 0.1.0) and its own handoff, `HANDOFF.md`. Read `android/README.md` first for build and install.

## Branches and versions (2026-11-14)
- `master` is still the pre-Typeless-parity state (Android 0.1.0). Nothing below is merged and no PR is open.
- Three stacked branches, all pushed and equal to origin. Each builds on the previous one:
  1. `feat/phase1-pill-reliability`, head `5153fa1`: Phase 1, Steps 1-5.
  2. `feat/opus-upload`, head `0ae4273`: Ogg/Opus uploads.
  3. `feat/phase2-companion-ui`, head `ae33c1c` plus this handoff: Phase 2 UI and the 0.2.0 version bump. **This is the newest code.**
- To land the work: open one PR from `feat/phase2-companion-ui` into `master` (it contains all 12 commits), or three stacked PRs. Ask the user first.

## Android 0.2.0: what was done (2026-11-13 to 2026-11-14)
Goal: bring the Android pill and companion app up to the reverse-engineered Typeless 2.12.0 (`com.typeless.mobile`). It stays a floating pill, not a keyboard (IME). The analysis is in `/home/arch/dev/nodaysidle/brez_tipk_apk/`: `TYPELESS_UX_ARCHITECTURE.md` (spec, sections 1-7), `reverseuno_profile.md`, `pulled_apk/` (Typeless APKs plus REA evidence). The user asked for one step at a time, one commit per step, each built, unit-tested and tried on the phone before the next.

| Commit | What |
|---|---|
| `dcf312f` | Recordings stay in `filesDir/recordings/` until transcription succeeds. History entries get `status` (pending/done/failed/empty), `audioPath`, `attempts`, `lastError` (store v3, migration test). Failed dictations retry from the pill ("... tap to retry") or History. A streaming buffer overflow sends the full WAV instead of losing audio. |
| `ef523e2` | OpenRouter is a first-class provider (`openrouter`, one upload after you stop, default on fresh installs). Free-text model ID plus a picker from OpenRouter's model list. Default model `openai/gpt-4o-mini-transcribe`. |
| `21dc527` | The retry pill stays until the dictation is resolved, a new recording starts, or it is dismissed. The retry ID is process-wide because MIUI recreates the accessibility service. |
| `f4e107b` | Capture: exclusive transient audio focus (focus loss ends and transcribes), muted or blocked mic detection ("Microphone blocked" / "Mic in use by another app"), AudioRecord reopen on a dead stream, partial wake lock capped at 5 min + 90 s. Screen off mid-recording stops and saves "Screen turned off · tap to retry". |
| `3f4f8f7` | Insertion: `commitText` through the accessibility input connection first on Android 13+, with a read-back check, then the old chain (set text, paste, re-query focus, clipboard). Spacing and double-space fixes. |
| `5153fa1` | Pill is `TYPE_ACCESSIBILITY_OVERLAY` (2032) while the service runs, so "display over other apps" is optional. Docks above the keyboard, hidden on the lock screen, drag to a bottom target to dismiss it in that app. |
| `0ae4273` | OpenRouter uploads are Ogg/Opus at 32 kbps (291 KB to 49 KB); the WAV stays the source of truth and is resent on 400/415/422. Settings switch "Compress uploads" (on). |
| `0d32bb6` | History: app names instead of package names (`<queries>` for Android 11+ visibility), "Not transcribed" filter, "Keep dictations for" retention (failed entries are never removed). |
| `f25d9d3` | Dictionary: All / Auto / Manual tabs with counts, sparkle icon on learned words, long-press multi-select and batch delete. |
| `6707d6b` | Settings: overlay permission optional (setup is 3 steps), "Bubble position > Reset", disabled-button grey box fix. |
| `ae33c1c` | Version 0.2.0 (versionCode 2). |

Unit tests: 96 pass (53 before this work). Gate: `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`.

## Phone state (Xiaomi Mi 10T, adb serial `a2f3cdaa`, MIUI, Android 12 / API 31)
- Release `com.nodaysidle.reverseuno` 0.2.0 is installed and is the **only** enabled accessibility service. It kept its old data.
- Typeless is **uninstalled**. Its APK set (base plus 3 splits) is in `brez_tipk_apk/pulled_apk/com.typeless.mobile/` if it ever needs to come back.
- The `.debug` test copy is uninstalled.
- **Leave alone (user's instruction):** `org.futo.voiceinput`, `com.piu.vtranscript`, and Android's default voice recognizer (Google). Do not remove or change them.
- The release app's Transcription provider is still **Deepgram Nova-3 (live)** (its old setting; updates keep user settings). Its OpenRouter, Deepgram and xAI keys are saved. The user can switch to OpenRouter in Settings > Transcription.
- Verified on 0.2.0 after install: a live dictation into Chrome was transcribed (Deepgram) and inserted, no crash, pill drawn as type 2032.

## Not verified, or not done
- `commitText` (Android 13+): unit tests only; the phone is Android 12. The user chose no emulator.
- Not tried on the phone: Bluetooth headset switching mid-recording, a call or alarm taking audio focus, another app holding the mic, the Ogg-to-WAV resend.
- Not tried on the 0.2.0 release build: OpenRouter transcription and Opus (both worked on the debug build).
- Not built: re-insert from History into another app (needs a design), text cleanup through OpenRouter (the user has not picked a model). Cleanup still needs an Anthropic key, DeepSeek as fallback.
- History rows show a cleanup label like "Light" even when cleanup did not run.

## Phone testing notes
- Never `am force-stop` the app on MIUI: it clears the accessibility setting and "display over other apps".
- A secure lock screen blocks adb input; ask the user to unlock. `adb shell wm dismiss-keyguard` only helps without a PIN.
- Release signing: `~/.config/reverseuno/android-signing.properties` (key in `~/.config/reverseuno/android-release.jks`, cert SHA-256 `14e400f6...`). Same key as the installed app, so `adb install -r` updates in place.
- Never read, print or type the user's API keys; the user enters them in Settings.
- Test helpers (scratch, may be pruned): `~/.hermes/cache/scratch/reverseuno-test/` (`gate.sh`, `page.sh`, `dictate.sh`, `say1.mp3`).

## Earlier: Android 0.1.0 (2026-10-29)
- The dark/white SuperWhisper-style UI and streaming STT lived only on the unpushed branch `feat/android-streaming` and an installed debug build (`com.nodaysidle.reverseuno.debug`). Both are now merged into `master`. The debug app is uninstalled, and the release app `com.nodaysidle.reverseuno` was updated in place with its data kept.
- Redesign on top, at the user's request (antislop dials ENERGY 1 / RHYTHM 1 / MOTION 1):
  - No logo or name bar.
  - Home shows only a status line, search and the dictation list. The stat cards moved to Settings > Usage.
  - No uppercase eyebrows above page titles.
  - The Style tab uses a pen icon.
  - No emoji or em dashes in the UI copy.
- The floating bubble is a rounded square drawn with the logo's five bars, the center bar in the logo's orange-red `#F45B36`, the one accent. It replaced the round pill with a stock mic. The same mark is the in-app record button.
- The bubble's visibility, keyboard anchoring, tap/long-press and edge snapping were already in place on a Wispr Flow model and were left unchanged.
- Verified on the Mi 10T (stock MIUI 14, bootloader locked, verified boot green). A dictation through the bubble into Messages was inserted (OpenRouter `gpt-4o-mini-transcribe` + Anthropic cleanup), with no crashes. The 53 Android unit tests pass.
- The phone and the desktop both stream through Deepgram `nova-3`, with Haiku cleanup at Light. The bubble can cover a field's left-side button (Messages "+"); dragging it to the right edge works around this.
- Cleaned up:
  - the second worktree `reverseuno-android`
  - the stale `/tmp` worktrees and test folders
  - the `.superpowers/` ledger
  - the merged branches
  - the deleted, user-approved branches `feat/phone-provider-palette` (xAI/DeepSeek/phone-provider experiment) and `chore/claude-canary`
  - on the phone: the agent's `/data/local/tmp/reverseuno.apk`, its UI dumps and the dead accessibility entry
