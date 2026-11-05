"""Voice-marker transcription (POST /sessions/transcribe) via AssemblyAI.

Server-side rather than Android's on-device SpeechRecognizer, which has no
accent adaptation and no domain vocabulary: a practitioner speaking fluent,
regionally-accented Mandarin full of TCM terminology was heard as a
different, more common homophone.

Upload, submit, then poll until done, all blocking - the route runs this in
a worker thread so it can't stall the server while it waits. The transcript
is then deleted from AssemblyAI (retried in the background if that fails).
"""
import logging
import time
from concurrent.futures import ThreadPoolExecutor

import requests
from fastapi import HTTPException, status
from opencc import OpenCC

from .config import settings

logger = logging.getLogger("smartring.transcription")

_BASE_URL = "https://api.assemblyai.com/v2"
# Universal-3.5 Pro covers English and Mandarin; Universal-2 is AssemblyAI's
# fallback for anything it doesn't. Named rather than left to their default,
# so a change on their side can't silently swap the model.
_SPEECH_MODELS = ["universal-3-5-pro", "universal-2"]
# The apps stop waiting after 30 s (ApiClient.kt transcribeMarker). Every
# request gets only what's left of this, so the whole thing ends well inside
# that. Most clips take 2-4 s; a queued one took 11 s in testing.
_DEADLINE_SECONDS = 20.0
_POLL_SECONDS = 0.5

# Context, not instructions (AssemblyAI ignores instructions in `prompt`).
_PROMPT = "A Traditional Chinese Medicine practitioner dictating a short note during a treatment session."

# Words to favour over a more common sound-alike, which is the whole reason
# this is done server-side. Without them the acupoint 合谷
# came back as 颌骨 (jawbone). Mandarin terms are Simplified because that's
# what AssemblyAI writes; 繁體 is converted afterwards. Point codes are in both
# lists since they get said mid-sentence in either language. AssemblyAI takes
# up to 200 words on Universal-2 (1000 on 3.5 Pro), at most 6 per phrase.
_POINT_CODES = [
    "LI4", "LI11", "ST25", "ST36", "SP6", "SP9", "SP10", "PC6", "HT7", "LR3", "KI1", "KI3",
    "BL23", "BL40", "GB20", "GB34", "GV14", "GV20", "CV4", "CV6", "CV12", "Yintang",
]
_KEYTERMS = {
    "en": _POINT_CODES + [
        "acupuncture", "electroacupuncture", "moxibustion", "cupping", "gua sha", "tui na",
        "acupoint", "meridian", "qi", "de qi", "needle retention",
    ],
    "zh": _POINT_CODES + [
        "合谷", "足三里", "三阴交", "内关", "太冲", "百会", "风池", "曲池", "关元", "气海", "中脘",
        "天枢", "肾俞", "太溪", "神门", "阳陵泉", "阴陵泉", "血海", "委中", "大椎", "涌泉", "印堂",
        "针灸", "艾灸", "拔罐", "刮痧", "推拿", "电针", "耳穴", "留针", "得气", "下针", "起针",
        "经络", "穴位", "气血", "气滞", "血瘀", "脾虚", "肾虚", "阴虚", "阳虚", "湿热",
    ],
}

# The apps' language toggle (SessionActivity.kt VoiceLanguage) sends these
# BCP-47 tags -> (AssemblyAI language code, convert to Traditional?). No tag
# is the EN button.
_LANGUAGES = {
    None: ("en", False),
    "cmn-Hans-CN": ("zh", False),
    "cmn-Hant-TW": ("zh", True),
}
# Taiwan character forms, character by character - not the phrase-level
# "s2twp", which would also swap vocabulary and could reword a TCM term.
# Loaded once here; convert() only reads it, so threads can share it.
_to_traditional = OpenCC("s2tw")

# Deleting the transcript afterwards happens off the request, so it neither
# slows the answer nor is skipped when the request fails.
_cleanup = ThreadPoolExecutor(max_workers=2, thread_name_prefix="assemblyai-cleanup")
_DELETE_ATTEMPTS = 6
_DELETE_RETRY_SECONDS = 10.0

_NO_SPEECH = "Didn't catch that — try again or use Add marker"
_TOO_SLOW = "Transcription is taking too long — try again or use Add marker"
_BAD_KEY = "Voice transcription is unavailable — the configured AssemblyAI API key is invalid"


