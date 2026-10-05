# Android TV Speed Compiler

A private, single-instance service that watches selected Android TV app installations and can request Android's fixed `speed` compilation filter after an installation changes. It uses TLS Wireless debugging, SQLite, and a small local dashboard. It does not install apps, change system apps, expose arbitrary shell commands, measure launch time, or promise a performance improvement.

## Quick start: Docker Desktop or a Linux Docker host

Requirements: Docker Engine/Desktop with Compose v2; an Android TV with Wireless debugging; and a computer reachable on the same private network for pairing. The image installs Debian's `adb` package, which includes TLS pairing and mDNS support for the image's native architecture.

```sh
git clone https://github.com/k45sle/android-tv-speed-compiler.git
cd android-tv-speed-compiler
docker compose up -d --build
```

The repository is private; `git clone` requires GitHub authentication with access granted by `k45sle`. Open <http://127.0.0.1:8000>. On the bundled Compose first run, choose a unique password of at least 12 characters and select **Create account**. No terminal or token retrieval is needed. This password-only setup is enabled by `TVCOMPILER_LOCAL_SETUP=1` in the bundled files, which bind the service to host loopback.

Source and custom installations default to token-required setup. The token is generated in the instance directory or supplied through `TVCOMPILER_BOOTSTRAP_TOKEN`; use the private local retrieval instructions shown by the setup page. The bundled local option is an operator declaration, not proof that every route is private: do not expose, proxy, or change the bind for a service with local setup enabled. Set `TVCOMPILER_LOCAL_SETUP=0` before any such change. Public origin, secure cookies, a supplied token, proxy headers, and non-loopback Host requests require the token. The token is never returned to a browser or placed in a URL or log, and the generated token file is removed after successful setup. Keep the Docker named volume `tvcompiler-data`; it contains account/session data, SQLite state, and this instance's ADB keys. The service starts while every TV is offline. Monitoring starts paused.

The published UI port binds to loopback only. To use another local port, change the host side of the Compose mapping, for example `127.0.0.1:8088:8000`; keep the container port at 8000. Keep `TVCOMPILER_LOCAL_SETUP=1` only with bundled loopback-only access. Before exposing or proxying the service, set it to `0` and retain token setup. Do not publish this service to the public internet.

## Set up a TV

1. On the TV, enable Developer options (usually tap **Build** seven times under **Settings → System → About**; menu wording varies), then open **Developer options → Wireless debugging** and enable it.
2. Choose **Start setup** and follow the steps. The TV’s connection IP:port comes from the main **Wireless debugging** screen. For a new pairing, the separate pairing IP:port and short code come from **Pair device with pairing code**. The code is sent to ADB and cleared after submission or cancellation.
3. Select apps from the fresh inventory and choose **Save selected apps**. Saving records each selected app’s current version as its baseline and does not queue a compile. **Skip for now** leaves app selection for later under **Manage watched apps** on that TV.
4. Finish keeps the existing global monitoring setting. Resume it only when you choose **Resume monitoring** in the final review or in **Advanced settings → Monitoring control**. Scheduling defaults remain unchanged; maintenance windows and retry settings are under **Advanced settings → Global scheduling**.

Already paired means paired with this service’s saved ADB keys. Pairing the TV through another ADB installation does not satisfy that choice. Experienced users can open **Advanced settings → Manual TV setup** for direct pairing or add of a TV that this service already paired.

Choose **Appearance** in the header to follow the device theme or select Light or Night. The choice is saved in this browser.

Wireless debugging can use a different connection port after a TV reboot or network change. Re-enable Wireless debugging, discover services, then save the current connection endpoint using **Manage TV details → Save name and reconnect**. Discovery can fail on some host networks; enter the current connection IP:port manually. A changed serial or Android build fingerprint fails closed and requires removing and pairing the TV again. To change an existing app allowlist, open **Manage watched apps** on that TV and load its fresh inventory; the setup wizard is for adding a TV.

## Where it works and network limits

The image targets Linux `amd64` and `arm64`; CI builds both architectures. The included Dockerfile uses Debian's distribution `adb` rather than Google's Linux x86-only download, so the ARM64 image has a native ADB client with `pair` and mDNS support. Debian's ADB package version follows the base distribution and can differ from Google's current platform-tools release.

For Linux hosts, `docker compose -f compose.linux-host.yaml up -d --build` uses host networking and binds the UI to `127.0.0.1:8000`. Host networking lets ADB mDNS discovery use the host network stack. Configure the host firewall to permit only trusted private-network access to the TVs and local UI.

Docker Desktop on macOS/Windows does not forward multicast mDNS discovery into containers reliably. The UI can still run and pair/connect by manually entering the TV's IP and the current pairing/connection ports. Port mapping exposes the dashboard only on the host loopback. The local ADB daemon listens on loopback only; no ADB server port is published to the LAN.

