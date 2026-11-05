"""The Claude call behind both Summarize buttons (ai_summary._call_claude),
against a fake client: the request it sends, reading the answer, and every
failure message the app can show."""
from types import SimpleNamespace

import anthropic
import httpx2
import pytest
from fastapi import HTTPException

from app import ai_summary
from app.config import settings

_REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")


def _status_error(cls, code):
    return cls("error", response=httpx2.Response(code, request=_REQUEST), body=None)


def _reply(*blocks, stop_reason="end_turn"):
    return SimpleNamespace(stop_reason=stop_reason, stop_details=None, content=list(blocks))


def _text(text):
    return SimpleNamespace(type="text", text=text)


class FakeClaude:
    """Stands in for anthropic.Anthropic; `outcome` is the reply or an exception to raise."""

    def __init__(self, outcome):
        self.outcome = outcome
        self.client_kwargs = None
        self.calls = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(create=self._create))

    def __call__(self, **kwargs):  # the constructor
        self.client_kwargs = kwargs
        return self

    def _create(self, **kwargs):
        self.calls.append(kwargs)
        if isinstance(self.outcome, BaseException):
            raise self.outcome
        return self.outcome


@pytest.fixture
def claude(monkeypatch):
    """install(outcome) -> the FakeClaude now standing in for the SDK client."""
    monkeypatch.setattr(settings, "anthropic_api_key", "test-key")

    def install(outcome) -> FakeClaude:
        fake = FakeClaude(outcome)
        monkeypatch.setattr(ai_summary.anthropic, "Anthropic", fake)
        return fake
    return install


def _error(call) -> HTTPException:
    with pytest.raises(HTTPException) as caught:
        call()
    return caught.value


def test_the_request_and_the_answer(claude):
    fake = claude(_reply(SimpleNamespace(type="thinking", thinking=""), _text("  Two sessions.  ")))
    assert ai_summary._call_claude("system prompt", "context", "test") == "Two sessions."
    call = fake.calls[0]
    assert call["model"] == ai_summary.CLAUDE_MODEL
    assert call["system"] == "system prompt"
    assert call["messages"] == [{"role": "user", "content": "context"}]
    assert call["output_config"] == {"effort": "low"}
    assert call["fallbacks"] == "default" and call["betas"] == [ai_summary._CLAUDE_FALLBACK_BETA]
    # One short attempt: both apps stop waiting after 15 s.
    assert fake.client_kwargs["timeout"] < 15 and fake.client_kwargs["max_retries"] == 0


def test_a_fallback_block_is_not_part_of_the_text(claude):
    claude(_reply(SimpleNamespace(type="fallback"), _text("Summary.")))
    assert ai_summary._call_claude("s", "c", "test") == "Summary."


@pytest.mark.parametrize("reply", [
    _reply(_text("partial"), stop_reason="refusal"),
    _reply(_text("   ")),
    _reply(),
])
def test_a_refusal_or_empty_answer_is_a_503(claude, reply):
    claude(reply)
    err = _error(lambda: ai_summary._call_claude("s", "c", "test"))
    assert err.status_code == 503 and err.detail == "Failed to generate summary"


def test_no_key_is_reported_without_calling_out(monkeypatch):
    monkeypatch.setattr(settings, "anthropic_api_key", "")
    monkeypatch.setattr(ai_summary.anthropic, "Anthropic", lambda **k: pytest.fail("built a client"))
    err = _error(lambda: ai_summary._call_claude("s", "c", "test"))
    assert err.status_code == 503 and "ANTHROPIC_API_KEY" in err.detail


@pytest.mark.parametrize("exc, expected", [
    (_status_error(anthropic.AuthenticationError, 401), "API key is invalid"),
    (_status_error(anthropic.PermissionDeniedError, 403), "isn't permitted"),
    (_status_error(anthropic.RateLimitError, 429), "rate-limited"),
    (_status_error(anthropic.InternalServerError, 500), "Failed to generate summary"),
    (anthropic.APIConnectionError(request=_REQUEST), "Could not reach the Claude API"),
    (anthropic.APITimeoutError(request=_REQUEST), "Could not reach the Claude API"),
    (
        anthropic.APIResponseValidationError(httpx2.Response(200, request=_REQUEST), body=None),
        "Failed to generate summary",
    ),
])
def test_each_failure_has_its_own_message(claude, exc, expected):
    claude(exc)
    err = _error(lambda: ai_summary._call_claude("s", "c", "test"))
    assert err.status_code == 503 and expected in err.detail


def test_the_summary_routes(client, login, add_patient, session_body, claude):
    fake = claude(_reply(_text("A 4-minute acupuncture session.")))
    h = login()
    p = add_patient(h)
    empty = client.post(f"/patients/{p}/summary", headers=h)
    assert empty.status_code == 400  # nothing recorded yet, and Claude isn't called
    assert fake.calls == []

    session_id = client.post("/sessions", json=session_body(p), headers=h).json()["id"]
    r = client.post(f"/sessions/{session_id}/summary", headers=h)
    assert r.status_code == 200 and r.json() == {"summary": "A 4-minute acupuncture session."}
    assert fake.calls[-1]["system"] == ai_summary.SESSION_SYSTEM_PROMPT
    r = client.post(f"/patients/{p}/summary", headers=h)
    assert r.status_code == 200
    assert fake.calls[-1]["system"] == ai_summary.PATIENT_SYSTEM_PROMPT


def test_another_practitioners_session_cannot_be_summarized(client, login, add_patient, session_body, claude):
    fake = claude(_reply(_text("x")))
    h1, h2 = login("one@example.com"), login("two@example.com")
    session_id = client.post("/sessions", json=session_body(add_patient(h1)), headers=h1).json()["id"]
    assert client.post(f"/sessions/{session_id}/summary", headers=h2).status_code == 404
    assert fake.calls == []
