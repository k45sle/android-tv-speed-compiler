from __future__ import annotations

import hashlib
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from tvcompiler.adb import (
    AdbError,
    AdbResult,
    BusyStatus,
    CompilationInspection,
    DeviceIdentity,
    DiscoveredService,
    PackageInfo,
)
from tvcompiler.scheduler import Scheduler
from tvcompiler.store import Store
from tvcompiler.web import COOKIE_NAME, CSRF_COOKIE, create_app

TOKEN = "bootstrap-token-test-value-should-be-long-enough-12345"
PASSWORD = "correct horse battery staple"


def package(version: int = 10) -> PackageInfo:
    return PackageInfo(
        "com.nuvio.tv", None, "2.0", version, f"2026-10-0{version % 9 + 1} 12:00:00", f"/data/app/{version}/base.apk"
    )


class FakeAdb:
    def __init__(self):
        self.devices: dict[str, DeviceIdentity] = {}
        self.packages: dict[tuple[str, str], PackageInfo] = {}
        self.installed: dict[str, list[str]] = {}
        self.fail: dict[str, Exception] = {}
        self.discovered = [
            DiscoveredService("pairing", "adb-tv._adb-tls-pairing._tcp", "10.0.0.5:37123"),
            DiscoveredService("connect", "adb-tv._adb-tls-connect._tcp", "10.0.0.5:41267"),
        ]
        self.pair_code_seen = None
        self.block_reconnect = False
        self.reconnect_entered = threading.Event()
        self.reconnect_release = threading.Event()
        self.block_inventory = False
        self.inventory_entered = threading.Event()
        self.inventory_release = threading.Event()
        self.compile_calls = []

    def pair(self, endpoint: str, pairing_code: str):
        self.pair_code_seen = pairing_code
        if endpoint in self.fail:
            raise self.fail[endpoint]
        return "paired"

    def connect(self, endpoint: str):
        if endpoint in self.fail:
            raise self.fail[endpoint]
        self.devices.setdefault(endpoint, DeviceIdentity(f"serial-{endpoint}", f"build-{endpoint}"))
        return "connected"

    def identity(self, serial: str):
        if serial in self.fail:
            raise self.fail[serial]
        return self.devices.setdefault(serial, DeviceIdentity(f"serial-{serial}", f"build-{serial}"))

    def discover(self):
        return self.discovered

    def reconnect(self, *, expected_serial, expected_fingerprint, endpoint):
        self.reconnect_entered.set()
        if self.block_reconnect:
            self.reconnect_release.wait(3)
        if endpoint in self.fail:
            raise self.fail[endpoint]
        identity = self.identity(endpoint or "fallback:55555")
        if expected_serial and identity.serial != expected_serial:
            raise AdbError("identity", "does not match saved identity")
        if expected_fingerprint and identity.build_fingerprint != expected_fingerprint:
            raise AdbError("identity", "build fingerprint changed")
        return endpoint, identity

    def installed_packages(self, serial):
        return list(self.installed.get(serial, []))

    def package_info(self, serial, package_id):
        if self.block_inventory:
            self.inventory_entered.set()
            self.inventory_release.wait(3)
        try:
            return self.packages[(serial, package_id)]
        except KeyError as exc:
            raise AdbError("package", "not installed") from exc

    def busy_status(self, serial):
        return BusyStatus(False, False, ())

    def compile_speed(self, serial, package_id):
        self.compile_calls.append((serial, package_id))
        return AdbResult(("adb",), 0, "Success\n", "")

    def inspect_compilation(self, serial, package_id):
        return CompilationInspection(True, "speed", "all entries speed")


def make_app(tmp_path: Path, adb: FakeAdb | None = None, *, start_scheduler=False, env=None):
    adb = adb or FakeAdb()
    instance = tmp_path / "instance"
    store = Store(instance / "state.sqlite3")
    scheduler = Scheduler(store, adb, instance, base_backoff=1, max_backoff=2)
    app = create_app(
        instance,
        adb=adb,
        scheduler=scheduler,
        start_scheduler=start_scheduler,
        environ={"TVCOMPILER_BOOTSTRAP_TOKEN": TOKEN} if env is None else env,
    )
    return app, adb, store, scheduler, instance


