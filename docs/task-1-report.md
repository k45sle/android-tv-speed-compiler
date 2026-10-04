# Task 1 implementation report

Implemented the package foundation, persistence layer, and constrained ADB adapter. This task did not add a scheduler, web application, or Docker files. No live TV commands were issued.

## Files

- `pyproject.toml`: Python 3.11+ setuptools package and pytest/Ruff development extras.
- `.gitignore`: excludes virtual environments, instance/database state, environment files, and local Serena state.
- `src/tvcompiler/models.py`: immutable device, app baseline, job, job-event, identity, and ADB result value objects.
- `src/tvcompiler/store.py`: WAL-backed SQLite persistence with short `BEGIN IMMEDIATE` transactions, explicit connection closing, baseline/device records, deduplicated jobs, job events, atomic claim, and interrupted-job recovery.
- `src/tvcompiler/adb.py`: allowlisted ADB operations, constrained endpoint/package validation, persistent per-instance ADB home, mDNS parsing, pairing, connection/reconnection, durable identity checks, package metadata and installation fingerprints, conservative busy-state checks, speed compilation, and dexopt-filter inspection.
- `tests/`: fixture-driven parser and store tests, plus fake-runner subprocess-boundary tests.

## Stable interfaces for later tasks

`Store(path)` owns persistence. Relevant operations are `upsert_device`, `get_device`, `list_devices`, `upsert_app`, `get_app`, `list_apps`, `set_app_enabled`, `enqueue_job`, `claim_next`, `recover_running`, `finish_job`, `mark_compiled`, `get_job`, `list_jobs`, and `list_job_events`. A successful `claim_next()` changes one due job to `running` and increments attempts inside an immediate transaction; it returns `None` while any job is running. `enqueue_job` deduplicates the same live fingerprint and supersedes other live fingerprints. `mark_compiled` returns true only when the fingerprint still matches the current stored version code, update time, and APK path.

`AdbClient(instance_dir, runner=..., timeout=...)` supplies explicit operations only. Pairing and connect/disconnect/discovery are separate from device data commands. Device data and compile methods require `verify_connected_identity(...)` or a `reconnect(...)` call with a pinned identity; the adapter re-reads the durable serial and build fingerprint before each such operation. Durable serial comes from `ro.serialno` or `ro.boot.serialno`; TCP/TLS endpoint strings are never treated as device identity. `compile_speed(serial, package_id)` hard-codes the `speed` filter and rejects package-manager output reporting `Failure`. `inspect_compilation(...)` returns `supported=False` when the device output does not expose a recognizable per-package filter, and reports mixed filters rather than claiming `speed` for all dexopt entries.

`PackageFingerprint.value` combines package ID, version code, `lastUpdateTime`, and APK path, so a reinstall with an unchanged version code remains distinguishable. Package labels are intentionally `None` at this layer; later inventory/UI code may obtain a readable label separately without making the package ID optional.

`BusyStatus.idle_confirmed` is true only when the display/screen and playback are both known inactive. Awake/active signals, active media states, unknown screen state, unknown playback state, and unfamiliar playback-state numbers remain busy or unknown. Explicit zero-session output and known inactive playback states can establish no active playback. These checks are conservative signals, not a guarantee that playback detection is complete on every Android build.

## Task 2 integration notes

- Call `recover_running()` during startup. The store only records state; the scheduler owns retries, backoff, idle/window gating, and user-visible wait reasons.
- Keep the scheduler's execution lock until the real ADB subprocess ends. A newly observed fingerprint or a disable/remove action can change a currently running row to `superseded`/`cancelled`; the subprocess itself is not cancellable through this store. After execution, re-read the job and installation before recording success. `finish_job` rejects transitions from anything no longer `running`, and `mark_compiled` refuses a stale fingerprint.
- A successful command return code alone does not establish the requested filter. Use post-run `inspect_compilation`; when unsupported or mixed, report that result instead of marking the requested filter verified.
- Keep one long-lived `AdbClient` or redo `verify_connected_identity` after constructing a client. Pin the durable serial and, where policy requires, the build fingerprint from the store. Discovery may fail on some hosts; `reconnect(endpoint=...)` still tries the explicit endpoint.
- This foundation has no label lookup, scheduler, manual foreground override, maintenance-window parser, or UI. Those remain later-task responsibilities.

## Verification

Used a local Python 3.11.17 virtual environment created with `uv`; only pytest and Ruff development dependencies were added.

- `.venv/bin/pytest -q` — 16 passed.
- `.venv/bin/ruff check src tests` — passed.
- `.venv/bin/ruff format src tests` — all files formatted.

All ADB behavior in tests uses fixtures or a fake runner. Compilation was never run against a real TV.
