from __future__ import annotations

import os
import sys
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))
from test_web import FakeAdb, package  # noqa: E402

from tvcompiler.adb import BusyStatus  # noqa: E402
from tvcompiler.web import create_app  # noqa: E402


class BrowserAdb(FakeAdb):
    def connect(self, endpoint: str) -> str:
        self.installed[endpoint] = ["com.nuvio.tv", "com.nuvio.tv.test"]
        self.packages[(endpoint, "com.nuvio.tv")] = package(11)
        self.packages[(endpoint, "com.nuvio.tv.test")] = replace(package(3), package_id="com.nuvio.tv.test")
        return super().connect(endpoint)

    def busy_status(self, serial: str) -> BusyStatus:
        return BusyStatus(True, True, ("screen active", "playback active"))


app = create_app(
    Path(sys.argv[1]) / "instance",
    adb=BrowserAdb(),
    start_scheduler=True,
    environ={"TVCOMPILER_BOOTSTRAP_TOKEN": "fake-browser-smoke-bootstrap-token-2026-only"},
)

import uvicorn  # noqa: E402

uvicorn.run(app, host="127.0.0.1", port=int(os.environ["SMOKE_PORT"]), workers=1, log_level="warning")
