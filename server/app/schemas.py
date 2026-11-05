import math
from datetime import UTC, datetime

from pydantic import BaseModel, EmailStr, Field, field_serializer, field_validator, model_validator

from .models import Role


def to_utc_naive(dt: datetime) -> datetime:
    """Normalize any datetime to naive UTC — the canonical internal form.

    SQLite discards tzinfo on storage, so keeping everything naive-UTC makes
    dedupe comparisons and range queries behave identically on SQLite and
    Postgres.
    """
    if dt.tzinfo is not None:
        dt = dt.astimezone(UTC).replace(tzinfo=None)
    return dt


def serialize_utc(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    return to_utc_naive(dt).isoformat(timespec="seconds") + "Z"

# Relative band-power channels the Muse SDK exposes natively (ALPHA_RELATIVE,
# BETA_RELATIVE, etc.) — see the Android client's packet-listener registration.
# The "_absolute" variants (ALPHA_ABSOLUTE etc.) ride alongside as a second,
# independent series: relative power is a share of total power (all 5 sum to
# ~100%), so a rise in one band mechanically forces the others down whether
# or not anything real changed. Absolute power doesn't have that particular
# artifact (though it's still uncorrected raw band power, so it still carries
# the 1/f-background/drowsiness confound in docs/eeg-aperiodic-correction.md).
# Nothing reads these yet — BAND_ORDER (Android's Models.kt / ai_summary.py)
# only covers the relative 5 — they're stored for later analysis.
BAND_TYPES = {
    "alpha", "beta", "delta", "theta", "gamma",
    "alpha_absolute", "beta_absolute", "delta_absolute", "theta_absolute", "gamma_absolute",
}


class GoogleLoginRequest(BaseModel):
    # No role or name: Google sign-in never creates an account, so there's
    # nothing to apply them to. A client that still sends them is ignored.
    id_token: str


class DevLoginRequest(BaseModel):
    email: EmailStr
    role: Role | None = None
    name: str = ""


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=128)
    name: str = Field(min_length=1)
    role: Role


class PasswordLoginRequest(BaseModel):
    email: EmailStr
    password: str


class PendingRegistrationOut(BaseModel):
    email: str
    # Where the code actually went: the typed address, or for a practitioner
    # the team inbox (config.practitioner_verification_inbox). The apps show
    # this rather than guessing.
    code_sent_to: str
    expires_at: datetime

    @field_serializer("expires_at")
    def _ser(self, v: datetime) -> str:
        return serialize_utc(v)


class VerifyEmailRequest(BaseModel):
    email: EmailStr
    code: str = Field(min_length=6, max_length=6, pattern=r"^\d{6}$")


class UserOut(BaseModel):
    id: int
    # Null for a name-only patient with no account/email assigned yet (see
    # models.User.email) — never null for a practitioner or a patient who can
    # actually sign in.
    email: str | None = None
    name: str
    role: Role
    clinic_name: str | None = None

    model_config = {"from_attributes": True}


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserOut


class AddPatientRequest(BaseModel):
    """A practitioner adding a patient to their roster, with no device
    involved yet — the patient connects to whatever headband is on hand
    later, at session time (see models.PatientRoster).

    Two ways in: `patient_email` links an account that already exists (404
    if there's no such account). `patient_name`
    instead creates a brand-new NAME-ONLY patient with no email/login at all
    (models.User.email nullable) — intake before the patient has any account,
    the email assigned later via PATCH /patients/{id}/email once it's known
    which account should receive their data. Exactly one is required; if both
    are given, patient_email wins (it targets a specific existing account, so
    patient_name would be ignored anyway)."""

    patient_email: EmailStr | None = None
    patient_name: str | None = Field(default=None, min_length=1, max_length=120)

    @model_validator(mode="after")
    def _one_required(self) -> "AddPatientRequest":
        if not self.patient_email and not self.patient_name:
            raise ValueError("patient_email or patient_name is required")
        return self


class AssignPatientEmailRequest(BaseModel):
    """Assigns an email to a name-only patient (models.User.email was null) —
    "choosing the account to give this patient's session information to."
    Deliberately one-shot: only settable while the patient's email is still
    null (see routers.patients.assign_patient_email), so a practitioner can't
    reroute a patient's data to a different account later by mistake. This
    does NOT give the patient a password; Google Sign-In with that email
    links to this account (POST /auth/google)."""

    email: EmailStr


class PatientSummary(BaseModel):
    id: int
    name: str
    # Null until a practitioner assigns one (see AssignPatientEmailRequest).
    email: str | None
    headband_device_id: str | None = None
    last_session_at: datetime | None = None

    @field_serializer("last_session_at")
    def _ser(self, v: datetime | None) -> str | None:
        return serialize_utc(v)


