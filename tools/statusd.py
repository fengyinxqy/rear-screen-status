#!/usr/bin/env python3
"""Status daemon for the rear-screen panel.

Reads what the coding agents on this PC are doing and publishes it as one small JSON
document the phone polls:

    {"active": 1, "waiting": 0, "status": "developing",
     "detail": "fix-login-retry", "updated": "2026-11-24T12:30:00+08:00"}

Where the state comes from, in priority order:

  1. Hook pushes (authoritative, but they expire). The agent hooks in hook_status.py POST to
     /push the moment something starts, needs input or finishes. A push is a heartbeat, not a
     fact that stays true: it is believed for PUSH_TTL_SEC and then it is history. One push
     that never got its matching "finished" used to leave the panel claiming work for the next
     45 minutes.
  2. Session files (fallback). Claude Code keeps one JSONL per session under
     ~/.claude/projects/<project>/<session>.jsonl and Codex under ~/.codex/sessions. A session
     counts as running only while it is still being written to (LIVENESS_SEC). "Touched in the
     last 15 minutes" is not the same thing as "running", and treating it as such is how a
     panel ends up saying 开发中 at an idle desk.

There is no "waiting for a human" state any more, and nothing here guesses one. Every probe for
it was wrong in production: the role of a transcript's last line is not a signal (a tool result
is written as a "user" entry, and a finished turn looks exactly like a question), and the agent
lifecycle events that report "turn over" mean the work is done, not that anything is being
asked. The panel says what it can prove — working or not — because an idle screen is a better
answer than a wrong one. An incoming "waiting" is folded into idle.

    python tools/statusd.py                 # serve on 0.0.0.0:8770
    python tools/statusd.py --once          # print one reading and exit
    python tools/statusd.py --port 9000 --window 20
"""

from __future__ import annotations

import argparse
import json
import re
import socket
import sys
import threading
import time
from datetime import datetime
from urllib.parse import unquote
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOME = Path.home()

SESSION_ROOTS = [
    HOME / ".claude" / "projects",
    HOME / ".codex" / "sessions",
    HOME / ".config" / "claude" / "projects",
]

# The DSH bridge writes this file and then POSTs; nothing else on the machine writes it. Newer
# builds of the bridge also name their agent inside the push, but older ones (and anything
# already loaded in a running editor) do not — this is how the panel still gets the right mark
# during the gap between the two, without anyone having to restart the editor.
DSH_STATE_FILE = HOME / ".rear-status" / "dsh-state.json"
DSH_FILE_TRUST_SEC = 120

WINDOW_SEC = 15 * 60
# How long a push is believed, per status it claims. Work that stopped sending events two
# minutes ago is over; an explicit "waiting for you" stays meaningful for longer.
PUSH_TTL_SEC = {"developing": 180, "idle": 300}
# A session is running only while its file is still being written to.
LIVENESS_SEC = 180

_lock = threading.Lock()
_manual: dict[str, Any] = {}
# Bumped by every /push, so a held request can answer the instant something is pushed without
# rescanning the session files on every tick of its wait loop.
_version = 0


def _signature(payload: dict[str, Any]) -> str:
    """The part of a reading the panel actually draws, as one comparable string.

    "updated" is deliberately left out: it is recomputed on every request, so including it
    would make every long poll return immediately.
    """
    return "%s|%d|%s" % (payload["status"], payload["active"], payload["detail"])


def _query_param(query: str, name: str) -> str:
    for pair in query.split("&"):
        key, _, value = pair.partition("=")
        if key == name:
            return unquote(value)
    return ""


def _wait_seconds(query: str, cap: float) -> float:
    for pair in query.split("&"):
        key, _, value = pair.partition("=")
        if key == "wait":
            try:
                return min(max(float(value), 0.0), cap)
            except ValueError:
                return 0.0
    return 0.0


def _session_files() -> list[Path]:
    out: list[Path] = []
    for root in SESSION_ROOTS:
        if not root.is_dir():
            continue
        try:
            out.extend(root.rglob("*.jsonl"))
        except OSError:
            continue
    return out


def _bridge_just_spoke() -> bool:
    """True when the DSH bridge wrote its state file moments ago, i.e. it is the pusher."""
    try:
        return time.time() - DSH_STATE_FILE.stat().st_mtime <= DSH_FILE_TRUST_SEC
    except OSError:
        return False


