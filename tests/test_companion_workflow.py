from __future__ import annotations

from contextlib import contextmanager
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from tvcompiler.adb import DeviceIdentity
from tvcompiler.companion import CompanionSetupController, CompanionSetupError
from tvcompiler.scheduler import Scheduler
from tvcompiler.store import Store
from tvcompiler.web import CSRF_COOKIE, create_app


class FakeArtifactManager:
    def __init__(self):
        self.downloads = 0
        self.cached_calls = 0

    def cached(self):
        self.cached_calls += 1
        return None

    def download(self):
        self.downloads += 1
        return object()


class FakeBridge:
    def __init__(self, *, paired=False, malformed=False):
        self.paired = paired
        self.malformed = malformed
        self.target_port = 0
        self.web_enabled = True
        self.seen_code = None

    def status(self):
        if self.malformed:
            return {"isPaired": "yes", "targetPort": self.target_port, "webServerEnabled": True}
        return {
            "isPaired": self.paired,
            "targetPort": self.target_port,
            "webServerEnabled": self.web_enabled,
        }

    def set_target_port(self, port):
        self.target_port = port

    def pair(self, code, port):
        self.seen_code = code
        self.paired = True

    def disable_webserver(self):
        self.web_enabled = False


class FakeAdb:
    def __init__(self):
        self.sdk = 34
        self.bridge = FakeBridge()
        self.install_calls = self.enable_calls = self.grant_calls = self.launch_calls = 0
        self.tcpip_calls = []
        self.endpoint_verified = False
        self.endpoint_failures = 0
        self.forget_on_verify = False
        self.pause_on_verify = False
        self.wrong_identity = False
        self.store = None
        self.fail_switch = False

    def reconnect_with_selector(self, *, expected_serial, expected_fingerprint, endpoint):
        assert endpoint == "10.0.0.5:41267"
        return SimpleNamespace(
            selector="transport-serial", identity=DeviceIdentity(expected_serial, expected_fingerprint)
        )

    def companion_sdk_level(self, serial):
        assert serial == "transport-serial"
        return self.sdk

    def install_companion(self, serial, artifact):
        self.install_calls += 1

    def enable_companion(self, serial):
        self.enable_calls += 1

    def grant_companion_permission(self, serial):
        self.grant_calls += 1

    def launch_companion(self, serial):
        self.launch_calls += 1

    def set_companion_tcpip(self, serial, port):
        self.tcpip_calls.append((serial, port))
        if self.fail_switch:
            raise RuntimeError("transport disappeared")

    def verify_companion_endpoint(self, endpoint, *, expected_serial, expected_fingerprint):
        assert endpoint == "10.0.0.5:5555"
        assert (expected_serial, expected_fingerprint) == ("hardware-1", "fingerprint-1")
        if self.endpoint_failures:
            self.endpoint_failures -= 1
            raise RuntimeError("not connected")
        self.endpoint_verified = True
        if self.forget_on_verify:
            self.store.forget_device("tv")
        if self.pause_on_verify:
            self.store.set_device_enabled("tv", False)
        serial = "different-hardware" if self.wrong_identity else "hardware-1"
        return SimpleNamespace(identity=DeviceIdentity(serial, "fingerprint-1"))


class FakeScheduler:
    @contextmanager
    def maintenance_operation(self):
        yield


def make_controller(tmp_path, *, paired=False, malformed=False):
    store = Store(tmp_path / "state.sqlite3")
    store.upsert_device("tv", "Living room", "10.0.0.5:41267", "hardware-1", "fingerprint-1", "wireless")
    adb = FakeAdb()
    adb.bridge = FakeBridge(paired=paired, malformed=malformed)
    artifacts = FakeArtifactManager()
    controller = CompanionSetupController(
        store, adb, FakeScheduler(), tmp_path,
        artifact_manager=artifacts, bridge_factory=lambda _serial, **_kwargs: adb.bridge,
    )
    return controller, store, adb, artifacts


