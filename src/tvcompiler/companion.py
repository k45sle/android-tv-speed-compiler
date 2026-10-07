"""Pinned companion APK retrieval and narrow localhost HTTP API."""

from __future__ import annotations

import hashlib
import http.client
import json
import os
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

COMPANION_PACKAGE = "com.tpn.adbautoenable"
COMPANION_VERSION = "0.3.5"
COMPANION_URL = (
    "https://github.com/mouldybread/adb-auto-enable/releases/download/v0.3.5/"
    "ADB-auto-enable-0.3.5.apk"
)
COMPANION_SIZE = 13_501_636
COMPANION_SHA256 = "454008f3dd2a90ffcd44fc14ac3baf183c6f29ec9b194c35bc21308485c8b13b"
COMPANION_FILENAME = "ADB-auto-enable-0.3.5.apk"
_ALLOWED_ASSET_HOSTS = {
    "github.com",
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
}
_MAX_HTTP_RESPONSE = 64 * 1024


@dataclass(frozen=True, slots=True)
class VerifiedCompanionArtifact:
    """Handle for the one pinned companion artifact in this application's private cache."""

    path: Path
    sha256: str
    size: int


class CompanionSetupError(ValueError):
    """A controller failure with a fixed, secret-free message suitable for HTTP output."""


