# Operations reference

## Exposed network paths

- Dashboard: host loopback `127.0.0.1:8000` in the default Compose setup; TCP 8000 inside the container.
- ADB: outbound TLS wireless-debugging connections to the TV's current pairing/connection ports, plus mDNS discovery where the host network supports multicast. The local ADB daemon listens on loopback only; no ADB server port is published to the LAN. The app does not configure legacy port 5555.

On Docker Desktop, multicast discovery may not cross the VM bridge. Use the current IP and distinct pairing/connection ports shown in the TV settings. On Linux, use `compose.linux-host.yaml` when discovery through the host network is needed. Host networking removes the Compose port boundary, so use the host firewall and the configured loopback bind.

## Persistent data and permissions

The Compose named volume stores `/data/instance/state.sqlite3`, `/data/instance/auth.sqlite3`, scheduler lock and the private ADB home at `/data/instance/home/.android`. The runtime account uses stable UID/GID `10001:10001` so volume ownership persists across image upgrades. Do not share the volume among replicas; one process owns its scheduler lock.

The bundled loopback-only Compose files opt in to password-only local first setup. Choose a unique password of at least 6 characters and select **Create account**; longer passwords are recommended, especially for remote access. No token retrieval is required. Source and custom installations default to token-required setup. The generated token file is mode 0600 and removed once setup succeeds. With a pre-provisioned `TVCOMPILER_BOOTSTRAP_TOKEN`, use 32–256 random characters and provide it through a secret mechanism, never a checked-in `.env` file. Do not paste tokens or pairing codes into support requests.

## Runtime resource limits

Both bundled standalone Compose variants set adjustable container runtime defaults: `cpus: 0.5`, `mem_limit: 512m`, `mem_reservation: 128m`, `memswap_limit: 512m`, and `pids_limit: 128`. Override these with `TVCOMPILER_CPUS_LIMIT`, `TVCOMPILER_MEM_LIMIT`, `TVCOMPILER_MEM_RESERVATION`, `TVCOMPILER_MEMSWAP_LIMIT`, and `TVCOMPILER_PIDS_LIMIT`. CPU is a quota of half a logical CPU; memory is a hard limit; reservation is a soft target; setting memory-plus-swap equal to the memory limit disables extra container swap; and the PID limit bounds processes/threads counted by Docker. Docker Engine/host support affects enforcement details. These Compose settings constrain the service container at runtime only; they do not constrain image builds and exclude Docker Desktop's Linux VM overhead.

Five passive samples supplied from the then-current container reported 61.87–62.12 MiB memory, 0.38–0.59% CPU, and 10 PIDs. They are observations from a quiet interval, not controlled idle or active benchmarks, and do not establish a peak or a guaranteed safe budget.

An integrated-image disposable native ARM64 probe used `android-tv-speed-compiler:improvements-check` (image SHA `4e6e56c5fca58a1e254cf0579a4bd6c9690473b96086d50571faa59e6f6696f6`), the defaults above, Compose project `task3resourcefinal`, `compose.yaml` plus temporary overlay `../../work/tvcompiler_resource_probe.compose.yaml`, and isolated volume `task3resourcefinal_tvcompiler-data`. The service started offline and returned HTTP 200 health as UID/GID `10001:10001`, with `/data` writable. During a 45.4-second synthetic run, three concurrent clients completed 228 successful logins, 228 `/api/status` reads, 228 `/api/devices` reads, and 228 monitoring-setting updates. Twenty-four timestamped `docker stats` observations ranged from 0.39–53.49% CPU, 87.71–99.21 MiB memory, and 6–7 PIDs. Health returned HTTP 200 during and after activity; the container stayed healthy with no OOM kill or restart. A same-volume restart preserved the account: login returned HTTP 200 and the app remained configured and authenticated. This workload had no real TV, no ADB operations, no scheduler polling, and no compile requests; it is a synthetic API/auth baseline, not a representative peak benchmark. Repeat measurements under representative deployment workloads before treating these values as capacity guidance. Neither synthetic checks nor container health establish real-TV compile behavior or resource needs. Increase the defaults if representative operation shows sustained pressure or OOM/restarts, and monitor the host as well as the container.

## Stop, backup, restore

