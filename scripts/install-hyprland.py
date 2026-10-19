#!/usr/bin/env python3
"""Install or update reverseuno's Hyprland binds and window rules.

Only touches ~/.config/hypr/bindings.lua and keeps a timestamped backup. The
reverseuno block lives between marker comments, so running this again replaces
it in place (and upgrades the unmarked block older versions wrote). It also
comments out an old WhisperBar bind on Right Super so the two do not
double-fire.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
from pathlib import Path

HOME = Path.home()
BINDINGS = HOME / ".config/hypr/bindings.lua"
CLI = HOME / ".local/bin/reverseuno"

BEGIN = "-- >>> reverseuno (managed by scripts/install-hyprland.py)"
END = "-- <<< reverseuno"

# First and last line of the unmarked block written by earlier versions.
LEGACY_START = "-- reverseuno: hold Right Cmd to dictate"
LEGACY_LAST_RULE = 'o.window({ class = "^reverseuno$", title = "^reverseuno$" }, {'


def block() -> list[str]:
    cli = str(CLI)
    return [
        BEGIN,
        "-- Push to talk: hold Right Super, speak, release to paste.",
        f'o.bind("Super_R", "reverseuno hold", "{cli} start")',
        f'o.bind("Super_R", "reverseuno release", "{cli} stop", {{ release = true }})',
        f'o.bind("SUPER + Delete", "reverseuno cancel", "{cli} cancel")',
        "",
        "-- The overlay never takes focus, so the paste lands where you were typing.",
        'o.window({ class = "^reverseuno$", title = "^reverseuno-overlay$" }, {',
        "  float = true,",
        "  pin = true,",
        "  no_focus = true,",
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
        END,
    ]


def strip_managed(lines: list[str]) -> tuple[list[str], int]:
    """Remove the marked block, or the legacy unmarked one. Returns (lines, index)."""
    if BEGIN in lines and END in lines:
        start = lines.index(BEGIN)
        end = lines.index(END, start)
        return lines[:start] + lines[end + 1 :], start

    if LEGACY_START in lines:
        start = lines.index(LEGACY_START)
        try:
            rule = lines.index(LEGACY_LAST_RULE, start)
            end = lines.index("})", rule)
        except ValueError:
            print("error: found an old reverseuno block but not where it ends; edit it by hand.", file=sys.stderr)
            raise SystemExit(1)
        return lines[:start] + lines[end + 1 :], start

    return lines, len(lines)


def disable_whisperbar(lines: list[str]) -> tuple[list[str], int]:
    changed: list[str] = []
    disabled = 0
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('o.bind("Super_R"') and "whisper-bar" in stripped:
            changed.append(f"-- (disabled by reverseuno install) {line}")
            disabled += 1
        else:
            changed.append(line)
    return changed, disabled


def main() -> int:
    if not BINDINGS.exists():
        print(f"error: {BINDINGS} does not exist (is Hyprland configured?)", file=sys.stderr)
        return 1

    text = BINDINGS.read_text(encoding="utf-8")
    lines, index = strip_managed(text.splitlines())
    lines, disabled = disable_whisperbar(lines)
    lines[index:index] = block()
    updated = "\n".join(lines).rstrip() + "\n"

    if updated == text:
        print("reverseuno block is already up to date; nothing to do.")
        return 0

    backup = BINDINGS.with_name(f"{BINDINGS.name}.bak.{int(time.time())}")
    shutil.copy2(BINDINGS, backup)
    BINDINGS.write_text(updated, encoding="utf-8")

    print(f"backup: {backup}")
    if disabled:
        print(f"disabled {disabled} active WhisperBar Right Super binding(s).")
    print(f"wrote reverseuno block in {BINDINGS}")

    try:
        result = subprocess.run(["hyprctl", "reload"], check=False, capture_output=True, text=True)
        print(f"hyprctl reload: {result.stdout.strip() or 'done'}")
    except FileNotFoundError:
        print("hyprctl not found; reload Hyprland manually.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
