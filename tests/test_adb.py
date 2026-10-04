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

FIXTURES = Path(__file__).parent / "fixtures"


class FakeRunner:
    def __init__(self, responses=None):
        self.responses = responses or {}
        self.calls = []

    def run(self, argv, *, timeout, env):
        self.calls.append((list(argv), timeout, env.copy()))
        response = self.responses.get(tuple(argv[1:]), (0, "", ""))
        return AdbResult(tuple(argv), response[0], response[1], response[2])


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


def test_compilation_inspection_does_not_infer_success_without_filter():
    result = parse_compilation_filter(fixture("compilation.txt"), "com.example.stream")
    assert result.supported and result.compiler_filter == "speed"
    unknown = parse_compilation_filter("Package [com.example.stream]", "com.example.stream")
    assert not unknown.supported and unknown.compiler_filter is None
    mixed = parse_compilation_filter(
        "[com.example.stream]\narm: [status=speed]\nx86: [status=verify]\n", "com.example.stream"
    )
    assert mixed.supported and mixed.compiler_filter == "mixed"


def test_pair_keeps_leading_zero_code_and_never_exposes_it_on_failure(tmp_path):
    runner = FakeRunner({("pair", "tv.local:37123", "001234"): (1, "pair 001234", "bad")})
    client = AdbClient(tmp_path / "instance", runner=runner)
    with pytest.raises(AdbError) as caught:
        client.pair("tv.local:37123", "001234")
    assert "001234" not in str(caught.value)
    assert runner.calls[0][0][-1] == "001234"
    assert runner.calls[0][2]["HOME"].endswith("/instance/home")
    assert (tmp_path / "instance/home/.android").is_dir()


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
            ("-s", endpoint, "shell", "getprop", "ro.serialno"): (0, "serial-a", ""),
            ("-s", endpoint, "shell", "getprop", "ro.build.fingerprint"): (0, "build-a", ""),
        }
    )
    client = AdbClient(tmp_path, runner=runner)
    chosen, identity = client.reconnect(expected_serial="serial-a", expected_fingerprint="build-a", endpoint=endpoint)
    assert chosen == endpoint
    assert identity.serial == "serial-a"
    assert client._verified_serials[endpoint] == ("serial-a", "build-a")


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
