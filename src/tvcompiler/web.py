"""Authenticated FastAPI dashboard and constrained HTTP integration."""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import sqlite3
import tempfile
import threading
import time
from contextlib import asynccontextmanager, closing
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, ConfigDict, Field

from .adb import AdbClient, validate_endpoint
from .scheduler import Scheduler
from .store import Store
from .validation import validate_package_id

PACKAGE_LABELS = {"com.nuvio.tv": "Nuvio", "com.nuvio.tv.test": "Nuvio Test"}
COOKIE_NAME = "tvcompiler_session"
CSRF_COOKIE = "tvcompiler_csrf"
SESSION_AGE_SECONDS = 12 * 60 * 60


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SetupInput(Input):
    token: str = Field(max_length=256)
    password: str = Field(max_length=256)
    csrf: str = Field(min_length=32, max_length=128)
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=False)


class LoginInput(Input):
    password: str = Field(min_length=1, max_length=256)
    csrf: str = Field(min_length=32, max_length=128)
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=False)


class DeviceInput(Input):
    name: str = Field(min_length=1, max_length=80)
    endpoint: str = Field(min_length=3, max_length=300)


class PairInput(DeviceInput):
    pairing_endpoint: str = Field(min_length=3, max_length=300)
    pairing_code: str = Field(min_length=4, max_length=12, pattern=r"^\d{4,12}$")


class ReconnectInput(Input):
    endpoint: str = Field(min_length=3, max_length=300)


class DeviceUpdate(Input):
    name: str | None = Field(default=None, min_length=1, max_length=80)
    endpoint: str | None = Field(default=None, min_length=3, max_length=300)
    enabled: bool | None = None


class WatchInput(Input):
    package_id: str = Field(min_length=3, max_length=255)
    initial_compile: bool = False


class ManualInput(Input):
    package_id: str = Field(min_length=3, max_length=255)
    foreground_override: bool = False


class MonitoringInput(Input):
    enabled: bool


class SettingsInput(Input):
    poll_interval_seconds: int = Field(ge=5, le=3600)
    max_attempts: int = Field(ge=1, le=8)
    window_start: str | None = Field(default=None, max_length=5)
    window_end: str | None = Field(default=None, max_length=5)
    timezone: str = Field(default="UTC", min_length=1, max_length=80)