def _agent_of(path: Path) -> str:
    """Which program a session file belongs to, from the well-known session roots."""
    parts = path.parts
    if ".claude" in parts:
        return "claude"
    if ".codex" in parts:
        return "codex"
    return ""


def _codex_cwd(path: Path) -> str:
    """The working directory Codex records in the session header, as a short name.

    Codex files the rollouts under sessions/YYYY/MM/DD/, so the parent directory is a day
    number — reading it produced panels captioned "任务 24".
    """
    try:
        with path.open("rb") as f:
            head = f.read(262144).decode("utf-8", "ignore")
    except OSError:
        return ""
    for line in head.splitlines()[:3]:
        try:
            entry = json.loads(line)
        except Exception:
            continue
        payload = entry.get("payload") if isinstance(entry, dict) else None
        cwd = payload.get("cwd") if isinstance(payload, dict) else None
        if isinstance(cwd, str) and cwd.strip():
            return Path(cwd.strip()).name
    # The header can outgrow any cap we pick; pull the field out of the raw text instead.
    found = re.search(r'"cwd"\s*:\s*"((?:[^"\\]|\\.)*)"', head)
    if found:
        try:
            cwd = json.loads('"' + found.group(1) + '"')
            if cwd.strip():
                return Path(cwd.strip()).name
        except Exception:
            return ""
    return ""


def _claude_turn_running(path: Path) -> bool | None:
    """True while Claude Code is mid-turn, False once it has handed control back.

    Every assistant record carries a stop_reason: "end_turn" closes the turn, "tool_use" means a
    call is pending (or a tool result was just written, which arrives as a user record). The tail
    is enough — only the last message record matters. None means the tail had no message at all,
    and the caller falls back to mtime.
    """
    try:
        size = path.stat().st_size
        with path.open("rb") as f:
            f.seek(max(0, size - 65536))
            tail = f.read().decode("utf-8", "ignore")
    except OSError:
        return None
    verdict = None
    for line in tail.splitlines():
        try:
            entry = json.loads(line)
        except Exception:
            continue
        if not isinstance(entry, dict):
            continue
        kind = entry.get("type")
        if kind not in ("assistant", "user"):
            continue
        msg = entry.get("message")
        if not isinstance(msg, dict):
            continue
        content = msg.get("content")
        if kind == "user":
            # Prompts and tool results both arrive as user records: the model still owes an
            # answer, so the turn is running.
            verdict = True
            continue
        pending_tool = False
        if isinstance(content, list):
            pending_tool = any(isinstance(c, dict) and c.get("type") == "tool_use" for c in content)
        stop = msg.get("stop_reason")
        verdict = not (stop in ("end_turn", "stop_sequence") and not pending_tool)
    return verdict


def _turn_finished(path: Path) -> bool:
    """Whether the session's own transcript says the turn is over."""
    agent = _agent_of(path)
    if agent == "codex":
        return _codex_turn_running(path) is False
    if agent == "claude":
        return _claude_turn_running(path) is False
    return False


def _codex_turn_running(path: Path) -> bool | None:
    """True while Codex says a turn is running, False once it says the turn is done.

    Codex writes task_started, then task_complete (or turn_aborted) into the rollout, and keeps
    appending housekeeping afterwards — token counts, item_completed. Judging by file mtime kept
    a finished turn "running" for the whole liveness window, which is exactly the panel that
    would not go back to 空闲. None means "no turn event in the tail", i.e. fall back to mtime.
    """
    try:
        size = path.stat().st_size
        with path.open("rb") as f:
            f.seek(max(0, size - 65536))
            tail = f.read().decode("utf-8", "ignore")
    except OSError:
        return None
    verdict = None
    for line in tail.splitlines():
        try:
            entry = json.loads(line)
        except Exception:
            continue
        if not isinstance(entry, dict) or entry.get("type") != "event_msg":
            continue
        payload = entry.get("payload")
        kind = payload.get("type") if isinstance(payload, dict) else None
        if kind == "task_started":
            verdict = True
        elif kind in ("task_complete", "turn_aborted"):
            verdict = False
    return verdict


