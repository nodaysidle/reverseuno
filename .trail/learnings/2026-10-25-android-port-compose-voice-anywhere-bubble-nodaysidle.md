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
- Desktop keys go to the phone with scripts/push-keys-to-phone.py: `am broadcast` (sent over adb stdin) to security/KeyImportReceiver, which is guarded by android.permission.DUMP so only the adb shell can call it. This works on release builds. The first version used run-as, which only works on debug builds.
- Release builds are signed with ~/.config/reverseuno/android-release.jks (props in android-signing.properties, outside the repo). Debug builds use the .debug suffix so both can be installed side by side.
- Jev (TypeSafe) is not used. needsFormatting() already decides when to skip cleanup, and Jev would add a fourth key and another network hop.

## Tried and ruled out
- DeepSeek's direct API rejects "deepseek-v4.1-flash" (that is the OpenRouter id). The direct id is "deepseek-flash" (= DeepSeek-V4.1-Flash). Send reasoning_effort "low", because the default is high.

## Watch out
- Voice Anywhere and reverseuno draw the same N bubble in the same spot. Enable only one of their accessibility services at a time.
- On this Mi 10T, `adb install -r -g` worked directly and accessibility stayed enabled across the reinstall. Still re-check enabled_accessibility_services after each install.
- Gradle is shared when agents build in parallel: serialize builds with flock on one lock file.
- R8 for the release needs -Xmx6g (it hit OOM at 2g with anthropic-java) plus keep rules for com.anthropic.** and Jackson; see proguard-rules.pro. Verified on device that Haiku cleanup works minified.
- Switching between debug-signed and release-signed builds with the same package needs an uninstall, which wipes keys and history. Re-run the key script afterwards and re-enable accessibility.
