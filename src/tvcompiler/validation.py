"""Input validation shared by persistence and the ADB adapter."""

import re

_PACKAGE_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+$")


def validate_package_id(package_id: str) -> str:
    if not isinstance(package_id, str) or not _PACKAGE_RE.fullmatch(package_id):
        raise ValueError("invalid Android package ID")
    return package_id