def test_requires_explicit_consent_and_sdk_floor_precedes_download(tmp_path):
    controller, store, adb, artifacts = make_controller(tmp_path)
    with pytest.raises(ValueError, match="Explicit consent"):
        controller.prepare("tv", consent=False)
    assert adb.install_calls == artifacts.downloads == 0

    adb.sdk = 29
    with pytest.raises(ValueError, match="Android 11"):
        controller.prepare("tv", consent=True)
    assert adb.install_calls == artifacts.downloads == 0
    assert store.get_companion_setup("tv")["phase"] == "failed"


def test_prepare_pair_and_finalize_pinned_endpoint_without_persisting_code(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path)
    adb.store = store
    prepared = controller.prepare("tv", consent=True)
    assert prepared == {
        "phase": "needs_pairing", "ready": False, "paired": False,
        "web_disabled": False, "target_port": 5555, "reason": None,
    }
    assert (adb.install_calls, adb.enable_calls, adb.grant_calls, adb.launch_calls) == (1, 1, 1, 1)
    assert store.get_device("tv").endpoint == "10.0.0.5:41267"

    secret = "001234"
    finished = controller.pair("tv", pairing_code=secret, pairing_port=37123)
    assert finished["ready"] is True and finished["paired"] is True and finished["web_disabled"] is True
    assert finished["target_port"] == 5555
    assert adb.bridge.seen_code == secret
    assert adb.tcpip_calls == [("transport-serial", 5555)]
    saved = store.get_device("tv")
    assert (saved.endpoint, saved.connection_mode) == ("10.0.0.5:5555", "tcpip")
    assert secret not in repr(store.get_companion_setup("tv"))


def test_already_paired_companion_finalizes_without_pair_code(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path, paired=True)
    adb.store = store
    result = controller.prepare("tv", consent=True)
    assert result["ready"] is True
    assert store.get_companion_setup("tv")["paired"] is True
    assert adb.bridge.seen_code is None


def test_same_target_ready_retry_verifies_endpoint_without_reopening_app(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path, paired=True)
    first = controller.prepare("tv", consent=True)
    installs = adb.install_calls
    second = controller.prepare("tv", consent=True)
    assert first["ready"] and second["ready"]
    assert adb.install_calls == installs == 1
    assert store.get_device("tv").endpoint == "10.0.0.5:5555"


def test_malformed_companion_status_fails_and_is_sanitized(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path, malformed=True)
    with pytest.raises(CompanionSetupError):
        controller.prepare("tv", consent=True)
    record = store.get_companion_setup("tv")
    assert record["phase"] == "failed"
    assert "yes" not in repr(record) and "pairing" not in repr(record)
    assert adb.install_calls == 1


def test_pairing_code_is_removed_from_exceptions_and_saved_reason(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path)
    controller.prepare("tv", consent=True)
    secret = "001234"

    def leak_code(code, _port):
        raise ValueError(f"bad input {code}")

    adb.bridge.pair = leak_code
    with pytest.raises(CompanionSetupError) as error:
        controller.pair("tv", pairing_code=secret, pairing_port=37123)
    assert secret not in str(error.value)
    assert secret not in repr(store.get_companion_setup("tv"))


def test_http_disabled_partial_setup_retries_without_reopening_companion(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path)
    adb.bridge.paired = True
    adb.store = store
    adb.endpoint_failures = 4
    with pytest.raises(CompanionSetupError):
        controller.prepare("tv", consent=True)
    partial = store.get_companion_setup("tv")
    assert partial["web_disabled"] is True
    adb.fail_switch = True
    resumed = controller.prepare("tv", consent=True)
    assert resumed["ready"] is True
    assert adb.install_calls == 1
    assert len(adb.tcpip_calls) == 2


def test_forget_during_endpoint_verification_never_resurrects_device(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path, paired=True)
    adb.store = store
    adb.forget_on_verify = True
    with pytest.raises(ValueError, match="removed or changed"):
        controller.prepare("tv", consent=True)
    assert store.get_device("tv") is None
    assert store.get_companion_setup("tv") is None


def test_pause_during_endpoint_verification_prevents_ready_state(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path, paired=True)
    adb.store = store
    adb.pause_on_verify = True
    with pytest.raises(CompanionSetupError):
        controller.prepare("tv", consent=True)
    assert store.get_device("tv").enabled is False
    assert store.get_device("tv").connection_mode == "wireless"
    assert store.get_companion_setup("tv")["phase"] == "failed"


