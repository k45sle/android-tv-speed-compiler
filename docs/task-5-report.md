# Task 5 review fixes

Resolved the two material findings from the independent final review:

- The scheduler now rechecks automatic-monitoring pause state, the maintenance window, device/app/job state, and live idle status after package metadata preflight and immediately before compile dispatch. It rechecks pause/window after the fresh idle query as well. A pause, expired window, active/unknown TV state, or ADB status error defers a claimed job with a visible reason and no consumed attempt. Manual jobs still run while monitoring is paused; only explicit foreground override bypasses both window and idle gates.
- Media parsing recognizes named Android playback states, including spaced/unspaced `PlaybackState{state=ERROR(7), ...}` wrappers and named `PLAYING` without a numeric code. Transitions such as `CONNECTING`, unknown numeric/named/malformed states, and mismatched name/code pairs remain non-idle even alongside `active=false`, inactive entries, or a zero-session hint. Explicit active evidence still defers.
- `.dockerignore` now excludes `build/`, `dist/`, `.venv*/`, and `.mypy_cache` in addition to existing exclusions.

Added deterministic regressions for pause during blocked idle and metadata checks, window expiration at dispatch, late active/playing/unknown TV state, foreground override semantics, and known/transition/unknown/malformed playback states.

Validation:

- `.venv/bin/python -m pytest -q tests/test_adb.py tests/test_scheduler.py`: 48 passed.
- `make check`: Ruff passed; 65 tests passed; wheel, source archive, and package contents passed.
- `git diff --check`: passed.

No live TV operations were performed. Docker image and browser checks are left to the parent task's final integration pass.