def setup_account(client: TestClient, token=TOKEN, password=PASSWORD):
    page = client.get("/")
    assert page.status_code == 200
    csrf = client.cookies[CSRF_COOKIE]
    return client.post("/api/setup", json={"token": token, "password": password, "csrf": csrf})


def authenticated(tmp_path: Path, adb=None):
    app, adb, store, scheduler, instance = make_app(tmp_path, adb)
    client = TestClient(app)
    result = setup_account(client)
    assert result.status_code == 200
    return client, adb, store, scheduler, instance


def add_tv(client: TestClient, name="Living room", endpoint="10.0.0.5:41267"):
    result = client.post(
        "/api/devices", json={"name": name, "endpoint": endpoint}, headers={"X-CSRF-Token": client.cookies[CSRF_COOKIE]}
    )
    assert result.status_code == 200, result.text
    return result.json()


def test_health_offline_setup_token_file_permissions_and_no_secret_health(tmp_path):
    app, adb, _store, _scheduler, instance = make_app(tmp_path, env={})
    bootstrap = instance / "bootstrap.token"
    assert bootstrap.exists() and bootstrap.stat().st_mode & 0o777 == 0o600
    with TestClient(app) as client:
        response = client.get("/health")
        assert response.json() == {"status": "ok"}
        assert TOKEN not in response.text and bootstrap.read_text().strip() not in response.text
        assert client.get("/api/status").status_code == 401
    # Once setup consumes the token, a restart does not create a new first-run token.
    bootstrap_token = bootstrap.read_text().strip()
    c = TestClient(app)
    c.get("/")
    assert (
        c.post(
            "/api/setup", json={"token": bootstrap_token, "password": PASSWORD, "csrf": c.cookies[CSRF_COOKIE]}
        ).status_code
        == 200
    )
    app2 = create_app(instance, adb=adb, start_scheduler=False, environ={})
    assert not bootstrap.exists()
    with TestClient(app2) as restarted:
        assert restarted.get("/health").json() == {"status": "ok"}
        assert not bootstrap.exists()


def test_setup_csrf_password_hash_session_expiry_logout_and_auth_routes(tmp_path):
    app, adb, store, scheduler, instance = make_app(tmp_path)
    with TestClient(app) as c:
        page = c.get("/")
        csrf = c.cookies[CSRF_COOKIE]
        assert 'name="token"' in page.text
        setup_response = c.post("/api/setup", json={"token": TOKEN, "password": PASSWORD, "csrf": csrf})
        assert setup_response.status_code == 200
        cookie = c.cookies.get(COOKIE_NAME)
        assert cookie and PASSWORD not in cookie
        set_cookie = setup_response.headers["set-cookie"].lower()
        assert "httponly" in set_cookie and "samesite=strict" in set_cookie and "secure" not in set_cookie
        assert c.get("/api/status").status_code == 200
        with sqlite3.connect(instance / "auth.sqlite3") as db:
            salt, password_hash = db.execute("select salt,hash from users").fetchone()
            stored_token = db.execute("select token_hash,csrf_hash,expires_at from sessions").fetchone()
        assert hashlib.scrypt(PASSWORD.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32) == password_hash
        assert PASSWORD.encode() not in bytes(password_hash) and cookie.encode() not in bytes(stored_token[0])
        assert TOKEN not in (instance / "auth.sqlite3").read_bytes().decode("latin1")
        restarted_app = create_app(instance, adb=adb, start_scheduler=False, environ={})
        with TestClient(restarted_app) as restarted:
            restarted.cookies.set(COOKIE_NAME, cookie)
            restarted.cookies.set(CSRF_COOKIE, c.cookies[CSRF_COOKIE])
            assert restarted.get("/api/status").status_code == 200
        # Expired and revoked opaque sessions cannot authorize diagnostics.
        with sqlite3.connect(instance / "auth.sqlite3") as db:
            db.execute("update sessions set expires_at='2000-01-01T00:00:00+00:00'")
        assert c.get("/api/diagnostics").status_code == 401
        c.cookies.set(COOKIE_NAME, cookie)
        with sqlite3.connect(instance / "auth.sqlite3") as db:
            db.execute("update sessions set expires_at='2999-01-01T00:00:00+00:00'")
        csrf_token = c.cookies[CSRF_COOKIE]
        assert c.post("/api/logout", headers={"X-CSRF-Token": csrf_token}).status_code == 200
        assert c.get("/api/status").status_code == 401
        assert (
            c.post(
                "/api/pair",
                json={
                    "name": "x",
                    "endpoint": "10.0.0.1:5",
                    "pairing_endpoint": "10.0.0.1:4",
                    "pairing_code": "123456",
                },
            ).status_code
            == 401
        )


