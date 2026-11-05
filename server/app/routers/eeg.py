"""Raw-EEG endpoints: upload, analysis, stored results, and the per-patient
trend across sessions."""

import dataclasses
import logging
from collections import defaultdict

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status
from fastapi.concurrency import run_in_threadpool
from sqlalchemy import delete, select
from sqlalchemy.orm import Session, defer

from ..database import get_db
from ..eeg import storage as eeg_storage
from ..eeg.analysis import EegAnalysisError, analyze_session_eeg
from ..eeg.artifacts import EXCLUDING_SIGNS
from ..eeg.binary import EegBinaryError, parse_eeg_blob
from ..eeg.interpretation import LOW_FIT_R_SQUARED, eye_activity_differs
from ..eeg.params import CHANNEL_NAMES
from ..models import (
    Role,
    SessionEegMetric,
    SessionOscillationPeak,
    TcmSession,
    User,
    utcnow,
)
from ..schemas import (
    EegAnalysisOut,
    EegHalfMetricOut,
    EegTrendPointOut,
    EegUploadOut,
    ElectrodeFindingOut,
    OscillationPeakOut,
)
from ..security import require_role
from .sessions import _discard_raw_eeg, _get_owned_session

logger = logging.getLogger("smartring.sessions")

router = APIRouter(tags=["eeg"])


_HALF_PERIOD = {"first": 0, "baseline": 0, "second": 1, "treatment": 1}


def _excluded_indices(raw: str | None) -> list[int] | None:
    """TcmSession.eeg_excluded_channels as a list. "" means the analysis ran
    and excluded nothing; null means it predates the field. Only the latter
    should reach the client as null."""
    return None if raw is None else [int(i) for i in raw.split(",") if i.strip()]


def _all_electrodes_flagged(findings: list | None, excluded_raw: str | None) -> bool | None:
    """Every electrode showed an excluding sign, so none could be excluded
    (eeg.artifacts._check_electrodes) - then the stored peaks are "not
    evaluable" and the rates weren't measured. Null when the analysis
    predates the electrode check."""
    if findings is None:
        return None
    return any(f["sign"] in EXCLUDING_SIGNS for f in findings) and not _excluded_indices(excluded_raw)


def _eye_activity_differs(metrics: list[SessionEegMetric]) -> bool | None:
    """eeg.interpretation.eye_activity_differs over a session's two stored halves, in
    recording order - the one rule the card, the trend tab and ai_summary
    share. Null unless exactly two halves are stored."""
    if len(metrics) != 2:
        return None
    first, second = sorted(metrics, key=lambda m: _HALF_PERIOD.get(m.half, 1))
    return eye_activity_differs(
        first.deflection_rate_per_min, second.deflection_rate_per_min,
        first.eog_sem_rate_per_min, second.eog_sem_rate_per_min,
    )


