#!/usr/bin/env python3
"""Comment out the old WhisperBar Right Cmd bind and add reverseuno's hold bind.

Only touches ~/.config/hypr/bindings.lua, keeps a timestamped backup, and is
idempotent: running it twice leaves one reverseuno block.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

HOME = Path.home()
BINDINGS = HOME / ".config/hypr/bindings.lua"
CLI = HOME / ".local/bin/reverseuno"


def main() -> int:
    if not BINDINGS.exists():
        print(f"error: {BINDINGS} does not exist (is Hyprland configured?)", file=sys.stderr)
        return 1

    text = BINDINGS.read_text(encoding="utf-8")
    if "reverseuno: hold Right Cmd" in text:
        print("reverseuno bind already present; nothing to do.")
        return 0

    backup = BINDINGS.with_name(f"{BINDINGS.name}.bak.{int(time.time())}")
    shutil.copy2(BINDINGS, backup)

    lines = text.splitlines()
    changed: list[str] = []
    disabled = 0
    for line in lines:
        stripped = line.strip()
        if (
            stripped.startswith('o.bind("Super_R"')
            and "whisper-bar" in stripped
            and not stripped.startswith("--")
        ):
            changed.append(f"-- (disabled by reverseuno install) {line}")
            disabled += 1
        else:
            changed.append(line)

    cli = str(CLI)
    block = [
        "",
        "-- reverseuno: hold Right Cmd to dictate",
        f'o.bind("Super_R", "reverseuno hold", "{cli} start")',
        f'o.bind("Super_R", "reverseuno release", "{cli} stop", {{ release = true }})',
        "",
        "-- reverseuno: floating windows",
        'o.window({ class = "^reverseuno$", title = "^reverseuno-overlay$" }, {',
        "  float = true,",
        "  pin = true,",
        "  no_initial_focus = true,",
        "  no_dim = true,",
        "  border_size = 0,",
        '  opacity = "1 1",',
        "  size = { 500, 108 },",
        '  move = { "(monitor_w-window_w)/2", "(monitor_h-window_h-18)" },',
        "})",
        'o.window({ class = "^reverseuno$", title = "^reverseuno$" }, {',
        "  float = true,",
        "  center = true,",
        "  size = { 1180, 820 },",
        "})",
        "",
    ]
    changed.extend(block)
    BINDINGS.write_text("\n".join(changed).rstrip() + "\n", encoding="utf-8")

    print(f"backup: {backup}")
    if disabled:
        print(f"disabled {disabled} active WhisperBar Right Cmd binding(s).")
    print(f"added reverseuno hold bind in {BINDINGS}")

    try:
        subprocess.run(["hyprctl", "reload"], check=False, capture_output=True, text=True)
        print("hyprctl reload: done")
    except FileNotFoundError:
        print("hyprctl not found; reload Hyprland manually.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