class PatientNoteIn(BaseModel):
    body: str = Field(min_length=1, max_length=2000)


class PatientNoteOut(BaseModel):
    id: int
    body: str
    created_at: datetime
    # Populated from PatientNote.practitioner_name — only meaningfully used
    # by the patient's own self-view (GET /me/notes), where a note can come
    # from any practitioner who's treated them; harmless (just their own
    # name) on the practitioner's own list, which is always self-authored.
    practitioner_name: str

    model_config = {"from_attributes": True}

    @field_serializer("created_at")
    def _ser(self, v: datetime) -> str:
        return serialize_utc(v)


class AiSummaryOut(BaseModel):
    """AI-generated, descriptive-only — never a clinical recommendation. See
    ai_summary.py's system prompts for the framing this enforces. Shared by
    both the patient-level (POST /patients/{id}/summary) and session-level
    (POST /sessions/{id}/summary) summary endpoints — same shape either way."""

    summary: str


class TranscribeOut(BaseModel):
    """POST /sessions/transcribe's response — the verbatim text AssemblyAI heard
    in a voice-marker recording, for the client to attach as a marker label
    (or show for the practitioner to edit)."""

    text: str


# ---- Muse headband + TCM sessions ----


class RegisterHeadbandRequest(BaseModel):
    device_id: str = Field(min_length=1, max_length=64)
    patient_email: EmailStr


class HeadbandAssignmentOut(BaseModel):
    headband_device_id: str
    patient: UserOut
    started_at: datetime
    ended_at: datetime | None = None

    @field_serializer("started_at")
    def _ser_started(self, v: datetime) -> str:
        return serialize_utc(v)

    @field_serializer("ended_at")
    def _ser_ended(self, v: datetime | None) -> str | None:
        return serialize_utc(v)


class HeadbandSummary(BaseModel):
    """One headband in the clinic's inventory, with its current usage state.

    Privacy: `patient` (and `assigned_since`) are populated ONLY when the
    active assignment belongs to the requesting practitioner; other
    practitioners' patients are never exposed, their headbands just read
    in_use."""

    device_id: str
    in_use: bool
    patient: UserOut | None = None
    assigned_since: datetime | None = None

    @field_serializer("assigned_since")
    def _ser(self, v: datetime | None) -> str | None:
        return serialize_utc(v)


class SessionMarkerIn(BaseModel):
    offset_seconds: int = Field(ge=0)
    label: str = Field(min_length=1, max_length=200)


class SessionMarkerOut(SessionMarkerIn):
    model_config = {"from_attributes": True}


class SessionSampleIn(BaseModel):
    offset_seconds: int = Field(ge=0)
    band: str
    value: float

    @field_validator("band")
    @classmethod
    def _known_band(cls, v: str) -> str:
        if v not in BAND_TYPES:
            raise ValueError(f"band must be one of {sorted(BAND_TYPES)}")
        return v

    @field_validator("value")
    @classmethod
    def _finite_value(cls, v: float) -> float:
        if not math.isfinite(v):
            raise ValueError("value must be a finite number")
        return v


class SessionSampleOut(SessionSampleIn):
    model_config = {"from_attributes": True}


class TcmSessionCreate(BaseModel):
    patient_id: int
    # The phone's id for this recording, the same on every retry; see
    # models.TcmSession.client_session_id. Optional so older clients work.
    client_session_id: str | None = Field(default=None, min_length=1, max_length=64)
    headband_device_id: str | None = None
    modality: str = Field(min_length=1, max_length=32)
    started_at: datetime
    ended_at: datetime
    practitioner_note: str | None = Field(default=None, max_length=2000)
    # Optional post-session context — see the matching comment on
    # models.TcmSession. Both null means "not answered," not "no/lowest,"
    # so an old client (or a practitioner who skipped the prompt) doesn't
    # silently read as "confirmed alert."
    patient_dozed: bool | None = None
    alertness_rating: int | None = Field(default=None, ge=1, le=7)
    # See the matching comment on models.TcmSession.baseline_duration_seconds
    # — null means no baseline phase was recorded. ge=0 only; no upper bound
    # here (validated meaningfully against the actual recording length in
    # eeg.analysis.analyze_session_eeg, not against a guessed ceiling here).
    baseline_duration_seconds: int | None = Field(default=None, ge=0)
    # See models.TcmSession.treatment_end_seconds — null means Treatment done
    # wasn't tapped. Checked against the baseline (below), not against the
    # recording length: the phone's elapsed clock and ended_at's wall clock
    # can disagree by a second or two.
    treatment_end_seconds: int | None = Field(default=None, ge=0)
    markers: list[SessionMarkerIn] = Field(default_factory=list)
    # 10 bands (relative + absolute, see BAND_TYPES) per second for up to
    # ~66 minutes.
    samples: list[SessionSampleIn] = Field(default_factory=list, max_length=40000)

    @field_validator("started_at", "ended_at")
    @classmethod
    def _normalize(cls, v: datetime) -> datetime:
        return to_utc_naive(v)

    @field_validator("ended_at")
    @classmethod
    def _ended_after_started(cls, v: datetime, info) -> datetime:
        started = info.data.get("started_at")
        if started is not None and v < started:
            raise ValueError("ended_at must not be before started_at")
        return v

    @model_validator(mode="after")
    def _treatment_end_not_before_baseline(self) -> "TcmSessionCreate":
        # The phone can't send this (it hides the baseline button once
        # Treatment done is tapped, and Treatment done always comes later
        # than a baseline already marked), so a violation is a client bug:
        # refuse it rather than store a "before" window that starts after the
        # "after" one.
        if (
            self.baseline_duration_seconds is not None
            and self.treatment_end_seconds is not None
            and self.treatment_end_seconds < self.baseline_duration_seconds
        ):
            raise ValueError("treatment_end_seconds must not be before baseline_duration_seconds")
        return self