@router.get("/patients/{patient_id}/eeg-trend", response_model=list[EegTrendPointOut])
def get_patient_eeg_trend(
    patient_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Cross-session view of a patient's corrected EEG metrics. Same scope
    rule as list_patient_sessions: filtered to sessions THIS
    practitioner authored for THIS patient (TcmSession.practitioner_id ==
    practitioner.id in the WHERE clause itself), so — like that endpoint —
    there's no separate _practitioner_covers_patient check or patient-not-
    found 404 needed; a practitioner literally cannot see another
    practitioner's sessions for a shared patient, and an unknown/uncovered
    patient_id just yields an empty list, same as list_patient_sessions.

    Only sessions with at least one analyzed half are included (the JOIN to
    SessionEegMetric does that filtering for free) — an un-analyzed or
    raw-EEG-less session contributes nothing here, same as it shows nothing
    on its own detail screen's EEG analysis card. Ordered oldest-to-newest
    (chronological, the natural reading order for a trend) then by period,
    unlike every other list endpoint in this app (which lead with the most
    recent) — deliberately different here since this is charting data, not a
    record list to scan for the newest entry first."""
    rows = db.execute(
        select(
            TcmSession.id, TcmSession.started_at, TcmSession.eeg_excluded_channels,
            TcmSession.eeg_electrode_findings, SessionEegMetric,
        )
        # The trend never draws a spectrum, and each one is ~700 floats —
        # multiplied by every analyzed half in the patient's history.
        .options(defer(SessionEegMetric.spectrum))
        .join(SessionEegMetric, SessionEegMetric.session_id == TcmSession.id)
        .where(
            TcmSession.patient_id == patient_id,
            TcmSession.practitioner_id == practitioner.id,
            TcmSession.deleted_at.is_(None),
        )
        .order_by(TcmSession.started_at.asc())
    ).all()

    metrics_by_session: dict[int, list[SessionEegMetric]] = defaultdict(list)
    for session_id, _, _, _, metric in rows:
        metrics_by_session[session_id].append(metric)
    eye_differs = {sid: _eye_activity_differs(ms) for sid, ms in metrics_by_session.items()}

    def excluded_names(raw: str | None) -> list[str] | None:
        indices = _excluded_indices(raw)
        return None if indices is None else [CHANNEL_NAMES[i] for i in indices if i < len(CHANNEL_NAMES)]

    return [
        EegTrendPointOut(
            session_id=session_id,
            session_started_at=started_at,
            half=metric.half,
            period=_HALF_PERIOD.get(metric.half, 1),
            aperiodic_offset=metric.aperiodic_offset,
            aperiodic_exponent=metric.aperiodic_exponent,
            r_squared=metric.r_squared,
            epoch_count=metric.epoch_count,
            eog_sem_rate_per_min=metric.eog_sem_rate_per_min,
            eog_windows_analyzed=metric.eog_windows_analyzed,
            low_fit_r_squared=LOW_FIT_R_SQUARED,
            deflection_rate_per_min=metric.deflection_rate_per_min,
            eye_activity_differs=eye_differs.get(session_id),
            excluded_channel_names=excluded_names(excluded_raw),
            all_electrodes_flagged=_all_electrodes_flagged(findings, excluded_raw),
        )
        for session_id, started_at, excluded_raw, findings, metric in sorted(
            rows, key=lambda r: (r[1], _HALF_PERIOD.get(r[4].half, 1))
        )
    ]


def _eeg_analysis_out(session: TcmSession, db: Session) -> EegAnalysisOut:
    """Shared response builder for the analyze and reread endpoints below —
    reads back whatever's currently stored, regardless of which endpoint just
    wrote it. Takes the session row rather than an id because the response
    also carries session-wide recording quality (sample rate,
    interpolated fraction, excluded channels) that lives on it."""
    metrics = db.scalars(
        select(SessionEegMetric).where(SessionEegMetric.session_id == session.id)
    ).all()
    session_id = session.id
    peaks_by_half: dict[str, list[SessionOscillationPeak]] = defaultdict(list)
    for p in db.scalars(select(SessionOscillationPeak).where(SessionOscillationPeak.session_id == session_id)):
        peaks_by_half[p.half].append(p)
    halves = [
        EegHalfMetricOut(
            half=m.half,
            aperiodic_offset=m.aperiodic_offset,
            aperiodic_exponent=m.aperiodic_exponent,
            r_squared=m.r_squared,
            epoch_count=m.epoch_count,
            eog_sem_rate_per_min=m.eog_sem_rate_per_min,
            eog_windows_analyzed=m.eog_windows_analyzed,
            epochs_rejected_bad_contact=m.epochs_rejected_bad_contact,
            epochs_rejected_amplitude=m.epochs_rejected_amplitude,
            epochs_partial_contact=m.epochs_partial_contact,
            peaks=[OscillationPeakOut.model_validate(p) for p in peaks_by_half.get(m.half, [])],
            spectrum=m.spectrum,
            deflection_rate_per_min=m.deflection_rate_per_min,
        )
        for m in metrics
    ]
    findings = session.eeg_electrode_findings
    return EegAnalysisOut(
        halves=halves,
        analyzed_at=session.eeg_analyzed_at,
        observed_sample_rate_hz=session.eeg_sample_rate_hz,
        interpolated_sample_fraction=session.eeg_interpolated_fraction,
        excluded_channel_indices=_excluded_indices(session.eeg_excluded_channels),
        electrode_findings=None if findings is None else [ElectrodeFindingOut(**f) for f in findings],
        all_electrodes_flagged=_all_electrodes_flagged(findings, session.eeg_excluded_channels),
        low_fit_r_squared=LOW_FIT_R_SQUARED,
        eye_activity_differs=_eye_activity_differs(list(metrics)),
    )


@router.post("/sessions/{session_id}/eeg", response_model=EegUploadOut)
async def upload_session_eeg(
    session_id: int,
    eeg_data: UploadFile = File(...),
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Uploads the raw EEG binary captured during this session (see
    eeg/binary.py for the wire format, SessionRecorder.kt's EegBinaryWriter
    for the client side that produces it) — a SEPARATE call from POST
    /sessions since the session needs an id before the blob can be attached
    to it, and this is a much bigger binary body that doesn't belong folded
    into the same JSON request as markers/samples.

    Stores the blob via eeg/storage.py (a local folder, or a Cloud Storage
    bucket when EEG_RAW_BUCKET is set; kept after analysis) rather than the
    database. Re-uploading for the
    same session id overwrites the previous file and clears any earlier
    analysis of it — this is meant for a client retry after a dropped
    connection, not a way to append a second recording."""
    session = _get_owned_session(session_id, practitioner, db)
    data = await eeg_data.read()
    if not data:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Empty EEG upload")
    try:
        blob = parse_eeg_blob(data)
    except EegBinaryError as e:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, str(e))
    sample_rate = blob.observed_sample_rate_hz
    if sample_rate is None:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "EEG upload has a degenerate timestamp span")

    # Off the event loop: in a bucket this is a network upload of up to ~15 MB.
    location = await run_in_threadpool(eeg_storage.save, session_id, data)
    replaced = session.eeg_raw_path if session.eeg_raw_path != location else None

    session.eeg_raw_path = location
    session.eeg_sample_rate_hz = sample_rate
    session.eeg_sample_count = blob.sample_count
    session.eeg_analyzed_at = None
    # These describe the BLOB being replaced, so they are stale the moment a
    # new one lands - cleared alongside the analysis they belong to, or a
    # later reader publishes a quality claim about a file that is gone.
    session.eeg_interpolated_fraction = None
    session.eeg_excluded_channels = None
    session.eeg_electrode_findings = None
    db.execute(delete(SessionEegMetric).where(SessionEegMetric.session_id == session_id))
    db.execute(delete(SessionOscillationPeak).where(SessionOscillationPeak.session_id == session_id))
    db.commit()
    if replaced:
        # In a bucket every upload gets its own name, so the recording this
        # one replaces is removed here (a local file was overwritten in place).
        await run_in_threadpool(_discard_raw_eeg, replaced)

    return EegUploadOut(sample_count=blob.sample_count, sample_rate_hz=sample_rate)