def _project_name(path: Path) -> str:
    parts = path.parts
    if ".claude" in parts:
        i = parts.index(".claude")
        if i + 2 < len(parts):
            encoded = parts[i + 2]
            # Claude flattens a path into one directory name; its tail is the repo. Trailing
            # separators survive as dashes, so empty segments are dropped before taking the last.
            segments = [s for s in encoded.split("-") if s]
            return segments[-1] if segments else ""
    if ".codex" in parts:
        named = _codex_cwd(path)
        if named:
            return named
    for p in reversed(parts[:-1]):
        if p in ("sessions", "projects", ".codex", ".claude", ".config") or p.isdigit():
            continue
        return p
    return ""


def scan_sessions(window: int) -> dict[str, Any]:
    now = time.time()
    recent: list[tuple[float, Path]] = []
    for f in _session_files():
        try:
            mtime = f.stat().st_mtime
        except OSError:
            continue
        if now - mtime <= window:
            recent.append((mtime, f))

    # Recent is not running. Only files still being written to count as work in progress; the
    # rest are finished turns. Counting them was how an idle desk showed 开发中 for 15 minutes.
    running: list[tuple[float, Path]] = []
    for mtime, f in recent:
        if now - mtime > LIVENESS_SEC:
            continue
        if _turn_finished(f):
            continue          # the turn is over; the file is just being written to afterwards
        running.append((mtime, f))
    latest = max(running, key=lambda t: t[0]) if running else None
    agents = []
    for _m, f in running:
        agent = _agent_of(f)
        if agent and agent not in agents:
            agents.append(agent)
    return {
        "active": len(running),
        # Which programs are running, so the panel can show a mark per agent instead of
        # guessing from the project name.
        "agents": agents,
        "detail": _project_name(latest[1]) if latest else "",
        "latest_age": int(now - latest[0]) if latest else None,
    }


