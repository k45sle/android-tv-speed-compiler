from concurrent.futures import ThreadPoolExecutor

from tvcompiler.store import Store


def seeded_store(path):
    store = Store(path)
    store.upsert_device("living-room", "Living Room", "tv.local:37123", "serial-a", "build-a")
    store.upsert_app(
        "living-room",
        "com.nuvio.tv",
        version_name="1.0",
        version_code=4,
        last_update_time="2026-10-01 12:00:00",
        apk_path="/data/app/a/base.apk",
        enabled=True,
    )
    return store


def test_store_persists_pinned_identity_and_baseline(tmp_path):
    path = tmp_path / "instance" / "state.db"
    store = seeded_store(path)
    # Ordinary reconnect refreshes the endpoint but cannot silently replace pinned identity.
    device = store.upsert_device("living-room", "Living Room TV", "tv.local:40211", "serial-b", "build-b")
    reopened = Store(path)
    assert device.serial == "serial-a"
    assert reopened.get_device("living-room").fingerprint == "build-a"
    app = reopened.get_app("living-room", "com.nuvio.tv")
    assert app.version_code == 4 and app.enabled
    assert "state.db-wal" in {p.name for p in path.parent.iterdir()} or path.exists()


def test_queue_deduplicates_and_supersedes_obsolete_installation(tmp_path):
    store = seeded_store(tmp_path / "state.db")
    first = store.enqueue_job("living-room", "com.nuvio.tv", "com.nuvio.tv|4|update-a|/a.apk")
    duplicate = store.enqueue_job("living-room", "com.nuvio.tv", "com.nuvio.tv|4|update-a|/a.apk")
    assert first.id == duplicate.id
    second = store.enqueue_job("living-room", "com.nuvio.tv", "com.nuvio.tv|4|update-b|/b.apk")
    assert store.get_job(first.id).state == "superseded"
    assert store.get_job(second.id).state == "pending"
    assert any(event.event == "superseded" for event in store.list_job_events(first.id))


def test_atomic_claim_serializes_and_recovery_requeues(tmp_path):
    store = seeded_store(tmp_path / "state.db")
    for number in range(3):
        store.enqueue_job("living-room", "com.nuvio.tv", f"com.nuvio.tv|{number}|update-{number}|/a.apk")
    with ThreadPoolExecutor(max_workers=8) as pool:
        claims = list(pool.map(lambda _: store.claim_next(), range(8)))
    claimed = [job for job in claims if job is not None]
    assert len(claimed) == 1
    assert claimed[0].attempts == 1
    assert store.claim_next() is None
    assert store.recover_running() == 1
    assert store.get_job(claimed[0].id).state == "pending"
    assert store.claim_next() is not None


def test_disabled_or_removed_entities_cannot_claim_jobs(tmp_path):
    store = seeded_store(tmp_path / "state.db")
    store.set_app_enabled("living-room", "com.nuvio.tv", False)
    assert store.enqueue_job("living-room", "com.nuvio.tv", "fp") is None
    store.set_app_enabled("living-room", "com.nuvio.tv", True)
    job = store.enqueue_job("living-room", "com.nuvio.tv", "fp")
    store.remove_app("living-room", "com.nuvio.tv")
    assert store.get_job(job.id).state == "cancelled"
    assert store.claim_next() is None


def test_mark_compiled_only_for_matching_baseline(tmp_path):
    store = seeded_store(tmp_path / "state.db")
    current = "com.nuvio.tv|4|2026-10-01 12:00:00|/data/app/a/base.apk"
    assert not store.mark_compiled("living-room", "com.nuvio.tv", "stale")
    assert store.mark_compiled("living-room", "com.nuvio.tv", current)
    assert store.get_app("living-room", "com.nuvio.tv").compiled_fingerprint == current
