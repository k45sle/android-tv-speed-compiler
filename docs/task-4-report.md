# Task 4 delivery report

Completed packaging, deployment, operations documentation, and delivery acceptance checks. Checks use fakes and disposable state only; no real TV was paired, connected, or changed.

## Deliverables

- Pinned runtime and development dependencies in `uv.lock`; `uv sync --locked` installs the locked graph. `httpx2==2.13.1` is the TestClient transport tested with the locked Starlette version, removing its deprecation warning.
- Corrected package metadata to describe the service, and added a package-content check for the wheel and source archive, including Jinja template, CSS, JavaScript, and `tvcompiler` console entry point.
- Added Make commands for clean installation, lint, tests, package build, Compose validation, and optional fake-backed Playwright browser smoke.
- Added Debian Trixie ARM64/AMD64 Docker packaging. The image uses Debian's native `adb`, installs timezone data, runs as stable UID/GID `10001:10001`, and verifies `adb pair`, `adb mdns`, and `America/Chicago` at build time. Docker Compose binds the dashboard to host loopback, drops capabilities, persists `/data`, and allows 15 minutes for graceful stop. `compose.linux-host.yaml` is a standalone Linux mDNS host-network variant.
- Added least-permission CI for locked Python checks and separate AMD64/ARM64 image builds. CI starts the built image without a TV and curls its health route. Added MIT license, README, and operational/recovery guide.
- Expanded exclusions for `.venv`, Serena/cache state, instance and SQLite files/sidecars, keys, bootstrap token, environment secrets, build/dist, and temporary outputs in Git/build context.
- Added graceful scheduler-stop checks before queue claim and compile, and a regression test that disables a watched app during slow preflight and proves ADB compile is not dispatched. Re-read durable job/device/app state after preflight so a concurrent cancellation, removal, or superseding update does not start a stale compile.
- Tightened compilation-filter parsing to require an exact package section. Added a regression proving `com.nuvio.tv.test` output cannot verify `com.nuvio.tv`.
- Corrected the dashboard and quick start to locate Wireless debugging under Developer options and raised small interface type sizes. No styling exceptions were suppressed.

## Validation results

- `uv sync --locked --all-groups` — passed on the workspace Python 3.11 environment.
- `make check` — passed: Ruff clean; **53 tests passed**; wheel and source archive built and both package contents passed validation.
- `UV_PROJECT_ENVIRONMENT=/tmp/android-tv-speed-compiler-clean uv sync --locked --all-groups --python 3.11` — clean locked environment installed successfully. `UV_PROJECT_ENVIRONMENT=/tmp/android-tv-speed-compiler-clean uv run --locked pytest -q` — **53 passed**.
- `make docker-check` — default Compose and standalone Linux host-network Compose configs both validated.
- `PLAYWRIGHT_MODULE=/Users/rk/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright make smoke` — passed browser setup, logout/login, TLS discovery, pairing and second-Tv add, package inventory for both Nuvio IDs, watch selection, default-unchecked manual options, manual queue, settings/window, pause/resume, diagnostics download, mobile overflow and JavaScript-error checks. Fake server and state were removed afterward. Screenshot: `../../work/dashboard-smoke.png`.
- `node --check src/tvcompiler/static/app.js` and `node --check scripts/browser-smoke.mjs` — passed.
- `docker compose -p task4smoke up --build -d` — final ARM64 image built and started offline, health endpoint returned 200, container reached healthy, and container ADB help exposed pair and mDNS. In a disposable named volume, `AdbClient('/data/instance').devices()` started ADB with no connected devices and generated the actual instance key at `/data/instance/home/.android/adbkey` (mode 0600). Its SHA-256, auth password/session, SQLite state marker, and consumed one-time-token state all persisted across restart. Key/token/session contents were not printed. The container ran as UID/GID `10001:10001`, non-privileged, with all capabilities dropped.
- `docker build --platform linux/amd64 -t android-tv-speed-compiler:amd64 .` — passed under Docker Desktop/QEMU; AMD64 container started offline, reached healthy, and returned HTTP health 200.
- `git diff --check` — passed. Disposable containers, network, and volume were removed. Build artifacts are ignored.

## Limits and handoff

No live TV operations were run. Docker Desktop may not carry mDNS into its Linux VM; manual TLS endpoint entry is documented. The Android filter may remain unverified on builds that do not expose its status; mixed/non-speed reported filters fail and follow retry handling. The service remains one process and one host per state volume. Debian's ADB package version follows Trixie updates. Independent parent review and the authorized private-repository source push remain pending.