## Operation and recovery

- **Pause monitoring** pauses automatic checks and automatic queued work globally. Explicit manual jobs can still run; the per-TV pause disables that device's work.
- **Disable a TV or stop watching an app** cancels its pending work. An already-running Android compile cannot be recalled; its result is rechecked before state is recorded.
- **Queue** shows wait reasons and attempts. Android must return an explicit success and the installation must still match before the result is recorded. If Android does not expose package compilation filter data, a successful command is reported as unverified. When the device exposes a mixed or non-speed filter, the job fails and follows bounded retry handling.
- **Diagnostics** exports an allowlist of app-level state. It excludes passwords, sessions, bootstrap tokens, pairing codes, ADB keys, raw command output, and dumpsys output. Review the JSON before sharing because it includes TV names, package IDs, and operation timestamps.
- **TV reboot, Wi-Fi change, or revoked authorization:** enable Wireless debugging, reconnect to the current TLS endpoint, and approve the TV's authorization prompt. If identity verification reports a mismatch, forget the saved TV and pair it again only after confirming it is the intended device.
- **ADB pairing keys:** stored under `/data/instance/home/.android` with private directory permissions. Do not copy them into Git, diagnostic exports, or shared backups.

The service has one Uvicorn worker and a SQLite-backed queue; do not mount one data volume into multiple replicas or run multiple workers. Docker shutdown allows 15 minutes for a bounded 600-second Android compile and follow-up checks. If the container is forcibly killed or the host fails, an in-flight job is recovered as pending on restart; Android may have continued compiling after the service lost contact, so the service does not claim that interrupted operation succeeded.

## Backup, restore, and upgrades

Stop the service before taking a consistent volume snapshot. Back up the entire `tvcompiler-data` volume, including `/data/instance` (both SQLite databases and ADB keys); protect it like a password and keep it offline or encrypted. To restore, stop the service, restore the snapshot to the same named volume, then start the service. Restoring only SQLite without the matching ADB keys may require pairing again.

Upgrade by fetching reviewed source changes and rebuilding with `docker compose up -d --build`. Keep a backup first. The application applies additive SQLite migrations at startup. If an upgrade cannot read or migrate state, it should fail visibly; restore the previous image and volume snapshot rather than deleting files to make startup succeed. Rebuilding the image preserves the named volume.

To intentionally reset a forgotten account password while preserving TV/app/job state, stop the service and back up the volume. Remove only `/data/instance/auth.sqlite3` and its `-wal`/`-shm` sidecars while stopped; leave `state.sqlite3`, `home/.android`, and keys in place. Start the service and complete first-run setup. Bundled local Compose uses password-only setup; custom/remote setup requires its newly generated or provisioned `bootstrap.token`. This revokes previous sessions. The bootstrap token is never returned to a browser or printed to logs.

## Security and remote access

ADB is a trusted shell-level channel to the TV even though this service exposes only fixed operations. Use a trusted private LAN and strong account password. Keep the dashboard bound to localhost unless you deliberately configure a trusted HTTPS reverse proxy and firewall. When TLS terminates at that proxy, set `TVCOMPILER_COOKIE_SECURE=1` and `TVCOMPILER_PUBLIC_ORIGIN=https://your-private-host.example`; the proxy must preserve the public Host/Origin and must not expose the app without authentication. Proxy headers are not trusted by Uvicorn. Prefer a private VPN over direct internet exposure. The app has CSRF checks, same-origin validation, rate-limited password attempts, and no shell endpoint, but those controls do not make an untrusted public deployment safe.

The container runs as stable UID/GID `10001:10001`, drops Linux capabilities, enables `no-new-privileges`, and writes persistent data under `/data`. It does not use privileged mode or switch the TV to legacy TCP port 5555.

## Development and checks

Requires Python 3.11+, `uv`, Docker, and Node only for the optional browser smoke (Playwright is loaded from `PLAYWRIGHT_MODULE`).

```sh
make install
make check
make package
make docker-check
docker build --platform linux/arm64 -t android-tv-speed-compiler:local .
```

`uv.lock` pins runtime and development Python dependencies; `uv sync --locked --all-groups` installs them. The container installs from that same lock. `make package` builds both wheel and source archive and checks that the dashboard assets and console entry point are included. Tests use a fake ADB client. The optional browser flow uses only a disposable fake TV and state directory:

```sh
PLAYWRIGHT_MODULE=/path/to/node_modules/playwright make smoke
```

See [`docs/operations.md`](docs/operations.md) for network, backup, recovery, and limitations detail. MIT license; original project copyright 2026 k45sle.

For self-hosting setup, upgrades, or recovery performed by an AI agent, explicitly provide [`docs/agent-self-hosting.md`](docs/agent-self-hosting.md) as its runbook. Agents do not necessarily load it automatically.
