#!/usr/bin/env python3
"""Client for the Agent Bridge localhost command server (runs agent-browser as the app UID).

Usage:
  python3 bridge.py <token> <agent-browser args...>
  python3 bridge.py --token-from-logcat <agent-browser args...>
"""
import json
import re
import socket
import subprocess
import sys

HOST, PORT = "127.0.0.1", 9224


def token_from_logcat():
    out = subprocess.run(
        ["logcat", "-d", "-s", "AgentBridge:I"],
        capture_output=True, text=True, timeout=30).stdout
    hits = re.findall(r"AB-TOKEN: ([0-9a-f]{16,})", out)
    if not hits:
        raise SystemExit("token not found in logcat")
    return hits[-1]


def call(token, args, timeout=320):
    s = socket.create_connection((HOST, PORT), timeout=15)
    s.settimeout(timeout)
    s.sendall((json.dumps({"token": token, "args": args}) + "\n").encode())
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = s.recv(65536)
        if not chunk:
            break
        buf += chunk
    s.close()
    return json.loads(buf.decode("utf-8", "replace"))


def main():
    argv = sys.argv[1:]
    if not argv:
        raise SystemExit(__doc__)
    if argv[0] == "--token-from-logcat":
        token = token_from_logcat()
        argv = argv[1:]
    else:
        token = argv.pop(0)
    r = call(token, argv)
    sys.stdout.write(r.get("out") or "")
    if r.get("err"):
        sys.stderr.write(r["err"])
    print(f"\n[exit {r.get('code')}]", file=sys.stderr)
    return 0 if r.get("code") == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
