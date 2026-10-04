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
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
with tempfile.TemporaryDirectory(prefix="tvcompiler-browser-smoke-") as state:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(root / "src")
    server = subprocess.Popen(
        [sys.executable, str(root / "scripts/fake_browser_server.py"), state],
        cwd=root,
        env=env | {"SMOKE_PORT": str(port)},
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError("fake browser server exited during startup")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                    break
            except OSError:
                time.sleep(0.1)
        else:
            raise TimeoutError("fake browser server did not start")
        smoke_env = env | {"PLAYWRIGHT_MODULE": module, "SMOKE_URL": f"http://127.0.0.1:{port}"}
        subprocess.run(["node", str(root / "scripts/browser-smoke.mjs")], cwd=root, env=smoke_env, check=True)
    finally:
        server.terminate()
        try:
            server.wait(timeout=15)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
