# Task 2 implementation report

Implemented the scheduler and its persistent state/API surface. No dashboard, Docker, GitHub, or live-TV mutation work was added. All scheduler tests use a fake ADB client.

## Changed files

- `src/tvcompiler/scheduler.py`: single-thread polling, eligibility checks, serialized execution, reconnect and live-install rechecks, retry/backoff, maintenance windows, process lock, lifecycle, and status API.
- `src/tvcompiler/store.py`: additive SQLite migration for settings and job manual intent; atomic installation observation plus queueing; persistent wait reasons; claim-specific serialized execution; preflight deferral without charging attempts; and atomic current-install success.
- `src/tvcompiler/models.py`: installation fingerprint property and durable manual/foreground-override fields on jobs.
- `tests/test_scheduler.py`: fake-ADB tests for baseline and same-version reinstalls, burst updates, disabled/stale work, offline/revoked authorization, pause/manual behavior, idle waits/fairness, timezone windows, retries/timeouts/filter outcomes, process restart, and execution locking.
- `docs/progress.md`: task status.

## Task 3 API

Construct `Scheduler(store, adb, instance_dir, ...)`. The stable entry points are:

- `start()` / `stop(timeout=None)` start or stop the background worker. A timed-out stop keeps the durable process lock until the in-flight command ends.
- `watch_app(device_id, package_id, initial_compile=False)` reads a live, complete installation and atomically enables it with a fresh baseline. Optional initial work is an explicit manual job, still subject to idle and window gates.
- `manual_compile(device_id, package_id, foreground_override=False)` refreshes the live installation and queues an explicit retry. Only `foreground_override=True` bypasses idle and maintenance-window gates.
- `set_monitoring(bool)`, `configure_polling(interval_seconds, max_attempts=None)`, and `configure_window(start, end, timezone_name="UTC")` persist scheduler settings.
- `poll_once()` returns the count of newly queued fingerprints. `run_once()` performs a deterministic poll/eligibility/one-job pass and returns the resulting `Job` or `None`; both support injected clocks through the constructor.
- `status()` returns monitoring/poll/error/window state and the latest 100 `Job` objects. `Store.list_jobs()` and `Store.list_job_events(job_id)` expose queue history and reasons.

Global monitoring defaults to paused. Pausing blocks automatic polling and automatic queued jobs; explicit manual jobs may still run. Manual jobs remain subject to idle and maintenance-window gates unless their persisted foreground override is true.

## Behavior and limits

The current fingerprint is `package_id|versionCode|lastUpdateTime|APK path`; incomplete live metadata is deferred and never used to record success. Observation and automatic enqueue/supersede happen in one SQLite transaction. A failed/succeeded fingerprint is not automatically requeued by polling; explicit manual retry may create another attempt. Offline, authorization, idle, unknown, paused, and window waits persist a reason without consuming a compile attempt. The queue scans past ineligible TVs to reach another eligible device.

The worker uses a nonblocking OS file lock in the instance directory plus an in-process mutex. It is intentionally a single-process service; a second scheduler for the same state directory fails to start. On command completion it checks for explicit package-manager `Success`, re-reads the installation, then checks the filter. A supported mixed/non-speed filter fails; unsupported inspection is recorded as “command succeeded, compilation filter unverified.” Only the still-enabled/current job and fingerprint are marked compiled in the same SQLite transaction as success.

Activity/playback detection remains the foundation's conservative ADB parser and may report unknown on some Android versions. An Android build-fingerprint change fails identity verification and requires re-verification. Interruption recovery requeues a running job without claiming success; if its already-counted attempts exhausted the bound, startup records it failed. The process lock prevents duplicate schedulers but does not make multi-host/shared-filesystem deployment supported.

## Verification

- `.venv/bin/ruff format src tests` — passed (9 files unchanged).
- `.venv/bin/ruff check .` — passed.
- `.venv/bin/pytest -q` — 39 passed in 0.51s.
- `git diff --check` — passed.

No ADB executable or real TV was used by the scheduler tests.