class TcmSessionNoteUpdate(BaseModel):
    """Practitioner notes are often written up after the session has already
    ended and been uploaded — this lets one be set or edited from the past-
    sessions screen without redoing the whole session."""

    practitioner_note: str | None = Field(default=None, max_length=2000)


class TcmSessionSummary(BaseModel):
    """One row in a patient's session list — no samples/markers, just enough
    to render the list screen."""

    id: int
    modality: str
    started_at: datetime
    ended_at: datetime

    model_config = {"from_attributes": True}

    @field_serializer("started_at", "ended_at")
    def _ser(self, v: datetime) -> str:
        return serialize_utc(v)


class EegUploadOut(BaseModel):
    """POST /sessions/{id}/eeg's response — echoes back what the server
    parsed from the binary blob (see eeg/binary.py), mainly so the client can
    log/confirm the upload landed with the sample count and rate it
    expected."""

    sample_count: int
    sample_rate_hz: float


class OscillationPeakOut(BaseModel):
    band: str
    center_frequency: float
    power: float
    bandwidth: float

    model_config = {"from_attributes": True}


class EegSpectrumOut(BaseModel):
    """One half's spectrum and fit, index-aligned — see
    eeg.analysis._spectrum_arrays. Power values are log10, the space the fit
    was made in; with frequency also on a log axis, the aperiodic line is
    straight and its slope is aperiodic_exponent."""

    freqs_hz: list[float]
    log_power: list[float]
    log_model_fit: list[float]
    log_aperiodic_fit: list[float]


class EegHalfMetricOut(BaseModel):
    half: str
    aperiodic_offset: float
    aperiodic_exponent: float
    r_squared: float
    epoch_count: int
    # Signal-derived arousal marker, independent of self-report — see
    # models.SessionEegMetric.eog_sem_rate_per_min. Null means "not enough
    # clean signal this half to score," not a rate of zero.
    eog_sem_rate_per_min: float | None
    eog_windows_analyzed: int
    # Null (not 0) on an analysis that predates these fields — see
    # models.SessionEegMetric. The client words that as "not recorded"
    # rather than showing a zero it can't stand behind.
    epochs_rejected_bad_contact: int | None = None
    epochs_rejected_amplitude: int | None = None
    epochs_partial_contact: int | None = None
    peaks: list[OscillationPeakOut]
    # Null on an analysis that predates the field — the client says so
    # rather than drawing an empty plot.
    spectrum: EegSpectrumOut | None = None
    # Large slow deflections per minute (blinks, eye or head movement) - see
    # models.SessionEegMetric.deflection_rate_per_min. Null is "not
    # measured", never zero.
    deflection_rate_per_min: float | None = None


class ElectrodeFindingOut(BaseModel):
    """One electrode-check finding - see eeg.artifacts.ElectrodeFinding and
    the ELECTRODE_* constants. `sign` is interference_lines |
    upside_down_spectrum | no_signal | much_noisier_than_partner; `value` is
    in log10 (0 for no_signal)."""

    channel: int
    channel_name: str
    half: str
    sign: str
    value: float
    excluded: bool
    partner_name: str | None = None


