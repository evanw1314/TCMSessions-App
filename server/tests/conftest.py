"""Shared test setup. Run from server/: `.venv/bin/python -m pytest`.

Everything below is set BEFORE the app is imported, because the database
engine and settings are built at import time: a throwaway SQLite database, a
fixed signing key (so harden_settings doesn't write server/.jwt_secret), and
every outside service blanked - API keys, mail, Google sign-in, the Cloud
Storage bucket - so no test can reach, or bill, a real
service even when server/.env holds real values. Tests that exercise Claude,
AssemblyAI or Cloud Storage fake them.
"""
import os
import shutil
import tempfile
from pathlib import Path

_TMP = Path(tempfile.mkdtemp(prefix="smartring-tests-"))
os.environ["DATABASE_URL"] = f"sqlite:///{(_TMP / 'test.db').as_posix()}"
os.environ["AUTH_DEV_MODE"] = "true"
os.environ["JWT_SECRET"] = "tests-only-signing-key-not-a-secret-0123456789"
for name in (
    "ANTHROPIC_API_KEY", "ASSEMBLYAI_API_KEY",
    "SMTP_HOST", "SMTP_USER", "SMTP_PASSWORD", "EMAIL_FROM",
    "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_ID_ANDROID", "EEG_RAW_BUCKET",
):
    os.environ[name] = ""
os.environ.pop("K_SERVICE", None)  # not "running on Cloud Run", whatever the shell says

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app.config import settings  # noqa: E402
from app.database import Base, engine  # noqa: E402
from app.eeg import storage as eeg_storage  # noqa: E402
from app.main import app  # noqa: E402

# Stop before any test runs if a real key got through (e.g. a future
# settings change that prefers .env over the environment).
assert not settings.anthropic_api_key and not settings.assemblyai_api_key, "tests must not see real API keys"
assert not settings.smtp_host, "tests must not be able to send mail"
assert not settings.eeg_raw_bucket, "tests must not reach a real Cloud Storage bucket"
assert settings.database_url == os.environ["DATABASE_URL"], "tests must not touch the real database"


def pytest_sessionfinish(session, exitstatus):
    engine.dispose()
    shutil.rmtree(_TMP, ignore_errors=True)


@pytest.fixture(autouse=True)
def _raw_eeg_in_tmp(monkeypatch, tmp_path):
    """Raw EEG kept on disk lands in the test's own folder, never server/data/eeg_raw."""
    monkeypatch.setattr(eeg_storage, "EEG_RAW_DIR", tmp_path / "eeg_raw")


@pytest.fixture
def client():
    """The API, over a fresh, empty database for every test."""
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    with TestClient(app) as c:
        yield c


@pytest.fixture
def login(client):
    """login(email) -> auth headers for a practitioner, created on first use."""
    def _login(email: str = "practitioner@example.com") -> dict[str, str]:
        r = client.post("/auth/dev", json={"email": email, "role": "practitioner", "name": email.split("@")[0]})
        r.raise_for_status()
        return {"Authorization": f"Bearer {r.json()['access_token']}"}
    return _login


@pytest.fixture
def add_patient(client):
    """add_patient(headers, name) -> id of a name-only patient on that practitioner's roster."""
    def _add(headers: dict[str, str], name: str = "Test Patient") -> int:
        r = client.post("/patients", json={"patient_name": name}, headers=headers)
        r.raise_for_status()
        return r.json()["id"]
    return _add


@pytest.fixture
def session_body():
    """session_body(patient_id, client_session_id) -> a minimal POST /sessions body."""
    def _body(patient_id: int, client_session_id: str | None = "rec-1", **overrides) -> dict:
        body = {
            "patient_id": patient_id,
            "modality": "Acupuncture",
            "started_at": "2026-09-11T10:00:00Z",
            "ended_at": "2026-09-11T10:04:00Z",
            "markers": [{"offset_seconds": 5, "label": "needles in"}],
            "samples": [{"offset_seconds": 0, "band": "alpha", "value": 0.2}],
        }
        if client_session_id is not None:
            body["client_session_id"] = client_session_id
        body.update(overrides)
        return body
    return _body
