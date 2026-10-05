from __future__ import annotations

import os
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
module = os.environ.get("PLAYWRIGHT_MODULE")
if not module:
    raise SystemExit("Set PLAYWRIGHT_MODULE to an installed Playwright package path")
def available_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


with tempfile.TemporaryDirectory(prefix="tvcompiler-browser-smoke-") as state:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(root / "src")
    servers = []
    urls = {}
    try:
        for mode in ("local", "token", "missing-adb", "noauth"):
            port = available_port()
            server = subprocess.Popen(
                [sys.executable, str(root / "scripts/fake_browser_server.py"), str(Path(state) / mode)],
                cwd=root,
                env=env | {"SMOKE_PORT": str(port), "SMOKE_MODE": mode},
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            servers.append(server)
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                if server.poll() is not None:
                    raise RuntimeError(f"fake {mode} browser server exited during startup")
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise TimeoutError(f"fake {mode} browser server did not start")
            urls[mode] = f"http://127.0.0.1:{port}"
        smoke_env = env | {
            "PLAYWRIGHT_MODULE": module,
            "SMOKE_URL": urls["token"],
            "SMOKE_LOCAL_URL": urls["local"],
            "SMOKE_MISSING_ADB_URL": urls["missing-adb"],
            "SMOKE_NOAUTH_URL": urls["noauth"],
        }
        subprocess.run(["node", str(root / "scripts/browser-smoke.mjs")], cwd=root, env=smoke_env, check=True)
    finally:
        for server in servers:
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
