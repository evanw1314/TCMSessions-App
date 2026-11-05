import logging

from fastapi import APIRouter, Depends, File, Form, HTTPException, Response, UploadFile, status
from fastapi.concurrency import run_in_threadpool
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, selectinload

from ..ai_summary import generate_session_summary
from ..database import get_db
from ..eeg import storage as eeg_storage
from ..models import (
    Headband,
    HeadbandAssignment,
    Role,
    SessionEegMetric,
    SessionMarker,
    SessionOscillationPeak,
    SessionSample,
    TcmSession,
    User,
    utcnow,
)
from ..schemas import (
    AiSummaryOut,
    TcmSessionCreate,
    TcmSessionNoteUpdate,
    TcmSessionOut,
    TcmSessionSummary,
    TranscribeOut,
)
from ..security import get_current_user, require_role
from ..transcription import transcribe_marker_audio
from .patients import _practitioner_covers_patient

logger = logging.getLogger("smartring.sessions")

router = APIRouter(tags=["sessions"])


def _get_owned_session(session_id: int, practitioner: User, db: Session) -> TcmSession:
    """Lookup for the raw-EEG endpoints (routers/eeg.py) — the same "only the
    authoring practitioner, and only if not soft-deleted" rule get_session/
    update_session_note/delete_session enforce inline."""
    session = db.scalar(select(TcmSession).where(TcmSession.id == session_id))
    if session is None or session.practitioner_id != practitioner.id or session.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session")
    return session


def _discard_raw_eeg(location: str) -> bool:
    """Best effort: whether the recording is gone. A failure is logged with
    where the recording is, so it can be removed by hand, and is never an
    error for the request that no longer needs it."""
    try:
        eeg_storage.delete(location)
        return True
    except Exception as e:  # noqa: BLE001 - any storage failure, local disk or Cloud Storage
        logger.error("Could not delete raw EEG %s (left behind; remove it by hand): %s", location, e)
        return False


def _session_by_client_id(db: Session, practitioner_id: int, client_session_id: str) -> TcmSession | None:
    return db.scalar(
        select(TcmSession).where(
            TcmSession.practitioner_id == practitioner_id,
            TcmSession.client_session_id == client_session_id,
        )
    )


def _session_with_children(db: Session, session_id: int) -> TcmSession:
    return db.scalar(
        select(TcmSession)
        .options(selectinload(TcmSession.markers), selectinload(TcmSession.samples))
        .where(TcmSession.id == session_id)
    )


def _replayed_session(db: Session, existing: TcmSession, body: TcmSessionCreate, response: Response) -> TcmSession:
    """A repeat of a save the server already has: hand that session back,
    with 200 rather than 201. Even one deleted since, since the practitioner
    deleted what this retry would have saved. The same id with a different
    patient is a client bug, not a retry, so it's refused rather than
    answered with another patient's session."""
    if existing.patient_id != body.patient_id:
        raise HTTPException(status.HTTP_409_CONFLICT, "client_session_id was already used for a different session")
    response.status_code = status.HTTP_200_OK
    stored = _session_with_children(db, existing.id)
    # The stored copy wins. A retry can carry edits made after the first
    # attempt quietly succeeded (a marker added, the note changed); those
    # are dropped, which beats a duplicate, but shouldn't vanish unlogged.
    if len(stored.markers) != len(body.markers) or stored.practitioner_note != body.practitioner_note:
        logger.warning(
            "Session %s: a repeat save differs from the stored one (markers %d vs %d, note differs: %s) - "
            "edits after the first attempt saved, or a later note edit; kept the stored one",
            stored.id, len(stored.markers), len(body.markers), stored.practitioner_note != body.practitioner_note,
        )
    return stored