class _RestrictedRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if parsed.scheme != "https" or parsed.hostname not in _ALLOWED_ASSET_HOSTS:
            raise urllib.error.HTTPError(req.full_url, code, "untrusted release redirect", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class CompanionArtifactManager:
    """Download the pinned release only on explicit invocation, with bounded streaming."""

    def __init__(
        self,
        instance_dir: str | Path,
        *,
        opener: Callable[[str, float], BinaryIO] | None = None,
        timeout: float = 20.0,
        clock: Callable[[], float] = time.monotonic,
    ):
        if not 0 < timeout <= 120:
            raise ValueError("download timeout must be between 0 and 120 seconds")
        self.cache_dir = Path(instance_dir) / "companion"
        self.cache_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            os.chmod(self.cache_dir, 0o700)
        except OSError:
            pass
        self.timeout = timeout
        self._clock = clock
        self._opener = opener or self._open_release

    @staticmethod
    def _open_release(url: str, timeout: float):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != "https" or parsed.hostname not in _ALLOWED_ASSET_HOSTS:
            raise ValueError("untrusted companion release URL")
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            _RestrictedRedirectHandler(),
        )
        return opener.open(
            urllib.request.Request(url, headers={"User-Agent": "android-tv-speed-compiler"}),
            timeout=timeout,
        )

    def download(self) -> VerifiedCompanionArtifact:
        """Fetch the fixed official URL and atomically replace the cache after verification."""
        target = self.cache_dir / COMPANION_FILENAME
        try:
            cached = self._verify_path(target, missing_ok=True)
        except RuntimeError:
            cached = None
        if cached:
            return cached
        temporary: str | None = None
        response = None
        deadline = self._clock() + self.timeout
        try:
            response = self._opener(COMPANION_URL, self._remaining(deadline))
            self._remaining(deadline)
            final_url = response.geturl()
            parsed = urllib.parse.urlsplit(final_url)
            if parsed.scheme != "https" or parsed.hostname not in _ALLOWED_ASSET_HOSTS:
                raise ValueError("release redirected to an untrusted host")
            status = getattr(response, "status", 200)
            if status != 200:
                raise ValueError("release server returned an unsuccessful response")
            content_length = response.headers.get("Content-Length")
            if content_length and (not content_length.isdigit() or int(content_length) != COMPANION_SIZE):
                raise ValueError("release size did not match the pinned artifact")
            fd, temporary = tempfile.mkstemp(prefix=".companion-", suffix=".tmp", dir=self.cache_dir)
            os.fchmod(fd, 0o600)
            digest = hashlib.sha256()
            size = 0
            with os.fdopen(fd, "wb") as output:
                while True:
                    remaining = self._remaining(deadline)
                    self._set_response_timeout(response, remaining)
                    read_chunk = getattr(response, "read1", response.read)
                    chunk = read_chunk(min(64 * 1024, COMPANION_SIZE + 1 - size))
                    self._remaining(deadline)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > COMPANION_SIZE:
                        raise ValueError("release exceeded the pinned artifact size")
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if size != COMPANION_SIZE or digest.hexdigest() != COMPANION_SHA256:
                raise ValueError("release checksum did not match the pinned artifact")
            os.replace(temporary, target)
            temporary = None
            return VerifiedCompanionArtifact(target.resolve(), COMPANION_SHA256, COMPANION_SIZE)
        except Exception:
            raise RuntimeError("could not retrieve the pinned companion artifact") from None
        finally:
            if response is not None:
                try:
                    response.close()
                except Exception:
                    pass
            if temporary:
                try:
                    os.unlink(temporary)
                except OSError:
                    pass

    def _remaining(self, deadline: float) -> float:
        remaining = deadline - self._clock()
        if remaining <= 0:
            raise TimeoutError("companion download exceeded its time budget")
        return remaining

    @staticmethod
    def _set_response_timeout(response, timeout: float) -> None:
        """Tighten urllib's socket timeout to the remaining whole-download budget."""
        response_socket = getattr(response, "_sock", None)
        if response_socket is None:
            file_pointer = getattr(response, "fp", None)
            raw_stream = getattr(file_pointer, "raw", None)
            response_socket = getattr(raw_stream, "_sock", None)
        if response_socket is not None:
            response_socket.settimeout(timeout)

    def cached(self) -> VerifiedCompanionArtifact | None:
        """Return a verified cached artifact, or None when the pinned APK is absent."""
        return self._verify_path(self.cache_dir / COMPANION_FILENAME, missing_ok=True)

    def _verify_path(self, path: Path, *, missing_ok: bool) -> VerifiedCompanionArtifact | None:
        try:
            resolved = path.resolve(strict=True)
        except FileNotFoundError:
            if missing_ok:
                return None
            raise RuntimeError("pinned companion artifact is missing") from None
        if resolved.parent != self.cache_dir.resolve() or resolved.name != COMPANION_FILENAME:
            raise RuntimeError("companion artifact is outside the private cache")
        try:
            stat = resolved.stat()
            if not resolved.is_file() or stat.st_size != COMPANION_SIZE:
                raise RuntimeError("cached companion artifact failed size verification")
            digest = hashlib.sha256()
            with resolved.open("rb") as source:
                while chunk := source.read(64 * 1024):
                    digest.update(chunk)
            if digest.hexdigest() != COMPANION_SHA256:
                raise RuntimeError("cached companion artifact failed checksum verification")
        except OSError as exc:
            raise RuntimeError("could not verify cached companion artifact") from exc
        return VerifiedCompanionArtifact(resolved, COMPANION_SHA256, COMPANION_SIZE)


def verify_companion_artifact(artifact: object, instance_dir: str | Path) -> Path:
    """Revalidate cache location, size, and hash immediately before an ADB install."""
    cache_dir = (Path(instance_dir) / "companion").resolve()
    if not isinstance(artifact, VerifiedCompanionArtifact):
        raise ValueError("a verified pinned companion artifact is required")
    path = artifact.path.resolve(strict=True)
    if path.parent != cache_dir or path.name != COMPANION_FILENAME:
        raise ValueError("companion artifact is outside the private cache")
    stat = path.stat()
    if stat.st_size != COMPANION_SIZE or artifact.size != COMPANION_SIZE or artifact.sha256 != COMPANION_SHA256:
        raise ValueError("companion artifact metadata failed verification")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(64 * 1024):
            digest.update(chunk)
    if digest.hexdigest() != COMPANION_SHA256:
        raise ValueError("companion artifact checksum failed verification")
    return path


