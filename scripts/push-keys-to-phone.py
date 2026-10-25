#!/usr/bin/env python3
"""Copy the desktop API keys into the reverseuno Android app, once.

Keys are resolved the way src/main/secrets.ts does (app store, then environment,
then the WhisperBar env file) and streamed over adb straight into the app's
private files dir with `run-as`, so they never touch the phone's shared storage
or a temp file here. The app moves them into its Keystore-backed vault on the
next launch and deletes the file. Needs the debug build (run-as only works on
debuggable apps).
"""
from __future__ import annotations

import base64
import json
import os
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.nodaysidle.reverseuno"
HOME = Path.home()
APP_SECRETS = HOME / ".config/reverseuno/secrets.json"
WHISPERBAR = HOME / ".config/whisper-bar/config.env"

# Android Secret enum name -> (desktop secret name, env var)
KEYS = {
    "OPENROUTER": ("openrouter", "OPENROUTER_API_KEY"),
    "ANTHROPIC": ("anthropic", "ANTHROPIC_API_KEY"),
    "DEEPSEEK": ("deepseek", "DEEPSEEK_API_KEY"),
}


def app_value(name: str) -> str:
    try:
        value = json.loads(APP_SECRETS.read_text())["values"].get(name, "")
    except (OSError, ValueError, KeyError):
        return ""
    if value.startswith("plain:"):
        return base64.b64decode(value[6:]).decode()
    # enc: values are sealed by Electron safeStorage; only the desktop app can open them.
    return "" if value.startswith("enc:") else value


def env_file_value(var: str) -> str:
    try:
        lines = WHISPERBAR.read_text().splitlines()
    except OSError:
        return ""
    for line in lines:
        key, sep, value = line.strip().partition("=")
        if sep and key.strip() == var:
            return value.strip().strip("\"'")
    return ""


def main() -> int:
    keys = {}
    for secret, (name, var) in KEYS.items():
        value = app_value(name) or os.environ.get(var, "") or env_file_value(var)
        if value:
            keys[secret] = value
        print(f"{secret:<10} {'found' if value else 'missing'}")
    if not keys:
        print("No keys found; nothing to copy.", file=sys.stderr)
        return 1

    result = subprocess.run(
        ["adb", "shell", f"run-as {PACKAGE} sh -c 'mkdir -p files && cat > files/import-keys.json'"],
        input=json.dumps(keys).encode(),
        capture_output=True,
    )
    if result.returncode != 0 or result.stderr.strip():
        print(f"adb failed: {result.stderr.decode().strip()}", file=sys.stderr)
        return 1
    print(f"Copied {len(keys)} key(s). Open (or switch back to) reverseuno on the phone to import them.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