def test_wrong_endpoint_identity_never_marks_ready(tmp_path):
    controller, store, adb, _artifacts = make_controller(tmp_path, paired=True)
    adb.wrong_identity = True
    with pytest.raises(CompanionSetupError):
        controller.prepare("tv", consent=True)
    assert store.get_device("tv").endpoint == "10.0.0.5:41267"
    assert store.get_companion_setup("tv")["phase"] == "failed"


def test_busy_maintenance_operation_is_available_to_http_layer():
    scheduler = Scheduler.__new__(Scheduler)
    import threading

    scheduler._run_lock = threading.Lock()
    scheduler._run_lock.acquire()
    try:
        with pytest.raises(RuntimeError, match="scheduler is busy"):
            with scheduler.maintenance_operation():
                pass
    finally:
        scheduler._run_lock.release()


def test_http_routes_inject_controller_and_finish_is_gated(tmp_path):
    class BrowserFake:
        def __init__(self):
            self.current = {"phase": "not_started", "ready": False, "paired": False,
                            "web_disabled": False, "target_port": 5555, "reason": None}
            self.store = None
            self.busy = False

        def status(self, _device_id):
            return self.current

        def prepare(self, _device_id, *, consent, target_port=5555):
            if self.busy:
                raise RuntimeError("scheduler is busy; wait for the current ADB operation to finish")
            assert consent is True and target_port == 5555
            self.current = {**self.current, "phase": "needs_pairing"}
            self.store.save_companion_setup(
                _device_id, phase="needs_pairing", target_port=target_port,
                bootstrap_endpoint="10.0.0.5:41267",
            )
            return self.current

        def pair(self, _device_id, *, pairing_code, pairing_port):
            assert pairing_code == "001234" and pairing_port == 37123
            self.current = {**self.current, "phase": "ready", "ready": True,
                            "paired": True, "web_disabled": True}
            self.store.update_companion_setup(_device_id, phase="ready", paired=True, web_disabled=True)
            return self.current

        def verify_ready(self, _device_id):
            if self.busy:
                raise RuntimeError("scheduler is busy; wait for the current ADB operation to finish")
            return self.current

    base = tmp_path / "instance"
    fake = BrowserFake()
    app = create_app(
        base,
        companion_controller=fake,
        start_scheduler=False,
        environ={"LOGIN_ENABLE": "true", "TVCOMPILER_BOOTSTRAP_TOKEN": "test-token-long-enough-for-bootstrap"},
    )
    app.state.store.upsert_device("tv", "TV", "10.0.0.5:41267", "serial", "fingerprint")
    fake.store = app.state.store
    with TestClient(app) as client:
        client.get("/")
        setup = client.post(
            "/api/setup",
            json={
                "token": "test-token-long-enough-for-bootstrap",
                "password": "correct horse",
                "csrf": client.cookies[CSRF_COOKIE],
            },
        )
        assert setup.status_code == 200
        csrf = {"X-CSRF-Token": client.cookies[CSRF_COOKIE]}
        device_id = "tv"
        path = f"/api/devices/{device_id}/companion"
        assert client.get(path).json()["phase"] == "not_started"
        fake.busy = True
        busy = client.post(path + "/prepare", json={"consent": True}, headers=csrf)
        assert busy.status_code == 409
        assert "Another ADB operation" in busy.json()["error"]
        fake.busy = False
        assert client.post(path + "/prepare", json={"consent": True}, headers=csrf).json()["phase"] == "needs_pairing"
        blocked = client.post(f"/api/devices/{device_id}/finish", json={}, headers=csrf)
        assert blocked.status_code == 409
        paired = client.post(
            path + "/pair", json={"pairing_code": "001234", "pairing_port": 37123}, headers=csrf
        )
        assert paired.json()["ready"]
        fake.busy = True
        finished = client.post(
            f"/api/devices/{device_id}/finish", json={"require_companion": True}, headers=csrf
        )
        assert finished.status_code == 409
        assert "Another ADB operation" in finished.json()["error"]
        fake.busy = False
        finished = client.post(
            f"/api/devices/{device_id}/finish", json={"require_companion": True}, headers=csrf
        )
        assert finished.status_code == 200