def test_login_generic_error_csrf_origin_and_bounded_throttling(tmp_path):
    app, *_ = make_app(tmp_path)
    with TestClient(app) as c:
        setup_account(c)
        c.post("/api/logout", headers={"X-CSRF-Token": c.cookies[CSRF_COOKIE]})
        c.get("/")
        csrf = c.cookies[CSRF_COOKIE]
        for _ in range(5):
            response = c.post("/api/login", json={"password": "bad password value", "csrf": csrf})
            assert response.status_code == 401 and "password" not in response.text
        assert c.post("/api/login", json={"password": PASSWORD, "csrf": csrf}).status_code == 429
        app.state.security.failures.clear()
        # A valid session's mutating API rejects missing CSRF and hostile Origin.
        c.post("/api/login", json={"password": PASSWORD, "csrf": csrf}, headers={"X-Forwarded-For": "127.0.0.1"})
        assert c.put("/api/monitoring", json={"enabled": True}).status_code == 403
        assert (
            c.put(
                "/api/monitoring",
                json={"enabled": True},
                headers={"X-CSRF-Token": c.cookies[CSRF_COOKIE], "Origin": "https://attacker.example"},
            ).status_code
            == 403
        )


def test_concurrent_first_setup_has_exactly_one_winner(tmp_path):
    app, *_ = make_app(tmp_path)

    def request_setup(_):
        client = TestClient(app)
        client.get("/")
        return client.post(
            "/api/setup", json={"token": TOKEN, "password": PASSWORD, "csrf": client.cookies[CSRF_COOKIE]}
        ).status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        codes = list(pool.map(request_setup, range(2)))
    assert sorted(codes) == [200, 400]


def test_setup_failures_are_rate_limited_too(tmp_path):
    app, *_ = make_app(tmp_path)
    with TestClient(app) as client:
        client.get("/")
        csrf = client.cookies[CSRF_COOKIE]
        for _ in range(5):
            assert (
                client.post("/api/setup", json={"token": "wrong" * 8, "password": PASSWORD, "csrf": csrf}).status_code
                == 400
            )
        response = client.post("/api/setup", json={"token": TOKEN, "password": PASSWORD, "csrf": csrf})
        assert response.status_code == 429 and TOKEN not in response.text


def test_startup_worker_survives_offline_device_with_fake_adb(tmp_path):
    adb = FakeAdb()
    app, adb, store, _scheduler, _ = make_app(tmp_path, adb, start_scheduler=True)
    store.upsert_device("offline", "Offline TV", "10.0.0.10:41267", "serial", "build")
    store.upsert_app(
        "offline",
        "com.nuvio.tv",
        version_code=1,
        last_update_time="today",
        apk_path="/data/app/a/base.apk",
        enabled=True,
    )
    store.set_monitoring(True)
    adb.fail["10.0.0.10:41267"] = AdbError("reconnect", "offline")
    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "ok"}
        assert adb.reconnect_entered.wait(1)
        setup_account(client)
        assert client.get("/api/status").status_code == 200


def test_non_ascii_csrf_is_rejected_without_error_page(tmp_path):
    app, *_ = make_app(tmp_path)
    with TestClient(app) as client:
        client.get("/")
        response = client.post("/api/setup", json={"token": TOKEN, "password": PASSWORD, "csrf": "é" * 32})
        assert response.status_code == 403 and "é" not in response.text


