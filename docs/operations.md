# Operations reference

## Exposed network paths

- Dashboard: host loopback `127.0.0.1:8000` in the default Compose setup; TCP 8000 inside the container.
- ADB: outbound TLS wireless-debugging connections to the TV's current pairing/connection ports, plus mDNS discovery where the host network supports multicast. The local ADB daemon listens on loopback only; no ADB server port is published to the LAN. The app does not configure legacy port 5555.

On Docker Desktop, multicast discovery may not cross the VM bridge. Use the current IP and distinct pairing/connection ports shown in the TV settings. On Linux, use `compose.linux-host.yaml` when discovery through the host network is needed. Host networking removes the Compose port boundary, so use the host firewall and the configured loopback bind.

## Persistent data and permissions

The Compose named volume stores `/data/instance/state.sqlite3`, `/data/instance/auth.sqlite3`, scheduler lock and the private ADB home at `/data/instance/home/.android`. The runtime account uses stable UID/GID `10001:10001` so volume ownership persists across image upgrades. Do not share the volume among replicas; one process owns its scheduler lock.

The initial token file is mode 0600, contains a randomly generated one-time token, and is removed once setup succeeds. With a pre-provisioned `TVCOMPILER_BOOTSTRAP_TOKEN`, use 32–256 random characters and provide it through a secret mechanism, never a checked-in `.env` file. Do not paste tokens or pairing codes into support requests.

## Stop, backup, restore

Run `docker compose stop` before snapshotting. The Compose stop allowance is 15 minutes: Android compilation has a hard 600-second subprocess timeout, followed by package and filter verification. Scheduler shutdown waits for current work and starts no new compile once shutdown is requested. If the outer stop allowance is exceeded or the host loses power, job recovery is conservative: a running job becomes pending after restart. Since Android may have continued an already-sent request, retry can repeat the speed compilation; no interrupted request is recorded as successful without postchecks.

Back up the complete `tvcompiler-data` volume, not only SQLite, and encrypt or tightly restrict the backup because it contains ADB authorization keys. Restore while stopped. A database restored without its matching ADB home may require wireless re-pairing.

## Reset a forgotten password

Stop the service and take a backup. Remove `auth.sqlite3` and any SQLite `-wal`/`-shm` files in `/data/instance` while stopped. Preserve `state.sqlite3` and `/data/instance/home/.android`. Start the app, retrieve the fresh `bootstrap.token` locally with Compose exec, and set a new password. This removes old sessions and leaves the TV records, watch selections, and job history intact.

## Identity and reconnect

The service pins the TV serial and Android build fingerprint. Pairing keys do not prove that a saved IP still identifies the same TV; each reconnect validates the pinned identity. If a factory reset, OS update, or device replacement changes identity, the app refuses commands. Confirm the intended TV, forget the old record, and pair it again. An offline TV or revoked Wireless debugging authorization can be re-enabled and reconnected from the dashboard.

The TV's mDNS TLS connection endpoint can change independently of its pairing endpoint. Discovery gives both service kinds when supported; use the connection endpoint for later reconnects. Manual entry accepts the same validated host/port form.

## Security controls and limits

The dashboard starts on loopback. For intentional private-network access, put a maintained HTTPS reverse proxy in front, restrict its network ingress, configure `TVCOMPILER_COOKIE_SECURE=1` and the exact public origin in `TVCOMPILER_PUBLIC_ORIGIN`, and keep proxy header trust disabled unless code is deliberately reviewed. Authentication, CSRF, origin checks, and a bounded login/setup throttle reduce browser and guessing risks; ADB remains a highly privileged TV channel. Never make the dashboard publicly reachable.

Diagnostics are a limited operational export but can still identify device names and app usage. Inspect before sharing. Pairing secrets, ADB keys, authentication data, shell output, and Android dumpsys data are not included.

## Behavioral limits

- No test has modified or compiled a real TV during development. Fake ADB checks cannot establish compatibility with every TV firmware, authorization prompt, power/media dump format, or OEM package manager.
- Busy detection is conservative. Unknown means wait, but a device-specific format may still fail to reveal active playback.
- The service requests Android's `speed` compilation filter; it does not claim faster launch, smoother playback, a unique optimization, GPU/decoder fixes, or any measured performance change.
- If Android does not expose package compilation filter data, a successful command is reported as unverified. When the device exposes a mixed or non-speed filter, the job fails and follows bounded retry handling.
- One host and one Uvicorn worker are supported. The process lock guards a local state directory, not multiple hosts or network filesystems.
