#!/usr/bin/env python3
"""Add reverseuno to the Omarchy menu under Apps, idempotently."""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
from pathlib import Path

MENU = Path.home() / ".config/omarchy/extensions/omarchy-menu.jsonc"
ENTRY = '''  // reverseuno
  "apps.reverseuno": {
    "icon": "󰍬",
    "label": "reverseuno",
    "description": "Hold Right Command to dictate",
    "action": "uwsm-app -- gtk-launch reverseuno.desktop"
  }'''


def main() -> int:
    if not MENU.exists():
        print(f"skip: {MENU} does not exist", file=sys.stderr)
        return 0
    text = MENU.read_text(encoding="utf-8")
    if '"apps.reverseuno"' in text:
        print("reverseuno menu entry already present; nothing to do.")
        return 0

    backup = MENU.with_name(f"{MENU.name}.bak.{int(time.time())}")
    shutil.copy2(MENU, backup)

    marker = text.rfind("\n}")
    if marker == -1:
        print(f"error: cannot find the closing brace in {MENU}", file=sys.stderr)
        return 1
    before = text[:marker].rstrip()
    after = text[marker:]
    MENU.write_text(f"{before},\n\n{ENTRY}{after}", encoding="utf-8")
    print(f"backup: {backup}")
    print(f"added Apps > reverseuno to {MENU}")

    try:
        subprocess.run(["omarchy", "menu", "refresh"], check=False, capture_output=True, text=True)
    except FileNotFoundError:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