def test_pair_inventory_watch_baseline_manual_override_pause_and_settings(tmp_path):
    adb = FakeAdb()
    app, adb, store, scheduler, _ = make_app(tmp_path, adb)
    with TestClient(app) as c:
        setup_account(c)
        csrf = c.cookies[CSRF_COOKIE]
        assert c.post("/api/discover", headers={"X-CSRF-Token": csrf}).json()[0]["endpoint"] == "10.0.0.5:37123"
        pair = c.post(
            "/api/pair",
            json={
                "name": "Living",
                "pairing_endpoint": "10.0.0.5:37123",
                "pairing_code": "001234",
                "endpoint": "10.0.0.5:41267",
            },
            headers={"X-CSRF-Token": csrf},
        )
        assert pair.status_code == 200, pair.text
        device = pair.json()
        assert device["serial"] == "serial-10.0.0.5:41267"
        assert adb.pair_code_seen == "001234"
        adb.packages[(device["endpoint"], "com.nuvio.tv")] = package(10)
        adb.installed[device["endpoint"]] = ["com.nuvio.tv"]
        inventory = c.get(f"/api/devices/{device['id']}/inventory").json()
        assert inventory[0]["package_id"] == "com.nuvio.tv" and inventory[0]["label"] == "Nuvio"
        watched = c.post(
            f"/api/devices/{device['id']}/watch",
            json={"package_id": "com.nuvio.tv", "initial_compile": True},
            headers={"X-CSRF-Token": csrf},
        )
        assert watched.status_code == 200 and watched.json()["baseline"] is True
        assert len(store.list_jobs()) == 1 and store.list_jobs()[0].manual
        # Fresh inventory displays new data without prematurely overwriting the saved scheduler baseline.
        old_baseline = store.get_app(device["id"], "com.nuvio.tv").fingerprint
        adb.packages[(device["endpoint"], "com.nuvio.tv")] = package(11)
        assert c.get(f"/api/devices/{device['id']}/inventory").json()[0]["version_code"] == 11
        assert store.get_app(device["id"], "com.nuvio.tv").fingerprint == old_baseline
        queued = c.post(
            f"/api/devices/{device['id']}/compile",
            json={"package_id": "com.nuvio.tv", "foreground_override": True},
            headers={"X-CSRF-Token": csrf},
        )
        assert queued.status_code == 200 and queued.json()["foreground_override"] is True
        c.put("/api/monitoring", json={"enabled": False}, headers={"X-CSRF-Token": csrf})
        assert store.monitoring_enabled() is False
        settings = c.put(
            "/api/settings",
            json={
                "poll_interval_seconds": 120,
                "max_attempts": 4,
                "window_start": "23:00",
                "window_end": "06:00",
                "timezone": "America/Chicago",
            },
            headers={"X-CSRF-Token": csrf},
        )
        assert settings.status_code == 200, settings.text
        assert scheduler.status()["maintenance_window"] == {
            "start": "23:00",
            "end": "06:00",
            "timezone": "America/Chicago",
        }
        status = c.get("/api/status").json()
        assert status["monitoring_enabled"] is False
        assert status["jobs"][0]["state"] == "pending"
        assert c.get(f"/api/jobs/{status['jobs'][0]['id']}/events").status_code == 200


def test_multiple_tvs_wrong_identity_rename_preserves_endpoint_and_reconnect(tmp_path):
    adb = FakeAdb()
    app, adb, store, _scheduler, _ = make_app(tmp_path, adb)
    with TestClient(app) as c:
        setup_account(c)
        csrf = c.cookies[CSRF_COOKIE]
        first = add_tv(c, "Den", "10.0.0.1:41267")
        second = add_tv(c, "Bedroom", "10.0.0.2:41267")
        assert first["id"] != second["id"]
        response = c.patch(f"/api/devices/{first['id']}", json={"name": "Movie room"}, headers={"X-CSRF-Token": csrf})
        assert response.status_code == 200
        assert store.get_device(first["id"]).endpoint == "10.0.0.1:41267"
        adb.devices["10.0.0.1:41268"] = DeviceIdentity(first["serial"], "build-10.0.0.1:41267")
        adb.devices["10.0.0.9:40000"] = DeviceIdentity("wrong-serial", "different-build")
        wrong = c.post(
            f"/api/devices/{first['id']}/reconnect", json={"endpoint": "10.0.0.9:40000"}, headers={"X-CSRF-Token": csrf}
        )
        assert wrong.status_code == 400
        assert store.get_device(first["id"]).endpoint == "10.0.0.1:41267"
        good = c.post(
            f"/api/devices/{first['id']}/reconnect", json={"endpoint": "10.0.0.1:41268"}, headers={"X-CSRF-Token": csrf}
        )
        assert good.status_code == 200 and store.get_device(first["id"]).endpoint == "10.0.0.1:41268"


