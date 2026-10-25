---
title: "Android port: native Compose app in android/ with the Voice Anywhere bubble"
author: Nodaysidle
date: 2026-10-25
branch: feat/android-app
touches:
  - android/app/src/main/java/com/nodaysidle/reverseuno/
  - scripts/push-keys-to-phone.py
---

## Decided
- android/ is a native Kotlin + Compose rewrite (package com.nodaysidle.reverseuno, minSdk 31). The bubble, accessibility service, recorder and KeyVault come from Voice Anywhere 0.5.0 (~/Projects/_github-migrations/nodaysidle-voice-anywhere-v2). The hub pages port src/renderer/hub/*.tsx, and core/ ports src/shared plus src/main/format.ts and store.ts. Keep TextTest and FormatterTest in step with tests/text.test.ts and tests/prompt.test.ts.
- STT is OpenRouter only (multilingual; language "auto" leaves the field out). Cleanup is always Anthropic claude-haiku-5-5 first, then DeepSeek deepseek-flash only if Haiku errors, times out, or returns empty or runaway text (firstGoodCleanup). No provider picker; the owner prefers Haiku quality over speed.
- Desktop keys go to the phone once with scripts/push-keys-to-phone.py. adb run-as streams the JSON into files/import-keys.json; the app moves it into EncryptedSharedPreferences in onResume and deletes the file. This only works on the debug build.
- Jev (TypeSafe) is not used. needsFormatting() already decides when to skip cleanup, and Jev would add a fourth key and another network hop.

## Tried and ruled out
- DeepSeek's direct API rejects "deepseek-v4.1-flash" (that is the OpenRouter id). The direct id is "deepseek-flash" (= DeepSeek-V4.1-Flash). Send reasoning_effort "low", because the default is high.

## Watch out
- Voice Anywhere and reverseuno draw the same N bubble in the same spot. Enable only one of their accessibility services at a time.
- On this Mi 10T, `adb install -r -g` worked directly and accessibility stayed enabled across the reinstall. Still re-check enabled_accessibility_services after each install.
- Gradle is shared when agents build in parallel: serialize builds with flock on one lock file.
