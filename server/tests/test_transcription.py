"""Voice-marker transcription (app/transcription.py) against a fake
AssemblyAI: language mapping, the Traditional-character conversion, every
error message the app can show, the deadline, and transcript cleanup."""
from types import SimpleNamespace

import pytest
import requests
from fastapi import HTTPException

from app import transcription
from app.config import settings


class FakeResponse:
    def __init__(self, status_code=200, body=None, method="GET", url="https://api.assemblyai.com/v2/x"):
        self.status_code = status_code
        self._body = {} if body is None else body
        self.request = SimpleNamespace(method=method)
        self.url = url
        self.text = str(self._body)

    @property
    def ok(self):
        return self.status_code < 400

    def json(self):
        return self._body


class FakeAssemblyAI:
    """Just enough of the upload -> submit -> poll API, plus the key probe."""

    def __init__(self, text="Needles in at LI4.", statuses=("processing", "completed"), error=None,
                 upload_status=200, upload_error=None, submit_body=None, probe_status=200, delete_ok=True):
        self.text, self.error = text, error
        self.statuses = list(statuses)
        self.upload_status, self.upload_error = upload_status, upload_error
        self.submit_body = {"id": "t1"} if submit_body is None else submit_body
        self.probe_status = probe_status
        self.delete_ok = delete_ok
        self.uploaded = None
        self.submitted = None
        self.probed = False
        self.deleted = []

    def delete(self, url, headers=None, timeout=None):
        self.deleted.append(url.rsplit("/", 1)[-1])
        return FakeResponse(200 if self.delete_ok else 400, {}, "DELETE", url)

    def post(self, url, headers=None, data=None, json=None, timeout=None):
        if url.endswith("/upload"):
            if self.upload_error:
                raise self.upload_error
            self.uploaded = data
            return FakeResponse(self.upload_status, {"upload_url": "https://cdn.example/u1"}, "POST", url)
        self.submitted = json
        return FakeResponse(200, self.submit_body, "POST", url)

    def get(self, url, params=None, headers=None, timeout=None):
        if params:  # _key_rejected's probe
            self.probed = True
            return FakeResponse(self.probe_status, {}, "GET", url)
        status = self.statuses.pop(0) if len(self.statuses) > 1 else self.statuses[0]
        return FakeResponse(200, {"status": status, "text": self.text, "error": self.error}, "GET", url)


@pytest.fixture
def cleanups(monkeypatch):
    """Records the transcript deletes that would run, without running them."""
    submitted = []
    monkeypatch.setattr(transcription, "_cleanup", SimpleNamespace(submit=lambda fn, *args: submitted.append(args[0])))
    return submitted


@pytest.fixture
def service(monkeypatch, cleanups):
    """install(fake) wires a FakeAssemblyAI in place of the network."""
    monkeypatch.setattr(settings, "assemblyai_api_key", "test-key")
    monkeypatch.setattr(transcription, "_POLL_SECONDS", 0.0)

    def install(fake: FakeAssemblyAI) -> FakeAssemblyAI:
        monkeypatch.setattr(transcription.requests, "post", fake.post)
        monkeypatch.setattr(transcription.requests, "get", fake.get)
        monkeypatch.setattr(transcription.requests, "delete", fake.delete)
        return fake
    return install


def _error(call) -> HTTPException:
    with pytest.raises(HTTPException) as caught:
        call()
    return caught.value


def test_english_is_the_default_and_gets_english_keyterms(service, cleanups):
    fake = service(FakeAssemblyAI())
    assert transcription.transcribe_marker_audio(b"audio", None) == "Needles in at LI4."
    assert fake.uploaded == b"audio"
    assert fake.submitted["language_code"] == "en"
    assert "ST36" in fake.submitted["keyterms_prompt"]
    # Deleted before answering (Cloud Run throttles work left for after).
    assert fake.deleted == ["t1"] and cleanups == []


def test_a_failed_delete_is_retried_in_the_background(service, cleanups):
    fake = service(FakeAssemblyAI(delete_ok=False))
    assert transcription.transcribe_marker_audio(b"audio", None) == "Needles in at LI4."
    assert fake.deleted == ["t1"] and cleanups == ["t1"]


def test_simplified_mandarin_comes_back_unchanged(service):
    fake = service(FakeAssemblyAI(text="在合谷下针，患者说头痛减轻了。"))
    assert transcription.transcribe_marker_audio(b"a", "cmn-Hans-CN") == "在合谷下针，患者说头痛减轻了。"
    assert fake.submitted["language_code"] == "zh"
    assert "合谷" in fake.submitted["keyterms_prompt"]


