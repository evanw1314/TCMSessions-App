from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select, update
from sqlalchemy.orm import Session

from ..ai_summary import generate_patient_summary
from ..database import get_db
from ..models import (
    HeadbandAssignment,
    PatientNote,
    PatientRoster,
    Role,
    TcmSession,
    User,
    utcnow,
)
from ..schemas import (
    AddPatientRequest,
    AiSummaryOut,
    AssignPatientEmailRequest,
    PatientNoteIn,
    PatientNoteOut,
    PatientSummary,
)
from ..security import require_role

router = APIRouter(tags=["patients"])


def _practitioner_covers_patient(db: Session, practitioner_id: int, patient_id: int) -> bool:
    """Same "currently cares for this patient" test list_patients uses: on the
    roster, or an active headband assignment. Gates practitioner-authored
    per-patient writes (notes, AI summary) to patients the caller actually has
    a relationship with — without it, any practitioner could POST a note (or
    burn an AI summary call) against an arbitrary patient_id in the system."""
    if db.scalar(
        select(PatientRoster).where(
            PatientRoster.practitioner_id == practitioner_id,
            PatientRoster.patient_id == patient_id,
        )
    ):
        return True
    if db.scalar(
        select(HeadbandAssignment).where(
            HeadbandAssignment.practitioner_id == practitioner_id,
            HeadbandAssignment.patient_id == patient_id,
            HeadbandAssignment.ended_at.is_(None),
        )
    ):
        return True
    return False


@router.get("/patients", response_model=list[PatientSummary])
def list_patients(
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Patients this practitioner currently cares for — on their roster
    (PatientRoster, added explicitly and independent of any device), an
    active headband assignment, or both. A patient may be added at intake
    with no device at all, and a headband may be assigned without a roster
    entry, so this unions the two rather than filtering on one."""
    roster = db.scalars(
        select(PatientRoster).where(PatientRoster.practitioner_id == practitioner.id)
    ).all()
    headband_assignments = db.scalars(
        select(HeadbandAssignment).where(
            HeadbandAssignment.practitioner_id == practitioner.id,
            HeadbandAssignment.ended_at.is_(None),
        )
    ).all()

    by_patient: dict[int, PatientSummary] = {}

    def summary_for(patient: User) -> PatientSummary:
        if patient.id not in by_patient:
            by_patient[patient.id] = PatientSummary(id=patient.id, name=patient.name, email=patient.email)
        return by_patient[patient.id]

    for a in roster:
        summary_for(a.patient)

    for a in headband_assignments:
        s = summary_for(a.patient)
        s.headband_device_id = a.headband.device_id

    # Computed for every listed patient, not just those holding a headband:
    # headbands are normally unassigned after a session, and the patient's
    # history shouldn't vanish with it. Scoped like sessions.py: this
    # practitioner's own, non-deleted sessions (TcmSession is per-practitioner).
    last_sessions = dict(
        db.execute(
            select(TcmSession.patient_id, func.max(TcmSession.ended_at))
            .where(
                TcmSession.practitioner_id == practitioner.id,
                TcmSession.deleted_at.is_(None),
            )
            .group_by(TcmSession.patient_id)
        ).all()
    )
    for patient_id, s in by_patient.items():
        s.last_session_at = last_sessions.get(patient_id)

    return sorted(by_patient.values(), key=lambda s: s.name)


@router.post("/patients", response_model=PatientSummary, status_code=status.HTTP_201_CREATED)
def add_patient(
    body: AddPatientRequest,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Adds a patient to this practitioner's roster — no device required (see
    PatientRoster). Two paths (see AddPatientRequest's docstring):

    - `patient_email`: link an EXISTING patient account, same "ask them to
      sign up first" rule as register_headband.
    - `patient_name` (no email given): create a brand-new NAME-ONLY patient —
      intake before the patient has any account. Its email stays null until
      a later PATCH /patients/{id}/email.
    """
    if body.patient_email:
        patient = db.scalar(select(User).where(User.email == body.patient_email))
        if patient is None or patient.role != Role.patient:
            raise HTTPException(
                status.HTTP_404_NOT_FOUND,
                "No patient account with that email — ask the patient to sign up first",
            )
        existing = db.scalar(
            select(PatientRoster).where(
                PatientRoster.practitioner_id == practitioner.id,
                PatientRoster.patient_id == patient.id,
            )
        )
        if existing is not None:
            raise HTTPException(status.HTTP_409_CONFLICT, "This patient is already in your list")
    else:
        name = body.patient_name.strip()
        if not name:
            # min_length=1 on the schema only rejects an empty string, not
            # whitespace — a blank name would render badly everywhere it's
            # shown (roster, session screens, notes), same reasoning as
            # auth.py's register() name check.
            raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "name is required")
        # Name-only: always a brand-new patient row (nothing to look up or
        # collide with — it has no email), so there's no "already in your
        # list" case to check.
        patient = User(name=name, role=Role.patient, email=None)
        db.add(patient)
        db.flush()  # assign patient.id before the roster row references it

    db.add(PatientRoster(practitioner_id=practitioner.id, patient_id=patient.id))
    db.commit()
    return PatientSummary(id=patient.id, name=patient.name, email=patient.email)


