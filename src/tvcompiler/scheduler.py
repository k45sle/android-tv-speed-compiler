"""Single-process scheduler for current-installation speed compilation."""

from __future__ import annotations

import fcntl
import os
import re
import threading
import time
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .adb import AdbClient, AdbError, PackageInfo
from .models import Device, Job
from .store import Store
from .validation import validate_package_id


def _utcnow() -> datetime:
    return datetime.now(UTC)


class Scheduler:
    """Persistent polling and serialized job execution.

    The store's monitoring switch defaults to paused. Watching an app always refreshes its live
    baseline; an optional initial compile is explicit and remains idle/window gated.
    """

    def __init__(
        self,
        store: Store,
        adb: AdbClient,
        instance_dir: str | Path,
        *,
        poll_interval: float = 60.0,
        max_attempts: int = 3,
        base_backoff: float = 30.0,
        max_backoff: float = 900.0,
        clock: Callable[[], datetime] = _utcnow,
        sleep: Callable[[float], None] = time.sleep,
    ):
        if not 5 <= poll_interval <= 3600:
            raise ValueError("poll interval must be between 5 and 3600 seconds")
        if not 1 <= max_attempts <= 8:
            raise ValueError("max attempts must be between 1 and 8")
        if not 1 <= base_backoff <= max_backoff <= 86400:
            raise ValueError("backoff must be bounded to 1 second through 1 day")
        self.store = store
        self.adb = adb
        self.instance_dir = Path(instance_dir)
        self.instance_dir.mkdir(parents=True, exist_ok=True)
        self.poll_interval = poll_interval
        self.max_attempts = max_attempts
        self.base_backoff = base_backoff
        self.max_backoff = max_backoff
        self.clock = clock
        self._sleep = sleep
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._lock_file = None
        self._state_lock = threading.RLock()
        self._run_lock = threading.Lock()
        persisted = self.store.get_settings(("poll_interval", "max_attempts", "base_backoff", "max_backoff"))
        defaults = {
            "poll_interval": str(int(poll_interval)),
            "max_attempts": str(int(max_attempts)),
            "base_backoff": str(int(base_backoff)),
            "max_backoff": str(int(max_backoff)),
        }
        missing = {key: value for key, value in defaults.items() if key not in persisted}
        if missing:
            self.store.set_settings(missing)
        persisted.update(missing)
        self.poll_interval = self._setting_number(persisted, "poll_interval", 5, 3600)
        self.max_attempts = self._setting_number(persisted, "max_attempts", 1, 8)
        self.base_backoff = self._setting_number(persisted, "base_backoff", 1, 86400)
        self.max_backoff = self._setting_number(persisted, "max_backoff", self.base_backoff, 86400)

    @staticmethod
    def _setting_number(settings: dict[str, str], key: str, low: int, high: int) -> int:
        try:
            result = int(settings[key])
        except (KeyError, ValueError) as exc:
            raise ValueError(f"invalid persisted scheduler setting: {key}") from exc
        if not low <= result <= high:
            raise ValueError(f"invalid persisted scheduler setting: {key}")
        return result

    def configure_polling(self, *, interval_seconds: int, max_attempts: int | None = None) -> None:
        if not 5 <= interval_seconds <= 3600:
            raise ValueError("poll interval must be between 5 and 3600 seconds")
        values = {"poll_interval": str(interval_seconds)}
        if max_attempts is not None:
            if not 1 <= max_attempts <= 8:
                raise ValueError("max attempts must be between 1 and 8")
            values["max_attempts"] = str(max_attempts)
        self.store.set_settings(values)
        self.poll_interval = interval_seconds
        if max_attempts is not None:
            self.max_attempts = max_attempts

    def _acquire_process_lock(self) -> None:
        with self._state_lock:
            if self._lock_file is not None:
                return
            handle = (self.instance_dir / "scheduler.lock").open("a+")
            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                handle.close()
                raise RuntimeError("another scheduler already owns this state directory") from exc
            handle.seek(0)
            handle.truncate()
            handle.write(str(os.getpid()))
            handle.flush()
            self._lock_file = handle

    def _release_process_lock(self) -> None:
        with self._state_lock:
            if self._lock_file is not None:
                fcntl.flock(self._lock_file.fileno(), fcntl.LOCK_UN)
                self._lock_file.close()
                self._lock_file = None

    def start(self) -> None:
        self._acquire_process_lock()
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        recovered = self.store.recover_running()
        if recovered:
            self.store.set_setting("last_recovery", f"{recovered} interrupted job(s) recovered")
        self._fail_exhausted_pending()
        self._thread = threading.Thread(target=self._run, name="tvcompiler-scheduler", daemon=True)
        self._thread.start()

    def stop(self, timeout: float | None = None) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout)
            if self._thread.is_alive():
                return
        self._release_process_lock()

    def _run(self) -> None:
        try:
            while not self._stop.is_set():
                try:
                    self.run_once()
                except Exception as exc:  # Background service must survive transient host/ADB faults.
                    self.store.set_setting("scheduler_error", str(exc)[:500])
                self._stop.wait(self.poll_interval)
        finally:
            self._release_process_lock()

    def set_monitoring(self, enabled: bool) -> None:
        self.store.set_monitoring(enabled)

    def configure_window(self, start: str | None, end: str | None, timezone_name: str = "UTC") -> None:
        if (start is None) != (end is None):
            raise ValueError("both maintenance window start and end are required")
        if start is None:
            with self._state_lock:
                self.store.set_settings({"window_start": "", "window_end": "", "window_timezone": "UTC"})
            return
        self._parse_time(start)
        self._parse_time(end or "")
        if start == end:
            raise ValueError("maintenance window start and end must differ")
        try:
            ZoneInfo(timezone_name)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError("timezone must be a valid IANA timezone") from exc
        with self._state_lock:
            self.store.set_settings({"window_start": start, "window_end": end or "", "window_timezone": timezone_name})

    @staticmethod
    def _parse_time(value: str) -> tuple[int, int]:
        import re

        match = re.fullmatch(r"([01]\d|2[0-3]):([0-5]\d)", value)
        if not match:
            raise ValueError("window times must use HH:MM, from 00:00 through 23:59")
        return int(match.group(1)), int(match.group(2))

    def watch_app(self, device_id: str, package_id: str, *, initial_compile: bool = False) -> Job | None:
        validate_package_id(package_id)
        device = self._enabled_device(device_id)
        serial, _identity = self._connect(device)
        info = self.adb.package_info(serial, package_id)
        self._require_complete_metadata(info)
        _app, job, _changed = self.store.observe_app(
            device_id,
            package_id,
            **self._info_fields(info),
            enqueue_change=False,
            initial_compile=initial_compile,
            enable=True,
            manual=initial_compile,
        )
        return job

    def manual_compile(self, device_id: str, package_id: str, *, foreground_override: bool = False) -> Job | None:
        validate_package_id(package_id)
        device = self._enabled_device(device_id)
        app = self.store.get_app(device_id, package_id)
        if not app or not app.enabled:
            return None
        serial, _identity = self._connect(device)
        info = self.adb.package_info(serial, package_id)
        self._require_complete_metadata(info)
        _app, _job, changed = self.store.observe_app(
            device_id, package_id, **self._info_fields(info), enqueue_change=True
        )
        app = self.store.get_app(device_id, package_id)
        if not app:
            return None
        return self.store.enqueue_manual_job(
            device_id, package_id, app.fingerprint, foreground_override=foreground_override
        )

    def poll_once(self) -> int:
        if not self.store.monitoring_enabled():
            return 0
        queued = 0
        errors: list[str] = []
        for device in self.store.list_devices():
            if not device.enabled:
                continue
            apps = [app for app in self.store.list_apps(device.id) if app.enabled]
            if not apps:
                continue
            try:
                serial, _identity = self._connect(device)
                installed = set(self.adb.installed_packages(serial))
            except (AdbError, FileNotFoundError, OSError) as exc:
                errors.append(f"{device.name}: {self._actionable_error(exc)}")
                continue
            for app in apps:
                if app.package_id not in installed:
                    self.store.remove_app(device.id, app.package_id)
                    continue
                try:
                    info = self.adb.package_info(serial, app.package_id)
                    self._require_complete_metadata(info)
                    _saved, job, _changed = self.store.observe_app(
                        device.id, app.package_id, **self._info_fields(info), enqueue_change=True
                    )
                    queued += bool(job)
                except (AdbError, FileNotFoundError, OSError) as exc:
                    errors.append(f"{device.name}/{app.package_id}: {self._actionable_error(exc)}")
                except ValueError as exc:
                    errors.append(f"{device.name}/{app.package_id}: {exc}")
        self.store.set_setting("last_poll_at", self.clock().isoformat())
        self.store.set_setting("last_poll_error", "; ".join(errors)[:1000])
        return queued

    def run_once(self) -> Job | None:
        with self._run_lock:
            return self._run_once_locked()

    def _run_once_locked(self) -> Job | None:
        owned_lock = self._lock_file is None
        if owned_lock:
            self._acquire_process_lock()
        try:
            if self.store.monitoring_enabled():
                self.poll_once()
            now = self._db_time(self.clock())
            for candidate in self.store.pending_jobs(now=now):
                if not self.store.monitoring_enabled() and not candidate.manual:
                    self.store.set_wait_reason(candidate.id, "monitoring is paused")
                    continue
                if candidate.attempts >= self.max_attempts:
                    self.store.finish_job(
                        candidate.id, state="failed", reason="retry limit reached"
                    ) if candidate.state == "running" else self._fail_pending(candidate.id, "retry limit reached")
                    continue
                eligible, serial, reason = self._eligible(candidate)
                if not eligible:
                    self.store.set_wait_reason(candidate.id, reason or "waiting")
                    continue
                job = self.store.claim_job(candidate.id, now=now)
                if job is None:
                    return None
                return self._execute(job, serial)
            return None
        finally:
            if owned_lock:
                self._release_process_lock()

    def _eligible(self, job: Job) -> tuple[bool, str | None, str | None]:
        device = self.store.get_device(job.device_id)
        app = self.store.get_app(job.device_id, job.package_id)
        if not device or not device.enabled or not app or not app.enabled:
            return False, None, "device or app is disabled or removed"
        if self._in_window_wait() and not job.manual_override:
            return False, None, "outside configured maintenance window"
        try:
            serial, _identity = self._connect(device)
        except (AdbError, FileNotFoundError, OSError) as exc:
            return False, None, self._actionable_error(exc)
        if not job.manual_override:
            try:
                busy = self.adb.busy_status(serial)
            except (AdbError, FileNotFoundError, OSError) as exc:
                return False, None, self._actionable_error(exc)
            if not busy.idle_confirmed:
                return False, None, "; ".join(busy.reasons) or "idle state unknown"
        return True, serial, None

    def _execute(self, job: Job, serial: str | None) -> Job | None:
        if serial is None:
            return self._defer_claimed(job, "device unavailable before compilation")
        device = self.store.get_device(job.device_id)
        app = self.store.get_app(job.device_id, job.package_id)
        if not device or not device.enabled or not app or not app.enabled:
            self._finish_if_running(job.id, "cancelled", "device or app disabled before compilation")
            return self.store.get_job(job.id)
        try:
            current = self.adb.package_info(serial, job.package_id)
            self._require_complete_metadata(current)
        except (AdbError, FileNotFoundError, OSError) as exc:
            return self._defer_claimed(job, self._actionable_error(exc))
        except ValueError as exc:
            return self._defer_claimed(job, str(exc))
        if current.fingerprint.value != job.fingerprint:
            _saved, _new_job, _changed = self.store.observe_app(
                job.device_id, job.package_id, **self._info_fields(current), enqueue_change=True
            )
            self._finish_if_running(job.id, "superseded", "installation changed before compilation")
            return self.store.get_job(job.id)
        try:
            result = self.adb.compile_speed(serial, job.package_id)
            if not re.search(r"(?im)^\s*Success\s*$", result.stdout):
                raise AdbError("compile", "package manager did not return explicit Success")
            compile_error = None
        except (AdbError, FileNotFoundError, OSError, TimeoutError) as exc:
            compile_error = self._actionable_error(exc)
        try:
            after = self.adb.package_info(serial, job.package_id)
        except (AdbError, FileNotFoundError, OSError) as exc:
            after = None
            post_error = self._actionable_error(exc)
        else:
            post_error = None
        if after:
            try:
                self._require_complete_metadata(after)
            except ValueError as exc:
                return self._retry(job, f"cannot confirm complete installation metadata after compilation: {exc}")
        if after and after.fingerprint.value != job.fingerprint:
            self.store.observe_app(job.device_id, job.package_id, **self._info_fields(after), enqueue_change=True)
            self._finish_if_running(job.id, "superseded", "installation changed during compilation")
            return self.store.get_job(job.id)
        if after is None:
            return self._retry(job, f"cannot confirm installation after compilation: {post_error}")
        if compile_error:
            return self._retry(job, compile_error)
        try:
            inspection = self.adb.inspect_compilation(serial, job.package_id)
        except (AdbError, FileNotFoundError, OSError) as exc:
            inspection = None
            verify_detail = self._actionable_error(exc)
        else:
            verify_detail = inspection.detail
        reason = None
        if inspection is None or not inspection.supported:
            reason = f"command succeeded, compilation filter unverified: {verify_detail}"
        elif inspection.compiler_filter != "speed":
            return self._retry(job, f"speed compilation not verified: {inspection.detail}")
        # Verify current installation once more after the inspection before committing success.
        try:
            final = self.adb.package_info(serial, job.package_id)
            self._require_complete_metadata(final)
        except (AdbError, FileNotFoundError, OSError) as exc:
            return self._retry(
                job, f"cannot confirm installation before recording success: {self._actionable_error(exc)}"
            )
        except ValueError as exc:
            return self._retry(job, f"cannot confirm complete installation before recording success: {exc}")
        if final.fingerprint.value != job.fingerprint:
            self.store.observe_app(job.device_id, job.package_id, **self._info_fields(final), enqueue_change=True)
            self._finish_if_running(job.id, "superseded", "installation changed before success could be recorded")
            return self.store.get_job(job.id)
        if not self.store.finish_success_if_current(job.id, job.fingerprint, reason):
            latest = self.store.get_job(job.id)
            if latest and latest.state == "running":
                self._finish_if_running(job.id, "superseded", "job or installation is no longer current")
        return self.store.get_job(job.id)

    def _retry(self, job: Job, reason: str) -> Job | None:
        if job.attempts >= self.max_attempts:
            self._finish_if_running(job.id, "failed", reason)
        else:
            delay = min(self.base_backoff * (2 ** (job.attempts - 1)), self.max_backoff)
            available = self._db_time(self.clock() + timedelta(seconds=delay))
            self._finish_if_running(job.id, "pending", reason, available)
        return self.store.get_job(job.id)

    def _defer_claimed(self, job: Job, reason: str) -> Job | None:
        self.store.defer_running(job.id, reason, self._db_time(self.clock()))
        return self.store.get_job(job.id)

    def _finish_if_running(self, job_id: int, state: str, reason: str, available_at: str | None = None) -> None:
        try:
            self.store.finish_job(job_id, state=state, reason=reason, available_at=available_at)
        except ValueError:
            # A concurrent update may have cancelled/superseded the row during ADB execution.
            pass

    def _fail_pending(self, job_id: int, reason: str) -> None:
        with self.store._transaction() as db:  # Preserve retry exhaustion as a normal persisted outcome.
            row = db.execute("SELECT state FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row and row["state"] == "pending":
                db.execute(
                    """UPDATE jobs SET state='failed',reason=?,
                       updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?""",
                    (reason, job_id),
                )
                self.store._event(db, job_id, "failed", reason)

    def _fail_exhausted_pending(self) -> None:
        for job in self.store.pending_jobs(limit=1000):
            if job.attempts >= self.max_attempts:
                self._fail_pending(job.id, "retry limit reached after interruption")

    def _in_window_wait(self) -> bool:
        with self._state_lock:
            settings = self.store.get_settings(("window_start", "window_end", "window_timezone"))
        start, end = settings.get("window_start", ""), settings.get("window_end", "")
        if not start and not end:
            return False
        start_h, start_m = self._parse_time(start or "")
        end_h, end_m = self._parse_time(end or "")
        zone = ZoneInfo(settings.get("window_timezone", "UTC") or "UTC")
        local = self.clock().astimezone(zone)
        minute = local.hour * 60 + local.minute
        lower, upper = start_h * 60 + start_m, end_h * 60 + end_m
        allowed = lower <= minute < upper if lower < upper else minute >= lower or minute < upper
        return not allowed

    def _connect(self, device: Device) -> tuple[str, object]:
        if not device.serial and not device.fingerprint:
            raise AdbError("identity", "device has no pinned identity; verify or re-add it")
        return self.adb.reconnect(
            expected_serial=device.serial,
            expected_fingerprint=device.fingerprint,
            endpoint=device.endpoint,
        )

    @staticmethod
    def _require_complete_metadata(info: PackageInfo) -> None:
        if info.version_code is None or not info.last_update_time or not info.apk_path:
            raise ValueError("installation metadata incomplete (versionCode, lastUpdateTime, and APK path required)")

    @staticmethod
    def _db_time(value: datetime) -> str:
        return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")

    def _enabled_device(self, device_id: str) -> Device:
        device = self.store.get_device(device_id)
        if not device or not device.enabled:
            raise ValueError("device is missing or disabled")
        return device

    @staticmethod
    def _info_fields(info: PackageInfo) -> dict[str, object]:
        return {
            "label": info.label,
            "version_name": info.version_name,
            "version_code": info.version_code,
            "last_update_time": info.last_update_time,
            "apk_path": info.apk_path,
        }

    @staticmethod
    def _actionable_error(exc: Exception) -> str:
        message = str(exc)
        if "unauthorized" in message.lower() or "revoked" in message.lower():
            return "ADB authorization is missing or revoked; approve the TV pairing prompt and reconnect"
        if "offline" in message.lower() or "no tls" in message.lower() or "connect" in message.lower():
            return "TV is offline; check network and Wireless debugging, then reconnect"
        if isinstance(exc, FileNotFoundError):
            return "ADB executable is unavailable; install platform-tools or configure its path"
        return message[:300]

    def status(self) -> dict[str, object]:
        return {
            "monitoring_enabled": self.store.monitoring_enabled(),
            "poll_interval_seconds": self.poll_interval,
            "max_attempts": self.max_attempts,
            "last_poll_at": self.store.get_setting("last_poll_at"),
            "last_poll_error": self.store.get_setting("last_poll_error"),
            "scheduler_error": self.store.get_setting("scheduler_error"),
            "maintenance_window": {
                "start": self.store.get_setting("window_start") or None,
                "end": self.store.get_setting("window_end") or None,
                "timezone": self.store.get_setting("window_timezone", "UTC"),
            },
            "jobs": self.store.list_jobs(limit=100),
        }