class CompanionSetupController:
    """Explicit, resumable fixed-port setup for the pinned companion on one saved TV."""

    def __init__(self, store, adb, scheduler, instance_dir: str | Path, *, artifact_manager=None, bridge_factory=None):
        self.store, self.adb, self.scheduler = store, adb, scheduler
        self.instance_dir = Path(instance_dir)
        self.artifacts = artifact_manager or CompanionArtifactManager(instance_dir)
        self.bridge_factory = bridge_factory or (
            lambda serial, timeout=5.0: CompanionBridge(adb, serial, timeout=timeout)
        )

    def _bridge(self, serial: str, *, timeout: float = 5.0):
        return self.bridge_factory(serial, timeout=timeout)

    @staticmethod
    def _reply(record: dict) -> dict:
        return {key: record.get(key) for key in ("phase", "paired", "web_disabled", "target_port", "reason")} | {
            "ready": record.get("phase") == "ready"
        }

    def _matches_record(self, device, record: dict) -> bool:
        if (not device.enabled or device.serial != record.get("expected_serial")
                or device.fingerprint != record.get("expected_fingerprint")):
            return False
        expected_endpoint = (
            self._endpoint_for_port(record["bootstrap_endpoint"], record["target_port"])
            if record["phase"] == "ready" else record["bootstrap_endpoint"]
        )
        expected_mode = "tcpip" if record["phase"] == "ready" else device.connection_mode
        return device.endpoint == expected_endpoint and device.connection_mode == expected_mode

    @staticmethod
    def _endpoint_for_port(endpoint: str, port: int) -> str:
        host = endpoint.rsplit(":", 1)[0]
        return f"{host}:{port}"

    @staticmethod
    def _safe_reason(exc: Exception) -> str:
        # Never relay subprocess, HTTP payload, pairing input, or arbitrary exception text.
        if isinstance(exc, TimeoutError):
            return "The companion did not respond in time. Retry setup."
        if isinstance(exc, ValueError):
            allowed = {
                "The companion setup requires Android 11 (SDK 30) or newer",
                "A different target port is already in progress; finish or restart setup first",
                "TV has no saved bootstrap endpoint",
                "TV identity or endpoint changed; reconnect and retry setup",
                "TV was forgotten during companion setup",
                "TV was forgotten during companion pairing",
                "TV was removed during companion setup",
                "TV was removed or changed during companion setup",
                "TV was removed or changed before the endpoint could be saved",
                "Requested endpoint did not match the saved TV",
                "The fixed TCP/IP endpoint did not reconnect as the saved TV. Retry setup.",
                "The companion setup server is unavailable. On the TV, enable Enable Web Interface on Boot, "
                "open the companion, and retry.",
                "The saved companion endpoint no longer matches this TV.",
            }
            if str(exc) in allowed:
                return str(exc)
        if isinstance(exc, RuntimeError) and str(exc) in {
            "invalid companion status", "invalid companion target port",
            "invalid companion web server state", "companion readiness check failed",
        }:
            return "The companion status response was invalid. Open the companion on the TV and retry."
        if isinstance(exc, RuntimeError) and "companion API request failed" in str(exc):
            return (
                "The companion setup server is unavailable. On the TV, enable Enable Web Interface on Boot, "
                "open the companion, and retry."
            )
        return "Setup could not continue. Check the TV connection and retry."

    def _unchanged(self, original) -> bool:
        current = self.store.get_device(original.id)
        return bool(
            current and current.enabled and current.serial == original.serial
            and current.fingerprint == original.fingerprint
            and current.endpoint == original.endpoint
        )

    def _guard(self, original, message: str = "TV identity or endpoint changed; reconnect and retry setup") -> None:
        if not self._unchanged(original):
            raise ValueError(message)

    def _connect(self, device, endpoint: str):
        reconnect = getattr(self.adb, "reconnect_with_selector", None) or self.adb.reconnect
        result = reconnect(
            expected_serial=device.serial, expected_fingerprint=device.fingerprint, endpoint=endpoint
        )
        if hasattr(result, "selector"):
            return result.selector, result.identity
        return result

    def _status(self, bridge, target_port: int | None, *, require_paired: bool = False) -> dict:
        deadline = time.monotonic() + 8.0
        transport_failed = False
        while True:
            try:
                data = bridge.status()
            except RuntimeError:
                transport_failed = True
                if time.monotonic() >= deadline:
                    if transport_failed:
                        raise RuntimeError("companion API request failed") from None
                    raise RuntimeError("companion readiness check failed") from None
                time.sleep(0.2)
                continue
            if not isinstance(data, dict) or not isinstance(data.get("isPaired"), bool):
                raise RuntimeError("invalid companion status")
            if not isinstance(data.get("targetPort"), int) or isinstance(data.get("targetPort"), bool):
                raise RuntimeError("invalid companion target port")
            if not isinstance(data.get("webServerEnabled"), bool):
                raise RuntimeError("invalid companion web server state")
            if target_port is None or (data["targetPort"] == target_port and (not require_paired or data["isPaired"])):
                return data
            if time.monotonic() >= deadline:
                raise RuntimeError("companion readiness check failed")
            time.sleep(0.2)

    def status(self, device_id: str) -> dict:
        record = self.store.get_companion_setup(device_id)
        if record is None:
            return {"phase": "not_started", "paired": False, "web_disabled": False,
                    "target_port": 5555, "reason": None, "ready": False}
        reply = self._reply(record)
        if record["phase"] == "ready" and not self.is_current_ready(device_id):
            reply.update(phase="failed", ready=False, reason="The saved companion endpoint no longer matches this TV.")
        return reply

    def is_current_ready(self, device_id: str) -> bool:
        device = self.store.get_device(device_id)
        record = self.store.get_companion_setup(device_id)
        return bool(device and record and record["phase"] == "ready" and self._matches_record(device, record))

    def prepare(self, device_id: str, *, consent: bool, target_port: int = 5555) -> dict:
        if consent is not True:
            raise ValueError("Explicit consent is required to install and configure the companion")
        if (
            isinstance(target_port, bool) or not isinstance(target_port, int)
            or not 1024 <= target_port <= 65535 or target_port == 9093
        ):
            raise ValueError("Target port must be 1024-65535 and cannot be 9093")
        with self.scheduler.maintenance_operation():
            device = self.store.get_device(device_id)
            if not device or not device.enabled:
                raise ValueError("TV not found or paused")
            record = self.store.get_companion_setup(device_id)
            if record and record["target_port"] != target_port:
                raise ValueError("A different target port is already in progress; finish or restart setup first")
            if record and record["phase"] == "ready":
                if not self._matches_record(device, record):
                    raise CompanionSetupError("TV identity or endpoint changed; reconnect and retry setup")
                endpoint = self._endpoint_for_port(record["bootstrap_endpoint"], target_port)
                self._verify_with_retries(device, record, endpoint)
                if not self._unchanged(device):
                    raise ValueError("TV identity or endpoint changed; reconnect and retry setup")
                return self._reply(record)
            if record and not self._matches_record(device, record):
                raise CompanionSetupError("TV identity or endpoint changed; reconnect and retry setup")
            bootstrap = record["bootstrap_endpoint"] if record else device.endpoint
            if not bootstrap:
                raise ValueError("TV has no saved bootstrap endpoint")
            if record and record["web_disabled"]:
                return self._resume_disabled(device, record, bootstrap)
            if not self._unchanged(device):
                raise ValueError("TV identity or endpoint changed; reconnect and retry setup")
            if not record and not self.store.save_companion_setup(
                device_id, phase="failed", reason="Setup has not completed yet.",
                target_port=target_port, bootstrap_endpoint=bootstrap,
                expected_serial=device.serial, expected_fingerprint=device.fingerprint,
            ):
                raise ValueError("TV was forgotten before companion setup")
            try:
                serial, _identity = self._connect(device, bootstrap)
                # Android 11 is SDK 30. Check compatibility before any download or install.
                self._guard(device)
                if self.adb.companion_sdk_level(serial) < 30:
                    raise ValueError("The companion setup requires Android 11 (SDK 30) or newer")
                artifact = self.artifacts.cached() or self.artifacts.download()
                self._guard(device)
                self.adb.install_companion(serial, artifact)
                self._guard(device)
                self.adb.enable_companion(serial)
                self._guard(device)
                self.adb.grant_companion_permission(serial)
                self._guard(device)
                self.adb.launch_companion(serial)
                bridge = self._bridge(serial)
                self._guard(device)
                self._status(bridge, None)
                self._guard(device)
                bridge.set_target_port(target_port)
                status = self._status(bridge, target_port, require_paired=False)
                paired = status["isPaired"]
                phase = "paired" if paired else "needs_pairing"
                if not self.store.save_companion_setup(
                    device_id, phase=phase, target_port=target_port, bootstrap_endpoint=bootstrap,
                    paired=paired, web_disabled=False,
                    expected_serial=device.serial, expected_fingerprint=device.fingerprint,
                ):
                    raise ValueError("TV was forgotten during companion setup")
                if paired:
                    return self._finalize(device, serial, bridge, status)
                return self._reply(self.store.get_companion_setup(device_id))
            except Exception as exc:
                if self.store.get_device(device_id) and self.store.get_companion_setup(device_id):
                    reason = self._safe_reason(exc)
                    self.store.update_companion_setup(device_id, phase="failed", reason=reason)
                else:
                    reason = "TV was removed or changed during companion setup"
                raise CompanionSetupError(reason) from None

    def pair(self, device_id: str, *, pairing_code: str, pairing_port: int) -> dict:
        if not isinstance(pairing_code, str) or not re.fullmatch(r"\d{4,12}", pairing_code):
            raise ValueError("Pairing code must contain 4-12 digits")
        if isinstance(pairing_port, bool) or not isinstance(pairing_port, int) or not 1 <= pairing_port <= 65535:
            raise ValueError("Pairing port must be 1-65535")
        with self.scheduler.maintenance_operation():
            device = self.store.get_device(device_id)
            record = self.store.get_companion_setup(device_id)
            if not device or not device.enabled:
                raise ValueError("TV not found or paused")
            if not record or record["web_disabled"]:
                raise ValueError("Prepare the companion before pairing")
            if record["phase"] == "ready":
                if not self._matches_record(device, record):
                    raise CompanionSetupError("TV identity or endpoint changed; reconnect and retry setup")
                self._verify_with_retries(
                    device, record, self._endpoint_for_port(record["bootstrap_endpoint"], record["target_port"])
                )
                return self._reply(record)
            if not self._matches_record(device, record) or not self._unchanged(device):
                raise CompanionSetupError("TV identity or endpoint changed; reconnect and retry setup")
            try:
                serial, _identity = self._connect(device, record["bootstrap_endpoint"])
                bridge = self._bridge(serial, timeout=30.0)
                self._guard(device)
                bridge.pair(pairing_code, pairing_port)
                status = self._status(bridge, record["target_port"], require_paired=True)
                if not self.store.update_companion_setup(
                    device_id, phase="paired", reason=None, paired=True
                ):
                    raise ValueError("TV was forgotten during companion pairing")
                return self._finalize(device, serial, bridge, status)
            except Exception as exc:
                if self.store.get_device(device_id) and self.store.get_companion_setup(device_id):
                    reason = self._safe_reason(exc)
                    self.store.update_companion_setup(device_id, phase="failed", reason=reason)
                else:
                    reason = "TV was removed or changed during companion pairing"
                raise CompanionSetupError(reason) from None
            finally:
                pairing_code = ""

    def _finalize(self, device, serial, bridge, _status):
        record = self.store.get_companion_setup(device.id)
        if not record or not self._unchanged(device):
            raise ValueError("TV was removed or changed during companion setup")
        if not record["web_disabled"]:
            self._guard(device, "TV was removed or changed during companion setup")
            # The companion persists this preference before acknowledging, then shuts down
            # its HTTP server; a failed follow-up probe would misreport a successful disable.
            bridge.disable_webserver()
            if not self.store.update_companion_setup(device.id, phase="paired", web_disabled=True, reason=None):
                raise ValueError("TV was removed during companion setup")
        return self._switch_and_verify(device, record, serial)

    def _resume_disabled(self, device, record, bootstrap):
        if not self._matches_record(device, record):
            raise CompanionSetupError("TV identity or endpoint changed; reconnect and retry setup")
        endpoint = self._endpoint_for_port(bootstrap, record["target_port"])
        try:
            self.adb.verify_companion_endpoint(
                endpoint, expected_serial=device.serial, expected_fingerprint=device.fingerprint
            )
        except Exception:
            if not self._unchanged(device):
                raise ValueError("TV identity or endpoint changed; reconnect and retry setup") from None
            serial, _identity = self._connect(device, bootstrap)
            try:
                self._guard(device)
                self.adb.set_companion_tcpip(serial, record["target_port"])
            except Exception:
                # ADB commonly drops the bootstrap transport while changing ports.
                pass
        return self._verify_and_save(device, record, endpoint)

    def _switch_and_verify(self, device, record, serial):
        if not self._unchanged(device):
            raise ValueError("TV identity or endpoint changed; reconnect and retry setup")
        endpoint = self._endpoint_for_port(record["bootstrap_endpoint"], record["target_port"])
        try:
            self._guard(device)
            self.adb.set_companion_tcpip(serial, record["target_port"])
        except Exception:
            # The transport may close as Android switches ports; exact verification decides.
            pass
        return self._verify_and_save(device, record, endpoint)

    def _verify_and_save(self, device, record, endpoint):
        verified = self._verify_with_retries(device, record, endpoint)
        identity = getattr(verified, "identity", verified)
        if getattr(identity, "serial", device.serial) != device.serial or getattr(
            identity, "build_fingerprint", device.fingerprint
        ) != device.fingerprint:
            raise ValueError("Requested endpoint did not match the saved TV")
        if not self.store.complete_companion_setup(
            device.id, expected_endpoint=device.endpoint, expected_serial=device.serial,
            expected_fingerprint=device.fingerprint, endpoint=endpoint,
        ):
            raise ValueError("TV was removed or changed before the endpoint could be saved")
        return self._reply(self.store.get_companion_setup(device.id))

    def _verify_with_retries(self, device, record, endpoint):
        for attempt in range(3):
            current = self.store.get_device(device.id)
            if (not current or not current.enabled or current.endpoint != device.endpoint
                    or current.connection_mode != device.connection_mode
                    or current.serial != record.get("expected_serial")
                    or current.fingerprint != record.get("expected_fingerprint")):
                raise CompanionSetupError("TV was removed or changed before the endpoint could be saved")
            try:
                return self.adb.verify_companion_endpoint(
                    endpoint, expected_serial=record.get("expected_serial"),
                    expected_fingerprint=record.get("expected_fingerprint"),
                )
            except Exception:
                if attempt < 2:
                    time.sleep(0.2)
        raise CompanionSetupError("The fixed TCP/IP endpoint did not reconnect as the saved TV. Retry setup.") from None

    def verify_ready(self, device_id: str) -> dict:
        with self.scheduler.maintenance_operation():
            device = self.store.get_device(device_id)
            record = self.store.get_companion_setup(device_id)
            if not device or not device.enabled or not record or record["phase"] != "ready":
                raise CompanionSetupError("Requested companion setup is not ready.")
            if not self._matches_record(device, record):
                raise CompanionSetupError("TV identity or endpoint changed; reconnect and retry setup")
            endpoint = self._endpoint_for_port(record["bootstrap_endpoint"], record["target_port"])
            self._verify_with_retries(device, record, endpoint)
            return self._reply(record)