class Security:
    """Small persistent auth store plus bounded process-local failed-login throttling."""

    def __init__(self, db_path: Path, *, bootstrap_token: str | None = None):
        self.db_path = db_path
        self.bootstrap_token = bootstrap_token
        self.lock = threading.RLock()
        self.failures: dict[tuple[str, str], tuple[int, float]] = {}
        self.db_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.db_path.parent, 0o700)
        with closing(self._connect()) as db:
            db.executescript(
                "CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY CHECK(id=1), "
                "salt BLOB NOT NULL, hash BLOB NOT NULL);"
                "CREATE TABLE IF NOT EXISTS sessions (token_hash BLOB PRIMARY KEY, "
                "csrf_hash BLOB NOT NULL, expires_at TEXT NOT NULL);"
                "CREATE INDEX IF NOT EXISTS session_expiry_idx ON sessions(expires_at);"
            )
        os.chmod(self.db_path, 0o600)

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.db_path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=10000")
        return db

    @staticmethod
    def _digest(token: str) -> bytes:
        return hashlib.sha256(token.encode("utf-8")).digest()

    @staticmethod
    def _password(password: str, salt: bytes) -> bytes:
        return hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2**14, r=8, p=1, dklen=32)

    def setup(self, token: str, password: str, csrf_cookie: str, csrf: str) -> bool:
        if len(password) < 12 or not hmac.compare_digest(csrf_cookie.encode(), csrf.encode()):
            return False
        with self.lock, closing(self._connect()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                if db.execute("SELECT 1 FROM users WHERE id=1").fetchone():
                    db.rollback()
                    return False
                expected = self.bootstrap_token
                if expected is None or not hmac.compare_digest(token.encode(), expected.encode()):
                    db.rollback()
                    return False
                salt = secrets.token_bytes(16)
                db.execute("INSERT INTO users(id,salt,hash) VALUES(1,?,?)", (salt, self._password(password, salt)))
                db.commit()
            except BaseException:
                db.rollback()
                raise
        return True

    def login(self, password: str) -> bool:
        with closing(self._connect()) as db:
            row = db.execute("SELECT salt,hash FROM users WHERE id=1").fetchone()
        # Do comparable KDF work on an unconfigured system to reduce state disclosure.
        salt = row["salt"] if row else bytes(16)
        actual = self._password(password, salt)
        return bool(row and hmac.compare_digest(actual, row["hash"]))

    def create_session(self) -> tuple[str, str, str]:
        token, csrf = secrets.token_urlsafe(40), secrets.token_urlsafe(32)
        expires = (datetime.now(UTC) + timedelta(seconds=SESSION_AGE_SECONDS)).isoformat()
        with closing(self._connect()) as db:
            db.execute("DELETE FROM sessions WHERE expires_at < ?", (datetime.now(UTC).isoformat(),))
            db.execute(
                "INSERT INTO sessions(token_hash,csrf_hash,expires_at) VALUES(?,?,?)",
                (self._digest(token), self._digest(csrf), expires),
            )
        return token, csrf, expires

    def session(self, token: str | None) -> tuple[str, str] | None:
        if not token:
            return None
        with closing(self._connect()) as db:
            row = db.execute(
                "SELECT csrf_hash,expires_at FROM sessions WHERE token_hash=?", (self._digest(token),)
            ).fetchone()
        if not row or row["expires_at"] <= datetime.now(UTC).isoformat():
            return None
        return token, row["csrf_hash"].hex()

    def csrf_valid(self, token: str | None, supplied: str | None) -> bool:
        current = self.session(token)
        return bool(current and supplied and hmac.compare_digest(current[1], self._digest(supplied).hex()))

    def revoke(self, token: str | None) -> None:
        if token:
            with closing(self._connect()) as db:
                db.execute("DELETE FROM sessions WHERE token_hash=?", (self._digest(token),))

    def configured(self) -> bool:
        with closing(self._connect()) as db:
            return db.execute("SELECT 1 FROM users WHERE id=1").fetchone() is not None

    def throttled(self, request: Request, action: str) -> bool:
        # Use only the ASGI peer address. Forwarded headers are untrusted and ignored.
        key = (request.client.host if request.client else "unknown", action)
        now = time.monotonic()
        with self.lock:
            self._trim(now)
            count, started = self.failures.get(key, (0, now))
            return count >= 5 and now - started < 300

    def failed(self, request: Request, action: str) -> None:
        key = (request.client.host if request.client else "unknown", action)
        now = time.monotonic()
        with self.lock:
            self._trim(now)
            count, started = self.failures.get(key, (0, now))
            if now - started >= 300:
                count, started = 0, now
            self.failures[key] = (count + 1, started)

    def cleared(self, request: Request, action: str) -> None:
        with self.lock:
            self.failures.pop((request.client.host if request.client else "unknown", action), None)

    def _trim(self, now: float) -> None:
        expired = [key for key, (_, started) in self.failures.items() if now - started >= 300]
        for key in expired:
            self.failures.pop(key, None)
        if len(self.failures) > 2048:
            for key in sorted(self.failures, key=lambda item: self.failures[item][1])[: len(self.failures) - 1024]:
                self.failures.pop(key, None)


def _bootstrap(instance_dir: Path, environ: dict[str, str]) -> tuple[str, Path | None]:
    supplied = environ.get("TVCOMPILER_BOOTSTRAP_TOKEN")
    if supplied:
        if len(supplied) < 32 or len(supplied) > 256:
            raise ValueError("TVCOMPILER_BOOTSTRAP_TOKEN must be 32-256 characters")
        return supplied, None
    path = instance_dir / "bootstrap.token"
    instance_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = None
    try:
        token = secrets.token_urlsafe(32)
        fd, temporary = tempfile.mkstemp(prefix=".bootstrap-", dir=instance_dir, text=True)
        os.chmod(temporary, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(token)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            os.link(temporary, path)
        except FileExistsError:
            pass
    except FileExistsError:
        pass
    finally:
        if temporary:
            Path(temporary).unlink(missing_ok=True)
    try:
        token = path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise RuntimeError("Unable to read the first-run bootstrap token") from exc
    if len(token) < 32 or len(token) > 256:
        raise RuntimeError("The first-run bootstrap token is invalid")
    os.chmod(path, 0o600)
    return token, path


def create_app(
    instance_dir: str | Path = "./instance",
    *,
    adb: AdbClient | None = None,
    scheduler: Scheduler | None = None,
    start_scheduler: bool = True,
    secure_cookies: bool | None = None,
    environ: dict[str, str] | None = None,
) -> FastAPI:
    base = Path(instance_dir).resolve()
    base.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        os.chmod(base, 0o700)
    except OSError:
        pass
    env = os.environ if environ is None else environ
    store = Store(base / "state.sqlite3")
    client = adb or AdbClient(base)
    jobs = scheduler or Scheduler(store, client, base)
    security = Security(base / "auth.sqlite3")
    token_path = None
    if not security.configured():
        token, token_path = _bootstrap(base, env)
        security.bootstrap_token = token
    templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))
    secure = (env.get("TVCOMPILER_COOKIE_SECURE", "0") == "1") if secure_cookies is None else secure_cookies
    public_origin = env.get("TVCOMPILER_PUBLIC_ORIGIN")
    if public_origin:
        parsed_origin = urlsplit(public_origin)
        if parsed_origin.scheme not in {"http", "https"} or not parsed_origin.netloc or parsed_origin.path:
            raise ValueError("TVCOMPILER_PUBLIC_ORIGIN must be an origin URL without a path")
        expected_origin = (parsed_origin.scheme.lower(), parsed_origin.netloc.lower())
    else:
        expected_origin = None

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if start_scheduler:
            jobs.start()
        try:
            yield
        finally:
            if start_scheduler:
                jobs.stop(timeout=5)

    app = FastAPI(title="Android TV Speed Compiler", docs_url=None, redoc_url=None, lifespan=lifespan)
    app.state.store, app.state.adb, app.state.scheduler, app.state.security = store, client, jobs, security
    app.mount("/static", StaticFiles(directory=str(Path(__file__).parent / "static")), name="static")

    def current(request: Request) -> tuple[str, str] | None:
        return security.session(request.cookies.get(COOKIE_NAME))

    def require_auth(request: Request) -> tuple[str, str]:
        value = current(request)
        if not value:
            raise HTTPException(status_code=401, detail="Sign in required")
        return value

    def same_origin(request: Request) -> bool:
        origin = request.headers.get("origin")
        fetch_site = request.headers.get("sec-fetch-site", "")
        if fetch_site.lower() == "cross-site":
            return False
        if origin:
            parsed = urlsplit(origin)
            scheme, netloc = expected_origin or (
                request.url.scheme.lower(),
                request.headers.get("host", "").lower(),
            )
            return parsed.netloc.lower() == netloc and parsed.scheme.lower() == scheme
        return True

    def verify_csrf(request: Request, _user=Depends(require_auth)):  # noqa: B008
        supplied = request.headers.get("x-csrf-token")
        if not same_origin(request) or not security.csrf_valid(request.cookies.get(COOKIE_NAME), supplied):
            raise HTTPException(status_code=403, detail="Request verification failed")

    def bootstrap_csrf(request: Request, data: dict) -> None:
        supplied = request.headers.get("x-csrf-token") or data.get("csrf")
        cookie = request.cookies.get(CSRF_COOKIE, "")
        if (
            not same_origin(request)
            or len(cookie) < 32
            or not supplied
            or not hmac.compare_digest(cookie.encode(), supplied.encode())
        ):
            raise HTTPException(status_code=403, detail="Request verification failed")

    def safe_error(exc: Exception) -> str:
        message = str(exc).lower()
        if isinstance(exc, FileNotFoundError):
            return "ADB is unavailable. Install Android platform-tools and restart the service."
        if "unauthorized" in message or "authorization" in message or "revoked" in message:
            return "TV authorization is missing. Approve the Wireless debugging prompt, then reconnect."
        if "offline" in message or "connect" in message or "no tls" in message:
            return "TV is offline. Check Wireless debugging and the TV connection endpoint."
        if "identity" in message or "fingerprint" in message or "serial" in message:
            return "Connected device identity did not match the saved TV. Check its build and reconnect."
        if isinstance(exc, ValueError):
            return str(exc)[:180]
        return "TV operation failed. Check the TV network, authorization, and endpoint."

    def api_error(exc: Exception, status_code: int = 400) -> JSONResponse:
        return JSONResponse({"error": safe_error(exc)}, status_code=status_code)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, _exc: RequestValidationError):
        action = "setup" if request.url.path == "/api/setup" else "login" if request.url.path == "/api/login" else None
        if action:
            security.failed(request, action)
            if security.throttled(request, action):
                return JSONResponse({"error": "Too many credential attempts. Try again later."}, status_code=429)
        return JSONResponse({"error": "Invalid request fields."}, status_code=422)

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.get("/")
    def home(request: Request):
        logged_in = current(request) is not None
        csrf_value = current(request)
        csrf = request.cookies.get(CSRF_COOKIE) or ""
        if not csrf_value:
            csrf = secrets.token_urlsafe(32)
        response = templates.TemplateResponse(
            request=request, name="index.html", context={"authenticated": logged_in, "csrf": csrf}
        )
        if not csrf_value:
            response.set_cookie(
                CSRF_COOKIE, csrf, httponly=False, secure=secure, samesite="strict", max_age=600, path="/"
            )
        return response

    # A separately rendered setup/login page receives a double-submit token; rotation is bounded by cookie age.
    @app.get("/login", response_class=HTMLResponse)
    def login_page(request: Request):
        return home(request)

    @app.get("/api/session")
    def session_info(request: Request):
        value = current(request)
        csrf = (
            request.cookies.get(CSRF_COOKIE)
            if value and security.csrf_valid(request.cookies.get(COOKIE_NAME), request.cookies.get(CSRF_COOKIE))
            else None
        )
        return {"authenticated": bool(value), "configured": security.configured(), "csrf": csrf}

    @app.post("/api/setup")
    def setup(data: SetupInput, request: Request, response: Response):
        bootstrap_csrf(request, data.model_dump())
        if security.throttled(request, "setup"):
            raise HTTPException(status_code=429, detail="Too many setup attempts. Try again later.")
        if len(data.password) < 12 or not security.setup(
            data.token, data.password, request.cookies.get(CSRF_COOKIE, ""), data.csrf
        ):
            security.failed(request, "setup")
            raise HTTPException(status_code=400, detail="Setup failed. Check the one-time token and try again.")
        security.cleared(request, "setup")
        if token_path and token_path.exists():
            token_path.unlink(missing_ok=True)
        session_token, csrf, _expires = security.create_session()
        response.set_cookie(
            COOKIE_NAME,
            session_token,
            httponly=True,
            secure=secure,
            samesite="strict",
            max_age=SESSION_AGE_SECONDS,
            path="/",
        )
        response.set_cookie(
            CSRF_COOKIE, csrf, httponly=False, secure=secure, samesite="strict", max_age=SESSION_AGE_SECONDS, path="/"
        )
        return {"ok": True, "csrf": csrf}

    @app.post("/api/login")
    def login(data: LoginInput, request: Request, response: Response):
        bootstrap_csrf(request, data.model_dump())
        if security.throttled(request, "login"):
            raise HTTPException(status_code=429, detail="Too many sign-in attempts. Try again later.")
        if not security.configured() or not security.login(data.password):
            security.failed(request, "login")
            raise HTTPException(status_code=401, detail="Sign-in failed")
        security.cleared(request, "login")
        session_token, csrf, _expires = security.create_session()
        response.set_cookie(
            COOKIE_NAME,
            session_token,
            httponly=True,
            secure=secure,
            samesite="strict",
            max_age=SESSION_AGE_SECONDS,
            path="/",
        )
        response.set_cookie(
            CSRF_COOKIE, csrf, httponly=False, secure=secure, samesite="strict", max_age=SESSION_AGE_SECONDS, path="/"
        )
        return {"ok": True, "csrf": csrf}

    @app.post("/api/logout")
    def logout(request: Request, _auth=Depends(verify_csrf)):  # noqa: B008
        security.revoke(request.cookies.get(COOKIE_NAME))
        response = JSONResponse({"ok": True})
        response.delete_cookie(COOKIE_NAME, path="/")
        return response

    @app.get("/api/status")
    def status(_auth=Depends(require_auth)):  # noqa: B008
        result = jobs.status()
        result["jobs"] = [
            {
                "id": j.id,
                "device_id": j.device_id,
                "package_id": j.package_id,
                "state": j.state,
                "attempts": j.attempts,
                "reason": j.reason,
                "created_at": j.created_at,
                "updated_at": j.updated_at,
                "manual": j.manual,
                "foreground_override": j.manual_override,
            }
            for j in result["jobs"]
        ]
        result["last_poll_error"] = (result.get("last_poll_error") or "")[:400]
        result["scheduler_error"] = (result.get("scheduler_error") or "")[:400]
        return result

    @app.get("/api/devices")
    def devices(_auth=Depends(require_auth)):  # noqa: B008
        return [
            {
                "id": d.id,
                "name": d.name,
                "endpoint": d.endpoint,
                "serial": d.serial,
                "enabled": d.enabled,
                "last_seen_at": d.last_seen_at,
                "apps": [
                    {
                        "package_id": a.package_id,
                        "label": a.label or PACKAGE_LABELS.get(a.package_id, a.package_id),
                        "version_name": a.version_name,
                        "version_code": a.version_code,
                        "enabled": a.enabled,
                    }
                    for a in store.list_apps(d.id)
                ],
            }
            for d in store.list_devices()
        ]

    @app.post("/api/discover")
    def discover(_auth=Depends(verify_csrf)):  # noqa: B008
        try:
            return [
                {"kind": item.kind, "service": item.service, "endpoint": item.endpoint} for item in client.discover()
            ]
        except Exception as exc:
            return api_error(exc)

    def add_device(data: DeviceInput, *, pairing_endpoint: str | None = None, pairing_code: str | None = None):
        try:
            endpoint = validate_endpoint(data.endpoint)
            if pairing_endpoint is not None:
                pairing_endpoint = validate_endpoint(pairing_endpoint)
                client.pair(pairing_endpoint, pairing_code or "")
            client.connect(endpoint)
            identity = client.identity(endpoint)
            device_id = uuid4().hex
            device = store.upsert_device(device_id, data.name, endpoint, identity.serial, identity.build_fingerprint)
            return {
                "id": device.id,
                "name": device.name,
                "endpoint": device.endpoint,
                "serial": device.serial,
                "enabled": device.enabled,
            }
        except Exception as exc:
            return api_error(exc)

    @app.post("/api/pair")
    def pair(data: PairInput, _auth=Depends(verify_csrf)):  # noqa: B008
        return add_device(data, pairing_endpoint=data.pairing_endpoint, pairing_code=data.pairing_code)

    @app.post("/api/devices")
    def add_paired(data: DeviceInput, _auth=Depends(verify_csrf)):  # noqa: B008
        return add_device(data)

    @app.post("/api/devices/{device_id}/reconnect")
    def reconnect(device_id: str, data: ReconnectInput, _auth=Depends(verify_csrf)):  # noqa: B008
        device = store.get_device(device_id)
        if not device:
            raise HTTPException(status_code=404, detail="TV not found")
        try:
            endpoint = validate_endpoint(data.endpoint)
            transport, identity = client.reconnect(
                expected_serial=device.serial, expected_fingerprint=device.fingerprint, endpoint=endpoint
            )
            # ADB discovery may return a fresh endpoint; pin the refreshed connection address.
            if not store.update_device(device.id, endpoint=transport):
                raise ValueError("TV was forgotten while reconnecting")
            return {"ok": True, "endpoint": transport, "serial": identity.serial}
        except Exception as exc:
            return api_error(exc)

    @app.patch("/api/devices/{device_id}")
    def update_device(device_id: str, data: DeviceUpdate, _auth=Depends(verify_csrf)):  # noqa: B008
        device = store.get_device(device_id)
        if not device:
            raise HTTPException(status_code=404, detail="TV not found")
        if data.name is not None:
            name = data.name
        else:
            name = device.name
        if data.enabled is not None:
            store.set_device_enabled(device_id, data.enabled)
        if data.endpoint is not None:
            try:
                endpoint = validate_endpoint(data.endpoint)
                # Verify endpoint belongs to the pinned TV before saving it.
                client.reconnect(
                    expected_serial=device.serial, expected_fingerprint=device.fingerprint, endpoint=endpoint
                )
                if not store.update_device(device_id, name=name, endpoint=endpoint):
                    raise ValueError("TV was forgotten while verifying its endpoint")
            except Exception as exc:
                return api_error(exc)
        elif data.name is not None:
            if not store.update_device(device_id, name=name):
                raise ValueError("TV was forgotten while saving its name")
        return {"ok": True}

    @app.delete("/api/devices/{device_id}")
    def forget_device(device_id: str, _auth=Depends(verify_csrf)):  # noqa: B008
        if not store.get_device(device_id):
            raise HTTPException(status_code=404, detail="TV not found")
        store.forget_device(device_id)
        return {"ok": True}

    @app.get("/api/devices/{device_id}/inventory")
    def inventory(device_id: str, _auth=Depends(require_auth)):  # noqa: B008
        device = store.get_device(device_id)
        if not device or not device.enabled:
            raise HTTPException(status_code=404, detail="TV not found or paused")
        try:
            serial, _identity = jobs._connect(device)
            package_ids = client.installed_packages(serial)
            saved = {item.package_id: item for item in store.list_apps(device_id)}
            result = []
            for package_id in package_ids:
                try:
                    info = client.package_info(serial, package_id)
                except Exception:
                    info = None
                baseline = saved.get(package_id)
                result.append(
                    {
                        "package_id": package_id,
                        "label": (
                            PACKAGE_LABELS.get(package_id)
                            or (info.label if info else None)
                            or (baseline.label if baseline else None)
                            or package_id
                        ),
                        "version_name": info.version_name if info else (baseline.version_name if baseline else None),
                        "version_code": info.version_code if info else (baseline.version_code if baseline else None),
                        "watched": bool(baseline and baseline.enabled),
                    }
                )
            # Read-only inventory: do not mutate saved baselines before scheduler observes updates.
            if not store.get_device(device_id):
                raise HTTPException(status_code=404, detail="TV was removed")
            return result
        except HTTPException:
            raise
        except Exception as exc:
            return api_error(exc)

    @app.post("/api/devices/{device_id}/watch")
    def watch(device_id: str, data: WatchInput, _auth=Depends(verify_csrf)):  # noqa: B008
        try:
            validate_package_id(data.package_id)
            job = jobs.watch_app(device_id, data.package_id, initial_compile=data.initial_compile)
            app = store.get_app(device_id, data.package_id)
            if not app:
                raise ValueError("App is not installed or metadata is incomplete")
            return {
                "ok": True,
                "package_id": app.package_id,
                "label": app.label or PACKAGE_LABELS.get(app.package_id, app.package_id),
                "version_code": app.version_code,
                "baseline": True,
                "job_id": job.id if job else None,
            }
        except Exception as exc:
            return api_error(exc)

    @app.delete("/api/devices/{device_id}/watch/{package_id}")
    def unwatch(device_id: str, package_id: str, _auth=Depends(verify_csrf)):  # noqa: B008
        try:
            validate_package_id(package_id)
            if not store.get_app(device_id, package_id):
                raise HTTPException(status_code=404, detail="Watched app not found")
            store.set_app_enabled(device_id, package_id, False)
            return {"ok": True}
        except HTTPException:
            raise
        except Exception as exc:
            return api_error(exc)

    @app.post("/api/devices/{device_id}/compile")
    def compile_app(device_id: str, data: ManualInput, _auth=Depends(verify_csrf)):  # noqa: B008
        try:
            validate_package_id(data.package_id)
            job = jobs.manual_compile(device_id, data.package_id, foreground_override=data.foreground_override)
            if not job:
                raise ValueError("Select this installed app for monitoring before compiling it")
            return {
                "ok": True,
                "job_id": job.id,
                "state": job.state,
                "foreground_override": job.manual_override,
                "reason": job.reason,
            }
        except Exception as exc:
            return api_error(exc)

    @app.put("/api/monitoring")
    def monitoring(data: MonitoringInput, _auth=Depends(verify_csrf)):  # noqa: B008
        jobs.set_monitoring(data.enabled)
        return {"monitoring_enabled": store.monitoring_enabled()}

    @app.put("/api/settings")
    def settings(data: SettingsInput, _auth=Depends(verify_csrf)):  # noqa: B008
        try:
            jobs.configure_window(data.window_start, data.window_end, data.timezone)
            jobs.configure_polling(interval_seconds=data.poll_interval_seconds, max_attempts=data.max_attempts)
            return {"ok": True}
        except Exception as exc:
            return api_error(exc)

    @app.get("/api/jobs/{job_id}/events")
    def job_events(job_id: int, _auth=Depends(require_auth)):  # noqa: B008
        if not store.get_job(job_id):
            raise HTTPException(status_code=404, detail="Job not found")
        return [
            {"event": event.event, "detail": event.detail, "created_at": event.created_at}
            for event in store.list_job_events(job_id)
        ]

    @app.get("/api/diagnostics")
    def diagnostics(_auth=Depends(require_auth)):  # noqa: B008
        status = jobs.status()
        return {
            "generated_at": datetime.now(UTC).isoformat(),
            "monitoring_enabled": status["monitoring_enabled"],
            "poll_interval_seconds": status["poll_interval_seconds"],
            "last_poll_at": status["last_poll_at"],
            "maintenance_window": status["maintenance_window"],
            "devices": [
                {"id": d.id, "name": d.name, "enabled": d.enabled, "last_seen_at": d.last_seen_at}
                for d in store.list_devices()
            ],
            "jobs": [
                {
                    "id": job.id,
                    "device_id": job.device_id,
                    "package_id": job.package_id,
                    "state": job.state,
                    "attempts": job.attempts,
                    "created_at": job.created_at,
                    "updated_at": job.updated_at,
                }
                for job in store.list_jobs(limit=100)
            ],
        }

    return app


def main() -> None:
    """Run one local server process; reverse proxy deployments must set secure cookies."""
    import uvicorn

    instance = os.environ.get("TVCOMPILER_INSTANCE_DIR", "./instance")
    host = os.environ.get("TVCOMPILER_HOST", "127.0.0.1")
    port = int(os.environ.get("TVCOMPILER_PORT", "8000"))
    uvicorn.run(create_app(instance), host=host, port=port, workers=1, proxy_headers=False)