@router.delete("/patients/{patient_id}", status_code=status.HTTP_204_NO_CONTENT)
def remove_patient(
    patient_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Removes a patient from this practitioner's list entirely: drops the
    PatientRoster row (if any) AND ends any active headband assignment THIS
    practitioner holds for them, since a patient the practitioner no longer
    treats shouldn't be left holding a device registered under their care.
    Only ever touches this
    practitioner's own roster entry and assignments — the patient's account,
    history, and any other practitioner's roster entry for the same patient
    are untouched (a patient can be on several practitioners' rosters)."""
    patient = db.get(User, patient_id)
    if (
        patient is None
        or patient.role != Role.patient
        or not _practitioner_covers_patient(db, practitioner.id, patient_id)
    ):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown patient")

    roster_entry = db.scalar(
        select(PatientRoster).where(
            PatientRoster.practitioner_id == practitioner.id,
            PatientRoster.patient_id == patient_id,
        )
    )
    if roster_entry is not None:
        db.delete(roster_entry)

    active_headband = db.scalar(
        select(HeadbandAssignment).where(
            HeadbandAssignment.practitioner_id == practitioner.id,
            HeadbandAssignment.patient_id == patient_id,
            HeadbandAssignment.ended_at.is_(None),
        )
    )
    if active_headband is not None:
        active_headband.ended_at = utcnow()

    db.commit()


def _merge_name_only_patient(db: Session, *, source: User, target: User) -> None:
    """Folds a name-only intake patient (`source`, no email/login — see
    AddPatientRequest's docstring) into a real, already-existing patient
    account (`target`) that turns out to already own the email a
    practitioner just tried to assign `source`. Common case: the same person
    was added by name at intake AND separately has (or later created) their
    own account — without this, assign_patient_email's conflict check would
    just dead-end the practitioner with no way to attach `source`'s already-
    recorded sessions/notes to the account the patient actually logs into.

    Every TcmSession/PatientNote recorded against `source` is repointed to
    `target`; PatientRoster rows are merged rather than duplicated (per
    uq_roster_practitioner_patient — a practitioner may already have `target`
    on their roster too, from an earlier "add by email"). `source` can never
    have a headband assignment of its own: registering one requires
    patient_email (RegisterHeadbandRequest), which a name-only patient by
    definition doesn't have — so that table needs no migration here.
    `source` is deleted once emptied; `target`'s own name wins over
    `source`'s intake name (the account is the patient's real, self-chosen
    identity)."""
    db.execute(update(TcmSession).where(TcmSession.patient_id == source.id).values(patient_id=target.id))
    db.execute(update(PatientNote).where(PatientNote.patient_id == source.id).values(patient_id=target.id))
    for roster_row in db.scalars(select(PatientRoster).where(PatientRoster.patient_id == source.id)).all():
        already_on_roster = db.scalar(
            select(PatientRoster).where(
                PatientRoster.practitioner_id == roster_row.practitioner_id,
                PatientRoster.patient_id == target.id,
            )
        )
        if already_on_roster is not None:
            db.delete(roster_row)
        else:
            roster_row.patient_id = target.id
    db.delete(source)


@router.patch("/patients/{patient_id}/email", response_model=PatientSummary)
def assign_patient_email(
    patient_id: int,
    body: AssignPatientEmailRequest,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Assigns an email to a name-only patient — see AssignPatientEmailRequest's
    docstring for why this only works once, while the email is still null.

    If that email already belongs to a DIFFERENT, real (login-capable)
    patient account, this merges into it instead of rejecting — see
    _merge_name_only_patient. The only OTHER reachable conflict is a
    practitioner's own email, or (in principle, though the check just below
    already prevents a second placeholder from ever claiming a taken email)
    another still-unclaimed placeholder — neither is a person to merge into,
    so those still 409."""
    patient = db.get(User, patient_id)
    if (
        patient is None
        or patient.role != Role.patient
        or not _practitioner_covers_patient(db, practitioner.id, patient_id)
    ):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown patient")
    if patient.email is not None:
        raise HTTPException(
            status.HTTP_409_CONFLICT, "This patient already has an email assigned"
        )
    conflict = db.scalar(select(User).where(User.email == body.email))
    if conflict is not None:
        if conflict.role != Role.patient or (conflict.password_hash is None and conflict.google_id is None):
            raise HTTPException(status.HTTP_409_CONFLICT, "An account with that email already exists")
        _merge_name_only_patient(db, source=patient, target=conflict)
        db.commit()
        return PatientSummary(id=conflict.id, name=conflict.name, email=conflict.email)
    patient.email = body.email
    db.commit()
    return PatientSummary(id=patient.id, name=patient.name, email=patient.email)


@router.get("/patients/{patient_id}/notes", response_model=list[PatientNoteOut])
def list_patient_notes(
    patient_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """This practitioner's own notes about the patient, newest first — see
    PatientNote's docstring for why notes aren't shared across practitioners."""
    return db.scalars(
        select(PatientNote)
        .where(
            PatientNote.patient_id == patient_id,
            PatientNote.practitioner_id == practitioner.id,
            PatientNote.deleted_at.is_(None),
        )
        .order_by(PatientNote.created_at.desc())
    ).all()


@router.post("/patients/{patient_id}/notes", response_model=PatientNoteOut, status_code=status.HTTP_201_CREATED)
def add_patient_note(
    patient_id: int,
    body: PatientNoteIn,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    patient = db.get(User, patient_id)
    if (
        patient is None
        or patient.role != Role.patient
        or not _practitioner_covers_patient(db, practitioner.id, patient_id)
    ):
        # Same 404 whether the patient doesn't exist or just isn't this
        # practitioner's — don't confirm a patient_id exists to a caller who
        # has no relationship with them.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown patient")
    note = PatientNote(patient_id=patient_id, practitioner_id=practitioner.id, body=body.body)
    db.add(note)
    db.commit()
    db.refresh(note)
    return note


@router.delete("/notes/{note_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_patient_note(
    note_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """Soft delete only, same reasoning as sessions.delete_session — stamps
    deleted_at rather than removing the row, so an accidental delete is still
    recoverable from the DB directly. Not nested under /patients/{id} (mirrors
    /sessions/{id}): the author check on practitioner_id is enough to place it."""
    note = db.scalar(select(PatientNote).where(PatientNote.id == note_id))
    if note is None or note.practitioner_id != practitioner.id or note.deleted_at is not None:
        # Same 404 for "doesn't exist", "not yours" and "already deleted" —
        # avoids confirming a note id exists/existed to someone who can't see it.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown note")
    note.deleted_at = utcnow()
    db.commit()


@router.post("/patients/{patient_id}/summary", response_model=AiSummaryOut)
def summarize_patient(
    patient_id: int,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    """On-demand only — the practitioner app calls this from a "Summarize"
    button tap, never automatically, both to control API spend and because an
    ambient auto-generated summary reads as more authoritative than a tool the
    practitioner explicitly invoked. Descriptive-only framing is enforced in
    ai_summary.py's system prompt, not here — see that module's docstring."""
    patient = db.get(User, patient_id)
    if (
        patient is None
        or patient.role != Role.patient
        or not _practitioner_covers_patient(db, practitioner.id, patient_id)
    ):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown patient")

    notes = db.scalars(
        select(PatientNote)
        .where(
            PatientNote.patient_id == patient_id,
            PatientNote.practitioner_id == practitioner.id,
            PatientNote.deleted_at.is_(None),
        )
        .order_by(PatientNote.created_at.desc())
        .limit(20)
    ).all()
    sessions = db.scalars(
        select(TcmSession)
        .where(
            TcmSession.patient_id == patient_id,
            TcmSession.practitioner_id == practitioner.id,
            TcmSession.deleted_at.is_(None),
        )
        .order_by(TcmSession.started_at.desc())
        .limit(20)
    ).all()
    if not notes and not sessions:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "Nothing to summarize yet — no notes or sessions recorded for this patient",
        )

    summary = generate_patient_summary(patient_name=patient.name, notes=notes, sessions=sessions)
    return AiSummaryOut(summary=summary)


@router.get("/me/profile", response_model=PatientSummary)
def my_profile(
    patient: User = Depends(require_role(Role.patient)),
    db: Session = Depends(get_db),
):
    """The patient's own self-view profile — same shape as one row of
    GET /patients, but unscoped to a single practitioner: last_session_at and
    headband_device_id reflect this patient's WHOLE record (any practitioner
    who has ever treated them), not one practitioner's slice of it, since
    this is the patient looking at their own data rather than a
    practitioner's dashboard of their own patients."""
    active_headband = db.scalar(
        select(HeadbandAssignment).where(
            HeadbandAssignment.patient_id == patient.id,
            HeadbandAssignment.ended_at.is_(None),
        )
    )
    last_session_at = db.scalar(
        select(func.max(TcmSession.ended_at)).where(
            TcmSession.patient_id == patient.id,
            TcmSession.deleted_at.is_(None),
        )
    )
    return PatientSummary(
        id=patient.id, name=patient.name, email=patient.email,
        headband_device_id=active_headband.headband.device_id if active_headband else None,
        last_session_at=last_session_at,
    )


@router.get("/me/notes", response_model=list[PatientNoteOut])
def my_notes(
    patient: User = Depends(require_role(Role.patient)),
    db: Session = Depends(get_db),
):
    """Every note written about this patient, by any practitioner who has
    treated them, newest first. PatientNote's "never shared across
    practitioners" rule (see its docstring) is about practitioner-to-
    practitioner privacy — it doesn't extend to hiding a note from the
    person it's about."""
    return db.scalars(
        select(PatientNote)
        .where(
            PatientNote.patient_id == patient.id,
            PatientNote.deleted_at.is_(None),
        )
        .order_by(PatientNote.created_at.desc())
    ).all()