def compute(window: int) -> dict[str, Any]:
    with _lock:
        manual = dict(_manual)

    ttl = PUSH_TTL_SEC.get(manual.get("status") or "", 0)
    ready = bool(manual) and ttl > 0 and (time.time() - manual.get("ts", 0) <= ttl)
    scan = scan_sessions(window)

    if ready:
        # A push speaks for one agent; the scan sees the rest. Believing only the pusher is how a
        # running Codex stayed invisible behind a quiet DSH — worse, the panel could read 空闲
        # while another agent was plainly working, because the pusher's "idle" was taken as the
        # whole truth. Merge instead: activity from either side counts, and the marks are the
        # union of the agents each side can see.
        push_agents = list(manual.get("agents") or [])
        if not push_agents and _bridge_just_spoke():
            push_agents = ["dsh"]     # an unnamed push from the bridge still has an owner
        push_live = manual.get("status") == "developing"
        scan_live = scan["active"] > 0

        status = "developing" if (push_live or scan_live) else "idle"
        # max(), not sum: a hook push and the scan can both be describing the same session.
        active = max(int(manual.get("active") or 0), int(scan["active"] or 0))
        detail = ((manual.get("detail") if push_live else "")
                  or scan["detail"] or manual.get("detail") or "")
        agents = sorted({a for a in push_agents + list(scan["agents"]) if a})
        source = "hook+scan" if scan_live else "hook"
    else:
        active = scan["active"]
        status = "developing" if active else "idle"
        detail = scan["detail"]
        agents = scan["agents"]
        source = "sessions"

    # A client that still claims "waiting" is not believed: we cannot distinguish it from a
    # finished turn, and a panel that shows the wrong urgent state is worse than a quiet one.
    if status != "developing":
        status = "idle"

    return {
        "active": max(0, active),
        "waiting": 0,
        "status": status,
        "agents": agents if status != "idle" else [],
        "detail": (detail or "")[:80],
        "source": source,
        "updated": datetime.now().astimezone().isoformat(timespec="seconds"),
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "rear-status/1.0"
    window = 900

    def _answer(self, wait: float, since: str) -> None:
        """Send a reading, holding the request open until it changes (long poll).

        Without this the phone asks on a timer: it is on average five seconds behind and up to
        ten, which reads as a lagging screen. Holding one request costs an idle thread and turns
        the update into a push — the phone sees a change in a fraction of a second.

        `since` is what the caller last saw. It matters because a change can land in the gap
        between two requests: without it the next request would wait out its whole hold before
        reporting a reading that was already old when the request arrived — which is exactly how
        a long poll ends up no faster than the timer it replaced.
        """
        first = compute(self.window)
        signature = _signature(first)
        if wait <= 0 or (since and since != signature):
            self._send_json(first)
            return

        version = _version
        deadline = time.time() + wait
        rescanned = time.time()

        while time.time() < deadline:
            time.sleep(self.POLL_STEP_SEC)
            if _version != version:
                break                      # a push landed: answer with it
            if time.time() - rescanned >= self.RESCAN_SEC:
                rescanned = time.time()
                if _signature(compute(self.window)) != signature:
                    break                  # the session scan moved
        self._send_json(compute(self.window))

    def _send_json(self, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False) + "\n"
        self._send(200, body.encode("utf-8"), "application/json; charset=utf-8")

    def _send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        try:
            self.wfile.write(body)
        except OSError:
            pass

    # How long a request may be held open waiting for a change.
    MAX_WAIT_SEC = 25.0
    POLL_STEP_SEC = 0.15
    RESCAN_SEC = 2.0

    def do_GET(self) -> None:  # noqa: N802
        path, _, query = self.path.partition("?")
        if path in ("/status.json", "/", "/status"):
            self._answer(_wait_seconds(query, self.MAX_WAIT_SEC), _query_param(query, "since"))
        elif path == "/health":
            self._send(200, b"ok\n", "text/plain; charset=utf-8")
        else:
            self._send(404, b'{"error":"not found"}\n', "application/json")

    def do_POST(self) -> None:  # noqa: N802
        global _version
        if self.path.split("?")[0] != "/push":
            self._send(404, b'{"error":"not found"}\n', "application/json")
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(min(n, 64 * 1024)) if n else b"{}"
            data = json.loads(raw.decode("utf-8") or "{}")
            if not isinstance(data, dict):
                raise ValueError("body must be a JSON object")
        except Exception as e:
            self._send(400, json.dumps({"error": str(e)}).encode(), "application/json")
            return
        with _lock:
            _manual.clear()
            _manual.update({k: data.get(k) for k in ("status", "detail", "active", "waiting")})
            # A push names the program it speaks for; that is what the panel's marks show.
            agent = data.get("agent")
            _manual["agents"] = [str(agent).strip().lower()] if agent else []
            _manual["ts"] = time.time()
            _version += 1
        body = json.dumps(compute(self.window), ensure_ascii=False) + "\n"
        self._send(200, body.encode("utf-8"), "application/json; charset=utf-8")

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def log_message(self, fmt: str, *args: Any) -> None:
        # Timestamped: when a panel update is late, this line is what tells a held request
        # that was never woken apart from a phone that stopped asking.
        stamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        sys.stderr.write("%s %s - %s\n" % (stamp, self.address_string(), fmt % args))


def local_ips() -> list[str]:
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips)


class Server(ThreadingHTTPServer):
    """HTTP server that treats a dropped long poll as normal, not as an error.

    The phone holds a request open by design and drops it when the app is reinstalled or the
    Wi-Fi dozes; printing a traceback for that buries the events worth reading.
    """

    daemon_threads = True

    def handle_error(self, request: object, client_address: tuple[str, int]) -> None:
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, BrokenPipeError)):
            return
        super().handle_error(request, client_address)


def main() -> int:
    ap = argparse.ArgumentParser(description="publish coding-agent status for the rear screen")
    ap.add_argument("--port", type=int, default=8770)
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--window", type=int, default=WINDOW_SEC)
    ap.add_argument("--once", action="store_true")
    args = ap.parse_args()

    if args.once:
        print(json.dumps(compute(args.window), ensure_ascii=False, indent=2))
        return 0

    Handler.window = args.window
    httpd = Server((args.bind, args.port), Handler)
    print("rear status daemon on http://%s:%d/status.json" % (args.bind, args.port), flush=True)
    for ip in local_ips():
        print("  phone URL:  http://%s:%d/status.json" % (ip, args.port), flush=True)
    print('  push hook:  POST /push {"status":"waiting","detail":"..."}', flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("bye", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
