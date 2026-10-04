"""Thread-safe SQLite persistence with atomic queue transitions."""

from __future__ import annotations

import sqlite3
import threading
from collections.abc import Iterator
from contextlib import closing, contextmanager
from pathlib import Path

from .models import AppBaseline, Device, Job, JobEvent
from .validation import validate_package_id

_SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, endpoint TEXT, serial TEXT, fingerprint TEXT,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)), last_seen_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE UNIQUE INDEX IF NOT EXISTS device_serial_unique ON devices(serial) WHERE serial IS NOT NULL;
CREATE TABLE IF NOT EXISTS apps (
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package_id TEXT NOT NULL, label TEXT, version_name TEXT, version_code INTEGER,
  last_update_time TEXT, apk_path TEXT, enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)),
  compiled_fingerprint TEXT, updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  PRIMARY KEY(device_id, package_id)
);
CREATE TABLE IF NOT EXISTS jobs (
  id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  package_id TEXT NOT NULL, fingerprint TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('pending','running','succeeded','failed','superseded','cancelled')),
  attempts INTEGER NOT NULL DEFAULT 0, available_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  reason TEXT, created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE UNIQUE INDEX IF NOT EXISTS one_live_job_per_fingerprint
  ON jobs(device_id, package_id, fingerprint) WHERE state IN ('pending','running');
