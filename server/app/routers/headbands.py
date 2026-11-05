from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..database import get_db
from ..models import Headband, HeadbandAssignment, Role, User, utcnow
from ..schemas import HeadbandAssignmentOut, HeadbandSummary, RegisterHeadbandRequest, UserOut
from ..security import require_role

router = APIRouter(prefix="/headbands", tags=["headbands"])


def _current_assignment(db: Session, headband_id: int) -> HeadbandAssignment | None:
    return db.scalar(
        select(HeadbandAssignment).where(
            HeadbandAssignment.headband_id == headband_id,
            HeadbandAssignment.ended_at.is_(None),
        )
    )


@router.get("", response_model=list[HeadbandSummary])
def list_registered_headbands(
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    headbands = db.scalars(
        select(Headband).where(Headband.clinic_id == practitioner.clinic_id)
    ).all()
    summaries = []
    for headband in headbands:
        active = _current_assignment(db, headband.id)
        own = active is not None and active.practitioner_id == practitioner.id
        summaries.append(HeadbandSummary(
            device_id=headband.device_id,
            in_use=active is not None,
            patient=UserOut.model_validate(active.patient) if own else None,
            assigned_since=active.started_at if own else None,
        ))
    return summaries


@router.delete("/{device_id}/assignment", response_model=HeadbandSummary)
def unassign_headband(
    device_id: str,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    headband = db.scalar(
        select(Headband).where(
            Headband.device_id == device_id, Headband.clinic_id == practitioner.clinic_id
        )
    )
    if headband is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown headband")
    active = _current_assignment(db, headband.id)
    if active is None:
        raise HTTPException(status.HTTP_409_CONFLICT, "Headband is not currently assigned")
    if active.practitioner_id != practitioner.id:
        raise HTTPException(
            status.HTTP_403_FORBIDDEN,
            "Only the practitioner who assigned this headband can remove it",
        )
    active.ended_at = utcnow()
    db.commit()
    return HeadbandSummary(device_id=headband.device_id, in_use=False)


@router.post("", response_model=HeadbandAssignmentOut, status_code=status.HTTP_201_CREATED)
def register_headband(
    body: RegisterHeadbandRequest,
    practitioner: User = Depends(require_role(Role.practitioner)),
    db: Session = Depends(get_db),
):
    patient = db.scalar(select(User).where(User.email == body.patient_email))
    if patient is None or patient.role != Role.patient:
        raise HTTPException(
            status.HTTP_404_NOT_FOUND,
            "No patient account with that email — ask the patient to sign up first",
        )

    headband = db.scalar(select(Headband).where(Headband.device_id == body.device_id))
    if headband is None:
        # First time this device is seen: it joins the caller's clinic.
        headband = Headband(device_id=body.device_id, clinic_id=practitioner.clinic_id)
        db.add(headband)
        db.flush()
    elif headband.clinic_id != practitioner.clinic_id:
        # Same 404 as a never-registered device, so one clinic can't enumerate
        # or hijack another's headbands.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown headband")

    previous = _current_assignment(db, headband.id)
    if previous is not None:
        if previous.patient_id == patient.id:
            raise HTTPException(
                status.HTTP_409_CONFLICT, "Headband is already assigned to this patient"
            )
        # Only the assigning practitioner may end an assignment, or anyone
        # could silently take a headband out from under a colleague's patient.
        if previous.practitioner_id != practitioner.id:
            raise HTTPException(
                status.HTTP_403_FORBIDDEN,
                "This headband is assigned to another practitioner's patient. Ask "
                "them to release it first.",
            )
        previous.ended_at = utcnow()

    # One active headband per patient: the new one replaces any they hold.
    # Freeing a colleague's assignment this way would bypass the guard above,
    # so it is refused the same way.
    replaced = db.scalars(
        select(HeadbandAssignment).where(
            HeadbandAssignment.patient_id == patient.id,
            HeadbandAssignment.ended_at.is_(None),
            HeadbandAssignment.headband_id != headband.id,
        )
    ).all()
    foreign = next(
        (old for old in replaced if old.practitioner_id != practitioner.id), None
    )
    if foreign is not None:
        raise HTTPException(
            status.HTTP_403_FORBIDDEN,
            "This patient already has a headband assigned by another practitioner. "
            "Ask them to release it first.",
        )
    for old in replaced:
        old.ended_at = utcnow()

    # Flush the period-closing UPDATEs before inserting the new open row, or
    # SQLAlchemy may order the INSERT first and trip the partial unique
    # indexes on a legitimate reassignment.
    db.flush()

    assignment = HeadbandAssignment(
        headband_id=headband.id, patient_id=patient.id, practitioner_id=practitioner.id
    )
    db.add(assignment)
    try:
        db.commit()
    except IntegrityError:
        # A concurrent register_headband won the race; the unique indexes
        # caught it. Surface a retryable conflict rather than a 500.
        db.rollback()
        raise HTTPException(
            status.HTTP_409_CONFLICT,
            "That headband or patient was just assigned by someone else — reload and try again.",
        )
    return HeadbandAssignmentOut(
        headband_device_id=headband.device_id,
        patient=UserOut.model_validate(patient),
        started_at=assignment.started_at,
    )
