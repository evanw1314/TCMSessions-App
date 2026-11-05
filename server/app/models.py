import enum
from datetime import UTC, datetime

from sqlalchemy import JSON, Boolean, DateTime, Enum, ForeignKey, Index, String, UniqueConstraint, text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .database import Base

# Every datetime column below is `DateTime()` — deliberately WITHOUT
# `timezone=True`. SQLite has no native timestamp type either way (it's text
# underneath), but on Postgres `timezone=True` maps to TIMESTAMPTZ, which
# hands back AWARE datetimes — while SQLite always hands back naive ones. The
# app already normalizes every inbound value to naive UTC at the schema
# boundary (schemas.to_utc_naive) and writes only naive UTC internally
# (utcnow() below), so a plain `DateTime()`/TIMESTAMP column keeps both
# backends returning naive datetimes on read, identically. Getting this wrong
# doesn't just look ugly — routers compare a DB-read timestamp against a
# schema-normalized (naive) one directly in Python in several places (e.g.
# sessions.py's headband-overlap check), which raises TypeError the moment one side is aware and the
# other isn't. Keep new datetime columns plain `DateTime()` too.


def utcnow() -> datetime:
    # Naive, deliberately — see the DateTime() note above. (Already UTC, so
    # this is just dropping tzinfo, not schemas.to_utc_naive's full
    # astimezone conversion; importing that here would be circular.)
    return datetime.now(UTC).replace(tzinfo=None)


class Role(str, enum.Enum):
    practitioner = "practitioner"
    patient = "patient"


