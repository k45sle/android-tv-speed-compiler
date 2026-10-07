from __future__ import annotations

import asyncio
import os
import sys
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests"))
from fastapi.responses import JSONResponse  # noqa: E402
from test_web import FakeAdb, package  # noqa: E402

from tvcompiler import web as web_module  # noqa: E402
from tvcompiler.adb import BusyStatus, DeviceIdentity, DiscoveredService  # noqa: E402
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


class BrowserCompanion:
    """Synthetic companion workflow; it persists the same Finish-gated state as production."""

    def __init__(self, store, client):
        self.store = store
        self.client = client
        self.prepare_calls = {}
        self.pair_calls = {}

    @staticmethod
    def _reply(record):
        return {
            "phase": record["phase"], "paired": record["paired"],
            "web_disabled": record["web_disabled"], "target_port": record["target_port"],
            "reason": record["reason"], "ready": record["phase"] == "ready",
        }

    def status(self, device_id):
        record = self.store.get_companion_setup(device_id)
        if record is None:
            return {"phase": "not_started", "paired": False, "web_disabled": False,
                    "target_port": 5555, "reason": None, "ready": False}
        return self._reply(record)

    def is_current_ready(self, device_id):
        return self.status(device_id)["ready"]

    def verify_ready(self, device_id):
        status = self.status(device_id)
        if not status["ready"]:
            raise ValueError("Synthetic companion is not ready")
        return status

    def prepare(self, device_id, *, consent, target_port=5555):
        if not consent:
            raise ValueError("Explicit consent is required")
        device = self.store.get_device(device_id)
        calls = self.prepare_calls.get(device_id, 0)
        self.prepare_calls[device_id] = calls + 1
        phase = "failed" if device.name == "Companion retry TV" and calls == 0 else "needs_pairing"
        if device.name == "Companion already paired TV":
            phase = "ready"
        self.store.save_companion_setup(
            device_id, phase=phase, reason="Synthetic setup failure" if phase == "failed" else None,
            target_port=target_port, bootstrap_endpoint=device.endpoint,
            paired=phase == "ready", web_disabled=phase == "ready",
            expected_serial=device.serial, expected_fingerprint=device.fingerprint,
        )
        if phase == "ready":
            self._save_fixed_endpoint(device_id, device, target_port)
        return self.status(device_id)

    def pair(self, device_id, *, pairing_code, pairing_port):
        device = self.store.get_device(device_id)
        record = self.store.get_companion_setup(device_id)
        calls = self.pair_calls.get(device_id, 0)
        self.pair_calls[device_id] = calls + 1
        if device.name == "Companion retry TV" and calls == 0:
            raise ValueError("Synthetic pairing failure")
        if record is None or record["phase"] != "needs_pairing":
            raise ValueError("Prepare the companion before pairing")
        self.store.update_companion_setup(device_id, phase="paired", paired=True, reason=None)
        self.store.update_companion_setup(device_id, web_disabled=True)
        self._save_fixed_endpoint(device_id, device, record["target_port"])
        return self.status(device_id)

    def _save_fixed_endpoint(self, device_id, device, target_port):
        host = device.endpoint.rsplit(":", 1)[0]
        endpoint = f"{host}:{target_port}"
        self.store.complete_companion_setup(
            device_id, expected_endpoint=device.endpoint, expected_serial=device.serial,
            expected_fingerprint=device.fingerprint, endpoint=endpoint,
        )
        # The synthetic TV keeps its pinned identity across the port change.
        self.client.devices[endpoint] = DeviceIdentity(device.serial, device.fingerprint)
        self.client.connect(endpoint)


mode = os.environ.get("SMOKE_MODE", "token")
browser_adb = BrowserAdb()
web_module.CompanionSetupController = lambda store, client, _jobs, _base: BrowserCompanion(store, client)
environ = {"LOGIN_ENABLE": "false"} if mode == "noauth" else {"LOGIN_ENABLE": "true"}
if mode == "local":
    environ["TVCOMPILER_LOCAL_SETUP"] = "1"
elif mode != "noauth":
    environ["TVCOMPILER_BOOTSTRAP_TOKEN"] = "fake-browser-smoke-bootstrap-token-2026-only"
if mode == "missing-adb":
    environ["TVCOMPILER_ADB_PATH"] = "tvcompiler-browser-smoke-missing-adb"
app = create_app(
    Path(sys.argv[1]) / "instance",
    adb=browser_adb,
    start_scheduler=True,
    environ=environ,
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
