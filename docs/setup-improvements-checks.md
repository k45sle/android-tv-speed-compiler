# Setup and resource follow-up

The user's seven setup improvements were split across independent implementation tasks using gpt-6-luna with high reasoning. Parent review and a separate bounded reviewer covered the integration. The reviewer did not delegate. Tests used synthetic TVs and disposable instances; no real TV compile was requested during validation.

## Delivered behavior

- New setup pairs first using the pairing endpoint and code. It resolves the exact paired GUID when possible; manual connection entry follows only when lookup fails. Already-paired setup uses connection entry directly. Successful pairing survives Back/Continue without another pairing request.
- App search matches labels and package IDs without losing selections hidden by the filter, in both setup and app management.
- Review starts both Finish actions checked: automatic monitoring applies globally, and the first speed compile queues one job for each selected watched app. The owner can clear either choice. Existing monitoring remains enabled. Queued compile work waits for a confirmed idle TV and any configured maintenance window.
- Finish validates enabled watched selections and queues their saved baseline fingerprints in one transaction. Concurrent/repeated requests do not duplicate work or retry terminal fingerprints. App selection itself does not compile or rebaseline existing watched apps.
- UI and operations guidance explain persistent keys/settings, host availability, TV restarts, changed ports, discovery limitations, revoked authorization, and identity changes. Fully unattended operation and stutter-free playback are not guaranteed.
- Account creation accepts at least six characters in both local and token setup. Existing accounts, hashing, request guards, CSRF, and throttling remain covered.
- Both standalone Compose variants apply configurable defaults of 0.5 CPU, 512 MiB memory, 128 MiB reservation, 512 MiB memory-plus-swap, and 128 PIDs. Runtime limits exclude image builds and Docker Desktop VM overhead. See [operations measurements](operations.md#runtime-resource-limits).

## Review resolutions

Resolved pairing GUID verification, separate ADB selector and persisted network endpoint, unrelated connected TV handling, duplicate IP/GUID aliases, and fallback Back/Continue navigation. Two aliases of one endpoint use the verified GUID selector; matches across distinct endpoints fail closed. The independent reviewer found no remaining material issue in the scoped changes.

## Required checks

| Check | Result |
| --- | --- |
| `make check` | Ruff clean; `112 passed in 4.17s`; wheel and source archive built; package contents passed. |
| `make docker-check` | Exit 0 for both standalone Compose variants. |
| `node --check src/tvcompiler/static/app.js` and `node --check scripts/browser-smoke.mjs` | Exit 0. |
| `git diff --check` | Exit 0. |
| `PLAYWRIGHT_MODULE=/Users/rk/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright make smoke` | Exit 0: local/token account flows, pair-first success/fallback/Back, app search and hidden selections, Finish consent/retry, idle gating, management, themes, and mobile overflow checks passed. |
| `docker build -t android-tv-speed-compiler:improvements-check .` | Exit 0; native ARM64 image, bundled ADB pair/mDNS and timezone checks passed. |
| Final ARM64 capped disposable probe | HTTP 200, UID/GID 10001, writable data, enforced Compose limits; 45.4-second synthetic API/auth workload, 228 successful requests of each tested operation. 24 samples: 0.39–53.49% CPU, 87.71–99.21 MiB memory, 6–7 PIDs. No OOM or unexpected restart; account login survived a same-volume restart. Isolated test resources removed. |

Live TV update/reboot and firmware-specific idle behavior remain unverified by these checks. Resource-probe and deployment evidence are recorded separately; HTTP health does not prove TV reachability.
