#!/usr/bin/env python3
"""End-to-end test for tools/statusd.py: real HTTP round trip, no mocks."""

from __future__ import annotations

import json
import sys
import threading
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import statusd  # noqa: E402
from http.server import ThreadingHTTPServer  # noqa: E402

fails = 0


def check(name, ok, got=""):
    global fails
    print(("PASS " if ok else "FAIL ") + name + ("" if not got else " -> " + str(got)))
    if not ok:
        fails += 1


def get(url):
    with urllib.request.urlopen(url, timeout=5) as r:
        return r.status, json.loads(r.read().decode("utf-8"))


def post(url, payload):
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.status, json.loads(r.read().decode("utf-8"))


def longpoll_tests(base):
    """A held request must come back on change, and must not spin."""
    import threading as _threading
    import time

    post(base + "/push", {"status": "idle", "active": 0, "detail": ""})

    # Nothing changes: the request is held for the full wait, then answered.
    started = time.time()
    _code, body = get(base + "/status.json?wait=1")
    held = time.time() - started
    check("long poll holds when nothing changes", held >= 0.9, round(held, 2))
    check("long poll answers with the reading", body["status"] == "idle", body)

    # A caller that is already behind must not be made to wait out the hold: this is the gap
    # between two requests, where a long poll silently becomes a slow timer again.
    started = time.time()
    _code, behind = get(base + "/status.json?wait=5&since=nothing%7C9%7Cstale")
    took = time.time() - started
    check("a caller that is behind is answered at once", took < 1.0, round(took, 2))
    check("answered at once with the current reading", behind["status"] == "idle", behind)

    # A caller that is up to date is held, as before.
    current = "%s|%d|%s" % (behind["status"], behind["active"], behind["detail"])
    started = time.time()
    _code, _same = get(base + "/status.json?wait=1&since=" + urllib.parse.quote(current, safe=""))
    held_since = time.time() - started
    check("an up-to-date caller is still held", held_since >= 0.9, round(held_since, 2))

    # A push during the hold must return the answer almost immediately.
    result = {}

    def hold():
        started = time.time()
        try:
            result["body"] = get(base + "/status.json?wait=10")[1]
        except Exception as e:  # pragma: no cover - surfaced as a failure below
            result["error"] = str(e)
        result["took"] = time.time() - started

    worker = _threading.Thread(target=hold)
    worker.start()
    time.sleep(0.6)
    post(base + "/push", {"status": "developing", "active": 1, "detail": "changed"})
    worker.join(5)
    check("push wakes a held poll", result.get("took", 99) < 2.0, round(result.get("took", 99), 2))
    check("held poll returns the new reading",
          (result.get("body") or {}).get("status") == "developing", result.get("body"))


def merge_test(base):
    """A pusher that has gone quiet must not hide another agent that is still working."""
    import os
    import shutil
    import tempfile
    import time

    saved_roots = statusd.SESSION_ROOTS
    saved_file = statusd.DSH_STATE_FILE
    saved_manual = dict(statusd._manual)
    tmp = Path(tempfile.mkdtemp())
    bridge = tmp / "dsh-state.json"
    bridge.write_text("{}", encoding="utf-8")
    session = tmp / ".codex" / "sessions" / "2026" / "11" / "24" / "rollout.jsonl"
    session.parent.mkdir(parents=True)
    # An open turn: Codex has said task_started and not finished. This is what "running" is.
    session.write_text('{"type":"event_msg","payload":{"type":"task_started"}}\n'
                       + '{"type":"response_item"}\n', encoding="utf-8")
    now = time.time()
    os.utime(session, (now, now))
    try:
        statusd.SESSION_ROOTS = [tmp]
        statusd.DSH_STATE_FILE = bridge

        # DSH pushes "idle" while Codex is still being written to.
        post(base + "/push", {"status": "idle", "active": 0, "detail": ""})
        _code, body = get(base + "/status.json")
        check("a quiet pusher cannot hide a running agent",
              body["status"] == "developing" and "codex" in body["agents"], body)

        # DSH pushes "developing" without naming itself: both marks must survive.
        post(base + "/push", {"status": "developing", "active": 2, "detail": "dsh-proj"})
        _code, body = get(base + "/status.json")
        check("marks are the union of push and scan",
              body["agents"] == ["codex", "dsh"], body)
        check("detail follows the agent that is pushing",
              body["detail"] == "dsh-proj", body)

        # The turn is over, but Codex keeps appending housekeeping to the same file. The panel
        # must go back to idle instead of sitting on 开发中 for the whole liveness window.
        with session.open("a", encoding="utf-8") as f:
            f.write('{"type":"event_msg","payload":{"type":"task_complete"}}\n')
            f.write('{"type":"event_msg","payload":{"type":"token_count"}}\n')
        os.utime(session, (time.time(), time.time()))
        post(base + "/push", {"status": "idle", "active": 0, "detail": ""})
        _code, body = get(base + "/status.json")
        check("a finished turn goes back to idle",
              body["status"] == "idle" and body["agents"] == [], body)
    finally:
        statusd.SESSION_ROOTS = saved_roots
        statusd.DSH_STATE_FILE = saved_file
        statusd._manual.clear()
        statusd._manual.update(saved_manual)
        shutil.rmtree(tmp, ignore_errors=True)


