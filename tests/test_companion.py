import hashlib
import json
from contextlib import contextmanager
from io import BytesIO

import pytest

import tvcompiler.companion as companion


class FakeResponse:
    def __init__(self, body, *, status=200, url=companion.COMPANION_URL, headers=None):
        self._body = BytesIO(body)
        self.status = status
        self.headers = headers or {}
        self._url = url

    def geturl(self):
        return self._url

    def read(self, size=-1):
        return self._body.read(size)

    def read1(self, size=-1):
        return self.read(size)


def test_download_verifies_and_reuses_only_private_pinned_cache(tmp_path, monkeypatch):
    body = b"apk"
    monkeypatch.setattr(companion, "COMPANION_SIZE", len(body))
    monkeypatch.setattr(companion, "COMPANION_SHA256", hashlib.sha256(body).hexdigest())
    calls = []

    def opener(url, timeout):
        calls.append((url, timeout))
        return FakeResponse(body, headers={"Content-Length": str(len(body))})

    manager = companion.CompanionArtifactManager(tmp_path, opener=opener)
    artifact = manager.download()
    assert artifact.path.read_bytes() == body
    assert artifact.path.stat().st_mode & 0o777 == 0o600
    assert manager.cached() == artifact
    assert len(calls) == 1 and calls[0][0] == companion.COMPANION_URL
    assert companion.verify_companion_artifact(artifact, tmp_path) == artifact.path


def test_official_opener_passes_network_timeout_as_keyword(tmp_path, monkeypatch):
    captured = {}

    class FakeOpener:
        def open(self, request, *, timeout):
            captured["url"] = request.full_url
            captured["timeout"] = timeout
            return FakeResponse(b"unused")

    monkeypatch.setattr(companion.urllib.request, "build_opener", lambda *_handlers: FakeOpener())
    manager = companion.CompanionArtifactManager(tmp_path, timeout=17)
    companion.CompanionArtifactManager._open_release(companion.COMPANION_URL, manager.timeout)
    assert captured == {"url": companion.COMPANION_URL, "timeout": 17}


def test_download_enforces_one_total_budget_across_slow_trickle_reads(tmp_path, monkeypatch):
    body = b"good"
    monkeypatch.setattr(companion, "COMPANION_SIZE", len(body))
    monkeypatch.setattr(companion, "COMPANION_SHA256", hashlib.sha256(body).hexdigest())
    now = [0.0]

    class SlowTrickleResponse(FakeResponse):
        def __init__(self):
            super().__init__(body)

        def read(self, _size=-1):
            now[0] += 0.6
            return body[0:1]

    manager = companion.CompanionArtifactManager(
        tmp_path,
        opener=lambda _url, _timeout: SlowTrickleResponse(),
        timeout=1.0,
        clock=lambda: now[0],
    )
    with pytest.raises(RuntimeError, match="could not retrieve"):
        manager.download()
    assert list(manager.cache_dir.iterdir()) == []
    assert now[0] >= 1.0


def test_download_rejects_oversize_wrong_hash_and_untrusted_redirect(tmp_path, monkeypatch):
    body = b"good"
    monkeypatch.setattr(companion, "COMPANION_SIZE", len(body))
    monkeypatch.setattr(companion, "COMPANION_SHA256", hashlib.sha256(body).hexdigest())
    manager = companion.CompanionArtifactManager(tmp_path, opener=lambda _url, _timeout: FakeResponse(b"good!"))
    with pytest.raises(RuntimeError, match="could not retrieve"):
        manager.download()
    assert list(manager.cache_dir.iterdir()) == []

    wrong_hash = companion.CompanionArtifactManager(
        tmp_path / "wrong", opener=lambda _url, _timeout: FakeResponse(b"bad!")
    )
    with pytest.raises(RuntimeError, match="could not retrieve"):
        wrong_hash.download()

    redirect = companion.CompanionArtifactManager(
        tmp_path / "redirect",
        opener=lambda _url, _timeout: FakeResponse(body, url="http://attacker.invalid/file"),
    )
    with pytest.raises(RuntimeError, match="could not retrieve"):
        redirect.download()