@router.post("/sessions/{session_id}/eeg/analyze", response_model=EegAnalysisOut)
def analyze_session_eeg_endpoint(
    session_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """On-demand only — same "button the practitioner taps" precedent as
    summarize_session, not something that runs automatically on
    upload. This IS the compute step: mne filtering + specparam fitting (see
    eeg/analysis.py), run synchronously in this request. That's a deliberate
    choice for this scale (single-clinic pilot, on-demand trigger) over
    FastAPI's BackgroundTasks — a Welch PSD + specparam fit over a
    session-length recording is a few seconds of CPU, and a synchronous
    response lets the client show the numbers the moment the button
    resolves, the same way summarize_session's Claude call does, rather than
    needing a second poll-for-result endpoint this app doesn't have yet.

    Keeps the raw blob after analysis (see models.TcmSession.eeg_raw_path).
    Calling this again for the same session re-runs the pipeline from the same blob
    and replaces its SessionEegMetric/SessionOscillationPeak rows — it is
    NOT idempotent-no-op, each call recomputes."""
    session = _get_owned_session(session_id, practitioner, db)
    if session.eeg_raw_path is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "No raw EEG uploaded for this session")

    try:
        data = eeg_storage.load(session.eeg_raw_path)
    except FileNotFoundError:
        # The DB points at a recording that's gone: analyzed by an older
        # server that discarded blobs after analysis, a DB restored without
        # its data/eeg_raw/ directory, a DB copied between hosts (a local
        # eeg_raw_path is absolute), or a recording removed from the bucket.
        #
        # Worded differently from the "never uploaded" 404 above: the client
        # shows a 404's message as-is, and "this session never captured raw
        # EEG" calls for a different reaction than "there was one and it's
        # gone."
        #
        # The stale pointer is deliberately NOT cleared: a second tap of the
        # re-analyze button would then get the "never uploaded" message,
        # contradicting the first. Nothing else reads eeg_raw_path except
        # DELETE /sessions/{id} and a re-upload, which both accept a
        # recording that's already gone.
        raise HTTPException(
            status.HTTP_404_NOT_FOUND,
            "The raw EEG for this session is no longer on the server — it may have "
            "been analyzed before recordings were kept, or cleaned up since — so it "
            "can't be re-analyzed.",
        )

    try:
        blob = parse_eeg_blob(data)
        analysis = analyze_session_eeg(blob, baseline_duration_seconds=session.baseline_duration_seconds)
    except (EegBinaryError, EegAnalysisError) as e:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, str(e))

    db.execute(delete(SessionEegMetric).where(SessionEegMetric.session_id == session_id))
    db.execute(delete(SessionOscillationPeak).where(SessionOscillationPeak.session_id == session_id))
    for half in analysis.halves:
        db.add(SessionEegMetric(
            session_id=session_id,
            half=half.half,
            aperiodic_offset=half.aperiodic_offset,
            aperiodic_exponent=half.aperiodic_exponent,
            r_squared=half.r_squared,
            epoch_count=half.epoch_count,
            eog_sem_rate_per_min=half.eog_sem_rate_per_min,
            eog_windows_analyzed=half.eog_windows_analyzed,
            epochs_rejected_bad_contact=half.epochs_rejected_bad_contact,
            epochs_rejected_amplitude=half.epochs_rejected_amplitude,
            epochs_partial_contact=half.epochs_partial_contact,
            spectrum=half.spectrum,
            deflection_rate_per_min=half.deflection_rate_per_min,
        ))
        for peak in half.peaks:
            db.add(SessionOscillationPeak(
                session_id=session_id,
                half=half.half,
                band=peak.band,
                center_frequency=peak.center_frequency,
                power=peak.power,
                bandwidth=peak.bandwidth,
            ))

    session.eeg_analyzed_at = utcnow()
    session.eeg_interpolated_fraction = analysis.interpolated_sample_fraction
    # Stored as a string so "excluded nothing" ("") stays distinguishable
    # from "this analysis predates the field" (null).
    session.eeg_excluded_channels = ",".join(str(i) for i in analysis.excluded_channel_indices)
    session.eeg_electrode_findings = [dataclasses.asdict(f) for f in analysis.electrode_findings]
    # The blob's own observed rate, re-stamped: a re-analysis of the same
    # blob yields the same number, but an older session may never have had
    # it recorded at upload time.
    session.eeg_sample_rate_hz = analysis.observed_sample_rate_hz
    db.commit()

    return _eeg_analysis_out(session, db)


@router.get("/sessions/{session_id}/eeg-metrics", response_model=EegAnalysisOut)
def get_session_eeg_metrics(
    session_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Rereads whatever POST /sessions/{id}/eeg/analyze last computed, without
    re-running the pipeline — lets the app reload a session's EEG metrics
    (e.g. reopening the detail screen) without spending another analysis
    pass. Empty `halves` and `analyzed_at: null` both mean "never analyzed,"
    same as any other not-yet-populated field in this app."""
    session = _get_owned_session(session_id, practitioner, db)
    return _eeg_analysis_out(session, db)
