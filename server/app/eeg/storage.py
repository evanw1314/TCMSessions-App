"""Where raw EEG recordings are kept (POST /sessions/{id}/eeg).

A local folder (EEG_RAW_DIR below) by default, as on the dev Mac; a Cloud
Storage bucket when EEG_RAW_BUCKET is set, which Cloud Run needs because its
disk is wiped with every new revision or instance. The location saved in
TcmSession.eeg_raw_path says which ("gs://bucket/name" or a file path), so
recordings stored either way keep working whatever the setting is now.
"""
import functools
import uuid
from pathlib import Path

from ..config import settings

# server/data/eeg_raw/ — local home for uploaded raw EEG (~5-15 MB/session),
# used unless EEG_RAW_BUCKET is set. Kept after analysis so an improved
# pipeline can re-run on old sessions (see models.TcmSession.eeg_raw_path).
# Gitignored like smartring.db, so these do NOT survive a fresh clone.
EEG_RAW_DIR = Path(__file__).resolve().parents[2] / "data" / "eeg_raw"

_GCS = "gs://"


def save(session_id: int, data: bytes) -> str:
    """Stores one session's recording; returns where, for eeg_raw_path."""
    if settings.eeg_raw_bucket:
        # A fresh name per upload: session ids start again at 1 whenever a
        # database is recreated, and a fixed name would silently overwrite
        # the old database's recording. The route deletes the one replaced.
        name = f"eeg_raw/session_{session_id}_{uuid.uuid4().hex[:12]}.bin"
        _client().bucket(settings.eeg_raw_bucket).blob(name).upload_from_string(
            data, content_type="application/octet-stream"
        )
        return f"{_GCS}{settings.eeg_raw_bucket}/{name}"
    # Fixed name locally: tools/eeg_variants.py finds recordings by it.
    EEG_RAW_DIR.mkdir(parents=True, exist_ok=True)
    path = EEG_RAW_DIR / f"session_{session_id}.bin"
    path.write_bytes(data)
    return str(path)


def load(location: str) -> bytes:
    """The recording's bytes. FileNotFoundError when it's gone, from either
    kind of storage, so callers handle one case."""
    if location.startswith(_GCS):
        from google.api_core.exceptions import NotFound

        bucket, name = _split(location)
        try:
            return _client().bucket(bucket).blob(name).download_as_bytes()
        except NotFound:
            raise FileNotFoundError(location) from None
    return Path(location).read_bytes()


def delete(location: str) -> None:
    """Removes a recording; one that's already gone is fine."""
    if location.startswith(_GCS):
        from google.api_core.exceptions import NotFound

        bucket, name = _split(location)
        try:
            _client().bucket(bucket).blob(name).delete()
        except NotFound:
            pass
        return
    Path(location).unlink(missing_ok=True)


def _split(location: str) -> tuple[str, str]:
    bucket, _, name = location[len(_GCS):].partition("/")
    return bucket, name


@functools.cache
def _client():
    """Made on first use, with the credentials of wherever the server runs
    (on Cloud Run, its service account). Imported only then, so a server
    keeping recordings on local disk never loads the Google libraries."""
    from google.cloud import storage

    return storage.Client()