def test_bad_input_pairing_secrets_diagnostics_and_arbitrary_commands_are_not_exposed(tmp_path):
    adb = FakeAdb()
    app, adb, store, scheduler, instance = make_app(tmp_path, adb)
    with TestClient(app) as c:
        setup_account(c)
        csrf = c.cookies[CSRF_COOKIE]
        code = "765432"
        adb.fail["10.0.0.5:37123"] = RuntimeError(f"protocol echoed {code}")
        response = c.post(
            "/api/pair",
            json={
                "name": "TV",
                "pairing_endpoint": "10.0.0.5:37123",
                "pairing_code": code,
                "endpoint": "10.0.0.5:41267",
            },
            headers={"X-CSRF-Token": csrf},
        )
        assert response.status_code == 400 and code not in response.text
        injection = c.post(
            "/api/devices", json={"name": "x", "endpoint": "10.0.0.1:2;id"}, headers={"X-CSRF-Token": csrf}
        )
        assert injection.status_code == 400 and "id;" not in injection.text
        assert not any(route.path in {"/api/shell", "/api/command", "/api/exec"} for route in app.routes)
        diagnostic = c.get("/api/diagnostics")
        assert diagnostic.status_code == 200
        assert (
            code not in diagnostic.text
            and "bootstrap_token" not in diagnostic.text
            and "auth.sqlite3" not in diagnostic.text
        )
        assert "users" not in diagnostic.text and "sessions" not in diagnostic.text
        assert code.encode() not in (instance / "state.sqlite3").read_bytes()


def test_inventory_and_reconnect_races_do_not_reinsert_forgotten_device(tmp_path):
    adb = FakeAdb()
    app, adb, store, _scheduler, _ = make_app(tmp_path, adb)
    c = TestClient(app)
    setup_account(c)
    csrf = c.cookies[CSRF_COOKIE]
    device = add_tv(c)
    adb.packages[(device["endpoint"], "com.nuvio.tv")] = package()
    adb.installed[device["endpoint"]] = ["com.nuvio.tv"]
    adb.block_inventory = True
    with ThreadPoolExecutor(max_workers=2) as pool:
        inventory_future = pool.submit(c.get, f"/api/devices/{device['id']}/inventory")
        assert adb.inventory_entered.wait(1)
        assert c.delete(f"/api/devices/{device['id']}", headers={"X-CSRF-Token": csrf}).status_code == 200
        adb.inventory_release.set()
        assert inventory_future.result().status_code == 404
    assert store.get_device(device["id"]) is None

    device = add_tv(c, "Second", "10.0.0.6:41267")
    adb.block_reconnect = True
    with ThreadPoolExecutor(max_workers=2) as pool:
        reconnect_future = pool.submit(
            c.post,
            f"/api/devices/{device['id']}/reconnect",
            json={"endpoint": "10.0.0.6:41268"},
            headers={"X-CSRF-Token": csrf},
        )
        assert adb.reconnect_entered.wait(1)
        assert c.delete(f"/api/devices/{device['id']}", headers={"X-CSRF-Token": csrf}).status_code == 200
        adb.reconnect_release.set()
        assert reconnect_future.result().status_code in {400, 404}
    assert store.get_device(device["id"]) is None


def test_secure_cookie_configuration_and_invalid_bootstrap_token(tmp_path):
    app, *_ = make_app(tmp_path, env={"TVCOMPILER_BOOTSTRAP_TOKEN": TOKEN})
    with TestClient(app) as c:
        c.get("/")
        result = c.post("/api/setup", json={"token": TOKEN, "password": PASSWORD, "csrf": c.cookies[CSRF_COOKIE]})
        assert result.status_code == 200
        assert "secure" not in result.headers["set-cookie"].lower()
    secure_app = create_app(
        tmp_path / "secure",
        start_scheduler=False,
        environ={"TVCOMPILER_BOOTSTRAP_TOKEN": TOKEN, "TVCOMPILER_COOKIE_SECURE": "1"},
    )
    with TestClient(secure_app, base_url="https://testserver.local") as secure_client:
        secure_client.get("/")
        secure_result = secure_client.post(
            "/api/setup",
            json={"token": TOKEN, "password": PASSWORD, "csrf": secure_client.cookies[CSRF_COOKIE]},
        )
        assert "secure" in secure_result.headers["set-cookie"].lower()
    with pytest.raises(ValueError, match="32-256"):
        create_app(tmp_path / "bad", start_scheduler=False, environ={"TVCOMPILER_BOOTSTRAP_TOKEN": "too-short"})
