from pathlib import Path

import pytest

from tvcompiler.adb import (
    AdbClient,
    AdbError,
    AdbResult,
    DeviceIdentity,
    PackageFingerprint,
    parse_busy_status,
    parse_compilation_filter,
    parse_connected_devices,
    parse_mdns_services,
    parse_package_ids,
    parse_package_info,
    validate_endpoint,
)
from tvcompiler.models import Device
from tvcompiler.scheduler import Scheduler
from tvcompiler.store import Store

FIXTURES = Path(__file__).parent / "fixtures"


class FakeRunner:
    def __init__(self, responses=None):
        self.responses = responses or {}
        self.calls = []

    def run(self, argv, *, timeout, env):
        self.calls.append((list(argv), timeout, env.copy()))
        response = self.responses.get(tuple(argv[1:]), (0, "", ""))
        return AdbResult(tuple(argv), response[0], response[1], response[2])


class SequencedDevicesRunner(FakeRunner):
    def __init__(self, responses, device_outputs):
        super().__init__(responses)
        self.device_outputs = iter(device_outputs)

    def run(self, argv, *, timeout, env):
        if tuple(argv[1:]) == ("devices", "-l"):
            self.calls.append((list(argv), timeout, env.copy()))
            return AdbResult(tuple(argv), 0, next(self.device_outputs), "")
        return super().run(argv, timeout=timeout, env=env)


def fixture(name):
    return (FIXTURES / name).read_text()


def test_mdns_filters_to_tls_and_endpoint_validation():
    found = parse_mdns_services(fixture("mdns-services.txt"))
    assert [(x.kind, x.endpoint) for x in found] == [
        ("connect", "192.168.1.45:37123"),
        ("pairing", "living-room.local:42311"),
    ]
    assert validate_endpoint("[2001:db8::1]:5555")
    for invalid in ["127.0.0.1:0", "-evil:5555", "host:99999", "host:5555;id", "::1:5555"]:
        with pytest.raises(ValueError):
            validate_endpoint(invalid)


def test_package_list_supports_path_format_and_similar_package_names():
    assert parse_package_ids(fixture("packages.txt")) == [
        "com.example.other",
        "com.example.stream",
        "com.nuvio.tv",
        "com.nuvio.tv.test",
    ]


def test_package_fingerprint_includes_update_time_and_apk_path():
    first = parse_package_info(
        "com.example.stream",
        fixture("package-dumpsys.txt"),
        "package:/data/app/~~fresh/com.example.stream/base.apk\n",
    )
    second = PackageFingerprint(
        "com.example.stream",
        "2.4.0",
        14,
        "2026-10-02 12:34:56",
        "/data/app/~~fresh/com.example.stream/base.apk",
    )
    assert first.version_code == 14
    assert first.version_name == "2.4.0"
    assert first.last_update_time == "2026-10-01 12:34:56"
    assert first.apk_path.endswith("/base.apk")
    assert first.fingerprint.value != second.value


def test_busy_detection_is_conservative_and_fixture_parsing():
    text = fixture("busy.txt")
    status = parse_busy_status(
        text.split("DISPLAY")[0],
        text.split("DISPLAY\n")[1].split("MEDIA")[0],
        text.split("MEDIA\n")[1],
    )
    assert status.idle_confirmed
    assert status.reasons == ()
    unknown = parse_busy_status("", "", "")
    assert not unknown.idle_confirmed
    assert "screen state unknown" in unknown.reasons
    playing = parse_busy_status(
        "mWakefulness=Asleep",
        "mDisplayState=OFF",
        "active=true, state=PlaybackState {state=PLAYING(3)}",
    )
    assert playing.playback_active is True


@pytest.mark.parametrize("state", ["DOORBELLING", "42"])
def test_unrecognized_playback_state_stays_unknown_with_inactive_flag(state):
    status = parse_busy_status("mWakefulness=Asleep", "mDisplayState=OFF", f"active=false, state={state}")
    assert status.screen_active is False
    assert status.playback_active is None
    assert "playback state unknown" in status.reasons


