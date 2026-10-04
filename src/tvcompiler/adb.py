"""Constrained ADB adapter: explicit operations only, no shell or arbitrary commands."""

from __future__ import annotations

import ipaddress
import os
import re
import subprocess
import threading
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .validation import validate_package_id

_DNS_LABEL_RE = re.compile(
    r"^(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(?:\.(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*\.?$"
)


@dataclass(frozen=True, slots=True)
class AdbResult:
    argv: tuple[str, ...]
    returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True, slots=True)
class DeviceIdentity:
    serial: str
    build_fingerprint: str | None


@dataclass(frozen=True, slots=True)
class PackageFingerprint:
    package_id: str
    version_name: str | None
    version_code: int | None
    last_update_time: str | None
    apk_path: str | None

    @property
    def value(self) -> str:
        """Stable serialized installation identity, including same-version reinstalls."""
        fields = (
            self.package_id,
            str(self.version_code) if self.version_code is not None else "",
            self.last_update_time or "",
            self.apk_path or "",
        )
        return "|".join(fields)


@dataclass(frozen=True, slots=True)
class DiscoveredService:
    kind: str
    service: str
    endpoint: str


@dataclass(frozen=True, slots=True)
class PackageInfo:
    package_id: str
    label: str | None
    version_name: str | None
    version_code: int | None
    last_update_time: str | None
    apk_path: str | None

    @property
    def fingerprint(self) -> PackageFingerprint:
        return PackageFingerprint(
            self.package_id,
            self.version_name,
            self.version_code,
            self.last_update_time,
            self.apk_path,
        )


@dataclass(frozen=True, slots=True)
class CompilationInspection:
    supported: bool
    compiler_filter: str | None
    detail: str


@dataclass(frozen=True, slots=True)
class BusyStatus:
    screen_active: bool | None
    playback_active: bool | None
    reasons: tuple[str, ...]

    @property
    def idle_confirmed(self) -> bool:
        return self.screen_active is False and self.playback_active is False


def parse_mdns_services(text: str) -> list[DiscoveredService]:
    """Parse `adb mdns services`, retaining only ADB TLS pairing/connect services."""
    results: list[DiscoveredService] = []
    for line in text.splitlines():
        # Platform-tools versions emit either NAME TYPE ENDPOINT (current) or
        # NAME_WITH_TYPE ENDPOINT (older releases / wrappers).
        columns = line.split()
        parsed = None
        service_type = columns[1].rstrip(".") if len(columns) >= 3 else ""
        if len(columns) >= 3 and service_type in {"_adb-tls-connect._tcp", "_adb-tls-pairing._tcp"}:
            kind = "connect" if "connect" in service_type else "pairing"
            parsed = (kind, columns[2], " ".join(columns[:2]))
        else:
            legacy = re.search(r"(?P<name>\S+_adb-tls-(?P<kind>connect|pairing)\._tcp\.?)\s+(?P<endpoint>\S+)", line)
            if legacy:
                parsed = (legacy.group("kind"), legacy.group("endpoint"), legacy.group("name"))
        if parsed:
            try:
                endpoint = validate_endpoint(parsed[1])
            except ValueError:
                continue
            results.append(DiscoveredService(parsed[0], parsed[2], endpoint))
    return results


def parse_connected_devices(text: str) -> list[tuple[str, str]]:
    devices = []
    for line in text.splitlines():
        match = re.match(r"^(\S+)\s+(device|offline|unauthorized|no permissions)(?:\s|$)", line)
        if match:
            devices.append((match.group(1), match.group(2)))
    return devices


def parse_package_ids(text: str) -> list[str]:
    ids = []
    for line in text.splitlines():
        value = line.strip()
        if not value.startswith("package:"):
            continue
        # `pm list packages -f` appends `=package.name`; plain listings use package:name.
        package_id = value.rsplit("=", 1)[1] if "=" in value else value[len("package:") :]
        if validate_package_id(package_id):
            ids.append(package_id)
    return sorted(set(ids))


