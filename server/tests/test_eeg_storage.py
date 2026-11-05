"""Raw EEG storage (app/eeg/storage.py): a local folder by default, a Cloud
Storage bucket when EEG_RAW_BUCKET is set - and the startup guards that stop
a Cloud Run deploy from exposing or quietly losing data."""
import struct

import numpy as np
import pytest
from google.api_core.exceptions import NotFound

from app.config import Settings, harden_settings, settings
from app.database import SessionLocal
from app.eeg import storage as eeg_storage
from app.models import TcmSession


def _recording(samples: int = 512, channels: int = 4, rate: float = 256.0) -> bytes:
    """A valid upload in eeg/binary.py's format: header, then float32 samples."""
    first = 1_757_584_800_000_000
    last = first + int((samples - 1) / rate * 1_000_000)
    header = struct.pack("<4sBBHIqq", b"TEEG", 1, channels, 0, samples, first, last)
    return header + np.zeros(samples * channels, dtype="<f4").tobytes()


class FakeGcs:
    """Just enough of google.cloud.storage.Client: bucket(b).blob(n) and its three calls."""

    def __init__(self):
        self.objects: dict[tuple[str, str], bytes] = {}

    def bucket(self, bucket):
        store = self

        class _Blob:
            def __init__(self, name):
                self.key = (bucket, name)

            def upload_from_string(self, data, content_type=None):
                store.objects[self.key] = data

            def download_as_bytes(self):
                if self.key not in store.objects:
                    raise NotFound("gone")
                return store.objects[self.key]

            def delete(self):
                if self.key not in store.objects:
                    raise NotFound("gone")
                del store.objects[self.key]

        return type("_Bucket", (), {"blob": lambda _self, name: _Blob(name)})()


@pytest.fixture
def gcs(monkeypatch):
    store = FakeGcs()
    monkeypatch.setattr(settings, "eeg_raw_bucket", "test-bucket")
    monkeypatch.setattr(eeg_storage, "_client", lambda: store)
    return store


def test_local_round_trip():
    location = eeg_storage.save(7, b"raw")
    assert location.endswith("session_7.bin")
    assert eeg_storage.load(location) == b"raw"
    eeg_storage.delete(location)
    with pytest.raises(FileNotFoundError):
        eeg_storage.load(location)
    eeg_storage.delete(location)  # already gone is fine


def test_bucket_round_trip(gcs):
    location = eeg_storage.save(7, b"raw")
    assert location.startswith("gs://test-bucket/eeg_raw/session_7_")
    assert eeg_storage.load(location) == b"raw"
    eeg_storage.delete(location)
    assert gcs.objects == {}
    with pytest.raises(FileNotFoundError):
        eeg_storage.load(location)
    eeg_storage.delete(location)  # already gone is fine


def test_each_bucket_upload_gets_its_own_name(gcs):
    """Session ids restart when a database is recreated; a fixed name would
    overwrite the old database's recording."""
    assert eeg_storage.save(1, b"a") != eeg_storage.save(1, b"b")
    assert len(gcs.objects) == 2


def _upload(client, headers, session_id, data):
    return client.post(
        f"/sessions/{session_id}/eeg", headers=headers,
        files={"eeg_data": ("eeg.bin", data, "application/octet-stream")},
    )


@pytest.fixture
def saved_session(client, login, add_patient, session_body):
    h = login()
    session_id = client.post("/sessions", json=session_body(add_patient(h)), headers=h).json()["id"]
    return h, session_id


def test_upload_and_delete_through_the_api_on_local_disk(client, saved_session):
    h, session_id = saved_session
    r = _upload(client, h, session_id, _recording())
    assert r.status_code == 200 and r.json()["sample_count"] == 512
    stored = eeg_storage.EEG_RAW_DIR / f"session_{session_id}.bin"
    assert stored.read_bytes() == _recording()
    assert client.delete(f"/sessions/{session_id}", headers=h).status_code == 204
    assert not stored.exists()


def test_a_bucket_reupload_replaces_the_old_recording(client, saved_session, gcs):
    h, session_id = saved_session
    assert _upload(client, h, session_id, _recording(512)).status_code == 200
    assert _upload(client, h, session_id, _recording(1024)).status_code == 200
    assert len(gcs.objects) == 1  # the first one was removed
    assert len(next(iter(gcs.objects.values()))) == len(_recording(1024))
    assert client.delete(f"/sessions/{session_id}", headers=h).status_code == 204
    assert gcs.objects == {}


def test_a_failed_delete_keeps_the_pointer(client, saved_session, gcs, monkeypatch):
    """A recording that couldn't be removed stays findable, not orphaned."""
    h, session_id = saved_session
    _upload(client, h, session_id, _recording())

    def refuse(location):
        raise RuntimeError("storage unavailable")

    monkeypatch.setattr(eeg_storage, "delete", refuse)
    assert client.delete(f"/sessions/{session_id}", headers=h).status_code == 204
    with SessionLocal() as db:
        assert db.get(TcmSession, session_id).eeg_raw_path.startswith("gs://test-bucket/")
    assert len(gcs.objects) == 1


def test_a_recording_missing_from_the_bucket_is_reported_as_gone(client, saved_session, gcs):
    h, session_id = saved_session
    _upload(client, h, session_id, _recording())
    gcs.objects.clear()
    r = client.post(f"/sessions/{session_id}/eeg/analyze", headers=h)
    assert r.status_code == 404
    assert "no longer on the server" in r.json()["detail"]


# --- the Cloud Run startup guards (config.harden_settings) ---

def _settings(**overrides) -> Settings:
    values = dict(
        auth_dev_mode=False, eeg_raw_bucket="pilot-eeg-raw",
        database_url="postgresql+psycopg://user:pw@/smartring?host=/cloudsql/p:r:i",
        jwt_secret="a-real-signing-key-" + "x" * 40,
    )
    values.update(overrides)
    return Settings(**values)


def test_a_correct_cloud_run_setup_starts(monkeypatch):
    monkeypatch.setenv("K_SERVICE", "smartring-api")
    harden_settings(_settings())


@pytest.mark.parametrize("override, reason", [
    ({"auth_dev_mode": True}, "AUTH_DEV_MODE"),
    ({"eeg_raw_bucket": ""}, "EEG_RAW_BUCKET"),
    ({"database_url": "sqlite:///./smartring.db"}, "SQLite"),
])
def test_cloud_run_refuses_a_setup_that_would_expose_or_lose_data(monkeypatch, override, reason):
    monkeypatch.setenv("K_SERVICE", "smartring-api")
    with pytest.raises(RuntimeError, match=reason):
        harden_settings(_settings(**override))


def test_the_guards_leave_local_development_alone(monkeypatch):
    monkeypatch.delenv("K_SERVICE", raising=False)
    harden_settings(_settings(auth_dev_mode=True, eeg_raw_bucket="", database_url="sqlite:///./smartring.db"))