CREATE INDEX IF NOT EXISTS jobs_claim_idx ON jobs(state, available_at, id);
CREATE TABLE IF NOT EXISTS job_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  event TEXT NOT NULL, detail TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY, value TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
"""


class Store:
    """SQLite store; every mutation uses its own immediate transaction."""

    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        with closing(self._connect()) as db:
            db.executescript(_SCHEMA)
            columns = {row[1] for row in db.execute("PRAGMA table_info(jobs)")}
            if "manual_override" not in columns:
                db.execute("ALTER TABLE jobs ADD COLUMN manual_override INTEGER NOT NULL DEFAULT 0")
            if "manual" not in columns:
                db.execute("ALTER TABLE jobs ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
            db.execute("INSERT OR IGNORE INTO settings(key,value) VALUES('monitoring_enabled','0')")

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=10000")
        return db

    @contextmanager
    def _transaction(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            db = self._connect()
            try:
                db.execute("BEGIN IMMEDIATE")
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise
            finally:
                db.close()

    @staticmethod
    def _device(row: sqlite3.Row) -> Device:
        return Device(
            row["id"],
            row["name"],
            row["endpoint"],
            row["serial"],
            row["fingerprint"],
            bool(row["enabled"]),
            row["last_seen_at"],
        )

    @staticmethod
    def _app(row: sqlite3.Row) -> AppBaseline:
        return AppBaseline(
            row["device_id"],
            row["package_id"],
            row["label"],
            row["version_name"],
            row["version_code"],
            row["last_update_time"],
            row["apk_path"],
            bool(row["enabled"]),
            row["compiled_fingerprint"],
        )

    @staticmethod
    def _job(row: sqlite3.Row) -> Job:
        return Job(
            row["id"],
            row["device_id"],
            row["package_id"],
            row["fingerprint"],
            row["state"],
            row["attempts"],
            row["available_at"],
            row["reason"],
            row["created_at"],
            row["updated_at"],
            bool(row["manual_override"]) if "manual_override" in row.keys() else False,
            bool(row["manual"]) if "manual" in row.keys() else False,
        )

    def upsert_device(
        self,
        device_id: str,
        name: str,
        endpoint: str | None = None,
        serial: str | None = None,
        fingerprint: str | None = None,
    ) -> Device:
        with self._transaction() as db:
            db.execute(
                """INSERT INTO devices(id,name,endpoint,serial,fingerprint) VALUES(?,?,?,?,?)
              ON CONFLICT(id) DO UPDATE SET name=excluded.name, endpoint=excluded.endpoint,
              serial=COALESCE(devices.serial,excluded.serial),
              fingerprint=COALESCE(devices.fingerprint,excluded.fingerprint)""",
                (device_id, name, endpoint, serial, fingerprint),
            )
            return self._device(db.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone())

    def get_device(self, device_id: str) -> Device | None:
        with closing(self._connect()) as db:
            row = db.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
        return self._device(row) if row else None

    def list_devices(self) -> list[Device]:
        with closing(self._connect()) as db:
            return [self._device(r) for r in db.execute("SELECT * FROM devices ORDER BY name,id")]

    def set_device_enabled(self, device_id: str, enabled: bool) -> None:
        with self._transaction() as db:
            db.execute("UPDATE devices SET enabled=? WHERE id=?", (int(enabled), device_id))
            if not enabled:
                self._cancel_jobs(db, "device disabled", device_id=device_id)

    def forget_device(self, device_id: str) -> None:
        with self._transaction() as db:
            db.execute("DELETE FROM devices WHERE id=?", (device_id,))

    def upsert_app(
        self,
        device_id: str,
        package_id: str,
        *,
        label: str | None = None,
        version_name: str | None = None,
        version_code: int | None = None,
        last_update_time: str | None = None,
        apk_path: str | None = None,
        enabled: bool | None = None,
    ) -> AppBaseline:
        validate_package_id(package_id)
        with self._transaction() as db:
            db.execute(
                """INSERT INTO apps(device_id,package_id,label,version_name,version_code,
              last_update_time,apk_path,enabled) VALUES(?,?,?,?,?,?,?,?)
              ON CONFLICT(device_id,package_id) DO UPDATE SET label=COALESCE(excluded.label,apps.label),
              version_name=excluded.version_name, version_code=excluded.version_code,
              last_update_time=excluded.last_update_time, apk_path=excluded.apk_path,
              enabled=COALESCE(?,apps.enabled), updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')""",
                (
                    device_id,
                    package_id,
                    label,
                    version_name,
                    version_code,
                    last_update_time,
                    apk_path,
                    int(enabled) if enabled is not None else 0,
                    int(enabled) if enabled is not None else None,
                ),
            )
            return self._app(
                db.execute("SELECT * FROM apps WHERE device_id=? AND package_id=?", (device_id, package_id)).fetchone()
            )

    def get_app(self, device_id: str, package_id: str) -> AppBaseline | None:
        with closing(self._connect()) as db:
            row = db.execute(
                "SELECT * FROM apps WHERE device_id=? AND package_id=?", (device_id, package_id)
            ).fetchone()
        return self._app(row) if row else None

    def list_apps(self, device_id: str) -> list[AppBaseline]:
        with closing(self._connect()) as db:
            rows = db.execute("SELECT * FROM apps WHERE device_id=? ORDER BY package_id", (device_id,))
            return [self._app(r) for r in rows]

    def set_app_enabled(self, device_id: str, package_id: str, enabled: bool) -> None:
        validate_package_id(package_id)
        with self._transaction() as db:
            db.execute(
                """UPDATE apps SET enabled=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
                        WHERE device_id=? AND package_id=?""",
                (int(enabled), device_id, package_id),
            )
            if not enabled:
                self._cancel_jobs(db, "app disabled", device_id=device_id, package_id=package_id)

    def set_setting(self, key: str, value: str) -> None:
        self.set_settings({key: value})

    def set_settings(self, values: dict[str, str]) -> None:
        if any(not key or len(key) > 100 or len(value) > 2000 for key, value in values.items()):
            raise ValueError("invalid setting")
        with self._transaction() as db:
            db.executemany(
                """INSERT INTO settings(key,value) VALUES(?,?)
                   ON CONFLICT(key) DO UPDATE SET value=excluded.value,
                   updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')""",
                values.items(),
            )

    def get_settings(self, keys: tuple[str, ...]) -> dict[str, str]:
        if not keys:
            return {}
        placeholders = ",".join("?" for _ in keys)
        with closing(self._connect()) as db:
            rows = db.execute(f"SELECT key,value FROM settings WHERE key IN ({placeholders})", keys)
            return {row["key"]: row["value"] for row in rows}

    def get_setting(self, key: str, default: str | None = None) -> str | None:
        with closing(self._connect()) as db:
            row = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return row["value"] if row else default

    def set_monitoring(self, enabled: bool) -> None:
        self.set_setting("monitoring_enabled", "1" if enabled else "0")

    def monitoring_enabled(self) -> bool:
        return self.get_setting("monitoring_enabled", "0") == "1"

    def observe_app(
        self,
        device_id: str,
        package_id: str,
        *,
        label: str | None,
        version_name: str | None,
        version_code: int | None,
        last_update_time: str | None,
        apk_path: str | None,
        enqueue_change: bool,
        initial_compile: bool = False,
        enable: bool = False,
        manual: bool = False,
    ) -> tuple[AppBaseline, Job | None, bool]:
        """Persist observed installation and any resulting job in one transaction."""
        validate_package_id(package_id)
        fingerprint = "|".join(
            (package_id, str(version_code) if version_code is not None else "", last_update_time or "", apk_path or "")
        )
        with self._transaction() as db:
            before = db.execute(
                "SELECT * FROM apps WHERE device_id=? AND package_id=?", (device_id, package_id)
            ).fetchone()
            changed = bool(before and self._app(before).fingerprint != fingerprint)
            enabled = enable or bool(before and before["enabled"])
            db.execute(
                """INSERT INTO apps(
                   device_id,package_id,label,version_name,version_code,last_update_time,apk_path,enabled)
                   VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(device_id,package_id) DO UPDATE SET
                   label=COALESCE(excluded.label,apps.label),version_name=excluded.version_name,
                   version_code=excluded.version_code,last_update_time=excluded.last_update_time,
                   apk_path=excluded.apk_path,enabled=excluded.enabled,
                   updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')""",
                (device_id, package_id, label, version_name, version_code, last_update_time, apk_path, int(enabled)),
            )
            app = self._app(
                db.execute("SELECT * FROM apps WHERE device_id=? AND package_id=?", (device_id, package_id)).fetchone()
            )
            device = db.execute("SELECT enabled FROM devices WHERE id=?", (device_id,)).fetchone()
            should_enqueue = (
                (initial_compile or (enqueue_change and changed)) and app.enabled and bool(device and device["enabled"])
            )
            if not should_enqueue:
                return app, None, changed
            return (
                app,
                self._enqueue_job(
                    db,
                    device_id,
                    package_id,
                    fingerprint,
                    manual_override=False,
                    manual=manual or initial_compile,
                    force=False,
                ),
                changed,
            )

    def enqueue_manual_job(
        self, device_id: str, package_id: str, fingerprint: str, *, foreground_override: bool
    ) -> Job | None:
        """Explicitly retry a known fingerprint; manual retry may requeue a prior terminal result."""
        validate_package_id(package_id)
        with self._transaction() as db:
            row = db.execute(
                """SELECT d.enabled AS de,a.enabled AS ae,a.version_code,a.last_update_time,a.apk_path
                   FROM devices d JOIN apps a ON a.device_id=d.id
                   WHERE d.id=? AND a.package_id=?""",
                (device_id, package_id),
            ).fetchone()
            if not row or not row["de"] or not row["ae"]:
                return None
            current = "|".join(
                (
                    package_id,
                    str(row["version_code"]) if row["version_code"] is not None else "",
                    row["last_update_time"] or "",
                    row["apk_path"] or "",
                )
            )
            if current != fingerprint:
                return None
            return self._enqueue_job(
                db, device_id, package_id, fingerprint, manual_override=foreground_override, manual=True, force=True
            )

    def remove_app(self, device_id: str, package_id: str) -> None:
        validate_package_id(package_id)
        with self._transaction() as db:
            self._cancel_jobs(db, "app removed", device_id=device_id, package_id=package_id)
            db.execute("DELETE FROM apps WHERE device_id=? AND package_id=?", (device_id, package_id))

    @staticmethod
    def _event(db: sqlite3.Connection, job_id: int, event: str, detail: str | None = None) -> None:
        db.execute("INSERT INTO job_events(job_id,event,detail) VALUES(?,?,?)", (job_id, event, detail))

    def enqueue_job(
        self, device_id: str, package_id: str, fingerprint: str, *, manual_override: bool = False, manual: bool = False
    ) -> Job | None:
        """Queue a current installation once; supersede queued/running older fingerprints.

        Returns None when the device/app is missing or disabled. Fingerprint should be produced
        from package id, version code, lastUpdateTime, and APK path by the ADB layer.
        """
        validate_package_id(package_id)
        with self._transaction() as db:
            enabled = db.execute(
                """SELECT d.enabled AS device_enabled,a.enabled AS app_enabled
              FROM devices d JOIN apps a ON a.device_id=d.id
              WHERE d.id=? AND a.package_id=?""",
                (device_id, package_id),
            ).fetchone()
            if not enabled or not enabled["device_enabled"] or not enabled["app_enabled"]:
                return None
            return self._enqueue_job(
                db, device_id, package_id, fingerprint, manual_override=manual_override, manual=manual, force=False
            )

    def _enqueue_job(
        self,
        db: sqlite3.Connection,
        device_id: str,
        package_id: str,
        fingerprint: str,
        *,
        manual_override: bool,
        manual: bool,
        force: bool,
    ) -> Job | None:
        existing = db.execute(
            """SELECT * FROM jobs WHERE device_id=? AND package_id=?
              AND fingerprint=? AND state IN ('pending','running')""",
            (device_id, package_id, fingerprint),
        ).fetchone()
        if existing:
            if (manual and not existing["manual"]) or (manual_override and not existing["manual_override"]):
                db.execute(
                    "UPDATE jobs SET manual=MAX(manual,?),manual_override=MAX(manual_override,?) WHERE id=?",
                    (int(manual), int(manual_override), existing["id"]),
                )
                existing = db.execute("SELECT * FROM jobs WHERE id=?", (existing["id"],)).fetchone()
            return self._job(existing)
        if not force:
            terminal = db.execute(
                """SELECT 1 FROM jobs WHERE device_id=? AND package_id=? AND fingerprint=?
                       AND state IN ('succeeded','failed') LIMIT 1""",
                (device_id, package_id, fingerprint),
            ).fetchone()
            if terminal:
                return None
        old_rows = db.execute(
            """SELECT id FROM jobs WHERE device_id=? AND package_id=?
              AND fingerprint<>? AND state IN ('pending','running')""",
            (device_id, package_id, fingerprint),
        ).fetchall()
        for old in old_rows:
            db.execute(
                """UPDATE jobs SET state='superseded',reason='newer installation detected',
                            updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?""",
                (old["id"],),
            )
            self._event(db, old["id"], "superseded", "newer installation detected")
        cur = db.execute(
            """INSERT INTO jobs(device_id,package_id,fingerprint,state,manual_override,manual)
                              VALUES(?,?,?,'pending',?,?)""",
            (device_id, package_id, fingerprint, int(manual_override), int(manual)),
        )
        job_id = cur.lastrowid
        self._event(db, job_id, "queued")
        return self._job(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def pending_jobs(self, *, now: str | None = None, limit: int = 1000) -> list[Job]:
        with closing(self._connect()) as db:
            rows = db.execute(
                """SELECT j.* FROM jobs j WHERE j.state='pending'
                   AND j.available_at<=COALESCE(?,strftime('%Y-%m-%dT%H:%M:%fZ','now'))
                   ORDER BY j.available_at,j.id LIMIT ?""",
                (now, limit),
            )
            return [self._job(row) for row in rows]

    def claim_job(self, job_id: int, *, now: str | None = None) -> Job | None:
        """Claim a specific, currently eligible due row after scheduler gates have passed."""
        with self._transaction() as db:
            if db.execute("SELECT 1 FROM jobs WHERE state='running' LIMIT 1").fetchone():
                return None
            row = db.execute(
                """SELECT j.id FROM jobs j JOIN devices d ON d.id=j.device_id
                   JOIN apps a ON a.device_id=j.device_id AND a.package_id=j.package_id
                   WHERE j.id=? AND j.state='pending'
                   AND j.available_at<=COALESCE(?,strftime('%Y-%m-%dT%H:%M:%fZ','now'))
                   AND d.enabled=1 AND a.enabled=1""",
                (job_id, now),
            ).fetchone()
            if not row:
                return None
            db.execute(
                """UPDATE jobs SET state='running',attempts=attempts+1,reason=NULL,
                   updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?""",
                (job_id,),
            )
            self._event(db, job_id, "claimed")
            return self._job(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def finish_success_if_current(self, job_id: int, fingerprint: str, reason: str | None = None) -> bool:
        """Atomically persist successful outcome only while job, allowlist and current fingerprint agree."""
        with self._transaction() as db:
            row = db.execute(
                """SELECT j.device_id,j.package_id,j.state,a.enabled AS ae,d.enabled AS de,
                   a.version_code,a.last_update_time,a.apk_path FROM jobs j
                   JOIN devices d ON d.id=j.device_id
                   JOIN apps a ON a.device_id=j.device_id AND a.package_id=j.package_id
                   WHERE j.id=?""",
                (job_id,),
            ).fetchone()
            if not row or row["state"] != "running" or not row["ae"] or not row["de"]:
                return False
            current = "|".join(
                (
                    row["package_id"],
                    str(row["version_code"]) if row["version_code"] is not None else "",
                    row["last_update_time"] or "",
                    row["apk_path"] or "",
                )
            )
            if current != fingerprint:
                return False
            db.execute(
                """UPDATE apps SET compiled_fingerprint=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
                   WHERE device_id=? AND package_id=?""",
                (fingerprint, row["device_id"], row["package_id"]),
            )
            db.execute(
                "UPDATE jobs SET state='succeeded',reason=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",
                (reason, job_id),
            )
            self._event(db, job_id, "succeeded", reason)
            return True

    def claim_next(self, now: str | None = None) -> Job | None:
        """Atomically claim the oldest due job, enforcing a single running operation."""
        with self._transaction() as db:
            if db.execute("SELECT 1 FROM jobs WHERE state='running' LIMIT 1").fetchone():
                return None
            due_filter = "available_at<=COALESCE(?,strftime('%Y-%m-%dT%H:%M:%fZ','now'))"
            row = db.execute(
                f"""SELECT j.* FROM jobs j JOIN devices d ON d.id=j.device_id
              JOIN apps a ON a.device_id=j.device_id AND a.package_id=j.package_id
              WHERE j.state='pending' AND {due_filter} AND d.enabled=1 AND a.enabled=1
              ORDER BY j.available_at,j.id LIMIT 1""",
                (now,),
            ).fetchone()
            if not row:
                return None
            db.execute(
                """UPDATE jobs SET state='running',attempts=attempts+1,reason=NULL,
                        updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?""",
                (row["id"],),
            )
            self._event(db, row["id"], "claimed")
            return self._job(db.execute("SELECT * FROM jobs WHERE id=?", (row["id"],)).fetchone())

    def recover_running(self) -> int:
        """Return interrupted running jobs to pending on process startup."""
        with self._transaction() as db:
            rows = db.execute("SELECT id FROM jobs WHERE state='running'").fetchall()
            for row in rows:
                db.execute(
                    """UPDATE jobs SET state='pending',reason='recovered after interruption',
                            updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?""",
                    (row["id"],),
                )
                self._event(db, row["id"], "recovered", "process interrupted while running")
            return len(rows)

    def finish_job(
        self, job_id: int, *, state: str, reason: str | None = None, available_at: str | None = None
    ) -> None:
        if state not in {"pending", "succeeded", "failed", "superseded", "cancelled"}:
            raise ValueError("invalid terminal/retry job state")
        with self._transaction() as db:
            row = db.execute("SELECT state FROM jobs WHERE id=?", (job_id,)).fetchone()
            if not row or row["state"] != "running":
                raise ValueError("job is not running")
            db.execute(
                """UPDATE jobs SET state=?,reason=?,available_at=COALESCE(?,available_at),
              updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?""",
                (state, reason, available_at, job_id),
            )
            self._event(db, job_id, state, reason)

    def set_wait_reason(self, job_id: int, reason: str) -> None:
        """Persist a deferral explanation without claiming the job or using an attempt."""
        with self._transaction() as db:
            row = db.execute("SELECT state,reason FROM jobs WHERE id=?", (job_id,)).fetchone()
            if not row or row["state"] != "pending" or row["reason"] == reason:
                return
            db.execute(
                "UPDATE jobs SET reason=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",
                (reason, job_id),
            )
            self._event(db, job_id, "deferred", reason)

    def defer_running(self, job_id: int, reason: str, available_at: str | None = None) -> None:
        """Release a preflight claim without charging an attempt to a compiler operation."""
        with self._transaction() as db:
            row = db.execute("SELECT state,attempts FROM jobs WHERE id=?", (job_id,)).fetchone()
            if not row or row["state"] != "running":
                return
            db.execute(
                """UPDATE jobs SET state='pending',attempts=MAX(0,attempts-1),reason=?,
                   available_at=COALESCE(?,available_at),
                   updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?""",
                (reason, available_at, job_id),
            )
            self._event(db, job_id, "deferred", reason)

    def mark_compiled(self, device_id: str, package_id: str, fingerprint: str) -> bool:
        """Persist verified success only if the fingerprint still matches the current baseline."""
        with self._transaction() as db:
            cursor = db.execute(
                """UPDATE apps SET compiled_fingerprint=?,
              updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
              WHERE device_id=? AND package_id=? AND package_id || '|' ||
              COALESCE(CAST(version_code AS TEXT),'') || '|' || COALESCE(last_update_time,'') || '|' ||
              COALESCE(apk_path,'') = ?""",
                (fingerprint, device_id, package_id, fingerprint),
            )
            return cursor.rowcount == 1

    def get_job(self, job_id: int) -> Job | None:
        with closing(self._connect()) as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return self._job(row) if row else None

    def list_jobs(self, *, limit: int = 100, state: str | None = None) -> list[Job]:
        if not 1 <= limit <= 1000:
            raise ValueError("limit must be between 1 and 1000")
        with closing(self._connect()) as db:
            if state:
                rows = db.execute("SELECT * FROM jobs WHERE state=? ORDER BY id DESC LIMIT ?", (state, limit))
            else:
                rows = db.execute("SELECT * FROM jobs ORDER BY id DESC LIMIT ?", (limit,))
            return [self._job(r) for r in rows]

    def list_job_events(self, job_id: int) -> list[JobEvent]:
        with closing(self._connect()) as db:
            rows = db.execute("SELECT * FROM job_events WHERE job_id=? ORDER BY id", (job_id,))
            return [JobEvent(r["id"], r["job_id"], r["event"], r["detail"], r["created_at"]) for r in rows]

    def _cancel_jobs(
        self, db: sqlite3.Connection, reason: str, *, device_id: str, package_id: str | None = None
    ) -> None:
        sql = "SELECT id FROM jobs WHERE device_id=? AND state IN ('pending','running')"
        args: list[object] = [device_id]
        if package_id is not None:
            sql += " AND package_id=?"
            args.append(package_id)
        for row in db.execute(sql, args):
            db.execute(
                "UPDATE jobs SET state='cancelled',reason=?,updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?",
                (reason, row["id"]),
            )
            self._event(db, row["id"], "cancelled", reason)