@router.post("/sessions", response_model=TcmSessionOut, status_code=status.HTTP_201_CREATED)
def create_session(
    body: TcmSessionCreate,
    response: Response,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Creates a whole session in one call, once it's ended on the device —
    there is no server-side start/draft state (see TcmSession docstring).

    Idempotent on client_session_id: the phone resends the same one on every
    attempt, so a retry of a save that did reach the server (the phone timed
    out waiting for the answer) gets that session back instead of creating a
    second one."""
    practitioner_id = practitioner.id
    if body.client_session_id is not None:
        existing = _session_by_client_id(db, practitioner_id, body.client_session_id)
        if existing is not None:
            return _replayed_session(db, existing, body, response)

    patient = db.get(User, body.patient_id)
    if patient is None or patient.role != Role.patient:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown patient")
    if not _practitioner_covers_patient(db, practitioner.id, patient.id):
        # Same "currently cares for this patient" gate as notes/AI summary —
        # without it, any practitioner could fabricate a full session (and
        # its band-power data) against an arbitrary patient_id.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown patient")

    headband = None
    if body.headband_device_id is not None:
        headband = db.scalar(
            select(Headband).where(Headband.device_id == body.headband_device_id)
        )
        if headband is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown headband")

        # The session window must overlap SOME period this patient held this
        # headband, so a headband reassigned between sessions can't get a
        # session attributed to the wrong patient.
        periods = db.execute(
            select(HeadbandAssignment.started_at, HeadbandAssignment.ended_at).where(
                HeadbandAssignment.headband_id == headband.id,
                HeadbandAssignment.patient_id == patient.id,
            )
        ).all()
        overlaps = any(
            started < body.ended_at and (ended is None or ended > body.started_at)
            for started, ended in periods
        )
        if not overlaps:
            raise HTTPException(
                status.HTTP_409_CONFLICT,
                "This headband was not assigned to this patient during the session window",
            )

    session = TcmSession(
        patient_id=patient.id,
        practitioner_id=practitioner.id,
        headband_id=headband.id if headband else None,
        modality=body.modality,
        started_at=body.started_at,
        ended_at=body.ended_at,
        practitioner_note=body.practitioner_note,
        patient_dozed=body.patient_dozed,
        alertness_rating=body.alertness_rating,
        baseline_duration_seconds=body.baseline_duration_seconds,
        treatment_end_seconds=body.treatment_end_seconds,
        client_session_id=body.client_session_id,
    )
    db.add(session)
    try:
        db.flush()
    except IntegrityError:
        # Two copies of the same save raced past the lookup above and the
        # other one's insert won: answer this one with that session.
        db.rollback()
        existing = (
            _session_by_client_id(db, practitioner_id, body.client_session_id)
            if body.client_session_id is not None
            else None
        )
        if existing is None:
            raise
        return _replayed_session(db, existing, body, response)

    for m in body.markers:
        db.add(SessionMarker(session_id=session.id, offset_seconds=m.offset_seconds, label=m.label))
    for s in body.samples:
        db.add(SessionSample(
            session_id=session.id, offset_seconds=s.offset_seconds, band=s.band, value=s.value
        ))
    db.commit()

    return _session_with_children(db, session.id)


@router.post("/sessions/transcribe", response_model=TranscribeOut)
async def transcribe_voice_marker(
    audio: UploadFile = File(...),
    language: str | None = Form(None),
    practitioner: User = Depends(require_role(Role.practitioner)),
):
    """Turns a short voice-marker recording into text via AssemblyAI — see
    transcription.py for why this is server-side. `language` is a BCP-47 tag
    from SessionActivity.kt's VoiceLanguage enum (null for English).

    No patient_id / ownership check, unlike notes and AI summaries: this
    doesn't read or write anything on a patient's record, it just turns audio
    into text for the client to attach as a marker locally — so any
    authenticated practitioner may call it, same as the role-only gate on
    e.g. add_patient."""
    data = await audio.read()
    if not data:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Empty audio")
    # Blocking HTTP with polling, up to ~20 s: off the event loop, or every
    # other request would wait behind it.
    text = await run_in_threadpool(transcribe_marker_audio, data, language)
    return TranscribeOut(text=text)


@router.get("/patients/{patient_id}/sessions", response_model=list[TcmSessionSummary])
def list_patient_sessions(
    patient_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    return db.scalars(
        select(TcmSession)
        .where(
            TcmSession.patient_id == patient_id,
            TcmSession.practitioner_id == practitioner.id,
            TcmSession.deleted_at.is_(None),
        )
        .order_by(TcmSession.started_at.desc())
    ).all()


@router.get("/me/sessions", response_model=list[TcmSessionSummary])
def list_my_sessions(
    patient: User = Depends(require_role(Role.patient)),
    db: Session = Depends(get_db),
):
    """A patient's own full session history, across every practitioner who
    has recorded one for them — unlike list_patient_sessions (a
    practitioner's own authored subset), this is the patient's unified
    record, same "not scoped to one practitioner" reasoning as
    patients.my_profile/my_notes."""
    return db.scalars(
        select(TcmSession)
        .where(
            TcmSession.patient_id == patient.id,
            TcmSession.deleted_at.is_(None),
        )
        .order_by(TcmSession.started_at.desc())
    ).all()


@router.get("/sessions/{session_id}", response_model=TcmSessionOut)
def get_session(
    session_id: int,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """A practitioner may only see a session they authored; a patient may
    only see their own session, authored by anyone — the detail-view
    counterpart to list_my_sessions above."""
    session = db.scalar(
        select(TcmSession)
        .options(selectinload(TcmSession.markers), selectinload(TcmSession.samples))
        .where(TcmSession.id == session_id)
    )
    owns = session is not None and (
        (user.role == Role.practitioner and session.practitioner_id == user.id)
        or (user.role == Role.patient and session.patient_id == user.id)
    )
    if not owns or session.deleted_at is not None:
        # Same 404 for "doesn't exist", "not yours" and "deleted" — avoids
        # confirming a session id exists/existed to someone who can't see it.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session")
    return session


@router.post("/sessions/{session_id}/summary", response_model=AiSummaryOut)
def summarize_session(
    session_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """On-demand only, same reasoning as patients.summarize_patient — never
    called automatically. Descriptive-only framing (no scheduling suggestions,
    no clinical opinions) is enforced in ai_summary.py's system prompt."""
    session = db.scalar(
        select(TcmSession)
        .options(selectinload(TcmSession.markers), selectinload(TcmSession.samples))
        .where(TcmSession.id == session_id)
    )
    if session is None or session.practitioner_id != practitioner.id or session.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session")
    # Feeds the corrected aperiodic-fit numbers into the summary instead of
    # relative band power whenever this session has been analyzed (POST
    # /sessions/{id}/eeg/analyze) — see ai_summary.generate_session_summary's
    # Form A/Form B split and docs/eeg-aperiodic-correction.md.
    eeg_metrics = db.scalars(
        select(SessionEegMetric).where(SessionEegMetric.session_id == session_id)
    ).all()
    eeg_peaks = db.scalars(
        select(SessionOscillationPeak).where(SessionOscillationPeak.session_id == session_id)
    ).all()
    summary = generate_session_summary(session, session.markers, session.samples, eeg_metrics, eeg_peaks)
    return AiSummaryOut(summary=summary)


@router.patch("/sessions/{session_id}", response_model=TcmSessionOut)
def update_session_note(
    session_id: int,
    body: TcmSessionNoteUpdate,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    session = db.scalar(
        select(TcmSession)
        .options(selectinload(TcmSession.markers), selectinload(TcmSession.samples))
        .where(TcmSession.id == session_id)
    )
    if session is None or session.practitioner_id != practitioner.id or session.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session")
    session.practitioner_note = body.practitioner_note
    db.commit()
    db.refresh(session)
    return session


@router.delete("/sessions/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_session(
    session_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Soft delete only — stamps deleted_at rather than removing the row (and
    its samples/markers), so an accidental delete is still recoverable from
    the DB directly. Every other query in this router filters deleted_at IS
    NULL, so a soft-deleted session disappears from the app immediately."""
    session = db.scalar(
        select(TcmSession).where(TcmSession.id == session_id)
    )
    if session is None or session.practitioner_id != practitioner.id or session.deleted_at is not None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown session")
    if session.eeg_raw_path is not None:
        # A soft-deleted session is 404 from every endpoint, so nothing else
        # could ever remove its raw EEG. One that couldn't be removed keeps
        # its pointer, so it can still be found and cleaned up by hand.
        if _discard_raw_eeg(session.eeg_raw_path):
            session.eeg_raw_path = None
    session.deleted_at = utcnow()
    db.commit()