def test_traditional_mandarin_is_converted(service):
    service(FakeAssemblyAI(text="在合谷下针，患者说头痛减轻了。"))
    assert transcription.transcribe_marker_audio(b"a", "cmn-Hant-TW") == "在合谷下針，患者說頭痛減輕了。"


def test_keyterms_stay_within_assemblyai_limits():
    for terms in transcription._KEYTERMS.values():
        assert len(terms) <= 200  # Universal-2's limit, the lower of the two
        assert all(len(t.split()) <= 6 for t in terms)


@pytest.mark.parametrize("fake", [
    FakeAssemblyAI(statuses=("error",), error="Transcoding failed"),
    FakeAssemblyAI(text="   "),
])
def test_nothing_usable_heard_is_didnt_catch_that(service, fake):
    service(fake)
    err = _error(lambda: transcription.transcribe_marker_audio(b"a", None))
    assert err.status_code == 422 and "Didn't catch that" in err.detail


def test_no_key_is_reported_without_calling_out(monkeypatch):
    monkeypatch.setattr(settings, "assemblyai_api_key", "")
    monkeypatch.setattr(transcription.requests, "post", lambda *a, **k: pytest.fail("called the network"))
    err = _error(lambda: transcription.transcribe_marker_audio(b"a", None))
    assert err.status_code == 503 and "ASSEMBLYAI_API_KEY" in err.detail


def test_a_rejected_key_says_so(service):
    service(FakeAssemblyAI(upload_status=401))
    err = _error(lambda: transcription.transcribe_marker_audio(b"a", None))
    assert err.status_code == 503 and "API key is invalid" in err.detail


def test_a_rejected_key_that_drops_the_upload_still_says_so(service):
    """AssemblyAI answers 401 before reading the audio and closes the
    connection, which often arrives as a dropped connection instead."""
    fake = service(FakeAssemblyAI(upload_error=requests.ConnectionError("Connection aborted."), probe_status=401))
    err = _error(lambda: transcription.transcribe_marker_audio(b"a", None))
    assert fake.probed
    assert err.status_code == 503 and "API key is invalid" in err.detail


def test_a_real_outage_says_could_not_reach(service):
    service(FakeAssemblyAI(upload_error=requests.ConnectionError("down"), probe_status=200))
    err = _error(lambda: transcription.transcribe_marker_audio(b"a", None))
    assert err.status_code == 503 and "Could not reach" in err.detail


def test_an_unexpected_response_is_a_503_not_a_crash(service, cleanups):
    service(FakeAssemblyAI(submit_body={"no": "id"}))
    err = _error(lambda: transcription.transcribe_marker_audio(b"a", None))
    assert err.status_code == 503 and err.detail == "Failed to transcribe audio"
    assert cleanups == []  # no transcript id came back, so nothing to delete


def test_a_slow_transcript_gives_up_and_is_still_cleaned_up(service, cleanups, monkeypatch):
    monkeypatch.setattr(transcription, "_DEADLINE_SECONDS", 0.2)
    monkeypatch.setattr(transcription, "_POLL_SECONDS", 0.05)
    fake = service(FakeAssemblyAI(statuses=("processing",)))
    err = _error(lambda: transcription.transcribe_marker_audio(b"a", None))
    assert err.status_code == 503 and "taking too long" in err.detail
    # Still processing, so not deletable yet: straight to the background retries.
    assert fake.deleted == [] and cleanups == ["t1"]


def test_the_route_forwards_audio_and_language(client, login, service):
    fake = service(FakeAssemblyAI(text="患者说头痛减轻了"))
    r = client.post(
        "/sessions/transcribe", headers=login(), data={"language": "cmn-Hant-TW"},
        files={"audio": ("marker.aac", b"clip-bytes", "audio/aac")},
    )
    assert r.status_code == 200
    assert r.json() == {"text": "患者說頭痛減輕了"}
    assert fake.uploaded == b"clip-bytes"


def test_the_route_refuses_empty_audio(client, login, service):
    fake = service(FakeAssemblyAI())
    r = client.post("/sessions/transcribe", headers=login(), files={"audio": ("marker.aac", b"", "audio/aac")})
    assert r.status_code == 400
    assert fake.uploaded is None  # refused before anything was sent
