#!/usr/bin/env python3
"""Claude Code / Codex hook -> status pushes for the rear screen.

Wire it into the agent's hooks, one line each (see README):

    SessionStart      python tools/hook_status.py start
    UserPromptSubmit  python tools/hook_status.py working
    PreToolUse        python tools/hook_status.py working
    Stop              python tools/hook_status.py idle
    Notification      python tools/hook_status.py waiting

Each invocation posts the current state to the daemon (--url, default
http://127.0.0.1:8770/push) and always writes the same JSON to --file as well, so a phone
reading a pushed file still sees it when the daemon is not running.

Hooks are fed the event as JSON on stdin; junk on stdin is tolerated and this script
always exits 0 — a hook that fails can block a tool call, and a status panel is never
worth that.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_URL = "http://127.0.0.1:8770/push"
DEFAULT_FILE = Path.home() / ".rear-status" / "status.json"

# "waiting" is retired: the notification event that used to feed it says "Claude needs your
# input", which on a real machine is indistinguishable from a turn that simply ended, and the
# panel showing a wrong urgent state is worse than showing the quiet one. The mode is kept so
# an existing settings.json does not break; it now reports idle, like "done".
MODES = {
    "start": ("developing", 1, 0, ""),
    "working": ("developing", 1, 0, ""),
    "tool": ("developing", 1, 0, ""),
    "idle": ("idle", 0, 0, ""),
    "waiting": ("idle", 0, 0, ""),
    "done": ("idle", 0, 0, ""),
    "error": ("idle", 0, 0, "出错了"),
}


def read_stdin() -> dict:
    try:
        if sys.stdin is None or sys.stdin.isatty():
            return {}
        raw = sys.stdin.read()
        if not raw.strip():
            return {}
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def detail_from(event: dict, fallback: str) -> str:
    for key in ("tool_name", "notification_type", "cwd", "session_id", "message"):
        v = event.get(key)
        if isinstance(v, str) and v.strip():
            if key == "cwd":
                return os.path.basename(v.rstrip("/\\")) or v
            if key == "session_id":
                return v[:8]
            return v[:60]
    return fallback


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=sorted(MODES), nargs="?", default="working")
    ap.add_argument("--url", default=os.environ.get("REAR_STATUS_URL", DEFAULT_URL))
    ap.add_argument("--file", default=str(DEFAULT_FILE))
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    status, active, waiting, note = MODES[args.mode]
    payload = {
        "status": status,
        "active": active,
        "waiting": waiting,
        # Which program this hook speaks for: the panel shows a mark per running agent.
        "agent": os.environ.get("REAR_STATUS_AGENT", "claude"),
        "detail": detail_from(read_stdin(), note),
        "updated": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }

    ok = False
    try:
        req = urllib.request.Request(args.url, data=json.dumps(payload).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=2) as r:
            ok = r.status == 200
    except (urllib.error.URLError, OSError, ValueError):
        ok = False

    try:
        path = Path(args.file)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(path)
    except OSError:
        pass

    if not args.quiet:
        print(("pushed: " if ok else "daemon down, wrote file: ")
              + json.dumps(payload, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