def parse_package_info(package_id: str, dumpsys: str, paths: str = "") -> PackageInfo:
    validate_package_id(package_id)
    version_code: int | None = None
    version_name: str | None = None
    last_update: str | None = None
    code_path: str | None = None
    # dumpsys output differs by Android version. Only parse metadata from this exact package call.
    for line in dumpsys.splitlines():
        if version_code is None:
            m = re.search(r"\bversionCode=(\d+)", line)
            if m:
                version_code = int(m.group(1))
        if version_name is None:
            m = re.search(r"\bversionName=([^\s]+)", line)
            if m:
                version_name = m.group(1)
        if last_update is None:
            m = re.search(r"\blastUpdateTime=([^\s]+(?:\s+[^\s]+)?)", line)
            if m:
                last_update = m.group(1).strip()
        if code_path is None:
            m = re.search(r"\bcodePath=(\S+)", line)
            if m:
                code_path = m.group(1)
    apk_path = None
    for line in paths.splitlines():
        if line.startswith("package:") and line.strip().endswith(".apk"):
            apk_path = line.strip()[len("package:") :]
            if apk_path.endswith("/base.apk"):
                break
    if apk_path is None and code_path:
        apk_path = code_path.rstrip("/") + "/base.apk"
    return PackageInfo(package_id, None, version_name, version_code, last_update, apk_path)


def parse_busy_status(power: str, display: str, media: str) -> BusyStatus:
    """Use clear active/inactive evidence only; absent or conflicting evidence stays unknown."""
    screen_evidence: set[bool] = set()
    combined = power + "\n" + display
    if re.search(r"\bmWakefulness\s*=\s*Awake\b|\bmScreenOn\s*=\s*true\b", combined, re.I):
        screen_evidence.add(True)
    if re.search(r"\bmWakefulness\s*=\s*Asleep\b|\bmScreenOn\s*=\s*false\b", combined, re.I):
        screen_evidence.add(False)
    if re.search(r"\bmDisplayState\s*=\s*ON\b|\bDisplay Power\s*[:=].*\bstate=ON\b", combined, re.I):
        screen_evidence.add(True)
    if re.search(r"\bmDisplayState\s*=\s*OFF\b|\bDisplay Power\s*[:=].*\bstate=OFF\b", combined, re.I):
        screen_evidence.add(False)
    screen = next(iter(screen_evidence)) if len(screen_evidence) == 1 else None

    # MediaSession exposes both active=false and numeric/named PlaybackState variants.
    active_values = [value.lower() == "true" for value in re.findall(r"\bactive\s*=\s*(true|false)\b", media, re.I)]
    state_codes = {
        "NONE": 0, "STOPPED": 1, "PAUSED": 2, "PLAYING": 3,
        "FAST_FORWARDING": 4, "REWINDING": 5, "BUFFERING": 6,
        "ERROR": 7, "CONNECTING": 8, "SKIPPING_TO_PREVIOUS": 9,
        "SKIPPING_TO_NEXT": 10, "SKIPPING_TO_QUEUE_ITEM": 11,
    }
    states: list[int | None] = []
    state_fields = re.finditer(
        r"\b(?:state|playbackState)\s*[=:]\s*(PlaybackState\s*\{|[^,\s}]+)", media, re.I
    )
    for match in state_fields:
        value = match.group(1)
        # PlaybackState.toString() wraps the actual nested `state=ERROR(7)` field.
        if re.fullmatch(r"PlaybackState\s*\{", value, re.I):
            continue
        numeric = re.fullmatch(r"[0-9]+", value)
        named = re.fullmatch(r"(?:STATE_)?([A-Z_]+)(?:\(([0-9]+)\))?", value, re.I)
        if numeric:
            states.append(int(value))
        elif named and named.group(1).upper() in state_codes:
            state_code = state_codes[named.group(1).upper()]
            supplied_code = int(named.group(2)) if named.group(2) else state_code
            states.append(state_code if supplied_code == state_code else None)
        else:
            states.append(None)
    active_states = any(state in {3, 4, 5, 6} for state in states)
    inactive_states = {0, 1, 2, 7}
    explicit_active = any(active_values)
    no_sessions = bool(re.search(r"\bhave\s+0\s+sessions\b|\bno (?:active )?sessions\b", media, re.I))
    if explicit_active or active_states:
        playback: bool | None = True
    elif states and any(state not in inactive_states for state in states):
        playback = None
    elif no_sessions:
        playback = False
    elif states:
        playback = False
    elif active_values and not explicit_active and not states:
        playback = False
    else:
        playback = None
    reasons = []
    if screen is True:
        reasons.append("screen is active")
    elif screen is None:
        reasons.append("screen state unknown")
    if playback is True:
        reasons.append("media playback is active")
    elif playback is None:
        reasons.append("playback state unknown")
    return BusyStatus(screen, playback, tuple(reasons))