def test_named_android_playback_states_and_wrapper_error_are_recognized():
    error = parse_busy_status(
        "mWakefulness=Asleep", "mDisplayState=OFF",
        "active=false, state=PlaybackState {state=ERROR(7), position=0}",
    )
    playing = parse_busy_status("mWakefulness=Asleep", "mDisplayState=OFF", "active=false, state=PLAYING")
    malformed = parse_busy_status("mWakefulness=Asleep", "mDisplayState=OFF", "active=false, state=wat?ever")
    assert error.playback_active is False and error.idle_confirmed
    assert playing.playback_active is True
    assert malformed.playback_active is None


def test_media_state_transitions_mismatches_and_unknowns_override_idle_hints():
    transition = parse_busy_status("mWakefulness=Asleep", "mDisplayState=OFF", "active=false, state=CONNECTING")
    mismatch = parse_busy_status("mWakefulness=Asleep", "mDisplayState=OFF", "active=false, state=PLAYING(2)")
    unknown_with_no_sessions = parse_busy_status(
        "mWakefulness=Asleep", "mDisplayState=OFF", "have 0 sessions; active=false, state=42"
    )
    no_space_wrapper = parse_busy_status(
        "mWakefulness=Asleep", "mDisplayState=OFF",
        "active=false, state=PlaybackState{state=ERROR(7), position=0}",
    )
    assert transition.playback_active is None
    assert mismatch.playback_active is None
    assert unknown_with_no_sessions.playback_active is None
    assert no_space_wrapper.playback_active is False and no_space_wrapper.idle_confirmed


def test_compilation_inspection_does_not_infer_success_without_filter():
    result = parse_compilation_filter(fixture("compilation.txt"), "com.example.stream")
    assert result.supported and result.compiler_filter == "speed"
    unknown = parse_compilation_filter("Package [com.example.stream]", "com.example.stream")
    assert not unknown.supported and unknown.compiler_filter is None
    mixed = parse_compilation_filter(
        "[com.example.stream]\narm: [status=speed]\nx86: [status=verify]\n", "com.example.stream"
    )
    assert mixed.supported and mixed.compiler_filter == "mixed"
    similarly_named_package = parse_compilation_filter(
        "[com.nuvio.tv.test]\narm: [status=speed]\n", "com.nuvio.tv"
    )
    assert not similarly_named_package.supported
    assert similarly_named_package.compiler_filter is None


def test_pair_keeps_leading_zero_code_and_never_exposes_it_on_failure(tmp_path):
    runner = FakeRunner({("pair", "tv.local:37123", "001234"): (1, "pair 001234", "bad")})
    client = AdbClient(tmp_path / "instance", runner=runner)
    with pytest.raises(AdbError) as caught:
        client.pair("tv.local:37123", "001234")
    assert "001234" not in str(caught.value)
    assert runner.calls[0][0][-1] == "001234"
    assert runner.calls[0][2]["HOME"].endswith("/instance/home")
    assert (tmp_path / "instance/home/.android").is_dir()


