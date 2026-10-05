# Connection and login checks

## Current behavior

- `LOGIN_ENABLE` defaults to `false`. The dashboard opens without an account, and startup creates no auth database or bootstrap token. Bundled Compose binds to host loopback. The app rejects non-loopback `Host` values and forwarded proxy headers in this mode, but those checks cannot prove that the full network route is private. Mutations require a same-origin `Origin` header and a matching CSRF cookie/header; the CSRF cookie is issued again when missing or invalid. Turning login off leaves existing auth data intact, and turning it back on restores the existing account.
- Each TV has a saved connection choice: encrypted Wireless debugging (`wireless`) or unencrypted fixed TCP/IP (`tcpip`). The choice guides connection behavior; it does not switch TV settings or verify the live transport's encryption. The service does not install the optional TV companion. Fixed TCP/IP requires the owner to configure the TV and approve this service's RSA key; the companion's separate key does not authorize the service. See [Wireless debugging and ADB](wireless-debugging-recovery.md) for tradeoffs and setup steps.
- When monitoring is paused, the automatic-monitoring option is checked by default; if monitoring is already on, Finish preserves it. The first speed compile is checked by default for selected apps. Owners can clear either option. Queued compiles wait until the TV is confirmed idle and any maintenance window is open. Busy, unknown, or out-of-window status can defer work.
- The dashboard reports connection and polling status, poll interval, time-zone choices, and missing ADB. **Run log** shows job outcomes and current wait reasons.

## Verification evidence

- `make check` passed with exit code 0: Ruff was clean, 130 tests passed in 4.24 seconds, and wheel/source archives contained the expected assets.
- `make docker-check` passed for both Compose variants. `node --check src/tvcompiler/static/app.js` and `node --check scripts/browser-smoke.mjs` passed. `PLAYWRIGHT_MODULE=/Users/rk/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright make smoke` passed. `git diff --check` passed.
- Browser smoke passed the no-login dashboard and mutation after a cleared CSRF cookie, both connection methods, mode persistence across reload, default and opt-out Finish choices, retry behavior, missing-ADB reporting, time-zone selection, themes, and mobile layout.
- `docker build --platform linux/arm64 -t android-tv-speed-compiler:connection-check .` passed. A disposable, network-disabled container stayed healthy and returned HTTP 200; it ran as UID/GID 10001, found bundled ADB, created no auth database or bootstrap token, and left monitoring paused. It ran with configured limits of 0.5 CPU, 512 MiB memory, and 128 PIDs; it had no OOM kill or restart.
- An independent security review found authorization classification, freshness-boundary, and CSRF-cookie expiry issues. Those were fixed; the follow-up review found no material findings.

## Limits

No test ran a command against a real TV. Companion installation, reboot recovery, and firmware compatibility remain unverified. The saved connection choice is metadata, not a transport-encryption audit. No CI run or deployment evidence is included here.