def transcribe_marker_audio(audio_bytes: bytes, language_hint: str | None) -> str:
    """The text spoken in a short voice-marker clip, for the app to save as a
    marker label. Raises HTTPException 422 when nothing usable was heard, 503
    for anything wrong on the service side."""
    if not settings.assemblyai_api_key:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Voice transcription is not configured on the server (ASSEMBLYAI_API_KEY)",
        )
    language_code, traditional = _LANGUAGES.get(language_hint, ("en", False))
    headers = {"authorization": settings.assemblyai_api_key}
    deadline = time.monotonic() + _DEADLINE_SECONDS

    def time_left() -> float:
        left = deadline - time.monotonic()
        if left <= 0:
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, _TOO_SLOW)
        return min(10.0, left)

    transcript_id = None
    uploaded = False
    finished = False
    try:
        upload = requests.post(
            f"{_BASE_URL}/upload",
            headers={**headers, "content-type": "application/octet-stream"},
            data=audio_bytes,
            timeout=time_left(),
        )
        uploaded = True
        _check(upload)
        submit = requests.post(
            f"{_BASE_URL}/transcript",
            headers=headers,
            json={
                "audio_url": upload.json()["upload_url"],
                "speech_models": _SPEECH_MODELS,
                "language_code": language_code,
                "prompt": _PROMPT,
                "keyterms_prompt": _KEYTERMS[language_code],
            },
            timeout=time_left(),
        )
        _check(submit)
        transcript_id = submit.json()["id"]
        while True:
            poll = requests.get(f"{_BASE_URL}/transcript/{transcript_id}", headers=headers, timeout=time_left())
            _check(poll)
            result = poll.json()
            if result["status"] in ("completed", "error"):
                finished = True
                break
            time.sleep(min(_POLL_SECONDS, time_left()))
    except requests.Timeout:
        logger.warning("AssemblyAI didn't answer in time (transcript %s)", transcript_id)
        if uploaded and transcript_id is None:
            logger.warning("The timed-out submit may have created an AssemblyAI transcript that won't be deleted")
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, _TOO_SLOW)
    except (KeyError, TypeError, ValueError):  # ValueError includes a body that isn't JSON
        logger.exception("Unexpected response from AssemblyAI (transcript %s)", transcript_id)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Failed to transcribe audio")
    except requests.RequestException:
        logger.exception("Could not reach AssemblyAI for a voice marker")
        # Only the upload can fail this way over a bad key (see
        # _key_rejected), and it's the first call, so there's time to ask.
        if not uploaded and _key_rejected(headers):
            raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, _BAD_KEY)
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "Could not reach the transcription service — try again shortly"
        )
    finally:
        # A finished transcript is deleted right away, while this request
        # still has the CPU: Cloud Run throttles an instance once it has
        # answered, so work left for afterwards may not run. One still
        # processing, or a failed try, goes to the background retries.
        if transcript_id is not None and not (finished and _delete_once(transcript_id, headers)):
            _cleanup.submit(_delete_transcript, transcript_id, headers)

    if result["status"] == "error":
        # Almost always the clip itself: empty, too short, or unreadable.
        logger.warning("AssemblyAI couldn't transcribe a voice marker: %s", result.get("error"))
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, _NO_SPEECH)
    text = (result.get("text") or "").strip()
    if not text:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, _NO_SPEECH)
    return _to_traditional.convert(text) if traditional else text


def _check(response: requests.Response) -> None:
    """A caller-facing 503 for a failed AssemblyAI request; their own message
    goes to the log."""
    if response.ok:
        return
    logger.error(
        "AssemblyAI %s %s -> %s: %s",
        response.request.method, response.url, response.status_code, response.text[:500],
    )
    if response.status_code == 401:
        detail = _BAD_KEY
    elif response.status_code == 429:
        detail = "Voice transcription is temporarily rate-limited — try again shortly"
    else:
        detail = "Failed to transcribe audio"
    raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail)


def _key_rejected(headers: dict[str, str]) -> bool:
    """Whether AssemblyAI refuses this key, asked after a request failed at
    the connection level. A rejected key usually comes back as a plain 401,
    but on the upload AssemblyAI answers before reading the audio and closes
    the connection, which the client often sees only as a dropped connection
    (3 of 5 tries on Windows) - not "Could not reach"."""
    try:
        response = requests.get(f"{_BASE_URL}/transcript", params={"limit": 1}, headers=headers, timeout=5)
    except requests.RequestException:
        return False
    return response.status_code == 401


def _delete_once(transcript_id: str, headers: dict[str, str]) -> bool:
    """One quick delete attempt; whether it worked."""
    try:
        # (connect, read) of 2 s each: at most ~4 s on top of the 20 s deadline.
        return requests.delete(f"{_BASE_URL}/transcript/{transcript_id}", headers=headers, timeout=(2, 2)).ok
    except requests.RequestException:
        return False


def _delete_transcript(transcript_id: str, headers: dict[str, str]) -> None:
    """Removes the transcript from AssemblyAI so what was said in a patient's
    session isn't kept there. One still processing (the too-slow case) may
    not be deletable yet, so this retries, for up to about two minutes if
    AssemblyAI is unreachable (a server shutdown waits for it)."""
    outcome = "not tried"
    for attempt in range(_DELETE_ATTEMPTS):
        if attempt:
            time.sleep(_DELETE_RETRY_SECONDS)
        try:
            response = requests.delete(f"{_BASE_URL}/transcript/{transcript_id}", headers=headers, timeout=10)
        except requests.RequestException as e:
            outcome = repr(e)
            continue
        if response.ok:
            return
        outcome = f"{response.status_code}: {response.text[:200]}"
    logger.warning("Could not delete AssemblyAI transcript %s (%s)", transcript_id, outcome)
