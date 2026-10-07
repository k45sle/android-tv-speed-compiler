# Companion setup checks

The fixed TCP/IP setup wizard has an optional companion checkbox. It starts unchecked. Selecting it authorizes installation and configuration of the pinned third-party `adb-auto-enable` v0.3.5 app, its declared `WRITE_SECURE_SETTINGS` permission, debugging settings, authorization-expiry setting, and chosen TCP port. No Shizuku or root access is used. The service does not install the companion at startup.

Setup first needs a TLS Wireless debugging connection authenticated with this service's ADB key. If this service has not paired with the TV, pair it through the wizard. The companion needs authorization for its own ADB key. A new or unpaired companion requires a fresh, separate pairing code from the TV; an already self-paired companion with verified pairing can reuse its authorization. Enter any required codes in the wizard; they are cleared after use. Android SDK 30 (Android 11) is the setup floor; upstream testing targets Android 14 and later. That floor does not guarantee compatibility with a TV or its firmware.

The wizard retrieves only the official v0.3.5 APK into a bounded private cache and checks its size and pinned SHA-256 before caching and again before installation. The verified artifact is 13,501,636 bytes with SHA-256 `454008f3dd2a90ffcd44fc14ac3baf183c6f29ec9b194c35bc21308485c8b13b`. This pins the downloaded bytes; it does not establish an independently signed release.

While enabled, the companion's setup API is unauthenticated plain HTTP and exposed on the TV's LAN. The wizard accesses it through an ADB-forwarded local port, but that does not remove the LAN exposure. The wizard warns about the interface and requires its disable-on-success acknowledgment. The pinned handler saves the disabled preference before acknowledging, then stops the server after 500 ms. Setup resumes from saved phases, but Finish stays unavailable until a requested setup is ready. Ready requires the selected fixed endpoint to reconnect as the saved TV identity and build. If setup is abandoned before the interface is disabled, turn **Enable Web Interface on Boot** off in the companion on the TV. Fixed TCP/IP traffic is unencrypted; use a trusted private network. The service exposes no arbitrary shell commands.

## Verification

- On 2026-10-07, `make check` passed: Ruff was clean, 172 tests passed in 5.07 seconds, and wheel and source archive contents passed.
- `make docker-check` passed for both Compose variants. `node --check src/tvcompiler/static/app.js`, `node --check scripts/browser-smoke.mjs`, and `git diff --check` passed.
- Browser smoke passed with `PLAYWRIGHT_MODULE` set to the installed Playwright package and `make smoke`. It covered both pairing stages; cancel, fail, reload, and code clearing; session-preserving reload; partial setup resume; opt-out without install requests; reuse of verified self-pairing; manual encrypted and legacy setup; search, themes, and mobile layout. A 390-pixel dark mobile screenshot was visually reviewed and clean.
- The final-source local image build was attempted on 2026-10-07 but could not run because the Docker daemon socket was unavailable. Final hosted image results for the pushed revision are available in [GitHub Actions](https://github.com/k45sle/android-tv-speed-compiler/actions).
- A fresh APK download and cache copy were verified on 2026-10-07 at the size and SHA-256 above.

No real TV installation, reboot recovery, or firmware compatibility has been verified.