class FakeConnection:
    def __init__(self, host, port, *, timeout, status=200, payload=b'{"success":true}'):
        self.host, self.port, self.timeout = host, port, timeout
        self.status = status
        self.payload = payload
        self.requests = []
        self.closed = False

    def request(self, method, path, *, body, headers):
        self.requests.append((method, path, body, headers))

    def getresponse(self):
        return self

    def read(self, size):
        return self.payload[:size]

    def close(self):
        self.closed = True


class FakeAdb:
    def __init__(self, port=45555):
        self.port = port
        self.forward_calls = 0

    @contextmanager
    def companion_forward(self, serial):
        self.forward_calls += 1
        yield self.port


def test_bridge_uses_only_loopback_api_and_sanitizes_status(tmp_path):
    adb = FakeAdb()
    connections = []

    def factory(host, port, *, timeout):
        connection = FakeConnection(
            host,
            port,
            timeout=timeout,
            payload=json.dumps(
                {
                    "isPaired": True,
                    "targetPort": 5555,
                    "currentPort": 5555,
                    "webServerEnabled": False,
                    "lastStatus": "pairing code 123456",
                    "adb5555Available": True,
                    "private": "drop",
                }
            ).encode(),
        )
        connections.append(connection)
        return connection

    bridge = companion.CompanionBridge(adb, "serial", connection_factory=factory)
    assert bridge.status() == {"isPaired": True, "webServerEnabled": False, "currentPort": 5555, "targetPort": 5555}
    connection = connections[0]
    assert (connection.host, connection.port) == ("127.0.0.1", 45555)
    assert connection.requests[0][:2] == ("GET", "/api/status")
    assert "pairing code" not in str(bridge.status())
    assert adb.forward_calls == 2


@pytest.mark.parametrize("status,payload", [(302, b""), (200, b"x" * (64 * 1024 + 1))])
def test_bridge_rejects_redirects_and_oversize_response_without_leaking_pair_code(status, payload):
    code = "001234"
    bridge = companion.CompanionBridge(
        FakeAdb(),
        "serial",
        connection_factory=lambda host, port, *, timeout: FakeConnection(
            host, port, timeout=timeout, status=status, payload=payload
        ),
    )
    with pytest.raises(RuntimeError) as error:
        bridge.pair(code, 37123)
    assert code not in str(error.value)


def test_bridge_timeout_does_not_expose_pairing_code():
    code = "001234"

    def timeout_factory(_host, _port, *, timeout):
        raise TimeoutError(f"timed out while sending {code} after {timeout}")

    bridge = companion.CompanionBridge(FakeAdb(), "serial", connection_factory=timeout_factory)
    with pytest.raises(RuntimeError) as error:
        bridge.pair(code, 37123)
    assert code not in str(error.value)


def test_bridge_validates_fields_and_uses_exact_mutation_endpoints():
    connections = []

    def factory(host, port, *, timeout):
        connection = FakeConnection(host, port, timeout=timeout)
        connections.append(connection)
        return connection

    bridge = companion.CompanionBridge(FakeAdb(), "serial", connection_factory=factory)
    bridge.set_target_port(5555)
    bridge.pair("001234", 37123)
    bridge.disable_webserver()
    assert [connection.requests[0][1] for connection in connections] == ["/api/port", "/api/pair", "/api/webserver"]
    assert connections[1].requests[0][2] == "code=001234&port=37123"
    assert connections[2].requests[0][2] == "enabled=false"
    for port in [0, 9093, 65536, True, "9090"]:
        with pytest.raises(ValueError):
            bridge.set_target_port(port)
    with pytest.raises(ValueError):
        bridge.pair("x123", 37123)


def test_cached_corruption_is_rejected_instead_of_installed(tmp_path, monkeypatch):
    body = b"good"
    monkeypatch.setattr(companion, "COMPANION_SIZE", len(body))
    monkeypatch.setattr(companion, "COMPANION_SHA256", hashlib.sha256(body).hexdigest())
    manager = companion.CompanionArtifactManager(tmp_path, opener=lambda _url, _timeout: FakeResponse(body))
    artifact = manager.download()
    artifact.path.write_bytes(b"evil")
    with pytest.raises(RuntimeError, match="checksum"):
        manager.cached()
    assert manager.download().path.read_bytes() == body
