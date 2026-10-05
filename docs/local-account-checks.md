# Password-only local account setup

The user testing first run requested account creation without terminal/token retrieval. Bundled loopback-only Compose deployments now explicitly enable `TVCOMPILER_LOCAL_SETUP=1`; their first-run page asks only for a password of at least 12 characters. This replaces the earlier assumption that every deployment requires manual ownership-token retrieval.

The app default remains token-required. Password-only setup additionally requires a valid loopback Host and no forwarded/proxy indicators. A public origin, secure-cookie configuration, or supplied bootstrap token disables the shortcut. The existing setup transaction receives the server-held token internally; no bootstrap secret is exposed through HTML, APIs, URLs, clipboard, or logs. CSRF, origin checks, credential throttling, password hashing, session handling, and exactly-one-account creation remain enforced. Existing accounts and device state are preserved.

Local mode is an explicit operator declaration combined with the bundled loopback binding. Request headers cannot prove network privacy; operators must set `TVCOMPILER_LOCAL_SETUP=0` before proxying, exposing, or changing that binding. Remote/custom token setup retains an actionable retrieval guide and copies only a static command.

## Review and exact final checks

Implementation used a bounded `gpt-6-luna` task with high reasoning, followed by parent review and an independent read-only security review. No material issue remained. Parent visually reviewed empty first-run Light/Night pages.

| Command/check | Result |
| --- | --- |
| `make check` | Exit 0; Ruff `All checks passed!`; `91 passed in 2.49s`; wheel/sdist built and package contents verified. |
| `make docker-check` | Exit 0 for both standalone Compose configurations. |
| `node --check src/tvcompiler/static/app.js` | Exit 0. |
| `node --check scripts/browser-smoke.mjs` | Exit 0. |
| `git diff --check` | Exit 0. |
| `PLAYWRIGHT_MODULE=/Users/rk/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright make smoke` | Exit 0; local password-only setup and token-required setup passed, including static command copy/fallback, help visibility, and existing wizard/theme/device-management/mobile regressions. |
| `docker build --platform linux/arm64 -t android-tv-speed-compiler:local-setup-check .` | Exit 0. |
| Disposable ARM64 instance with local setup enabled | Docker health `healthy`; first-run token input hidden; password-only setup HTTP 200; generated token consumed; account remained configured after container restart. Container and anonymous volume removed. |

Hosted CI and the existing local deployment update are reported in delivery. The offline account test used a disposable password and instance; no real setup token was read and no TV operations were requested.
