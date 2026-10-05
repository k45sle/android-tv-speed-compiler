from __future__ import annotations

import asyncio
import os
import sys
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))
from fastapi.responses import JSONResponse  # noqa: E402
from test_web import FakeAdb, package  # noqa: E402

from tvcompiler.adb import BusyStatus, DiscoveredService  # noqa: E402
from tvcompiler.web import create_app  # noqa: E402


class BrowserAdb(FakeAdb):
    force_pair_fallback = False

    def pair(self, endpoint: str, pairing_code: str, *, require_confirmation=False):
        result = super().pair(endpoint, pairing_code, require_confirmation=require_confirmation)
        if require_confirmation:
            host = endpoint.rsplit(":", 1)[0]
            self.discovered = [
                DiscoveredService("pairing", "adb-tv._adb-tls-pairing._tcp", f"{host}:37123"),
                DiscoveredService("connect", "adb-tv._adb-tls-connect._tcp", f"{host}:41267"),
            ]
        return result

    def connect(self, endpoint: str) -> str:
        self.installed[endpoint] = ["com.nuvio.tv", "com.nuvio.tv.test"]
        self.packages[(endpoint, "com.nuvio.tv")] = package(11)
        self.packages[(endpoint, "com.nuvio.tv.test")] = replace(package(3), package_id="com.nuvio.tv.test")
        return super().connect(endpoint)

    def resolve_paired_device(self, guid):
        if self.force_pair_fallback:
            return None
        resolved = super().resolve_paired_device(guid)
        if resolved is None:
            return None
        endpoint, identity = resolved
        self.connect(endpoint)
        return endpoint, identity

    def busy_status(self, serial: str) -> BusyStatus:
        return BusyStatus(True, True, ("screen active", "playback active"))


mode = os.environ.get("SMOKE_MODE", "token")
browser_adb = BrowserAdb()
app = create_app(
    Path(sys.argv[1]) / "instance",
    adb=browser_adb,
    start_scheduler=True,
    environ={"TVCOMPILER_LOCAL_SETUP": "1"} if mode == "local" else {
        "TVCOMPILER_BOOTSTRAP_TOKEN": "fake-browser-smoke-bootstrap-token-2026-only"
    },
)

fail_second_app_once = True
delay_stale_inventory = False
delay_first_watch_once = True
delay_finish_once = True


@app.middleware("http")
async def deterministic_browser_scenarios(request, call_next):
    global delay_stale_inventory, fail_second_app_once, delay_first_watch_once, delay_finish_once
    restore_pair_fallback = False
    if request.method == "POST" and request.url.path == "/api/pair":
        body = await request.body()
        if b'"name":"Smoke stale"' in body:
            delay_stale_inventory = True
        if b'"name":"Smoke fallback"' in body:
            browser_adb.force_pair_fallback = True
            restore_pair_fallback = True

        async def replay_pair_body():
            return {"type": "http.request", "body": body, "more_body": False}

        request._receive = replay_pair_body
        await asyncio.sleep(0.6)
        if b'"name":"Smoke pair failure"' in body:
            return JSONResponse({"detail": "Injected one-time pairing failure."}, status_code=503)
    if request.method == "GET" and request.url.path.endswith("/inventory") and delay_stale_inventory:
        delay_stale_inventory = False
        await asyncio.sleep(0.8)
    if request.method == "POST" and request.url.path.endswith("/watch"):
        body = await request.body()
        if b'"package_id":"com.nuvio.tv"' in body and delay_first_watch_once:
            delay_first_watch_once = False
            await asyncio.sleep(0.6)
        if b'"package_id":"com.nuvio.tv.test"' in body and fail_second_app_once:
            fail_second_app_once = False
            return JSONResponse({"detail": "Injected one-time app save failure."}, status_code=503)

        async def replay_body():
            return {"type": "http.request", "body": body, "more_body": False}

        request._receive = replay_body
    if request.method == "POST" and request.url.path.endswith("/finish"):
        body = await request.body()
        if delay_finish_once:
            delay_finish_once = False

            async def replay_finish_body():
                return {"type": "http.request", "body": body, "more_body": False}

            request._receive = replay_finish_body
            await asyncio.sleep(0.6)
    response = await call_next(request)
    if restore_pair_fallback:
        browser_adb.force_pair_fallback = False
    return response

import uvicorn  # noqa: E402

uvicorn.run(app, host="127.0.0.1", port=int(os.environ["SMOKE_PORT"]), workers=1, log_level="warning")
