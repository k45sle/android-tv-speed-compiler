# Android TV Speed Compiler

A private, single-instance service that watches selected Android TV app installations and can request Android's fixed `speed` compilation filter after an installation changes. It uses TLS Wireless debugging, SQLite, and a small local dashboard. It does not install apps, change system apps, expose arbitrary shell commands, measure launch time, or promise a performance improvement.

## Quick start: Docker Desktop or a Linux Docker host

Requirements: Docker Engine/Desktop with Compose v2; an Android TV with Wireless debugging; and a computer reachable on the same private network for pairing. The image installs Debian's `adb` package, which includes TLS pairing and mDNS support for the image's native architecture.

```sh
git clone https://github.com/k45sle/android-tv-speed-compiler.git
cd android-tv-speed-compiler
docker compose up -d --build
docker compose exec android-tv-speed-compiler cat /data/instance/bootstrap.token
```

The repository is private; `git clone` requires GitHub authentication with access granted by `k45sle`. Open <http://127.0.0.1:8000>, enter the one-time token, and set a unique password of at least 12 characters. The token is readable only by the container user and is removed after successful setup. Keep the Docker named volume `tvcompiler-data`; it contains account/session data, SQLite state, and this instance's ADB keys. The service starts while every TV is offline. Monitoring starts paused.

The published UI port binds to loopback only. To use another local port, change the host side of the Compose mapping, for example `127.0.0.1:8088:8000`; keep the container port at 8000. Do not publish this service to the public internet.

## Pair and select apps

1. On the TV, enable Developer options (usually tap **Build** seven times under **Settings → System → About**; menu wording varies), then open **Developer options → Wireless debugging** and enable it.
2. In the dashboard, choose **Discover TLS services**. Enter the TV name and the pairing and connection IP:port values separately. Enter the short pairing code shown by the TV and choose **Pair and add TV**. The code is sent to ADB and is not saved. Already paired TVs can use the separate add form.
3. Select **Load fresh app inventory**. Package IDs are always shown; `com.nuvio.tv` and `com.nuvio.tv.test` have distinct names. Select only the apps to watch. Watching saves the current installation as its baseline. An initial compilation is optional and unchecked by default.
4. Resume monitoring when ready. Automatic work waits while the TV is offline, awake, playing media, or idle state is unknown. Set an optional IANA time-zone maintenance window. A manual compile remains gated unless you explicitly select the foreground override.

Wireless debugging can use a different connection port after a TV reboot or network change. Re-enable Wireless debugging, discover services, then save the current connection endpoint using **Save name and reconnect endpoint**. Discovery can fail on some host networks; enter the current connection IP:port manually. A changed serial or Android build fingerprint fails closed and requires removing and pairing the TV again.

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

To intentionally reset a forgotten account password while preserving TV/app/job state, stop the service and back up the volume. Remove only `/data/instance/auth.sqlite3` and its `-wal`/`-shm` sidecars while stopped; leave `state.sqlite3`, `home/.android`, and keys in place. Start the service and complete first-run setup with the newly generated `bootstrap.token`. This revokes previous sessions. The bootstrap token must be read locally from the mounted volume/container and is never printed to logs.

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