def parse_compilation_filter(text: str, package_id: str) -> CompilationInspection:
    """Read every explicit per-package dexopt filter or report unsupported/conflicting data."""
    validate_package_id(package_id)
    in_package = False
    package_seen = False
    package_lines: list[str] = []
    for line in text.splitlines():
        header = re.search(r"\[([A-Za-z_][A-Za-z0-9_.]+)\]", line)
        if header:
            if in_package and package_lines:
                break
            in_package = header.group(1) == package_id
            package_seen |= in_package
            if in_package:
                package_lines = [line]
            continue
        if in_package:
            package_lines.append(line)
    filters = [
        match.group(1).lower()
        for line in package_lines
        if (
            match := re.search(
                r"(?:compiler[-_ ]filter|filter|status)\s*[=:]\s*([A-Za-z0-9_-]+)",
                line,
                re.I,
            )
        )
    ]
    if filters and len(filters) == sum(
        1 for line in package_lines if "status=" in line.lower() or "filter=" in line.lower()
    ):
        unique = set(filters)
        if len(unique) == 1:
            return CompilationInspection(True, filters[0], f"all reported dexopt entries: {filters[0]}")
        return CompilationInspection(True, "mixed", "dexopt entries have mixed filters: " + ", ".join(sorted(unique)))
    detail = (
        "package output found without a recognizable compiler filter"
        if package_seen
        else "device output does not expose package compilation filter"
    )
    return CompilationInspection(False, None, detail)


def validate_endpoint(endpoint: str) -> str:
    """Accept only a literal IP or DNS hostname plus a valid TCP port."""
    if not isinstance(endpoint, str) or len(endpoint) > 300:
        raise ValueError("invalid ADB endpoint")
    host: str
    port_text: str
    if endpoint.startswith("["):
        match = re.fullmatch(r"\[([^\]]+)\]:(\d{1,5})", endpoint)
        if not match:
            raise ValueError("invalid ADB endpoint")
        host, port_text = match.groups()
        try:
            ipaddress.IPv6Address(host)
        except ValueError as exc:
            raise ValueError("invalid ADB endpoint") from exc
    else:
        if endpoint.count(":") != 1:
            raise ValueError("invalid ADB endpoint")
        host, port_text = endpoint.rsplit(":", 1)
        try:
            ipaddress.ip_address(host)
        except ValueError:
            if not _DNS_LABEL_RE.fullmatch(host):
                raise ValueError("invalid ADB endpoint") from None
    port = int(port_text)
    if not 1 <= port <= 65535:
        raise ValueError("invalid ADB endpoint")
    return endpoint


class Runner(Protocol):
    def run(self, argv: Sequence[str], *, timeout: float, env: dict[str, str]) -> AdbResult: ...


class BoundedSubprocessRunner:
    """Run argv without a shell, bound time and retained output, and drain both pipes."""

    def __init__(self, output_limit: int = 256_000):
        self.output_limit = output_limit

    def run(self, argv: Sequence[str], *, timeout: float, env: dict[str, str]) -> AdbResult:
        proc = subprocess.Popen(
            list(argv),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.DEVNULL,
            shell=False,
            env=env,
        )
        out = bytearray()
        err = bytearray()

        def drain(stream, buffer: bytearray) -> None:
            while True:
                chunk = stream.read(8192)
                if not chunk:
                    break
                remaining = self.output_limit - len(buffer)
                if remaining > 0:
                    buffer.extend(chunk[:remaining])

        threads = [
            threading.Thread(target=drain, args=(proc.stdout, out), daemon=True),
            threading.Thread(target=drain, args=(proc.stderr, err), daemon=True),
        ]
        for thread in threads:
            thread.start()
        try:
            code = proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired as exc:
            proc.kill()
            proc.wait()
            for thread in threads:
                thread.join()
            raise TimeoutError("ADB subprocess timed out") from exc
        for thread in threads:
            thread.join()
        return AdbResult(tuple(argv), code, out.decode("utf-8", "replace"), err.decode("utf-8", "replace"))


