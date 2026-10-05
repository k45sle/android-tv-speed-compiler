# Night mode and guided setup evidence

The UI now offers System, Light, and Night appearance. System is the default, follows the device/browser preference, and the chosen mode is saved in this browser. Storage failures fall back safely.

The inline setup wizard shows one step at a time: TV preparation/name, connection endpoint, new pairing when needed, app selection, and review. Advanced settings and manual setup are collapsed. App selection saves baselines with `initial_compile: false`; Finish preserves monitoring. Pairing codes are cleared after submission or cancellation. This update changes no production backend APIs, compilation commands, or scheduling defaults.

Implementation used sequential bounded `gpt-6-luna` tasks with high reasoning, followed by parent review. Independent review identified three material issues: wizard re-entry during pairing, misleading Back navigation after saving a TV, and concurrent app saves. A bounded correction task addressed all three and added regression coverage. Scoped independent re-review found no material residual issue.

## Final local checks

| Command/check | Exact result |
| --- | --- |
| `make check` | Exit 0; Ruff `All checks passed!`; `65 passed in 2.00s`; wheel/sdist built and package contents verified. |
| `make docker-check` | Exit 0 for both standalone Compose configurations. |
| `node --check src/tvcompiler/static/app.js` | Exit 0. |
| `node --check src/tvcompiler/static/theme.js` | Exit 0. |
| `node --check scripts/browser-smoke.mjs` | Exit 0. |
| `git diff --check` | Exit 0. |
| `PLAYWRIGHT_MODULE=/Users/rk/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright make smoke` | Exit 0; pairing branches, explicit endpoint selection, required-field validation, transient code clearing, duplicate protection, partial-save retry, monitoring/compile defaults, normal TV/app management, settings/diagnostics, theme behavior, and mobile overflow checks passed. No JavaScript errors. |
| Light/Night text contrast | Browser assertions passed at least 4.5:1 for body, muted, and placeholder text. |
| `docker build --platform linux/arm64 -t android-tv-speed-compiler:ui-final .` | Exit 0; final native image built. |
| Disposable offline container | Docker health `healthy`; `/health`, `/`, `/static/app.js`, `/static/theme.js`, and `/static/style.css` returned HTTP 200. Root included wizard and Appearance controls. UID/GID `10001:10001`; instance directory writable. Init enabled, all capabilities dropped, no-new-privileges enabled. Container and anonymous volume removed. |

Parent visually reviewed the final desktop Night wizard, mobile Light wizard, and mobile Night dashboard. The design detector reported only the existing Inter font choice; it was retained to preserve the established UI. No further visual refinement was needed.

Hosted CI builds and starts both AMD64 and ARM64 images on each push; its actual run result is reported with delivery. All checks here used fake devices or offline containers. Live TV pairing, app updates, and playback performance remain unverified by this UI update.