class Clinic(Base):
    __tablename__ = "clinics"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(120), unique=True)

    practitioners: Mapped[list["User"]] = relationship(back_populates="clinic")


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    google_id: Mapped[str | None] = mapped_column(String(64), unique=True, nullable=True)
    # Nullable for a patient added by name only, before any account exists for
    # them (see PatientRoster.add_patient) — a practitioner assigns the email
    # later (PATCH /patients/{id}/email), once they know which account should
    # receive this patient's data. Every login path (Google/dev/password) only
    # ever creates a user WITH an email, so a null email always means
    # "name-only patient, not yet linked to any login" — never a practitioner
    # or a patient who can sign in.
    email: Mapped[str | None] = mapped_column(String(254), unique=True, index=True, nullable=True)
    name: Mapped[str] = mapped_column(String(120), default="")
    role: Mapped[Role] = mapped_column(Enum(Role))
    # Null for accounts that have never set a password (Google/dev-created).
    password_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)
    clinic_id: Mapped[int | None] = mapped_column(ForeignKey("clinics.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(), default=utcnow)

    clinic: Mapped[Clinic | None] = relationship(back_populates="practitioners")


class Headband(Base):
    __tablename__ = "headbands"

    id: Mapped[int] = mapped_column(primary_key=True)
    device_id: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    clinic_id: Mapped[int] = mapped_column(ForeignKey("clinics.id"))
    created_at: Mapped[datetime] = mapped_column(DateTime(), default=utcnow)


class HeadbandAssignment(Base):
    """One period during which a Muse headband belongs to one patient.

    Reassigning a headband closes the previous period (ended_at) and opens a
    new row, so history stays attributed to the right patient. The partial
    unique indexes allow at most ONE open period per headband and per
    patient, enforcing that against two concurrent register_headband calls
    that would both read "no active assignment" and both insert. They cover
    only open rows, so closed periods are unconstrained.
    """

    __tablename__ = "headband_assignments"
    __table_args__ = (
        Index(
            "uq_headband_active_assignment", "headband_id", unique=True,
            sqlite_where=text("ended_at IS NULL"),
            postgresql_where=text("ended_at IS NULL"),
        ),
        Index(
            "uq_headband_patient_active_assignment", "patient_id", unique=True,
            sqlite_where=text("ended_at IS NULL"),
            postgresql_where=text("ended_at IS NULL"),
        ),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    headband_id: Mapped[int] = mapped_column(ForeignKey("headbands.id"), index=True)
    patient_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    practitioner_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    started_at: Mapped[datetime] = mapped_column(DateTime(), default=utcnow)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(), nullable=True)

    headband: Mapped[Headband] = relationship()
    patient: Mapped[User] = relationship(foreign_keys=[patient_id])
    practitioner: Mapped[User] = relationship(foreign_keys=[practitioner_id])


class PatientRoster(Base):
    """Direct practitioner <-> patient relationship, independent of any
    headband assignment.

    A patient becomes "yours" the moment you add them here — e.g. at intake,
    before any device has ever been connected — and stays yours regardless of
    whether a headband gets assigned later. Unlike HeadbandAssignment this
    never closes; it's a roster, not a device-holding period. patients.py's
    list_patients unions the two so a device-less patient still shows up in
    the practitioner's list.
    """

    __tablename__ = "patient_roster"
    __table_args__ = (
        UniqueConstraint("practitioner_id", "patient_id", name="uq_roster_practitioner_patient"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    practitioner_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    patient_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(), default=utcnow)

    practitioner: Mapped[User] = relationship(foreign_keys=[practitioner_id])
    patient: Mapped[User] = relationship(foreign_keys=[patient_id])


class PatientNote(Base):
    """A free-form, dated note a practitioner keeps about a patient —
    independent of any single TCM session (see TcmSession.practitioner_note
    for the per-session equivalent; this is the general/overview one).

    Scoped to the AUTHORING practitioner, same ownership model as TcmSession:
    a patient can be on several practitioners' rosters (PatientRoster), but
    one practitioner's notes about that shared patient are never shown to
    another. Soft-deletable by its author only (deleted_at), same reasoning
    as TcmSession.deleted_at — recoverable from the DB directly, never hard-
    removed. Never edited in place — a running log, not a document — so
    there's no updated_at/PATCH here, delete-and-repost instead.
    """

    __tablename__ = "patient_notes"
    __table_args__ = (
        Index("ix_patient_notes_patient_practitioner", "patient_id", "practitioner_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    patient_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    practitioner_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    body: Mapped[str] = mapped_column(String(2000))
    created_at: Mapped[datetime] = mapped_column(DateTime(), default=utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(), nullable=True)

    patient: Mapped[User] = relationship(foreign_keys=[patient_id])
    practitioner: Mapped[User] = relationship(foreign_keys=[practitioner_id])

    @property
    def practitioner_name(self) -> str:
        """Surfaced only in the patient's own view (GET /me/notes), so a
        patient on more than one practitioner's roster can tell who wrote a
        given note. A practitioner's own list (GET /patients/{id}/notes) is
        always scoped to notes THEY wrote, so it would only ever show their
        own name there."""
        return self.practitioner.name


class PendingRegistration(Base):
    """Holds a not-yet-real account between /auth/register and /auth/verify.

    Deliberately NOT a users row — every existing "does this email have an
    account" check (login, headband/patient registration) does
    select(User).where(email==...) and must keep treating an unverified
    signup as "no such account".
    """

    __tablename__ = "pending_registrations"

    id: Mapped[int] = mapped_column(primary_key=True)
    email: Mapped[str] = mapped_column(String(254), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(120))
    role: Mapped[Role] = mapped_column(Enum(Role))
    password_hash: Mapped[str] = mapped_column(String(255))
    code_hash: Mapped[str] = mapped_column(String(255))
    attempts: Mapped[int] = mapped_column(default=0)
    expires_at: Mapped[datetime] = mapped_column(DateTime())
    created_at: Mapped[datetime] = mapped_column(DateTime(), default=utcnow)


class TcmSession(Base):
    """A single in-clinic TCM session, recorded start-to-finish on the
    practitioner's device and uploaded whole once it ends (no server-side
    draft/in-progress state)."""

    __tablename__ = "tcm_sessions"
    __table_args__ = (
        # See client_session_id below. NULLs never collide in a unique index
        # (SQLite and Postgres alike), so sessions without one are unaffected.
        Index("uq_tcm_session_client_id", "practitioner_id", "client_session_id", unique=True),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    patient_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    practitioner_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    headband_id: Mapped[int | None] = mapped_column(ForeignKey("headbands.id"), nullable=True)
    modality: Mapped[str] = mapped_column(String(32))
    started_at: Mapped[datetime] = mapped_column(DateTime())
    ended_at: Mapped[datetime] = mapped_column(DateTime())
    practitioner_note: Mapped[str | None] = mapped_column(String(2000), nullable=True)
    # Quick, optional post-session context. A rise in the low bands / fall in
    # the high bands during a resting session is the classic signature of the
    # patient's arousal simply dropping (drowsiness or sleep onset), not a
    # treatment effect — these let the record distinguish that from an actual
    # finding after the fact. Null means not answered.
    patient_dozed: Mapped[bool | None] = mapped_column(Boolean(), nullable=True)
    alertness_rating: Mapped[int | None] = mapped_column(nullable=True)  # 1 (drowsy) - 7 (fully alert)
    # Raw EEG capture — set by POST /sessions/{id}/eeg, read by POST
    # /sessions/{id}/eeg/analyze. eeg_raw_path is where eeg/storage.py put
    # it: a path under eeg.storage.EEG_RAW_DIR, or gs://bucket/name when
    # EEG_RAW_BUCKET is set (Cloud Run). Analysis KEEPS the file: the
    # pipeline is still evolving, and every improvement needs to re-run on
    # already-analyzed sessions. A session can be (re-)analyzed any number of
    # times; each run replaces its SessionEegMetric/SessionOscillationPeak
    # rows (see the endpoint). DELETE /sessions/{id} removes the file along
    # with the session it belongs to. eeg_sample_rate_hz/eeg_sample_count describe
    # whatever blob was last uploaded. eeg_analyzed_at is null until POST
    # /sessions/{id}/eeg/analyze has succeeded at least once.
    eeg_raw_path: Mapped[str | None] = mapped_column(String(500), nullable=True)
    eeg_sample_rate_hz: Mapped[float | None] = mapped_column(nullable=True)
    eeg_sample_count: Mapped[int | None] = mapped_column(nullable=True)
    eeg_analyzed_at: Mapped[datetime | None] = mapped_column(DateTime(), nullable=True)
    # Session-wide recording quality, written by POST /eeg/analyze alongside
    # the per-half rows, so whoever reads the card can tell, say, that two of
    # four channels were dropped.
    #
    # NULLABLE ON PURPOSE, not defaulted to 0/"": null means "this analysis
    # predates these fields," which is a different statement from "measured,
    # and it was zero" — the same informative-absence pattern as
    # SessionOscillationPeak's per-band absence.
    # eeg_excluded_channels is a comma-separated index list; "" means the
    # analysis ran and excluded nothing, distinct from null.
    eeg_interpolated_fraction: Mapped[float | None] = mapped_column(nullable=True)
    eeg_excluded_channels: Mapped[str | None] = mapped_column(String(64), nullable=True)
    # The electrode check's findings (eeg.artifacts._check_electrodes), as a
    # list of {channel, channel_name, half, sign, value, excluded,
    # partner_name} objects - what the exclusion above was based on, and the
    # warnings that didn't exclude anything. The electrode check is the only
    # thing that fills eeg_excluded_channels now. Null means the analysis
    # predates the check, so a "1,2" above came from the switched-off eye
    # correction; [] means the check ran and found nothing.
    eeg_electrode_findings: Mapped[list | None] = mapped_column(JSON, nullable=True)
    # Elapsed seconds from session start to the practitioner marking "baseline
    # done, treatment started" (SessionRecorder.markBaselineEnd on Android): a
    # short eyes-closed, no-treatment period at the start of the SAME
    # recording, so eeg/analysis.py can split on this cutoff (baseline vs
    # treatment) instead of blindly halving the session by wall-clock time.
    # Null means no baseline was recorded; analyze_session_eeg then splits
    # at the midpoint.
    baseline_duration_seconds: Mapped[int | None] = mapped_column(nullable=True)
    # Elapsed seconds from session start to the practitioner tapping
    # "Treatment done" (SessionRecorder.markTreatmentEnd on Android): the end
    # of the treatment itself, before the needles come out and the patient
    # moves and talks. The session card's before/after band-share pies take
    # their "after" minute from just before it, because the wrap-up minute is
    # mostly movement (in the research sessions, blinking or eye movement
    # alone moved the delta share by 17-42 points). Null means it wasn't
    # tapped; the pies then fall back to the recording's last minute, with a
    # warning. Never before baseline_duration_seconds when both are set (see
    # schemas.TcmSessionCreate).
    treatment_end_seconds: Mapped[int | None] = mapped_column(nullable=True)
    # A random id the phone makes when a recording starts and resends with
    # every attempt to save it, so a retry after a timeout (the server saved
    # it, the phone never heard back) gets the existing session instead of a
    # second copy - see routers/sessions.create_session. Unique per
    # practitioner (uq_tcm_session_client_id above); null for clients that
    # don't send one.
    client_session_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(), default=utcnow)
    # Soft delete only — set from a practitioner-confirmed DELETE /sessions/{id},
    # never hard-removed. Hidden from every list/get/patch query below but stays
    # in the DB, so an accidental delete is still recoverable by a DB admin.
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(), nullable=True)

    patient: Mapped[User] = relationship(foreign_keys=[patient_id])
    practitioner: Mapped[User] = relationship(foreign_keys=[practitioner_id])
    headband: Mapped[Headband | None] = relationship()
    markers: Mapped[list["SessionMarker"]] = relationship(
        back_populates="session", cascade="all, delete-orphan"
    )
    samples: Mapped[list["SessionSample"]] = relationship(
        back_populates="session", cascade="all, delete-orphan"
    )


class SessionMarker(Base):
    __tablename__ = "session_markers"

    id: Mapped[int] = mapped_column(primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("tcm_sessions.id"), index=True)
    offset_seconds: Mapped[int] = mapped_column()
    label: Mapped[str] = mapped_column(String(200))

    session: Mapped[TcmSession] = relationship(back_populates="markers")


class SessionSample(Base):
    """One band-power reading during a session: `band` is one of
    alpha|beta|delta|theta|gamma (relative power, averaged across the 4 EEG
    channels), `value` in [0, 1]."""

    __tablename__ = "session_samples"
    __table_args__ = (
        Index("ix_session_samples_session_offset", "session_id", "offset_seconds"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("tcm_sessions.id"), index=True)
    offset_seconds: Mapped[int] = mapped_column()
    band: Mapped[str] = mapped_column(String(16))
    value: Mapped[float] = mapped_column()

    session: Mapped[TcmSession] = relationship(back_populates="samples")


class SessionEegMetric(Base):
    """One session-half's aperiodic fit (docs/eeg-aperiodic-correction.md):
    the non-oscillatory background (offset/exponent) that a
    naive relative-band-power trend can't separate from an oscillatory
    change. Written once by POST /sessions/{id}/eeg/analyze; that endpoint is
    idempotent-by-replacement (re-analyzing deletes and re-inserts both halves'
    rows rather than erroring on the unique constraint below), since a
    practitioner may want to re-run analysis after this pipeline's tuning
    improves."""

    __tablename__ = "session_eeg_metrics"
    __table_args__ = (
        UniqueConstraint("session_id", "half", name="uq_session_eeg_metric_half"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("tcm_sessions.id"), index=True)
    # "first" | "second" (no baseline recorded) or "baseline" | "treatment"
    # (session has one — see eeg.analysis.analyze_session_eeg). String(16),
    # not (8): "treatment" is 9 characters and would truncate/error on a
    # backend that enforces VARCHAR length (SQLite doesn't, Postgres does).
    half: Mapped[str] = mapped_column(String(16))
    aperiodic_offset: Mapped[float] = mapped_column()
    aperiodic_exponent: Mapped[float] = mapped_column()
    r_squared: Mapped[float] = mapped_column()
    epoch_count: Mapped[int] = mapped_column()
    # Signal-derived arousal marker — counts SLOW ROLLING EYE MOVEMENTS
    # (SEMs), a well-established sleep-onset marker, using the two frontal
    # channels as an EOG proxy (this headband has no dedicated EOG
    # electrode) — see eeg/artifacts.py's EOG_* constants for the method. Null
    # eog_sem_rate_per_min means "not enough clean signal this half to score"
    # (see HalfResult.eog_sem_rate_per_min for the cases), not a rate of
    # zero — same "informative absence" pattern as
    # SessionOscillationPeak's per-band absence. eog_windows_analyzed is 0 in
    # that case too, so a reader can tell "not enough data" from "scored, and
    # the rate happens to be low" without re-deriving it.
    eog_sem_rate_per_min: Mapped[float | None] = mapped_column(nullable=True)
    eog_windows_analyzed: Mapped[int] = mapped_column(default=0)
    # What this half LOST, split by cause rather than summed: bad_contact is
    # the headband sending empty (NaN) readings - a lifted electrode that still
    # sends numbers is NOT counted - amplitude is movement, a jaw clench, or a
    # loose electrode being handled, and partial_contact counts epochs that
    # were KEPT but measured by fewer
    # electrodes than the headband has. One "rejected" total would hide
    # which of those happened, and they call for different responses.
    # Nullable for the same reason as the tcm_sessions pair above — null is
    # "this analysis predates these fields," not zero.
    epochs_rejected_bad_contact: Mapped[int | None] = mapped_column(nullable=True)
    epochs_rejected_amplitude: Mapped[int | None] = mapped_column(nullable=True)
    epochs_partial_contact: Mapped[int | None] = mapped_column(nullable=True)
    # This half's power spectrum and specparam's fit to it, as the four
    # index-aligned arrays eeg.analysis._spectrum_arrays documents (~177
    # points each) — what the session card plots under the numbers, so a fit
    # can be judged by looking at it rather than through r² alone.
    #
    # Stored at analyze time, NOT recomputed at read time: blobs are retained
    # and the pipeline keeps changing, so a read-time spectrum could come from
    # newer code than the offset/exponent/r² stored beside it, and the picture
    # and the numbers would disagree with nothing to show it. JSON rather than
    # a child table because it is only ever read whole, with its own row.
    # Null means "this analysis predates the field" — re-analyze to fill it.
    spectrum: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    # Large slow deflections per minute in this half - blinks, eye or head
    # movement (eeg.artifacts._deflection_times). Compared across the two
    # halves by eeg.interpretation.eye_activity_differs. Null means the analysis
    # predates the field, the half was too short to rate, or no electrode
    # pair was left to measure on - never a rate of zero.
    deflection_rate_per_min: Mapped[float | None] = mapped_column(nullable=True)


class SessionOscillationPeak(Base):
    """One detected oscillatory peak, on top of the aperiodic background
    captured in SessionEegMetric above — at most one row per (session, half,
    band); ABSENT entirely for a band where specparam found no real peak in
    that half, which is itself informative (see eeg/analysis.py) rather than
    a zero/null value that would look like "measured, and it was zero"."""

    __tablename__ = "session_oscillation_peaks"

    id: Mapped[int] = mapped_column(primary_key=True)
    session_id: Mapped[int] = mapped_column(ForeignKey("tcm_sessions.id"), index=True)
    half: Mapped[str] = mapped_column(String(16))  # see SessionEegMetric.half above
    band: Mapped[str] = mapped_column(String(16))
    center_frequency: Mapped[float] = mapped_column()
    power: Mapped[float] = mapped_column()
    bandwidth: Mapped[float] = mapped_column()