class CompanionBridge:
    """Localhost client for the LAN-exposed API through temporary ADB forwarding."""

    def __init__(self, adb, serial: str, *, timeout: float = 5.0, connection_factory=None):
        if not 0 < timeout <= 30:
            raise ValueError("HTTP timeout must be between 0 and 30 seconds")
        self.adb = adb
        self.serial = serial
        self.timeout = timeout
        self._connection_factory = connection_factory or http.client.HTTPConnection

    def _request(self, local_port: int, method: str, path: str, body: str = "") -> dict:
        if isinstance(local_port, bool) or not isinstance(local_port, int) or not 1 <= local_port <= 65535:
            raise ValueError("invalid forwarded localhost port")
        if (method, path) not in {
            ("GET", "/api/status"),
            ("POST", "/api/port"),
            ("POST", "/api/pair"),
            ("POST", "/api/webserver"),
        }:
            raise ValueError("unsupported companion API operation")
        connection = None
        try:
            connection = self._connection_factory("127.0.0.1", local_port, timeout=self.timeout)
            headers = {"Accept": "application/json"}
            if method == "POST":
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            connection.request(method, path, body=body or None, headers=headers)
            response = connection.getresponse()
            if 300 <= response.status < 400:
                raise RuntimeError("companion API redirects are not allowed")
            if not 200 <= response.status < 300:
                raise RuntimeError("companion API returned an unsuccessful response")
            payload = response.read(_MAX_HTTP_RESPONSE + 1)
            if len(payload) > _MAX_HTTP_RESPONSE:
                raise RuntimeError("companion API response exceeded the size limit")
            data = json.loads(payload)
            if not isinstance(data, dict):
                raise RuntimeError("companion API returned an invalid response")
            return data
        except Exception as exc:
            # Do not propagate transport/server text: the pairing endpoint and code are sensitive.
            if isinstance(exc, (ValueError, RuntimeError)) and str(exc) in {
                "invalid forwarded localhost port",
                "unsupported companion API operation",
            }:
                raise
            raise RuntimeError("companion API request failed") from None
        finally:
            if connection is not None:
                try:
                    connection.close()
                except Exception:
                    pass

    def status(self) -> dict[str, bool | int]:
        with self.adb.companion_forward(self.serial) as port:
            data = self._request(port, "GET", "/api/status")
        result: dict[str, bool | int] = {}
        for key in ("isPaired", "webServerEnabled"):
            if key in data and isinstance(data[key], bool):
                result[key] = data[key]
        for key in ("currentPort", "targetPort"):
            if (
                key in data
                and isinstance(data[key], int)
                and not isinstance(data[key], bool)
                and 0 <= data[key] <= 65535
            ):
                result[key] = data[key]
        return result

    def set_target_port(self, target_port: int) -> None:
        if (
            isinstance(target_port, bool)
            or not isinstance(target_port, int)
            or not 1024 <= target_port <= 65535
            or target_port == 9093
        ):
            raise ValueError("target port must be 1024-65535 and cannot be 9093")
        with self.adb.companion_forward(self.serial) as port:
            data = self._request(port, "POST", "/api/port", urllib.parse.urlencode({"port": target_port}))
        if data.get("success") is not True:
            raise RuntimeError("companion did not save the target port")

    def pair(self, pairing_code: str, pairing_port: int) -> None:
        if not isinstance(pairing_code, str) or not re.fullmatch(r"\d{4,12}", pairing_code):
            raise ValueError("pairing code must be a 4-12 digit string")
        if isinstance(pairing_port, bool) or not isinstance(pairing_port, int) or not 1 <= pairing_port <= 65535:
            raise ValueError("invalid pairing port")
        with self.adb.companion_forward(self.serial) as port:
            data = self._request(
                port,
                "POST",
                "/api/pair",
                urllib.parse.urlencode({"code": pairing_code, "port": pairing_port}),
            )
        if data.get("success") is not True:
            raise RuntimeError("companion pairing failed; the code was not retained")

    def disable_webserver(self) -> None:
        with self.adb.companion_forward(self.serial) as port:
            data = self._request(port, "POST", "/api/webserver", urllib.parse.urlencode({"enabled": "false"}))
        if data.get("success") is not True:
            raise RuntimeError("companion did not disable its web server")
