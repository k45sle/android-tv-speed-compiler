# Task 3 implementation report

Implemented the FastAPI service lifecycle, secure first-run/login flow, and responsive server-rendered dashboard with vanilla JavaScript. No real ADB device was accessed or changed. All HTTP/ADB coverage uses injected fakes.

## Integration and entry point

`tvcompiler.web.create_app(instance_dir, *, adb=None, scheduler=None, start_scheduler=True, secure_cookies=None, environ=None)` supports fake injection for HTTP tests. It creates the private instance directory, SQLite state/auth databases, ADB client, scheduler, packaged Jinja template/static mounts, and the ASGI lifespan. Scheduler start/stop is confined to lifespan; its stop waits up to five seconds and its existing process lock remains owned while a timed-out ADB operation is still running. All ADB-triggering endpoints are synchronous FastAPI handlers, so they execute in the framework's thread pool instead of blocking the event loop.

The `tvcompiler` console script runs Uvicorn with one worker, proxy-header trust disabled, `127.0.0.1:8000` defaults, and configurable instance/host/port. FastAPI docs/OpenAPI routes are disabled. `/health` is unauthenticated and returns only `{"status":"ok"}`.

Runtime dependencies are FastAPI, Uvicorn, and Jinja2. The setuptools package includes `templates/*.html`, `static/*.css`, and `static/*.js`. HTTP tests use the `httpx` development dependency with FastAPI's TestClient. Task 4 should resolve and lock exact dependency versions and verify a clean package/container install.

## Authentication and request protection

- Before an account exists, `TVCOMPILER_BOOTSTRAP_TOKEN` can supply a 32–256 character one-time token. Otherwise the service atomically creates `instance/bootstrap.token` with mode `0600`. First-user creation uses `BEGIN IMMEDIATE`; after success the generated token file is removed. Configured instances do not recreate a token on restart.
- Passwords must be at least 12 characters. A random salt and scrypt (`N=16384, r=8, p=1`) hash are stored; passwords and raw sessions are not persisted. The session token is random, persisted only by SHA-256 digest, expires after 12 hours, and is revocable at logout. Session cookies are HttpOnly, SameSite=Strict, and Secure when configured.
- First-run and login requests use a CSRF double-submit token and same-origin checks. Authenticated mutations require the session-bound CSRF value in `X-CSRF-Token`; cross-site `Origin` and `Sec-Fetch-Site` requests are rejected. `TVCOMPILER_PUBLIC_ORIGIN` can provide the external scheme/host when TLS terminates at a reverse proxy. `TVCOMPILER_COOKIE_SECURE=1` enables Secure cookies for HTTPS.
- Credential failures are rate-limited by the ASGI peer address, using a bounded in-memory table (five failures per five-minute window). Forwarded client-IP headers are ignored. The limiter resets on process restart.
- Request validation errors are generic and omit input values. Pairing errors are mapped to fixed safe text. Pairing codes are sent only to ADB, cleared from the browser field on submit, and never stored or returned. There are no arbitrary shell, command, filter, or secret-bearing diagnostic routes.

## HTTP/UI surface

- `GET /` and `/login`: accessible responsive setup/login/dashboard page. `GET /api/session`; `POST /api/setup`, `/api/login`, `/api/logout`.
- `GET /api/status`: monitoring state, polling/retry configuration, recent jobs and safe status fields. `GET /api/jobs/{id}/events`: events for one job.
- `GET /api/devices`; `POST /api/discover`, `/api/pair`, `/api/devices`; `POST /api/devices/{id}/reconnect`; `PATCH` and `DELETE /api/devices/{id}`.
- `GET /api/devices/{id}/inventory` reads fresh third-party app metadata without overwriting saved baselines. `POST /api/devices/{id}/watch`, `DELETE /api/devices/{id}/watch/{package_id}`, and `POST /api/devices/{id}/compile` delegate only to the existing scheduler's constrained APIs.
- `PUT /api/monitoring` and `/api/settings` change scheduler settings. `GET /api/diagnostics` exports an explicit allowlist of app-level state; it excludes user/session tables, bootstrap tokens, pairing codes, ADB keys, raw command output, and dumpsys output.

The page supports TLS service discovery; separate pairing and connection endpoints; pairing and already-paired setup; multiple named TVs; verified reconnect endpoints; pause/forget; live app inventory; explicit watched-app and optional initial-compile controls; a separate unchecked-by-default foreground-override checkbox; monitoring pause/resume; retry/poll/timezone-window controls; timestamped status/history/event details; and safe diagnostics download. The page refreshes queue/status every ten seconds without rereading inventory or overwriting a dirty settings form.

Specific labels are provided for `com.nuvio.tv` (Nuvio) and `com.nuvio.tv.test` (Nuvio Test); other apps use available stored/device metadata or the package ID. Inventory is transient until the scheduler observes changes or the user explicitly watches an app, preserving scheduler baselines. Existing monitor-pause semantics remain: automatic monitoring/jobs pause, while explicit manual jobs can run; only the explicit foreground override bypasses idle/window gates.

## Verification

- `.venv/bin/ruff format src tests` — passed.
- `.venv/bin/ruff check .` — passed.
- `.venv/bin/pytest -q` — 52 passed in 1.44 seconds. One upstream Starlette TestClient/httpx deprecation warning remains; Task 4 should check whether its locked Starlette version supports `httpx2`.
- `node --check src/tvcompiler/static/app.js` — passed.
- `git diff --check` — passed.
- `uv pip install --python .venv/bin/python -e .` — editable package install passed.
- Test coverage includes concurrent first setup, one-time token file permissions/removal/restart, password hashing, persistent/expired/revoked sessions, setup/login throttling, CSRF/origin and Unicode-token rejection, unauthorized routes, fake offline startup, multiple TV identity checks, endpoint edit/forget races, pairing-code exclusion, baseline-safe fresh inventory, Nuvio labels, watch/manual queue/foreground override/pause/settings/history, and diagnostic redaction.

## Limits for Task 4

- No browser automation was run in this task. Task 4 can run the bundled Playwright browser smoke against setup, login, and the dashboard using fake ADB.
- No live-TV behavior was checked. Device authorization, app labels beyond existing local metadata, Android build-specific activity/media parsing, reconnects, and compilation filter reporting still need honest hardware-side validation; simulated tests do not verify those claims.
- Performance improvement is not measured or promised. Android may report compilation filter status as unverified.
- The service remains single-process/single-worker with SQLite and the scheduler's process lock. Rate limits are intentionally bounded and process-local, so they reset on service restart.
