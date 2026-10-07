from datetime import UTC, datetime, timedelta
from threading import Event, Thread

import pytest

from tvcompiler.adb import AdbError, AdbResult, BusyStatus, CompilationInspection, PackageInfo
from tvcompiler.scheduler import Scheduler
from tvcompiler.store import Store


class FakeAdb:
    def __init__(self):
        self.packages = {}
        self.busy = {}
        self.installed = {}
        self.compile_output = "Success"
        self.compilation = CompilationInspection(True, "speed", "all entries speed")
        self.compile_callback = None
        self.compile_exception = None
        self.block_compile = False
        self.fail_reconnect = {}
        self.compile_count = 0
        self.entered = Event()
        self.release = Event()
        self.block_package_read = False
        self.block_package_read_calls = {1}
        self.package_read_calls = 0
        self.package_read_entered = Event()
        self.package_read_release = Event()
        self.busy_callback = None
        self.block_busy_read = False
        self.busy_read_calls = 0
        self.block_busy_read_calls = {1}
        self.busy_read_entered = Event()
        self.busy_read_release = Event()

    def reconnect(self, *, expected_serial, expected_fingerprint, endpoint):
        if endpoint in self.fail_reconnect:
            raise AdbError("reconnect", self.fail_reconnect[endpoint])
        return endpoint or expected_serial, object()

    def package_info(self, serial, package_id):
        self.package_read_calls += 1
        if self.block_package_read and self.package_read_calls in self.block_package_read_calls:
            self.package_read_entered.set()
            self.package_read_release.wait(5)
        try:
            return self.packages[(serial, package_id)]
        except KeyError as exc:
            raise AdbError("package", "not installed") from exc

    def installed_packages(self, serial):
        return self.installed.get(serial, [])

    def busy_status(self, serial):
        self.busy_read_calls += 1
        if self.busy_callback:
            self.busy_callback(serial)
        if self.block_busy_read and self.busy_read_calls in self.block_busy_read_calls:
            self.busy_read_entered.set()
            self.busy_read_release.wait(5)
        return self.busy.get(serial, BusyStatus(False, False, ()))

    def compile_speed(self, serial, package_id):
        self.compile_count += 1
        self.entered.set()
        if self.compile_callback:
            self.compile_callback(serial, package_id)
        if self.block_compile:
            self.release.wait(5)
        if self.compile_exception:
            raise self.compile_exception
        return AdbResult(("adb",), 0, self.compile_output, "")

    def inspect_compilation(self, serial, package_id):
        return self.compilation


NOW = datetime(2030, 10, 4, 20, 30, tzinfo=UTC)


def package(version=4, updated="2026-10-01 12:00:00", path="/data/app/a/base.apk"):
    return PackageInfo("com.nuvio.tv", "Nuvio", "1.0", version, updated, path)


def setup(tmp_path, *, clock=lambda: NOW, max_attempts=3):
    store = Store(tmp_path / "state.db")
    device = store.upsert_device("tv", "TV", "tv.local:37123", "serial", "build")
    adb = FakeAdb()
    adb.packages[(device.endpoint, "com.nuvio.tv")] = package()
    adb.installed[device.endpoint] = ["com.nuvio.tv"]
    scheduler = Scheduler(store, adb, tmp_path, clock=clock, max_attempts=max_attempts, base_backoff=10, max_backoff=20)
    return store, adb, scheduler, device


def watched(store, scheduler):
    scheduler.watch_app("tv", "com.nuvio.tv")
    return store.get_app("tv", "com.nuvio.tv")