def claude_turn_test(base):
    """Claude Code: a turn that ended must not keep counting as running."""
    import os
    import shutil
    import tempfile
    import time

    saved_roots = statusd.SESSION_ROOTS
    saved_manual = dict(statusd._manual)
    tmp = Path(tempfile.mkdtemp())
    session = tmp / ".claude" / "projects" / "C--Users-admin-repo" / "s.jsonl"
    session.parent.mkdir(parents=True)
    try:
        statusd.SESSION_ROOTS = [tmp]
        statusd._manual.clear()

        # Mid-turn: the assistant asked for a tool.
        session.write_text(
            '{"type":"assistant","message":{"role":"assistant","stop_reason":"tool_use",'
            '"content":[{"type":"tool_use","name":"Bash"}]}}\n', encoding="utf-8")
        now = time.time()
        os.utime(session, (now, now))
        _code, body = get(base + "/status.json")
        check("claude mid-turn counts as running", body["status"] == "developing", body)

        # The turn is over: end_turn, and Claude keeps appending bookkeeping afterwards.
        with session.open("a", encoding="utf-8") as f:
            f.write('{"type":"assistant","message":{"role":"assistant","stop_reason":"end_turn",'
                    '"content":[{"type":"text","text":"done"}]}}\n')
            f.write('{"type":"system","subtype":"hook_result"}\n')
        os.utime(session, (time.time(), time.time()))
        _code, body = get(base + "/status.json")
        check("claude finished turn goes back to idle", body["status"] == "idle", body)

        # A new prompt reopens it.
        with session.open("a", encoding="utf-8") as f:
            f.write('{"type":"user","message":{"role":"user","content":"do it again"}}\n')
        os.utime(session, (time.time(), time.time()))
        _code, body = get(base + "/status.json")
        check("a new prompt reopens the turn", body["status"] == "developing", body)
    finally:
        statusd.SESSION_ROOTS = saved_roots
        statusd._manual.clear()
        statusd._manual.update(saved_manual)
        shutil.rmtree(tmp, ignore_errors=True)


def attribution_test(base):
    """A push that does not name its agent still gets the right mark while the gap lasts."""
    import os
    import tempfile
    import time

    tmp = Path(tempfile.mkdtemp()) / "dsh-state.json"
    tmp.write_text("{}", encoding="utf-8")
    saved_file = statusd.DSH_STATE_FILE
    saved_manual = dict(statusd._manual)
    try:
        statusd.DSH_STATE_FILE = tmp
        post(base + "/push", {"status": "developing", "active": 1, "detail": "x"})
        _code, body = get(base + "/status.json")
        check("unnamed bridge push is attributed to dsh", body["agents"] == ["dsh"], body)

        old = time.time() - 3600
        os.utime(tmp, (old, old))
        _code, body = get(base + "/status.json")
        check("a stale bridge file attributes nothing", body["agents"] == [], body)
    finally:
        statusd.DSH_STATE_FILE = saved_file
        statusd._manual.clear()
        statusd._manual.update(saved_manual)


