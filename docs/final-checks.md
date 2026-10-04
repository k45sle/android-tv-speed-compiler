# Final integration evidence

Final source includes the two fixes accepted by independent scoped review. These checks used fake devices and disposable containers; no user's TV was modified.

| Command/check | Exact result |
| --- | --- |
| `make check` | Exit 0; Ruff `All checks passed!`; `65 passed in 1.96s`; wheel and source archive built; package contents passed. |
| `make docker-check` | Exit 0; both `docker compose config --quiet` and `docker compose -f compose.linux-host.yaml config --quiet` passed. |
| `git diff --check` | Exit 0. |
| `node --check src/tvcompiler/static/app.js` | Exit 0. |
| `PLAYWRIGHT_MODULE=/Users/rk/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright make smoke` | Exit 0; setup/login/logout, TLS discovery/pair/add, inventory/watch/manual defaults, settings, pause, diagnostics, and mobile layout passed. No JavaScript errors or horizontal overflow. |
| `UV_PROJECT_ENVIRONMENT=/Users/rk/Documents/Codex/2026-10-04/con/work/final-clean-env uv sync --locked --all-groups --python 3.11` | Exit 0; fresh environment installed 26 locked packages. |
| `UV_PROJECT_ENVIRONMENT=/Users/rk/Documents/Codex/2026-10-04/con/work/final-clean-env uv run --locked pytest -q` | Exit 0; `65 passed in 2.34s`. |
| `docker build --platform linux/arm64 -t android-tv-speed-compiler:final-arm64 .` | Exit 0; native image built, bundled ADB pair/mDNS and IANA timezone checks passed. |
| `docker build --platform linux/amd64 -t android-tv-speed-compiler:final-amd64 .` | Exit 0; image built under Docker Desktop/QEMU; bundled tool checks passed. |
| Offline container startup, both architectures | Health route HTTP 200 with `{"status":"ok"}`; Docker health `healthy`; runtime UID/GID `10001:10001`. Containers used init, dropped all capabilities, and enabled no-new-privileges. |
| ARM64 isolated ADB key restart | Zero connected devices; key SHA-256 matched before/after restart; mode 0600 preserved. Key contents were never printed. |

Task 4's additional probes verified auth/password/session, SQLite marker, consumed bootstrap token, and actual ADB key persistence across restart. See [task-4-report.md](task-4-report.md). Independent review and the regression evidence are described in [task-5-report.md](task-5-report.md).

## Acceptance coverage

All ten criteria in [implementation-plan.md](implementation-plan.md) are covered by the implementation, unit/integration tests, browser smoke, container checks, and operations documentation. The hosted CI workflow runs locked source checks and separate AMD64/ARM64 image/offline-start checks on every push. Its actual publication-run result is linked in the delivery response.

## Remaining limits

- Live TV update/reboot behavior was not exercised; platform-specific media/dexopt formats can vary. Unknown idle state defers, and unavailable filter verification is displayed explicitly.
- Docker Desktop may need manual connection endpoints because multicast discovery can be unavailable.
- One process and one host per state volume; no horizontal scaling, TV companion app, notification integrations, or continuous profiling.
- Speed compilation is not a guarantee of faster rendering, decoder performance, or stutter-free playback.