def test_auto_pair_parses_only_constrained_guid_and_keeps_code_private(tmp_path):
    endpoint = "10.0.0.5:41267"
    guid = "adb-serial-a-R4nd0m"
    pairing = "10.0.0.5:37123"
    transport = f"{guid}._adb-tls-connect._tcp"
    runner = FakeRunner(
        {
            ("pair", pairing, "001234"): (0, f"Successfully paired to {pairing} [guid={guid}]", ""),
            ("mdns", "services"): (
                0,
                f"{guid} _adb-tls-connect._tcp {endpoint}\nadb-unrelated _adb-tls-connect._tcp 10.0.0.6:55555\n",
                "",
            ),
            ("devices", "-l"): (0, f"{transport} device product:tv\n", ""),
            ("-s", transport, "shell", "getprop", "persist.adb.wifi.guid"): (0, f"{guid}\n", ""),
            ("-s", transport, "shell", "getprop", "ro.serialno"): (0, "serial-tv-a\n", ""),
            ("-s", transport, "shell", "getprop", "ro.build.fingerprint"): (0, "vendor/tv/build:1\n", ""),
        }
    )
    client = AdbClient(tmp_path / "instance", runner=runner)
    parsed_guid = client.pair(pairing, "001234", require_confirmation=True)
    resolved = client.resolve_paired_device(parsed_guid)
    assert parsed_guid == guid
    assert resolved == (endpoint, DeviceIdentity("serial-tv-a", "vendor/tv/build:1"))
    assert runner.calls[0][0][-1] == "001234"
    assert not any(call[0][1] == "connect" for call in runner.calls)


@pytest.mark.parametrize(
    "mdns",
    [
        "other-device _adb-tls-connect._tcp 10.0.0.8:5555\n",
        "adb-target _adb-tls-connect._tcp 10.0.0.8:5555\nadb-target _adb-tls-connect._tcp 10.0.0.9:5555\n",
    ],
)
def test_paired_guid_requires_one_exact_mdns_connect_record(tmp_path, mdns):
    runner = FakeRunner({("mdns", "services"): (0, mdns, "")})
    client = AdbClient(tmp_path, runner=runner)
    assert client.resolve_paired_device("adb-target") is None
    assert not any(call[0][1] == "connect" for call in runner.calls)


def test_auto_pair_rejects_rc_zero_without_recognized_success_line(tmp_path):
    code = "001234"
    runner = FakeRunner({("pair", "tv.local:37123", code): (0, f"error: protocol echoed {code}", "")})
    client = AdbClient(tmp_path, runner=runner)
    with pytest.raises(AdbError) as caught:
        client.pair("tv.local:37123", code, require_confirmation=True)
    assert code not in str(caught.value)


def test_auto_pair_success_without_guid_requires_manual_endpoint(tmp_path):
    runner = FakeRunner(
        {("pair", "tv.local:37123", "001234"): (0, "Successfully paired to tv.local:37123", "")}
    )
    client = AdbClient(tmp_path, runner=runner)
    assert client.pair("tv.local:37123", "001234", require_confirmation=True) is None


