# Android TV Speed Compiler

A single-instance service that watches selected Android TV app installations and can request Android's fixed `speed` compilation filter after an installation changes. It uses Android Debug Bridge (ADB), SQLite, and a small local dashboard. Choose encrypted TLS Wireless debugging or unencrypted fixed TCP/IP for TVs already configured for that connection. It does not install apps, change system apps, expose arbitrary shell commands, measure launch time, or promise a performance improvement.

## In plain terms

This service watches the apps you choose. When one updates, it can ask Android to compile it with the `speed` setting while the TV is idle and the maintenance window allows. That may not fix stuttering, and it is not a guarantee of faster apps.

1. Install Docker, and clone this public GitHub repository. Follow the [Quick start commands](#quick-start-docker-desktop-or-a-linux-docker-host) to start the service.
2. Open <http://127.0.0.1:8000> in your browser. The bundled Compose dashboard starts without login; anyone who can reach it can manage this service and its TVs.
3. Choose **Encrypted Wireless debugging** or **Unencrypted fixed TCP/IP** in the setup wizard. Wireless debugging uses TLS; fixed TCP/IP uses a configured TV companion or firmware and sends unencrypted ADB traffic. See [wireless debugging recovery](docs/wireless-debugging-recovery.md) for setup steps and tradeoffs.
4. Search the app list, select the apps to watch, and finish setup. When monitoring is paused, **Enable automatic monitoring after Finish** is checked by default; clear it to leave monitoring paused. If monitoring is already on, Finish keeps it on. **Run the first speed compile after setup** starts checked when apps are selected; clearing it leaves the compile unqueued. A compile starts after setup when the TV is confirmed idle and the maintenance window allows; it may wait while the TV is busy, its status is unknown, or the window is closed.

Keep your computer and Docker running for monitoring and scheduled work. If the TV's connection port changes, save its current endpoint; if pairing is revoked, pair it again. See [TV setup](#set-up-a-tv) and [operation and recovery](#operation-and-recovery).

## Quick start: Docker Desktop or a Linux Docker host

Requirements: Docker Engine/Desktop with Compose v2; an Android TV reachable over ADB; and a computer on the same private network. Wireless debugging needs its pairing screen; fixed TCP/IP needs compatible TV companion software or firmware configured separately. The image installs Debian's `adb` package, which includes TLS pairing and mDNS support for the image's native architecture.

```sh
git clone https://github.com/k45sle/android-tv-speed-compiler.git
cd android-tv-speed-compiler
docker compose up -d --build
```

Open <http://127.0.0.1:8000>. Login is off by default in the bundled Compose files, which publish the dashboard on host loopback. Anyone with access to the dashboard can control the service and its TVs. To require login, set `LOGIN_ENABLE=true` in a `.env` file beside the Compose file, then run `docker compose up -d` to recreate the service with that setting. On first setup, choose a unique password of at least 6 characters. Longer passwords are recommended. `TVCOMPILER_LOCAL_SETUP=1` allows password-only account setup for direct loopback requests while login is enabled.

`LOGIN_ENABLE` accepts only `true` or `false`; application default is `false`. With login disabled, the app does not create an auth database or bootstrap token. `TVCOMPILER_PUBLIC_ORIGIN` or `TVCOMPILER_COOKIE_SECURE=1` requires login and causes startup to fail when login is off. The app also refuses login-disabled dashboard/API requests with forwarded proxy headers or a non-loopback Host. These request checks cannot prove how the service is bound or whether a route is private. The bundled Compose files bind to host loopback; keep that boundary in place or enable login before changing network access. For custom installations with login enabled, the token is generated in the instance directory or supplied through `TVCOMPILER_BOOTSTRAP_TOKEN`; follow the private retrieval instructions shown by the setup page. A generated token is removed after successful setup. Keep the Docker named volume `tvcompiler-data`; it contains account/session data, SQLite state, and this instance's ADB keys. The service starts while every TV is offline. Monitoring starts paused.

The published UI port binds to loopback only. To use another local port, change the host side of the Compose mapping, for example `127.0.0.1:8088:8000`; keep the container port at 8000. Keep login disabled only with direct loopback access. Before exposing or proxying the service, set `LOGIN_ENABLE=true`; for remote setup also disable password-only local setup and use HTTPS with secure cookies and the exact public origin. Do not publish this service to the public internet.

## Set up a TV

1. Prepare the TV for the method you plan to use. For Wireless debugging, enable **Developer options → Wireless debugging** (Developer options usually appears after tapping **Build** seven times under **Settings → System → About**). For fixed TCP/IP, configure compatible TV companion software or firmware yourself; the wizard does not change TV settings.
2. Choose **Start setup** and select that method. Wireless debugging pairs a new TV with the pairing IP:port and short code from **Pair device with pairing code**; the code is cleared after submission. If discovery cannot find the endpoint, enter the current connection IP:port from the main **Wireless debugging** screen. A TV already paired with this service's saved ADB keys skips pairing. Fixed TCP/IP asks for the configured TV IP:port; approve this service's RSA authorization prompt. Its traffic is unencrypted, so use it only on a trusted private network. The selected method is saved as operator metadata; it does not switch TV settings or verify encryption. See [wireless debugging recovery](docs/wireless-debugging-recovery.md).
3. Select apps from the fresh inventory and choose **Save selected apps**. Saving records each selected app’s current version as its baseline and does not queue a compile. **Skip for now** leaves app selection for later under **Manage watched apps** on that TV.
4. On Review, **Enable automatic monitoring after Finish** starts checked and applies globally to all watched TVs. When monitoring is already enabled, Finish keeps it enabled; clearing the choice only prevents enabling paused monitoring. **Run the first speed compile after setup** also starts checked for selected watched apps. Clear it to leave the compile unqueued. A compile starts after setup when the TV is confirmed idle and the maintenance window allows; it may wait while the TV is busy, its status is unknown, or the window is closed. The **Run log** shows wait reasons and results. Pick the maintenance window's time zone from the available IANA zones; saved settings remain selected. Polling and retry settings are under **Advanced settings → Global scheduling**.

“Already paired” means paired with this service’s saved ADB keys. Pairing through another ADB installation does not satisfy that choice. Under **Advanced settings → Manual TV setup**, new pairing uses Wireless debugging; manual add lets you record either Wireless debugging or fixed TCP/IP for a TV that is already configured.

Choose **Appearance** in the header to follow the device theme or select Light or Night. The choice is saved in this browser.

Wireless debugging can use a different connection port after a TV reboot or network change. Paired keys and watch data persist across service restarts, but the service and its Docker host must stay running for scheduled work. Re-enable Wireless debugging, discover services, then save the current connection endpoint using **Manage TV details → Save name and reconnect**. Discovery can fail on some host networks; enter the current connection IP:port manually. Revoked keys or a changed serial/build identity require confirming the TV and pairing it again. To change an existing app allowlist, open **Manage watched apps** on that TV and load its fresh inventory; the setup wizard is for adding a TV. This service cannot guarantee fully unattended operation across TV power, network, authorization, and host failures.

## Where it works and network limits

The image targets Linux `amd64` and `arm64`; CI builds both architectures. The included Dockerfile uses Debian's distribution `adb` rather than Google's Linux x86-only download, so the ARM64 image has a native ADB client with `pair` and mDNS support. Debian's ADB package version follows the base distribution and can differ from Google's current platform-tools release.

For Linux hosts, `docker compose -f compose.linux-host.yaml up -d --build` uses host networking and binds the UI to `127.0.0.1:8000`. Host networking lets ADB mDNS discovery use the host network stack. Configure the host firewall to permit only trusted private-network access to the TVs and local UI.

Docker Desktop on macOS/Windows does not forward multicast mDNS discovery into containers reliably. The UI can still run and pair/connect by manually entering the TV's IP and the current pairing/connection ports. Port mapping exposes the dashboard only on the host loopback. The local ADB daemon listens on loopback only; no ADB server port is published to the LAN.

For a TV or network that requires fixed-port wireless debugging, see [wireless debugging recovery](docs/wireless-debugging-recovery.md) for the available connection paths and their tradeoffs.

## Operation and recovery

- **Pause monitoring** pauses automatic checks and automatic queued work globally. Explicit manual jobs can still run; the per-TV pause disables that device's work.
- **Disable a TV or stop watching an app** cancels its pending work. An already-running Android compile cannot be recalled; its result is rechecked before state is recorded.
- **Run log** shows wait reasons and attempts. Android must return an explicit success and the installation must still match before the result is recorded. If Android does not expose package compilation filter data, a successful command is reported as unverified. When the device exposes a mixed or non-speed filter, the job fails and follows bounded retry handling.
- **Diagnostics** exports an allowlist of app-level state. It excludes passwords, sessions, bootstrap tokens, pairing codes, ADB keys, raw command output, and dumpsys output. Review the JSON before sharing because it includes TV names, package IDs, and operation timestamps.
- **TV reboot, Wi-Fi change, or revoked authorization:** enable Wireless debugging, reconnect to the current TLS endpoint, and approve the TV's authorization prompt. If identity verification reports a mismatch, forget the saved TV and pair it again only after confirming it is the intended device.
- **ADB pairing keys:** stored under `/data/instance/home/.android` with private directory permissions. Do not copy them into Git, diagnostic exports, or shared backups.

The service has one Uvicorn worker and a SQLite-backed queue; do not mount one data volume into multiple replicas or run multiple workers. Docker shutdown allows 15 minutes for a bounded 600-second Android compile and follow-up checks. If the container is forcibly killed or the host fails, an in-flight job is recovered as pending on restart; Android may have continued compiling after the service lost contact, so the service does not claim that interrupted operation succeeded.

Both bundled Compose variants apply adjustable runtime defaults of 0.5 CPU, 512 MiB memory, a 128 MiB memory reservation, no extra swap (512 MiB total memory plus swap), and 128 process IDs. Override them with `TVCOMPILER_CPUS_LIMIT`, `TVCOMPILER_MEM_LIMIT`, `TVCOMPILER_MEM_RESERVATION`, `TVCOMPILER_MEMSWAP_LIMIT`, and `TVCOMPILER_PIDS_LIMIT` when the host or workload needs different limits. These apply to the running service container; they do not cap image builds or Docker Desktop's Linux VM overhead. The defaults are starting limits, not measured peak requirements or a guarantee that every workload fits. See [resource limits and measurements](docs/operations.md#runtime-resource-limits) for their meaning and the available sample evidence.

## Backup, restore, and upgrades

Stop the service before taking a consistent volume snapshot. Back up the entire `tvcompiler-data` volume, including `/data/instance` (both SQLite databases and ADB keys); protect it like a password and keep it offline or encrypted. To restore, stop the service, restore the snapshot to the same named volume, then start the service. Restoring only SQLite without the matching ADB keys may require pairing again.

Upgrade by fetching reviewed source changes and rebuilding with `docker compose up -d --build`. Keep a backup first. The application applies additive SQLite migrations at startup. If an upgrade cannot read or migrate state, it should fail visibly; restore the previous image and volume snapshot rather than deleting files to make startup succeed. Rebuilding the image preserves the named volume.

To intentionally reset a forgotten account password while preserving TV/app/job state, stop the service and back up the volume. Remove only `/data/instance/auth.sqlite3` and its `-wal`/`-shm` sidecars while stopped; leave `state.sqlite3`, `home/.android`, and keys in place. Start the service and complete first-run setup. Bundled local Compose uses password-only setup; custom/remote setup requires its newly generated or provisioned `bootstrap.token`. This revokes previous sessions. The bootstrap token is never returned to a browser or printed to logs.

## Security and remote access

ADB is a trusted shell-level channel to the TV even though this service exposes only fixed operations. With `LOGIN_ENABLE=false`, anyone who can reach the dashboard can control the service and TVs. Keep the bundled loopback bind in place; the app's Host and proxy-header checks cannot establish network privacy. For remote access, set `LOGIN_ENABLE=true`, use a trusted HTTPS reverse proxy and firewall, and configure `TVCOMPILER_COOKIE_SECURE=1` with the exact `TVCOMPILER_PUBLIC_ORIGIN`. The proxy must preserve the public Host/Origin and must not expose the app without authentication. Proxy headers are not trusted by Uvicorn. Prefer a private VPN over direct internet exposure. CSRF checks, same-origin validation, rate-limited password attempts, and the lack of a shell endpoint reduce browser and guessing risks, but do not make an untrusted public deployment safe.

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