def test_watch_sets_fresh_baseline_and_same_version_reinstall_is_one_job(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    baseline = watched(store, scheduler)
    assert baseline.fingerprint == package().fingerprint.value
    scheduler.set_monitoring(True)
    adb.packages[(device.endpoint, "com.nuvio.tv")] = package(
        updated="2026-10-04 19:00:00", path="/data/app/b/base.apk"
    )
    assert scheduler.poll_once() == 1
    assert (
        store.get_app("tv", "com.nuvio.tv").fingerprint
        == adb.packages[(device.endpoint, "com.nuvio.tv")].fingerprint.value
    )
    assert scheduler.poll_once() == 0
    jobs = store.list_jobs()
    assert len(jobs) == 1 and jobs[0].state == "pending"


def test_maintenance_operation_fails_fast_while_scheduler_is_busy_and_releases_lock(tmp_path):
    _store, _adb, scheduler, _device = setup(tmp_path)
    scheduler._run_lock.acquire()
    try:
        with pytest.raises(RuntimeError, match="scheduler is busy"):
            with scheduler.maintenance_operation():
                pytest.fail("busy maintenance operation must not enter")
    finally:
        scheduler._run_lock.release()

    with pytest.raises(ValueError, match="synthetic"):
        with scheduler.maintenance_operation():
            raise ValueError("synthetic")
    assert scheduler._run_lock.acquire(blocking=False)
    scheduler._run_lock.release()


def test_watch_reenables_existing_inventory_app_with_current_baseline(tmp_path):
    store, _adb, scheduler, _device = setup(tmp_path)
    watched(store, scheduler)
    store.set_app_enabled("tv", "com.nuvio.tv", False)
    assert scheduler.watch_app("tv", "com.nuvio.tv") is None
    app = store.get_app("tv", "com.nuvio.tv")
    assert app.enabled and app.fingerprint == package().fingerprint.value


def test_offline_is_persisted_without_attempt_and_startup_tolerates_it(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    job = store.enqueue_job("tv", app.package_id, app.fingerprint)
    scheduler.set_monitoring(True)
    adb.fail_reconnect[device.endpoint] = "device offline"
    scheduler.start()
    scheduler.stop()
    # run_once is deterministic even when the worker has not been started.
    scheduler.run_once()
    saved = store.get_job(job.id)
    assert saved.state == "pending" and saved.attempts == 0
    assert "offline" in saved.reason.lower()


def test_revoked_adb_authorization_is_actionable_and_does_not_consume_attempt(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    job = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    adb.fail_reconnect[device.endpoint] = "unauthorized"
    assert scheduler.run_once() is None
    saved = store.get_job(job.id)
    assert saved.attempts == 0 and "approve the TV pairing prompt" in saved.reason


def test_connection_status_requires_verified_reconnect_and_becomes_unknown_when_stale(tmp_path):
    now = [NOW]
    store, adb, scheduler, device = setup(tmp_path, clock=lambda: now[0])
    initial = scheduler.device_connection(device)
    assert initial["connection_status"] == "unknown"
    assert initial["connection_stale"] is True
    assert initial["connection_checked_at"] is None

    scheduler._connect(device)
    fresh = scheduler.device_connection(device)
    assert fresh["connection_status"] == "connected"
    assert fresh["last_known_connection_status"] == "connected"
    assert fresh["polling_status"] == "paused"
    assert fresh["poll_interval_seconds"] == 60

    now[0] += timedelta(seconds=120)
    stale = scheduler.device_connection(device)
    assert stale["connection_status"] == "unknown"
    assert stale["last_known_connection_status"] == "connected"
    assert stale["connection_stale"] is True


def test_poll_checks_enabled_tv_without_watched_apps_and_saves_failure_reason(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    scheduler.set_monitoring(True)
    adb.fail_reconnect[device.endpoint] = "unauthorized"

    assert scheduler.poll_once() == 0
    state = scheduler.device_connection(device)
    assert state["connection_status"] == "unauthorized"
    assert state["connection_reason"] == (
        "ADB authorization is missing or revoked; approve the TV pairing prompt and reconnect"
    )


def test_no_permissions_connection_failure_is_actionable_and_sanitized():
    error = AdbError("reconnect", "ADB reports no permissions for the intended TV; check service runtime access")

    reason = Scheduler._connection_reason(error)
    assert reason == "ADB reports no permissions for this TV; check the service runtime's ADB access"
    assert Scheduler._connection_status_for(error) == "error"


def test_missing_adb_readiness_uses_configured_executable(tmp_path, monkeypatch):
    import tvcompiler.scheduler as scheduler_module

    store, adb, scheduler, _device = setup(tmp_path)
    adb.adb_path = "/configured/path/adb"
    monkeypatch.setattr(scheduler_module.shutil, "which", lambda path: None)

    dependency = scheduler.status()["dependencies"]["adb"]
    assert dependency["available"] is False


def test_unknown_idle_defers_and_does_not_starve_an_idle_tv(tmp_path):
    store, adb, scheduler, first = setup(tmp_path)
    first_app = watched(store, scheduler)
    first_job = store.enqueue_job("tv", first_app.package_id, first_app.fingerprint)
    second = store.upsert_device("other", "Other", "other.local:37123", "serial2", "build2")
    adb.packages[(second.endpoint, "com.nuvio.tv")] = package()
    adb.installed[second.endpoint] = ["com.nuvio.tv"]
    second_app, second_job, _ = store.observe_app(
        "other", "com.nuvio.tv", **scheduler._info_fields(package()), enqueue_change=False, enable=True
    )
    first_status = BusyStatus(None, None, ("screen state unknown", "playback state unknown"))
    adb.busy[first.endpoint] = first_status
    assert second_job is None
    # Establish an automatic job for the second device by observing a real update.
    changed = package(updated="2026-10-04 20:00:00", path="/data/app/b/base.apk")
    adb.packages[(second.endpoint, "com.nuvio.tv")] = changed
    store.observe_app("other", "com.nuvio.tv", **scheduler._info_fields(changed), enqueue_change=True)
    scheduler.set_monitoring(True)
    result = scheduler.run_once()
    assert result is not None and result.device_id == "other"
    assert store.get_job(first_job.id).attempts == 0
    assert "unknown" in store.get_job(first_job.id).reason


@pytest.mark.parametrize(
    ("busy", "reason"),
    [
        (BusyStatus(True, False, ("screen is active",)), "screen is active"),
        (BusyStatus(False, True, ("media playback is active",)), "media playback is active"),
        (BusyStatus(None, False, ("screen state unknown",)), "screen state unknown"),
    ],
)
def test_screen_playback_and_unknown_waits_preserve_attempts(tmp_path, busy, reason):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    scheduler.set_monitoring(True)
    job = store.enqueue_job("tv", app.package_id, app.fingerprint)
    adb.busy[device.endpoint] = busy
    assert scheduler.run_once() is None
    saved = store.get_job(job.id)
    assert saved.attempts == 0 and reason in saved.reason


def test_paused_monitoring_holds_automatic_but_manual_can_run(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    automatic = store.enqueue_job("tv", app.package_id, app.fingerprint)
    assert scheduler.run_once() is None
    assert store.get_job(automatic.id).reason == "monitoring is paused"
    manual = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=False)
    result = scheduler.run_once()
    assert result.id == manual.id and result.state == "succeeded"
    assert store.get_app("tv", app.package_id).compiled_fingerprint == app.fingerprint


def test_foreground_override_persists_and_only_it_bypasses_window_and_busy(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    watched(store, scheduler)
    scheduler.configure_window("21:00", "05:00", "America/Chicago")
    adb.busy[device.endpoint] = BusyStatus(True, True, ("screen is active", "media playback is active"))
    scheduler.set_monitoring(True)
    app = store.get_app("tv", "com.nuvio.tv")
    job = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=False)
    assert scheduler.run_once() is None
    assert store.get_job(job.id).attempts == 0
    assert "window" in store.get_job(job.id).reason
    override = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    assert override.manual_override
    result = scheduler.run_once()
    assert result.state == "succeeded"
    with pytest.raises(ValueError):
        scheduler.configure_window("05:00", "05:00", "UTC")


def test_maintenance_window_crosses_midnight_in_selected_timezone(tmp_path):
    def clock():
        return datetime(2030, 10, 4, 5, 30, tzinfo=UTC)  # 00:30 in Chicago

    store, adb, scheduler, _device = setup(tmp_path, clock=clock)
    app = watched(store, scheduler)
    scheduler.configure_window("21:00", "05:00", "America/Chicago")
    job = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=False)
    result = scheduler.run_once()
    assert result.id == job.id and result.state == "succeeded"


def test_burst_install_updates_supersede_older_pending_fingerprints(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    watched(store, scheduler)
    scheduler.set_monitoring(True)
    jobs = []
    for update_time, path in [
        ("2026-10-04 19:00:00", "/data/app/b/base.apk"),
        ("2026-10-04 19:30:00", "/data/app/c/base.apk"),
        ("2026-10-04 20:00:00", "/data/app/d/base.apk"),
    ]:
        adb.packages[(device.endpoint, "com.nuvio.tv")] = package(updated=update_time, path=path)
        scheduler.poll_once()
        jobs = store.list_jobs()
    assert [job.state for job in reversed(jobs)] == ["superseded", "superseded", "pending"]


def test_retry_backoff_is_bounded_and_durable(tmp_path):
    now = [NOW]
    store, adb, scheduler, _device = setup(tmp_path, clock=lambda: now[0], max_attempts=2)
    app = watched(store, scheduler)
    store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    adb.compile_output = ""
    first = scheduler.run_once()
    assert first.state == "pending" and first.attempts == 1
    assert first.available_at == scheduler._db_time(NOW.replace(second=10))
    reopened = Scheduler(Store(tmp_path / "state.db"), adb, tmp_path, clock=lambda: now[0])
    assert reopened.max_attempts == 2 and reopened.base_backoff == 10
    assert reopened.run_once() is None
    now[0] = NOW.replace(second=11)
    adb.compile_output = "Success"
    second = reopened.run_once()
    assert second.state == "succeeded" and second.attempts == 2


def test_app_disabled_during_subprocess_cannot_record_success(tmp_path):
    store, adb, scheduler, _device = setup(tmp_path)
    app = watched(store, scheduler)
    job = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    adb.compile_callback = lambda _serial, _pkg: store.set_app_enabled("tv", "com.nuvio.tv", False)
    result = scheduler.run_once()
    assert result.id == job.id and result.state == "cancelled"
    assert store.get_app("tv", app.package_id).compiled_fingerprint is None


def test_compile_failure_is_bounded_and_not_requeued_by_poll(tmp_path):
    store, adb, scheduler, device = setup(tmp_path, max_attempts=1)
    watched(store, scheduler)
    scheduler.set_monitoring(True)
    changed = package(updated="2026-10-04 19:30:00", path="/data/app/b/base.apk")
    adb.packages[(device.endpoint, "com.nuvio.tv")] = changed
    scheduler.poll_once()
    adb.compile_output = ""
    failed = scheduler.run_once()
    assert failed.state == "failed" and "explicit Success" in failed.reason
    assert scheduler.poll_once() == 0
    assert len(store.list_jobs()) == 1


@pytest.mark.parametrize(
    ("exception", "filter_result", "expected"),
    [
        (TimeoutError("ADB subprocess timed out"), None, "timed out"),
        (None, CompilationInspection(True, "mixed", "mixed filters"), "not verified"),
        (None, CompilationInspection(True, "verify", "filter is verify"), "not verified"),
    ],
)
def test_timeout_and_non_speed_filter_fail_boundedly(tmp_path, exception, filter_result, expected):
    store, adb, scheduler, _device = setup(tmp_path, max_attempts=1)
    app = watched(store, scheduler)
    job = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    adb.compile_exception = exception
    if filter_result:
        adb.compilation = filter_result
    result = scheduler.run_once()
    assert result.id == job.id and result.state == "failed"
    assert expected in result.reason


def test_unsupported_verification_is_truthful_success_for_current_fingerprint(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=False)
    adb.compilation = CompilationInspection(False, None, "format unsupported")
    result = scheduler.run_once()
    assert result.state == "succeeded"
    assert "unverified" in result.reason
    assert store.get_app("tv", app.package_id).compiled_fingerprint == app.fingerprint


def test_changed_installation_during_compile_is_superseded_not_recorded(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    replacement = package(updated="2026-10-04 19:45:00", path="/data/app/new/base.apk")
    adb.compile_callback = lambda _serial, _pkg: adb.packages.__setitem__(
        (device.endpoint, "com.nuvio.tv"), replacement
    )
    result = scheduler.run_once()
    assert result.state == "superseded"
    assert store.get_app("tv", app.package_id).compiled_fingerprint is None
    assert len(store.list_jobs()) == 2


def test_recovered_attempts_stop_at_budget_and_retry_time_is_canonical(tmp_path):
    store, adb, scheduler, _device = setup(tmp_path, max_attempts=1)
    app = watched(store, scheduler)
    job = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    claimed = store.claim_job(job.id, now=scheduler._db_time(NOW))
    assert claimed.attempts == 1
    assert store.recover_running() == 1
    scheduler.start()
    scheduler.stop()
    assert store.get_job(job.id).state == "failed"


def test_scheduler_instance_lock_and_run_mutex(tmp_path):
    store, adb, scheduler, _device = setup(tmp_path)
    watched(store, scheduler)
    app = store.get_app("tv", "com.nuvio.tv")
    store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    scheduler._acquire_process_lock()
    other = Scheduler(store, adb, tmp_path)
    with pytest.raises(RuntimeError, match="another scheduler"):
        other._acquire_process_lock()
    scheduler._release_process_lock()

    adb.release.set()
    first = Thread(target=scheduler.run_once)
    second = Thread(target=scheduler.run_once)
    first.start()
    assert adb.entered.wait(1)
    second.start()
    first.join(1)
    second.join(1)
    assert adb.compile_count == 1


def test_stop_timeout_keeps_process_lock_until_subprocess_exits(tmp_path):
    store, adb, scheduler, _device = setup(tmp_path)
    app = watched(store, scheduler)
    store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    adb.block_compile = True
    scheduler.start()
    assert adb.entered.wait(1)
    stopping = Thread(target=scheduler.stop)
    stopping.start()
    stopping.join(0.05)
    assert stopping.is_alive()
    other = Scheduler(store, adb, tmp_path)
    with pytest.raises(RuntimeError, match="another scheduler"):
        other._acquire_process_lock()
    adb.release.set()
    stopping.join(1)
    assert not stopping.is_alive()
    assert store.get_job(1).state == "succeeded"
    other._acquire_process_lock()
    other._release_process_lock()


def test_disabling_app_during_precompile_metadata_read_prevents_compile(tmp_path):
    store, adb, scheduler, _device = setup(tmp_path)
    app = watched(store, scheduler)
    store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    adb.block_package_read = True
    adb.block_package_read_calls = {2}
    worker = Thread(target=scheduler.run_once)
    worker.start()
    assert adb.package_read_entered.wait(1)
    store.set_app_enabled("tv", app.package_id, False)
    adb.package_read_release.set()
    worker.join(2)
    assert not worker.is_alive()
    assert adb.compile_count == 0
    assert store.list_jobs()[0].state == "cancelled"


def test_pause_during_slow_idle_check_defers_automatic_job_without_attempt(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    scheduler.set_monitoring(True)
    changed = package(updated="2026-10-04 19:30:00", path="/data/app/b/base.apk")
    adb.packages[(device.endpoint, app.package_id)] = changed
    scheduler.poll_once()
    adb.block_busy_read = True
    worker = Thread(target=scheduler.run_once)
    worker.start()
    assert adb.busy_read_entered.wait(1)
    scheduler.set_monitoring(False)
    adb.busy_read_release.set()
    worker.join(2)
    assert not worker.is_alive()
    job = store.list_jobs()[0]
    assert adb.compile_count == 0
    assert job.state == "pending" and job.attempts == 0
    assert job.reason == "monitoring is paused"


def test_pause_during_slow_metadata_preflight_defers_automatic_job(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    scheduler.set_monitoring(True)
    changed = package(updated="2026-10-04 19:30:00", path="/data/app/b/base.apk")
    adb.packages[(device.endpoint, app.package_id)] = changed
    scheduler.poll_once()
    adb.block_package_read = True
    adb.block_package_read_calls = {4}
    worker = Thread(target=scheduler.run_once)
    worker.start()
    assert adb.package_read_entered.wait(1)
    scheduler.set_monitoring(False)
    adb.package_read_release.set()
    worker.join(2)
    assert not worker.is_alive()
    job = store.list_jobs()[0]
    assert adb.compile_count == 0
    assert job.state == "pending" and job.attempts == 0
    assert job.reason == "monitoring is paused"


def test_pause_during_final_idle_recheck_defers_without_attempt(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    scheduler.set_monitoring(True)
    changed = package(updated="2026-10-04 19:30:00", path="/data/app/b/base.apk")
    adb.packages[(device.endpoint, app.package_id)] = changed
    scheduler.poll_once()
    adb.block_busy_read = True
    adb.block_busy_read_calls = {2}
    worker = Thread(target=scheduler.run_once)
    worker.start()
    assert adb.busy_read_entered.wait(1)
    scheduler.set_monitoring(False)
    adb.busy_read_release.set()
    worker.join(2)
    assert not worker.is_alive()
    job = store.list_jobs()[0]
    assert adb.compile_count == 0
    assert job.state == "pending" and job.attempts == 0
    assert job.reason == "monitoring is paused"


def test_window_expiring_during_idle_check_defers_without_attempt(tmp_path):
    now = [datetime(2030, 10, 4, 20, 30, tzinfo=UTC)]
    store, adb, scheduler, device = setup(tmp_path, clock=lambda: now[0])
    app = watched(store, scheduler)
    scheduler.set_monitoring(True)
    scheduler.configure_window("20:00", "21:00", "UTC")
    changed = package(updated="2026-10-04 19:30:00", path="/data/app/b/base.apk")
    adb.packages[(device.endpoint, app.package_id)] = changed
    scheduler.poll_once()
    adb.busy_callback = lambda _serial: now.__setitem__(0, datetime(2030, 10, 4, 22, 0, tzinfo=UTC))
    result = scheduler.run_once()
    job = store.list_jobs()[0]
    assert result is not None and result.id == job.id and adb.compile_count == 0
    assert job.state == "pending" and job.attempts == 0
    assert job.reason == "outside configured maintenance window"


@pytest.mark.parametrize(
    "late_busy",
    [
        BusyStatus(True, False, ("screen is active",)),
        BusyStatus(False, True, ("media playback is active",)),
        BusyStatus(False, None, ("playback state unknown",)),
    ],
)
def test_new_busy_state_after_metadata_preflight_defers_automatic_job(tmp_path, late_busy):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    scheduler.set_monitoring(True)
    changed = package(updated="2026-10-04 19:30:00", path="/data/app/b/base.apk")
    adb.packages[(device.endpoint, app.package_id)] = changed
    scheduler.poll_once()
    status_calls = 0

    def busy_status(_serial):
        nonlocal status_calls
        status_calls += 1
        if status_calls >= 2:
            adb.busy[device.endpoint] = late_busy

    adb.busy_callback = busy_status
    result = scheduler.run_once()
    job = store.list_jobs()[0]
    assert result is not None and result.id == job.id and adb.compile_count == 0
    assert job.state == "pending" and job.attempts == 0
    assert "active" in job.reason or "unknown" in job.reason


def test_manual_without_override_obeys_idle_gate_but_override_bypasses_it(tmp_path):
    store, adb, scheduler, device = setup(tmp_path)
    app = watched(store, scheduler)
    scheduler.configure_window("20:00", "21:00", "UTC")
    adb.busy[device.endpoint] = BusyStatus(False, True, ("media playback is active",))
    held = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=False)
    assert scheduler.run_once() is None
    assert store.get_job(held.id).attempts == 0
    assert "media playback" in store.get_job(held.id).reason
    scheduler.configure_window("05:00", "06:00", "UTC")
    override = store.enqueue_manual_job("tv", app.package_id, app.fingerprint, foreground_override=True)
    result = scheduler.run_once()
    assert result.id == override.id and result.state == "succeeded"