def test_exact_guid_without_connected_transport_requires_manual_endpoint(tmp_path):
    guid = "adb-target"
    endpoint = "10.0.0.8:5555"
    runner = FakeRunner(
        {
            ("mdns", "services"): (0, f"{guid} _adb-tls-connect._tcp {endpoint}\n", ""),
            ("connect", endpoint): (0, "connection pending", ""),
            ("devices", "-l"): (0, "List of devices attached\n", ""),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    assert client.resolve_paired_device(guid) is None
    assert not any(call[0][1:2] == ["-s"] for call in runner.calls)


def test_misleading_exact_guid_mdns_record_cannot_authorize_another_connected_tv(tmp_path):
    paired_guid = "adb-target-123456"
    other_guid = "adb-other-654321"
    endpoint = "10.0.0.8:5555"
    runner = FakeRunner(
        {
            ("mdns", "services"): (0, f"{paired_guid} _adb-tls-connect._tcp {endpoint}\n", ""),
            ("connect", endpoint): (0, "already connected", ""),
            ("devices", "-l"): (0, f"{endpoint} device product:other-tv\n", ""),
            ("-s", endpoint, "shell", "getprop", "persist.adb.wifi.guid"): (0, f"{other_guid}\n", ""),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    assert client.resolve_paired_device(paired_guid) is None
    assert not any(call[0][-3:] == ["shell", "getprop", "ro.serialno"] for call in runner.calls)


def test_pair_resolve_scheduler_reconnect_and_inventory_use_verified_guid_selector(tmp_path):
    endpoint = "10.0.0.5:41267"
    pairing = "10.0.0.5:37123"
    guid = "adb-serial-a-R4nd0m"
    transport = f"{guid}._adb-tls-connect._tcp."
    runner = FakeRunner(
        {
            ("pair", pairing, "001234"): (0, f"Successfully paired to {pairing} [guid={guid}]", ""),
            ("mdns", "services"): (0, f"{guid} _adb-tls-connect._tcp {endpoint}\n", ""),
            ("devices", "-l"): (0, f"{transport} device product:tv\n", ""),
            ("-s", transport, "shell", "getprop", "persist.adb.wifi.guid"): (0, f"{guid}\n", ""),
            ("-s", transport, "shell", "getprop", "ro.serialno"): (0, "serial-tv-a\n", ""),
            ("-s", transport, "shell", "getprop", "ro.build.fingerprint"): (0, "vendor/tv/build:1\n", ""),
            ("-s", transport, "shell", "pm", "list", "packages", "-3", "-f"): (
                0,
                "package:/data/app/com.example.stream/base.apk=com.example.stream\n",
                "",
            ),
        }
    )
    client = AdbClient(tmp_path / "instance", runner=runner)
    assert client.resolve_paired_device(guid) == (
        endpoint,
        DeviceIdentity("serial-tv-a", "vendor/tv/build:1"),
    )
    store = Store(tmp_path / "state.db")
    scheduler = Scheduler(store, client, tmp_path / "instance")
    device = Device("tv-a", "Living Room", endpoint, "serial-tv-a", "vendor/tv/build:1", True, None)
    selector, identity = scheduler._connect(device)
    assert selector == transport
    assert identity.serial == "serial-tv-a"
    assert client.installed_packages(selector) == ["com.example.stream"]
    assert not any(call[0][1] == "connect" for call in runner.calls)
    assert all(endpoint not in call[0] or call[0][1] != "-s" for call in runner.calls)


def test_reconnect_skips_wrong_online_guid_transport_and_uses_matching_pinned_endpoint(tmp_path):
    endpoint = "10.0.0.5:41267"
    other_endpoint = "10.0.0.6:41267"
    guid = "adb-serial-a-R4nd0m"
    other_guid = "adb-other-tv-R4nd0m"
    transport = f"{other_guid}._adb-tls-connect._tcp"
    runner = FakeRunner(
        {
            ("mdns", "services"): (
                0,
                f"{other_guid} _adb-tls-connect._tcp {other_endpoint}\n"
                f"{guid} _adb-tls-connect._tcp {endpoint}\n",
                "",
            ),
            ("devices", "-l"): (0, f"{transport} device\n{endpoint} device\n", ""),
            ("-s", transport, "shell", "getprop", "ro.serialno"): (0, "other-tv\n", ""),
            ("-s", transport, "shell", "getprop", "ro.build.fingerprint"): (0, "build-other\n", ""),
            ("-s", endpoint, "shell", "getprop", "ro.serialno"): (0, "serial-tv-a\n", ""),
            ("-s", endpoint, "shell", "getprop", "ro.build.fingerprint"): (0, "build-tv-a\n", ""),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    resolved = client.reconnect_with_selector(
        expected_serial="serial-tv-a",
        expected_fingerprint="build-tv-a",
        endpoint=endpoint,
    )
    assert resolved.endpoint == endpoint
    assert resolved.selector == endpoint
    assert not any(call[0][1] == "connect" for call in runner.calls)
    assert transport not in client._verified_serials
    assert client._verified_serials[endpoint] == ("serial-tv-a", "build-tv-a")


def test_reconnect_deduplicates_guid_and_ip_aliases_and_prefers_guid_selector(tmp_path):
    endpoint = "10.0.0.5:41267"
    guid = "adb-serial-a-R4nd0m"
    transport = f"{guid}._adb-tls-connect._tcp"
    runner = FakeRunner(
        {
            ("mdns", "services"): (0, f"{guid} _adb-tls-connect._tcp {endpoint}\n", ""),
            ("devices", "-l"): (0, f"{endpoint} device\n{transport} device\n", ""),
            ("-s", endpoint, "shell", "getprop", "ro.serialno"): (0, "serial-tv-a\n", ""),
            ("-s", endpoint, "shell", "getprop", "ro.build.fingerprint"): (0, "build-tv-a\n", ""),
            ("-s", transport, "shell", "getprop", "ro.serialno"): (0, "serial-tv-a\n", ""),
            ("-s", transport, "shell", "getprop", "ro.build.fingerprint"): (0, "build-tv-a\n", ""),
            ("-s", transport, "shell", "pm", "list", "packages", "-3", "-f"): (
                0,
                "package:/data/app/com.example.stream/base.apk=com.example.stream\n",
                "",
            ),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    result = client.reconnect_with_selector(
        expected_serial="serial-tv-a",
        expected_fingerprint="build-tv-a",
        endpoint=endpoint,
    )
    assert result.endpoint == endpoint
    assert result.selector == transport
    assert client.installed_packages(result.selector) == ["com.example.stream"]
    assert endpoint not in client._verified_serials
    assert client._verified_serials[transport] == ("serial-tv-a", "build-tv-a")


def test_reconnect_fails_closed_when_pinned_identity_matches_distinct_endpoints(tmp_path):
    first_endpoint = "10.0.0.5:41267"
    second_endpoint = "10.0.0.6:41267"
    first_guid = "adb-serial-a-R4nd0m"
    second_guid = "adb-serial-a-Other"
    first_transport = f"{first_guid}._adb-tls-connect._tcp"
    second_transport = f"{second_guid}._adb-tls-connect._tcp"
    runner = FakeRunner(
        {
            ("mdns", "services"): (
                0,
                f"{first_guid} _adb-tls-connect._tcp {first_endpoint}\n"
                f"{second_guid} _adb-tls-connect._tcp {second_endpoint}\n",
                "",
            ),
            ("devices", "-l"): (0, f"{first_transport} device\n{second_transport} device\n", ""),
            ("-s", first_transport, "shell", "getprop", "ro.serialno"): (0, "serial-tv-a\n", ""),
            ("-s", first_transport, "shell", "getprop", "ro.build.fingerprint"): (0, "build-tv-a\n", ""),
            ("-s", second_transport, "shell", "getprop", "ro.serialno"): (0, "serial-tv-a\n", ""),
            ("-s", second_transport, "shell", "getprop", "ro.build.fingerprint"): (0, "build-tv-a\n", ""),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    with pytest.raises(AdbError, match="multiple connected devices"):
        client.reconnect_with_selector(
            expected_serial="serial-tv-a",
            expected_fingerprint="build-tv-a",
            endpoint=first_endpoint,
        )
    assert not client._verified_serials


def test_pair_timeout_is_sanitized_and_code_is_not_returned(tmp_path):
    class TimeoutRunner(FakeRunner):
        def run(self, argv, *, timeout, env):
            self.calls.append((list(argv), timeout, env.copy()))
            raise TimeoutError

    runner = TimeoutRunner()
    client = AdbClient(tmp_path, runner=runner)
    with pytest.raises(AdbError) as caught:
        client.pair("tv.local:37123", "001234", require_confirmation=True)
    assert "001234" not in str(caught.value)


def test_client_only_uses_fixed_command_templates(tmp_path):
    runner = FakeRunner(
        {
            (
                "-s",
                "192.168.1.5:5555",
                "shell",
                "cmd",
                "package",
                "compile",
                "-m",
                "speed",
                "-f",
                "com.example.stream",
            ): (0, "Success", "")
        }
    )
    runner.responses.update(
        {
            ("-s", "192.168.1.5:5555", "shell", "getprop", "ro.serialno"): (0, "serial-a", ""),
            ("-s", "192.168.1.5:5555", "shell", "getprop", "ro.build.fingerprint"): (0, "build-a", ""),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    client.verify_connected_identity("192.168.1.5:5555", expected_serial="serial-a", expected_fingerprint="build-a")
    result = client.compile_speed("192.168.1.5:5555", "com.example.stream")
    assert result.stdout == "Success"
    assert runner.calls[-1][0][1:] == [
        "-s",
        "192.168.1.5:5555",
        "shell",
        "cmd",
        "package",
        "compile",
        "-m",
        "speed",
        "-f",
        "com.example.stream",
    ]
    with pytest.raises(ValueError):
        client.compile_speed("tv", "com.example;evil")
    with pytest.raises(ValueError):
        client.connect("127.0.0.1:123;evil")


def test_connected_status_parser():
    assert parse_connected_devices("List of devices attached\na:1 device product:x\nb:2 unauthorized\n") == [
        ("a:1", "device"),
        ("b:2", "unauthorized"),
    ]
    AdbClient.verify_identity(DeviceIdentity("one", "build"), expected_serial="one", expected_fingerprint="build")
    with pytest.raises(AdbError):
        AdbClient.verify_identity(DeviceIdentity("two", "build"), expected_serial="one", expected_fingerprint="build")


def test_reconnect_keeps_manual_endpoint_when_mdns_is_unavailable(tmp_path):
    endpoint = "192.168.1.45:37123"
    runner = FakeRunner(
        {
            ("mdns", "services"): (1, "", "mDNS unavailable"),
            ("connect", endpoint): (0, "connected to endpoint", ""),
            ("devices", "-l"): (0, f"{endpoint} device product:tv\n", ""),
            ("-s", endpoint, "shell", "getprop", "ro.serialno"): (0, "serial-a", ""),
            ("-s", endpoint, "shell", "getprop", "ro.build.fingerprint"): (0, "build-a", ""),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    chosen, identity = client.reconnect(expected_serial="serial-a", expected_fingerprint="build-a", endpoint=endpoint)
    assert chosen == endpoint
    assert identity.serial == "serial-a"
    assert client._verified_serials[endpoint] == ("serial-a", "build-a")


@pytest.mark.parametrize(
    ("state", "message"),
    [
        ("unauthorized", "authorization prompt"),
        ("no permissions", "service runtime access"),
    ],
)
def test_reconnect_preserves_candidate_authorization_state_after_connect(tmp_path, state, message):
    endpoint = "192.168.1.45:5555"
    runner = SequencedDevicesRunner(
        {("mdns", "services"): (1, "", "mDNS unavailable"), ("connect", endpoint): (0, "connected", "")},
        ["List of devices attached\n", f"{endpoint} {state}\n"],
    )
    client = AdbClient(tmp_path, runner=runner)

    with pytest.raises(AdbError, match=message):
        client.reconnect(expected_serial="serial-a", expected_fingerprint="build-a", endpoint=endpoint)
    assert not client._verified_serials


def test_unrelated_unauthorized_adb_device_does_not_mask_intended_connection(tmp_path):
    endpoint = "192.168.1.45:5555"
    runner = FakeRunner(
        {
            ("mdns", "services"): (1, "", "mDNS unavailable"),
            ("devices", "-l"): (
                0,
                f"{endpoint} device product:tv\nunrelated:5555 unauthorized\n",
                "",
            ),
            ("-s", endpoint, "shell", "getprop", "ro.serialno"): (0, "serial-a", ""),
            ("-s", endpoint, "shell", "getprop", "ro.build.fingerprint"): (0, "build-a", ""),
        }
    )
    client = AdbClient(tmp_path, runner=runner)

    selected, identity = client.reconnect(
        expected_serial="serial-a", expected_fingerprint="build-a", endpoint=endpoint
    )
    assert selected == endpoint and identity == DeviceIdentity("serial-a", "build-a")
    assert client._verified_serials[endpoint] == ("serial-a", "build-a")


def test_intended_unauthorized_state_survives_unrelated_discovery_failures(tmp_path):
    endpoint = "192.168.1.45:5555"
    other_endpoint = "192.168.1.46:5555"
    guid = "adb-target-guid"
    other_guid = "adb-other-guid"
    runner = SequencedDevicesRunner(
        {
            ("mdns", "services"): (
                0,
                f"{guid} _adb-tls-connect._tcp {endpoint}\n"
                f"{other_guid} _adb-tls-connect._tcp {other_endpoint}\n",
                "",
            ),
            ("connect", endpoint): (0, "connected", ""),
            ("connect", other_endpoint): (1, "", "unrelated discovery failure"),
        },
        ["List of devices attached\n", f"{endpoint} unauthorized\n"],
    )
    client = AdbClient(tmp_path, runner=runner)

    with pytest.raises(AdbError, match="authorization prompt"):
        client.reconnect(expected_serial="serial-a", expected_fingerprint="build-a", endpoint=endpoint)
    assert not client._verified_serials


def test_target_identity_mismatch_outweighs_a_previous_unauthorized_state(tmp_path):
    endpoint = "192.168.1.45:5555"
    runner = SequencedDevicesRunner(
        {
            ("mdns", "services"): (1, "", "mDNS unavailable"),
            ("connect", endpoint): (0, "connected", ""),
            ("-s", endpoint, "shell", "getprop", "ro.serialno"): (0, "other-serial", ""),
            ("-s", endpoint, "shell", "getprop", "ro.build.fingerprint"): (0, "other-build", ""),
        },
        [f"{endpoint} unauthorized\n", f"{endpoint} device\n"],
    )
    client = AdbClient(tmp_path, runner=runner)

    with pytest.raises(AdbError, match="serial does not match the pinned device"):
        client.reconnect(expected_serial="serial-a", expected_fingerprint="build-a", endpoint=endpoint)
    assert not client._verified_serials


def test_compilation_failure_text_is_not_reported_as_success(tmp_path):
    endpoint = "192.168.1.5:5555"
    runner = FakeRunner(
        {
            ("-s", endpoint, "shell", "getprop", "ro.serialno"): (0, "serial-a", ""),
            ("-s", endpoint, "shell", "getprop", "ro.build.fingerprint"): (0, "build-a", ""),
            ("-s", endpoint, "shell", "cmd", "package", "compile", "-m", "speed", "-f", "com.example.stream"): (
                0,
                "Failure [package not found]",
                "",
            ),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    client.verify_connected_identity(endpoint, expected_serial="serial-a", expected_fingerprint="build-a")
    with pytest.raises(AdbError, match="reported compilation failure"):
        client.compile_speed(endpoint, "com.example.stream")


def test_bounded_runner_caps_retained_stdout_and_stderr():
    import sys

    from tvcompiler.adb import BoundedSubprocessRunner

    result = BoundedSubprocessRunner(output_limit=128).run(
        [sys.executable, "-c", "import sys; print('x'*10000); print('y'*10000, file=sys.stderr)"],
        timeout=5,
        env={},
    )
    assert result.returncode == 0
    assert len(result.stdout) <= 128
    assert len(result.stderr) <= 128


def test_parse_real_adb_mdns_three_column_output_and_bracketed_ipv6():
    from tvcompiler.adb import parse_mdns_services

    services = parse_mdns_services(
        "List of discovered mdns services\n"
        "adb-example-SqXLCd\t_adb-tls-connect._tcp\t192.168.1.45:37821\n"
        "adb-example-SqXLCd\t_adb-tls-pairing._tcp.\t[fd12::45]:37123\n"
    )
    assert [(item.kind, item.endpoint) for item in services] == [
        ("connect", "192.168.1.45:37821"),
        ("pairing", "[fd12::45]:37123"),
    ]
