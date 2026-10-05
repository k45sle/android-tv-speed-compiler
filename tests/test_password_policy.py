from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from tvcompiler.web import COOKIE_NAME, CSRF_COOKIE, create_app

TOKEN = "password-policy-bootstrap-token-value-long-enough"


@pytest.mark.parametrize(
    ("mode", "environ", "base_url", "setup_fields"),
    [
        ("local", {"LOGIN_ENABLE": "true", "TVCOMPILER_LOCAL_SETUP": "1"}, "http://localhost", {}),
        (
            "token",
            {"LOGIN_ENABLE": "true", "TVCOMPILER_BOOTSTRAP_TOKEN": TOKEN},
            "http://127.0.0.1",
            {"token": TOKEN},
        ),
    ],
)
def test_setup_requires_six_characters_and_six_character_password_survives_restart(
    tmp_path, mode, environ, base_url, setup_fields
):
    instance = tmp_path / mode
    app = create_app(instance, start_scheduler=False, environ=environ)
    with TestClient(app, base_url=base_url) as client:
        page = client.get("/").text
        assert 'minlength="6"' in page
        assert "at least 6 characters" in page
        csrf = client.cookies[CSRF_COOKIE]

        too_short = client.post(
            "/api/setup",
            json={**setup_fields, "password": "12345", "csrf": csrf},
        )
        assert too_short.status_code == 400

        accepted = client.post(
            "/api/setup",
            json={**setup_fields, "password": "123456", "csrf": csrf},
        )
        assert accepted.status_code == 200
        assert COOKIE_NAME in client.cookies

    # Reopening the same instance leaves the configured account intact and usable.
    restarted = create_app(instance, start_scheduler=False, environ={"LOGIN_ENABLE": "true"})
    with TestClient(restarted, base_url="http://localhost") as client:
        client.get("/")
        assert client.get("/api/session").json()["configured"] is True
        response = client.post(
            "/api/login",
            json={"password": "123456", "csrf": client.cookies[CSRF_COOKIE]},
        )
        assert response.status_code == 200
