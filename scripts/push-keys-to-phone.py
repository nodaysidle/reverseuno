#!/usr/bin/env python3
"""Copy the desktop API keys into the reverseuno Android app, once.

Keys are resolved the way src/main/secrets.ts does (app store, then environment,
then the WhisperBar env file) and sent to the app's KeyImportReceiver with
`am broadcast`. That receiver only accepts the adb shell. The command goes over
adb's stdin, so the keys never show up in this machine's process list and never
touch the phone's storage. Works for release and debug builds (pass --debug for
the .debug package).
"""
from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.nodaysidle.reverseuno"
RECEIVER = "com.nodaysidle.reverseuno.security.KeyImportReceiver"
# API keys are plain tokens; anything else would need shell quoting, so it is refused.
KEY_SHAPE = re.compile(r"^[A-Za-z0-9_\-.]+$")
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

    bad = [secret for secret, value in keys.items() if not KEY_SHAPE.match(value)]
    if bad:
        print(f"Unexpected characters in: {', '.join(bad)}", file=sys.stderr)
        return 1

    package = PACKAGE + (".debug" if "--debug" in sys.argv[1:] else "")
    extras = " ".join(f"--es {secret} {value}" for secret, value in keys.items())
    result = subprocess.run(
        ["adb", "shell"],
        input=f"am broadcast -n {package}/{RECEIVER} {extras}\n".encode(),
        capture_output=True,
    )
    output = result.stdout.decode()
    if result.returncode != 0 or f"imported {len(keys)}" not in output:
        print(f"adb failed: {(result.stderr.decode() or output).strip()[-300:]}", file=sys.stderr)
        return 1
    print(f"Imported {len(keys)} key(s) into {package}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
