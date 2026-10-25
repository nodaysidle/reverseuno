# reverseuno for Android

The same dictation hub, rebuilt natively in Kotlin and Jetpack Compose. Instead of
holding Right Super, a floating bubble appears just above the keyboard in any text
field. Tap it to start and stop, or hold it and talk. The polished text lands in the
field you are typing in.

```text
tap the bubble → speak → tap again
  → transcribe (OpenRouter, multilingual, auto-detect)
  → clean up (Claude Haiku 5.5, DeepSeek V4.1 Flash only if Haiku fails)
     + dictionary spellings and snippets
  → insert at the cursor (accessibility set-text, then paste, then clipboard)
```

| Piece | Where |
|---|---|
| Hub pages: Home, Dictionary, Snippets, Style, Settings | `ui/*Screen.kt`, look in `ui/Theme.kt` + `ui/Components.kt` |
| Bubble and text insertion (from Voice Anywhere 0.5.0) | `service/`, `ui/DictationBubble.kt` |
| Pipeline: STT → sanitize → snippet or cleanup → dictionary | `dictation/Pipeline.kt` |
| Ports of `src/shared` and `src/main/format.ts` / `store.ts` | `core/`, `cleanup/` |
| API keys (Keystore-backed EncryptedSharedPreferences) | `security/KeyVault.kt` |

Android 12+ (minSdk 31). The UI comes in two looks, Night (navy) and Paper.

## Build and install

Needs JDK 17 and an Android SDK with platform 36 (`local.properties` → `sdk.dir`).

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease
adb install -r -g app/build/outputs/apk/release/app-release.apk
```

The release build is R8-minified (about 31 MB) and signed with the key in
`~/.config/reverseuno/android-signing.properties`. Point `REVERSEUNO_SIGNING` at another
properties file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) to sign with a
different key. Without one, the release APK is built unsigned. Keep that keystore backed
up: an installed release only accepts updates signed with the same key.

`assembleDebug` builds `com.nodaysidle.reverseuno.debug`, which installs next to the
release.

Then, on the phone:

1. Turn on **reverseuno dictation bubble** in Accessibility. The Home screen links there.
2. Allow the microphone (`-g` already grants it).
3. On MIUI, allow Autostart and set Battery to "No restrictions" in Settings → Phone setup.

Only one bubble app should be on at a time. Voice Anywhere draws the same bubble in
the same spot.

## Keys

BYOK, the same three keys as the desktop app: OpenRouter (speech-to-text), Anthropic
(cleanup) and DeepSeek (cleanup fallback). Paste them in Settings → Providers, or copy
the desktop ones over once:

```sh
python3 scripts/push-keys-to-phone.py   # from the repo root, phone on adb
```

It reads the keys the way the desktop app does (its own store, then the environment,
then the WhisperBar env file). It hands them to the app with `am broadcast`, through a
receiver guarded by `android.permission.DUMP`, which only the adb shell and the system
hold. Add `--debug` to target the debug build.

Logcat shows one line per dictation, with timings and sizes only. Transcripts are
never logged:

```sh
adb logcat Pipeline:I ReverseunoService:D '*:S'
```