def scan_tests(base):
    """What the fallback scan must not claim.

    These exist because the panel once said 等你回答 at an idle desk. The scan used to turn
    "the last transcript line is a user message" into "a human is being waited on", which is
    wrong in both directions: a tool result is written as a user line, and a turn that has
    finished looks exactly like a question. The scan no longer has an opinion about waiting.
    """
    import os
    import shutil
    import tempfile
    import time

    saved_roots = statusd.SESSION_ROOTS
    saved_manual = dict(statusd._manual)
    saved_window = statusd.Handler.window
    tmp = tempfile.mkdtemp()
    try:
        statusd.Handler.window = 900
        statusd.SESSION_ROOTS = [Path(tmp)]
        proj = Path(tmp) / "C--Users-admin-repo"
        proj.mkdir()
        session = proj / "s.jsonl"
        session.write_text(
            '{"type":"user","message":{"role":"user","content":"do the thing"}}\n'
            '{"type":"tool_result","message":{"role":"user","content":"ok"}}\n',
            encoding="utf-8")
        now = time.time()
        os.utime(session, (now, now))
        statusd._manual.clear()

        _code, body = get(base + "/status.json")
        check("scan: live session -> developing",
              body["status"] == "developing" and body["active"] == 1, body)
        check("scan: never infers waiting", body["waiting"] == 0, body)

        quiet = now - 600
        os.utime(session, (quiet, quiet))
        _code, body = get(base + "/status.json")
        check("scan: quiet session -> idle",
              body["status"] == "idle" and body["active"] == 0, body)

        statusd._manual.clear()
        statusd._manual.update({"status": "developing", "active": 2, "waiting": 1,
                                "detail": "xiaomi", "ts": time.time() - 3600})
        _code, body = get(base + "/status.json")
        check("push older than its TTL stops counting",
              body["status"] == "idle" and body["source"] == "sessions", body)
    finally:
        statusd._manual.clear()
        statusd._manual.update(saved_manual)
        statusd.SESSION_ROOTS = saved_roots
        statusd.Handler.window = saved_window
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    statusd.Handler.window = 60
    srv = ThreadingHTTPServer(("127.0.0.1", 0), statusd.Handler)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = "http://127.0.0.1:%d" % port

    try:
        code, body = get(base + "/status.json")
        check("GET status.json", code == 200 and "status" in body, body)
        check("schema keys", set(body) >= {"active", "waiting", "status", "detail", "updated"}, sorted(body))
        check("status in enum", body["status"] in ("developing", "waiting", "idle"), body["status"])

        # A client still claiming "waiting for a human" is not believed: nothing on this machine
        # can tell a finished turn from a blocked question, and a wrong urgent state is worse
        # than a quiet one.
        code, body = post(base + "/push", {"status": "waiting", "waiting": 2, "active": 3, "detail": "needs you"})
        check("POST /push", code == 200, body)
        check("waiting claim folded into idle", body["status"] == "idle" and body["waiting"] == 0, body)

        # Dropped, not stored: an unverifiable claim must not outlive the scan that can see
        # the truth, otherwise one stuck client pins the panel for its whole TTL.
        code, body = get(base + "/status.json")
        check("waiting claim is not retained", body["source"] == "sessions", body)

        code, body = post(base + "/push", {"status": "idle", "active": 0, "waiting": 0, "detail": ""})
        check("push back to idle", body["status"] == "idle" and body["active"] == 0, body)

        try:
            post(base + "/push", {"status": "bogus", "active": 1, "waiting": 0})
            check("bogus status normalised", True, "accepted")
        except Exception as e:
            check("bogus status normalised", False, str(e))

        code, body = get(base + "/status.json")
        check("bogus normalised to idle", body["status"] == "idle", body["status"])

        try:
            urllib.request.urlopen(base + "/nope", timeout=5)
            check("404 for unknown path", False, "no error")
        except urllib.error.HTTPError as e:
            check("404 for unknown path", e.code == 404, e.code)

        code = urllib.request.urlopen(base + "/health", timeout=5).status
        check("health", code == 200, code)

        # A malformed body must be a 400, not a crash.
        req = urllib.request.Request(base + "/push", data=b"{oops",
                                     headers={"Content-Type": "application/json"})
        try:
            urllib.request.urlopen(req, timeout=5)
            check("bad push body -> 400", False, "no error")
        except urllib.error.HTTPError as e:
            check("bad push body -> 400", e.code == 400, e.code)

        # And the server still answers afterwards.
        code, body = get(base + "/status.json")
        check("alive after bad body", code == 200, code)

        longpoll_tests(base)
        merge_test(base)
        claude_turn_test(base)
        attribution_test(base)
        scan_tests(base)
    finally:
        srv.shutdown()

    print("ALL PASS" if fails == 0 else "%d FAILURES" % fails)
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