class AdbError(RuntimeError):
    def __init__(self, operation: str, message: str):
        super().__init__(f"{operation}: {message}")
        self.operation = operation


class AdbClient:
    """An allowlisted ADB API. All device-side operations use fixed command templates."""

    def __init__(
        self,
        instance_dir: str | Path,
        *,
        adb_path: str = "adb",
        runner: Runner | None = None,
        timeout: float = 15.0,
    ):
        self.instance_dir = Path(instance_dir)
        self.android_home = self.instance_dir / "home"
        self.android_home.mkdir(parents=True, exist_ok=True, mode=0o700)
        (self.android_home / ".android").mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            os.chmod(self.instance_dir, 0o700)
            os.chmod(self.android_home, 0o700)
            os.chmod(self.android_home / ".android", 0o700)
        except OSError:
            pass
        self.adb_path = adb_path
        self.runner = runner or BoundedSubprocessRunner()
        self._verified_serials: dict[str, tuple[str | None, str | None]] = {}
        if not 0 < timeout <= 600:
            raise ValueError("ADB timeout must be between 0 and 600 seconds")
        self.timeout = timeout
        self.env = {
            **os.environ,
            "HOME": str(self.android_home),
            "ANDROID_USER_HOME": str(self.android_home / ".android"),
        }

    def _run(self, args: Sequence[str], *, serial: str | None = None, timeout: float | None = None) -> AdbResult:
        argv = [self.adb_path]
        if serial:
            argv.extend(["-s", serial])
        argv.extend(args)
        try:
            result = self.runner.run(argv, timeout=timeout or self.timeout, env=self.env.copy())
        except TimeoutError as exc:
            raise AdbError(args[0] if args else "adb", "timed out") from exc
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip().replace("\n", " ")[:300]
            raise AdbError(args[0] if args else "adb", detail or f"exit status {result.returncode}")
        return result

    def pair(self, endpoint: str, pairing_code: str) -> str:
        validate_endpoint(endpoint)
        # Codes are strings to preserve leading zeroes, passed only to adb and never included in errors.
        if not isinstance(pairing_code, str) or not re.fullmatch(r"\d{4,12}", pairing_code):
            raise ValueError("pairing code must be a 4-12 digit string")
        argv = [self.adb_path, "pair", endpoint, pairing_code]
        try:
            result = self.runner.run(argv, timeout=self.timeout, env=self.env.copy())
        except TimeoutError as exc:
            raise AdbError("pair", "timed out; pairing code was not retained") from exc
        # adb may echo the command input or protocol text; never return or propagate raw output.
        if result.returncode != 0:
            raise AdbError("pair", "pairing failed; verify the code and pairing endpoint")
        return "paired"

    def connect(self, endpoint: str) -> str:
        validate_endpoint(endpoint)
        output = self._run(["connect", endpoint]).stdout.strip()
        # adb connect output is constrained by bounded output. Keep only an endpoint-free result.
        if "connected" in output.lower():
            return "connected"
        if "already connected" in output.lower():
            return "already connected"
        return "connect attempted"

    def disconnect(self, endpoint: str) -> None:
        validate_endpoint(endpoint)
        self._run(["disconnect", endpoint])

    def discover(self) -> list[DiscoveredService]:
        return parse_mdns_services(self._run(["mdns", "services"]).stdout)

    def devices(self) -> list[tuple[str, str]]:
        return parse_connected_devices(self._run(["devices", "-l"]).stdout)

    def identity(self, serial: str) -> DeviceIdentity:
        """Read durable Android hardware identity, never the endpoint-shaped ADB transport ID."""
        if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9_.:\-\[\]]{1,300}", serial):
            raise ValueError("invalid ADB serial")
        durable_serial = self._run(["shell", "getprop", "ro.serialno"], serial=serial).stdout.strip()
        if not durable_serial:
            durable_serial = self._run(["shell", "getprop", "ro.boot.serialno"], serial=serial).stdout.strip()
        fingerprint = self._run(["shell", "getprop", "ro.build.fingerprint"], serial=serial).stdout.strip()
        if not durable_serial or durable_serial.lower() in {"unknown", "offline", "0"}:
            raise AdbError("identity", "device does not expose a durable hardware serial")
        return DeviceIdentity(durable_serial, fingerprint or None)

    def verify_connected_identity(
        self,
        serial: str,
        *,
        expected_serial: str | None,
        expected_fingerprint: str | None,
    ) -> DeviceIdentity:
        """Check pinned identity and authorize constrained operations for this transport serial."""
        if not expected_serial and not expected_fingerprint:
            raise AdbError("identity", "no pinned device identity is available")
        actual = self.identity(serial)
        self.verify_identity(actual, expected_serial=expected_serial, expected_fingerprint=expected_fingerprint)
        self._verified_serials[serial] = (expected_serial, expected_fingerprint)
        return actual

    def _require_verified(self, serial: str) -> None:
        expected = self._verified_serials.get(serial)
        if expected is None:
            raise AdbError("identity", "verify this connected device against its pinned identity first")
        try:
            actual = self.identity(serial)
            self.verify_identity(actual, expected_serial=expected[0], expected_fingerprint=expected[1])
        except AdbError:
            self._verified_serials.pop(serial, None)
            raise

    @staticmethod
    def verify_identity(
        actual: DeviceIdentity, *, expected_serial: str | None, expected_fingerprint: str | None
    ) -> None:
        if expected_serial and actual.serial != expected_serial:
            raise AdbError("identity", "connected device serial does not match the pinned device")
        if expected_fingerprint and actual.build_fingerprint != expected_fingerprint:
            raise AdbError("identity", "connected device build identity changed; verification required")

    def reconnect(
        self,
        *,
        expected_serial: str | None = None,
        expected_fingerprint: str | None = None,
        endpoint: str | None = None,
    ) -> tuple[str, DeviceIdentity]:
        candidates: list[str] = []
        if endpoint:
            candidates.append(validate_endpoint(endpoint))
        last_error: AdbError | None = None
        try:
            for service in self.discover():
                if service.kind == "connect" and service.endpoint not in candidates:
                    candidates.append(service.endpoint)
        except AdbError as exc:
            last_error = exc
        for candidate in candidates:
            try:
                self.connect(candidate)
                identity = self.identity(candidate)
                if expected_serial or expected_fingerprint:
                    self.verify_identity(
                        identity,
                        expected_serial=expected_serial,
                        expected_fingerprint=expected_fingerprint,
                    )
                    self._verified_serials[candidate] = (expected_serial, expected_fingerprint)
                return candidate, identity
            except AdbError as exc:
                last_error = exc
        raise last_error or AdbError("reconnect", "no TLS ADB connection service was discovered")

    def installed_packages(self, serial: str) -> list[str]:
        self._require_verified(serial)
        return parse_package_ids(self._run(["shell", "pm", "list", "packages", "-3", "-f"], serial=serial).stdout)

    def package_info(self, serial: str, package_id: str) -> PackageInfo:
        self._require_verified(serial)
        validate_package_id(package_id)
        info = self._run(["shell", "dumpsys", "package", package_id], serial=serial).stdout
        paths = self._run(["shell", "pm", "path", package_id], serial=serial).stdout
        return parse_package_info(package_id, info, paths)

    def busy_status(self, serial: str) -> BusyStatus:
        self._require_verified(serial)
        power = self._run(["shell", "dumpsys", "power"], serial=serial).stdout
        display = self._run(["shell", "dumpsys", "display"], serial=serial).stdout
        media = self._run(["shell", "dumpsys", "media_session"], serial=serial).stdout
        return parse_busy_status(power, display, media)

    def compile_speed(self, serial: str, package_id: str) -> AdbResult:
        validate_package_id(package_id)
        self._require_verified(serial)
        # Mode is fixed; caller cannot select an arbitrary compiler filter or command.
        result = self._run(
            ["shell", "cmd", "package", "compile", "-m", "speed", "-f", package_id],
            serial=serial,
            timeout=600.0,
        )
        if re.search(r"\bFailure\b", result.stdout, re.I):
            raise AdbError("compile", "Android package manager reported compilation failure")
        return result

    def inspect_compilation(self, serial: str, package_id: str) -> CompilationInspection:
        self._require_verified(serial)
        validate_package_id(package_id)
        output = self._run(["shell", "dumpsys", "package", "dexopt"], serial=serial).stdout
        return parse_compilation_filter(output, package_id)