Run `docker compose stop` before snapshotting. The Compose stop allowance is 15 minutes: Android compilation has a hard 600-second subprocess timeout, followed by package and filter verification. Scheduler shutdown waits for current work and starts no new compile once shutdown is requested. If the outer stop allowance is exceeded or the host loses power, job recovery is conservative: a running job becomes pending after restart. Since Android may have continued an already-sent request, retry can repeat the speed compilation; no interrupted request is recorded as successful without postchecks.

Back up the complete `tvcompiler-data` volume, not only SQLite, and encrypt or tightly restrict the backup because it contains ADB authorization keys. Restore while stopped. A database restored without its matching ADB home may require wireless re-pairing.

## Reset a forgotten password

Stop the service and take a backup. Remove `auth.sqlite3` and any SQLite `-wal`/`-shm` files in `/data/instance` while stopped. Preserve `state.sqlite3` and `/data/instance/home/.android`. Start the app, retrieve the fresh `bootstrap.token` locally with Compose exec, and set a new password. This removes old sessions and leaves the TV records, watch selections, and job history intact.

## Identity and reconnect

The setup wizard pairs a new TV before asking for its connection endpoint. Paired keys and watch data persist across a TV or service reboot when the complete data volume is retained. Keep the Docker host and service running for scheduled work. If Wireless debugging is off, the endpoint changed, or mDNS is blocked, enable Wireless debugging and save the current connection IP:port under **Manage TV details**. If keys were revoked or the verified serial/build identity changed, confirm the TV and pair it again. The service retries the saved connection, but it cannot guarantee fully unattended operation across TV power, network, authorization, and host failures.

On the wizard's Review step, enabling automatic monitoring is an explicit global action for all watched TVs; when paused, the option starts checked, and when already enabled Finish preserves that state. Initial compiles are separately opt-in and default off. They use each saved current baseline, queue only once for a fingerprint, and remain idle and maintenance-window gated even while global monitoring is paused. Awake or playing TVs, unknown busy status, and closed windows defer jobs. A queued job may wait indefinitely; check Recent work for its current reason. Finish retries do not force a prior failed or completed fingerprint to run again; use the deliberate manual compile action when a retry is needed.

The TV's mDNS TLS connection endpoint can change independently of its pairing endpoint. Discovery gives both service kinds when supported; use the connection endpoint for later reconnects. Manual entry accepts the same validated host/port form.

## Security controls and limits

The bundled local setup option (`TVCOMPILER_LOCAL_SETUP=1`) is an operator declaration used with the bundled loopback-only bind; a Host header cannot prove the full route is private. Before exposing, proxying, or changing the bind, set `TVCOMPILER_LOCAL_SETUP=0`. Custom/source app configuration defaults to token-required setup. The server also requires token setup when `TVCOMPILER_PUBLIC_ORIGIN` or `TVCOMPILER_COOKIE_SECURE=1` is configured, a bootstrap token is supplied, proxy-forwarding headers are present, or the request Host is non-loopback. For intentional private-network access, put a maintained HTTPS reverse proxy in front, restrict its network ingress, configure `TVCOMPILER_COOKIE_SECURE=1` and the exact public origin in `TVCOMPILER_PUBLIC_ORIGIN`, and keep proxy header trust disabled unless code is deliberately reviewed. Authentication, CSRF, origin checks, and a bounded login/setup throttle reduce browser and guessing risks; ADB remains a highly privileged TV channel. Never make the dashboard publicly reachable.

Diagnostics are a limited operational export but can still identify device names and app usage. Inspect before sharing. Pairing secrets, ADB keys, authentication data, shell output, and Android dumpsys data are not included.

## Behavioral limits

- No test has modified or compiled a real TV during development. Fake ADB checks cannot establish compatibility with every TV firmware, authorization prompt, power/media dump format, or OEM package manager.
- Busy detection is conservative. Unknown means wait, but a device-specific format may still fail to reveal active playback.
- The service requests Android's `speed` compilation filter; it does not claim faster launch, smoother playback, a unique optimization, GPU/decoder fixes, or any measured performance change.
- If Android does not expose package compilation filter data, a successful command is reported as unverified. When the device exposes a mixed or non-speed filter, the job fails and follows bounded retry handling.
- One host and one Uvicorn worker are supported. The process lock guards a local state directory, not multiple hosts or network filesystems.
