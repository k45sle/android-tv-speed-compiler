# Implementation plan and acceptance criteria

Source: the user discussion and ../repository-assessment.md. No application existed at the start. Build a focused original Docker service named android-tv-speed-compiler, proposed private repository k45sle/android-tv-speed-compiler. No third-party repository code is copied. Use Python 3.11+, FastAPI, SQLite, official ADB command execution, and a small accessible server-rendered dashboard. Do not add a TV companion application, disable system apps, change ADB to legacy port 5555, or expose arbitrary shell execution. No real TV compilation or updates during project checks; use deterministic simulated ADB tests and optionally read-only live diagnostics.

## Acceptance criteria

1. Docker Compose starts one service, including when no TV is reachable. Persist SQLite state and ADB pairing keys across restarts. Provide documented Linux deployment and honest Docker Desktop networking limitations/fallbacks.
2. Authenticated first-run setup and guided TLS wireless pairing. Never log or persist pairing codes. Credentials, keys, and instance data excluded from Git/build context and diagnostic exports. Protect state-changing browser requests against CSRF; no arbitrary shell API. Rate-limit credential failures.
3. Multiple named TVs, identity verification before commands, mDNS reconnect for changing connection ports, manual endpoint fallback, actionable offline/authorization errors, and forget-device support.
4. List installed apps with readable names where available and package IDs always visible; distinguish com.nuvio.tv and com.nuvio.tv.test; explicit allowlist selection; inventory unavailable names remain usable. Watching establishes current version as baseline, with optional initial compilation.
5. Poll local package metadata regardless of install source. Fingerprint includes versionCode and lastUpdateTime (plus APK path), detecting same-version reinstalls. Deduplicate pending changes; supersede old-installation jobs; do not run stale or disabled/removed app jobs.
6. Persistent serialized job queue: exactly one compiler operation at a time in the single-service deployment. Bounded retries/backoff, subprocess timeouts, outcome logging, and restart recovery. Verify actual compilation filter where supported. Unsupported verification explicitly shown rather than fabricated. Only mark a fingerprint compiled after successful execution. Handle timeout/interruption without falsely claiming success.
7. Defer automatic work while offline, screen active, playback active, or idle detection unknown. Optional maintenance window with IANA timezone and crossing-midnight handling. Manual override requires explicit user choice. Explain every waiting reason. Conservative screen/media/power parsing with fixtures; never claim perfect playback detection.
8. User-friendly responsive dashboard: setup steps, discover/pair/add TV, select apps, optional initial/manual compile, pause/resume monitoring, scheduling, job history/status/reasons, reconnect/forget, diagnostics download, and informative empty/error states. Successful compilation is not advertised as measured improvement.
9. Recovery checks cover startup offline, container/process restart, changed ports, revoked authorization, same-version reinstall, multiple updates, superseded jobs, compilation failures/timeouts, and concurrent requests. Add meaningful security and queue tests. Lint, tests, package build, Docker Compose validation, image build, and container health/smoke checks must be run or precise environmental blocks reported.
10. README with quick start, pairing instructions, operation/recovery, backup/upgrade, security, supported architectures/OS, troubleshooting, and performance limitations. CI runs checks and builds image. MIT license for original project. Do not assert uniqueness or guaranteed smoothness.

## Ordered bounded tasks

1. Foundation: package/configuration, transactional SQLite store/schema and unit tests, constrained ADB adapter/parsers/reconnect/verification plus tests. Define stable interfaces for later tasks. No web/scheduler/Docker implementation yet.
2. Scheduler: monitoring baseline/update detection, persistent serialized compilation queue, idle/window gating, retries/recovery/outcome verification. Tests with fake ADB; use foundation interfaces.
3. Dashboard: FastAPI lifecycle integration, first-run/password/session/CSRF/rate limits, responsive accessible frontend and all setup/device/app/job/settings/diagnostic operations. HTTP tests and scheduler integration. No arbitrary command endpoints.
4. Delivery: pinned dependency lock/build/check commands, Docker/Compose/CI, documentation, cross-layer acceptance tests and smoke checks; repair packaging/integration gaps. Do not run live-TV mutation tests.
5. Independent bounded security/reliability/spec review (read-only, no further delegation). Fix material findings in one implementation task, then scoped re-review and final exact checks.

## Assumptions

- Default repository visibility private; user has already requested a new GitHub repository. Publish source only, never credentials or instance state.
- Single server process/worker; SQLite transactions plus scheduler lock serialize work. Multiple TVs supported; horizontally scaled workers are out of scope.
- First release uses speed compilation; no arbitrary compiler filters/system modifications. Immediate manual jobs need explicit foreground override if not idle.
- UI can use app label/icon only when available from installed metadata; no Google Play scraping, downloads, or dependency on an APK store.
- Environment cannot prove live update/reboot behavior without changing a user's TV. Automated simulated-device acceptance tests are required; mark live-device verification separately.
- Optional features such as push notifications and continuous profiling are deferred; diagnostics and clear dashboard status fulfill initial operations scope.
