"""Stable value objects shared by the store, ADB adapter, and later layers."""

from dataclasses import dataclass
from typing import Literal

JobState = Literal["pending", "running", "succeeded", "failed", "superseded", "cancelled"]


@dataclass(frozen=True, slots=True)
class Device:
    id: str
    name: str
    endpoint: str | None
    serial: str | None
    fingerprint: str | None
    enabled: bool
    last_seen_at: str | None


@dataclass(frozen=True, slots=True)
class AppBaseline:
    device_id: str
    package_id: str
    label: str | None
    version_name: str | None
    version_code: int | None
    last_update_time: str | None
    apk_path: str | None
    enabled: bool
    compiled_fingerprint: str | None


@dataclass(frozen=True, slots=True)
class Job:
    id: int
    device_id: str
    package_id: str
    fingerprint: str
    state: JobState
    attempts: int
    available_at: str
    reason: str | None
    created_at: str
    updated_at: str


@dataclass(frozen=True, slots=True)
class JobEvent:
    id: int
    job_id: int
    event: str
    detail: str | None
    created_at: str