class EegAnalysisOut(BaseModel):
    """Shared response shape for both POST /sessions/{id}/eeg/analyze (runs
    the pipeline fresh) and GET /sessions/{id}/eeg-metrics (rereads whatever
    was last stored) — same data either way, see routers/eeg.py."""

    halves: list[EegHalfMetricOut]
    analyzed_at: datetime | None
    # Session-wide recording quality (models.TcmSession's eeg_* group).
    # observed_sample_rate_hz is the rate the headband ACTUALLY achieved,
    # derived from the raw blob's timestamps - note it is written at UPLOAD
    # time, so it is non-null for any uploaded session including one that was
    # never analyzed. The other two are written by analyze, and are null for
    # an analysis that predates them.
    observed_sample_rate_hz: float | None = None
    interpolated_sample_fraction: float | None = None
    excluded_channel_indices: list[int] | None = None
    # The electrode check behind excluded_channel_indices. Null means the
    # analysis predates the check - then a non-empty exclusion came from
    # the old eye correction, and the client says so. [] means it ran and
    # found nothing.
    electrode_findings: list[ElectrodeFindingOut] | None = None
    # Every electrode flagged, so none could be excluded: the halves' empty
    # peaks mean "not evaluable" and their rates weren't measured. Null when
    # the analysis predates the electrode check.
    all_electrodes_flagged: bool | None = None
    # Supplied by the router from eeg.interpretation.LOW_FIT_R_SQUARED (not defaulted
    # here - one definition, and schemas must not import the pipeline). The
    # client flags exactly the halves ai_summary hedges.
    low_fit_r_squared: float
    # eeg.interpretation.eye_activity_differs over the two stored halves, computed by
    # the router so the card and ai_summary apply one rule. Null when it
    # couldn't be checked (no halves, or neither measure on both).
    eye_activity_differs: bool | None = None

    @field_serializer("analyzed_at")
    def _ser(self, v: datetime | None) -> str | None:
        return serialize_utc(v)


class TcmSessionOut(BaseModel):
    id: int
    patient_id: int
    modality: str
    started_at: datetime
    ended_at: datetime
    practitioner_note: str | None
    patient_dozed: bool | None
    alertness_rating: int | None
    baseline_duration_seconds: int | None
    treatment_end_seconds: int | None = None
    markers: list[SessionMarkerOut]
    samples: list[SessionSampleOut]

    model_config = {"from_attributes": True}

    @field_serializer("started_at", "ended_at")
    def _ser(self, v: datetime) -> str:
        return serialize_utc(v)


class EegTrendPointOut(BaseModel):
    """One session-half's corrected EEG metrics, for GET /patients/{id}/eeg-trend.
    This is deliberately a flat list of points (one per session-half that has been
    analyzed), not a pre-aggregated series — the client groups by session_id
    and orders by session_started_at itself, same "server sends facts, client
    decides how to draw them" split as everywhere else in this app.

    `period` is 0 for the earlier half ("first" or "baseline") and 1 for the
    later half ("second" or "treatment"), regardless of which raw `half`
    label this particular session actually used — lets the client plot a
    consistent two-series trend (e.g. "earlier half" vs "later half" per
    session date) across a patient's history even though some sessions have a
    recorded baseline and others only have the wall-clock midpoint split (see
    models.TcmSession.baseline_duration_seconds). `half` is still included
    so the client can label which kind of split a given point actually is.

    Deliberately ONE PATIENT's OWN history only — this endpoint is already
    scoped to sessions this practitioner authored for this one patient (see
    routers/eeg.py), and these numbers should never be compared against
    another patient's, only against this same patient's own earlier
    sessions."""

    session_id: int
    session_started_at: datetime
    half: str
    period: int
    aperiodic_offset: float
    aperiodic_exponent: float
    r_squared: float
    epoch_count: int
    eog_sem_rate_per_min: float | None
    eog_windows_analyzed: int
    # eeg.interpretation.LOW_FIT_R_SQUARED, supplied by the router like EegAnalysisOut's
    # field of the same name, so the trend tab flags exactly the halves the
    # session card and ai_summary do. Repeated on every point because this
    # endpoint returns a bare list; wrapping it would break older clients.
    low_fit_r_squared: float
    deflection_rate_per_min: float | None = None
    # Session-level facts repeated on both of a session's points, for the
    # same bare-list reason: whether its halves' eye activity differed
    # (eeg.interpretation.eye_activity_differs, the card's rule), which electrodes its
    # analysis left out, and whether every electrode was flagged (see
    # EegAnalysisOut). Null means the analysis predates the field.
    eye_activity_differs: bool | None = None
    excluded_channel_names: list[str] | None = None
    all_electrodes_flagged: bool | None = None

    @field_serializer("session_started_at")
    def _ser(self, v: datetime) -> str:
        return serialize_utc(v)
