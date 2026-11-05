import secrets
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..config import settings
from ..database import get_db
from ..email_service import send_verification_email
from ..models import Clinic, PendingRegistration, Role, User, utcnow
from ..schemas import (
    DevLoginRequest,
    GoogleLoginRequest,
    PasswordLoginRequest,
    PendingRegistrationOut,
    RegisterRequest,
    TokenResponse,
    UserOut,
    VerifyEmailRequest,
)
from ..security import (
    create_access_token,
    get_current_user,
    hash_password,
    verify_google_id_token,
    verify_password,
)

router = APIRouter(prefix="/auth", tags=["auth"])

VERIFICATION_CODE_TTL_MINUTES = 15
MAX_VERIFY_ATTEMPTS = 5


def _default_clinic(db: Session) -> Clinic:
    clinic = db.scalar(select(Clinic).where(Clinic.name == settings.seed_clinic_name))
    if clinic is None:
        clinic = Clinic(name=settings.seed_clinic_name)
        db.add(clinic)
        db.flush()
    return clinic


def _login_or_create(db: Session, *, email: str, name: str, role: Role | None) -> TokenResponse:
    user = db.scalar(select(User).where(User.email == email))
    if user is None:
        if role is None:
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "role is required on first sign-in",
            )
        if not name.strip():
            # Names are shown to practitioners/patients (patient lists,
            # session screens) — an account without one renders badly.
            raise HTTPException(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "name is required on first sign-in",
            )
        user = User(email=email, name=name.strip(), role=role)
        if role == Role.practitioner:
            user.clinic = _default_clinic(db)
        db.add(user)
        db.commit()
    return TokenResponse(
        access_token=create_access_token(user),
        user=_user_out(user),
    )


def _user_out(user: User) -> UserOut:
    out = UserOut.model_validate(user)
    out.clinic_name = user.clinic.name if user.clinic else None
    return out


def _sweep_expired_pending(db: Session) -> None:
    """Lazy cleanup — no cron in this prototype, so expired rows are purged
    opportunistically on the next register/verify call instead."""
    db.execute(delete(PendingRegistration).where(PendingRegistration.expires_at < utcnow()))


@router.post("/google", response_model=TokenResponse)
def google_login(body: GoogleLoginRequest, db: Session = Depends(get_db)):
    """Signs in to an EXISTING account, linking the Google identity on first
    use. Never creates one: new accounts go through /auth/register, whose
    emailed code is what gates who becomes a practitioner. Creating one here
    would let any Google account the OAuth client accepts skip that."""
    claims = verify_google_id_token(body.id_token)
    # The account is found by email, so the email must be one Google has
    # verified; otherwise someone could claim another person's address on a
    # Google account and sign in as them. Checked before the lookup so an
    # unverified address can't probe which accounts exist either.
    if claims.get("email_verified") is not True:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "This Google account's email isn't verified")
    user = db.scalar(select(User).where(User.email == claims["email"]))
    if user is None:
        # Both apps match "role is required" in this message to show their
        # "create an account first" prompt, so installed builds rely on it.
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "role is required on first sign-in: Google sign-in only links to an "
            "existing account, so create the account first",
        )
    if not user.google_id:
        user.google_id = claims["sub"]
        db.commit()
    return TokenResponse(access_token=create_access_token(user), user=_user_out(user))


@router.post("/dev", response_model=TokenResponse)
def dev_login(body: DevLoginRequest, db: Session = Depends(get_db)):
    if not settings.auth_dev_mode:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Not found")
    return _login_or_create(db, email=body.email, name=body.name, role=body.role)


@router.post("/register", response_model=PendingRegistrationOut, status_code=status.HTTP_201_CREATED)
def register(body: RegisterRequest, db: Session = Depends(get_db)):
    """Starts a signup: stashes the account in PendingRegistration and emails
    a 6-digit code — the real User row isn't created until POST /auth/verify
    succeeds (see PendingRegistration's docstring for why). Calling this
    again for a still-pending email IS the resend path: it replaces the old
    pending row (and invalidates its code) with a fresh one."""
    if not body.name.strip():
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "name is required")
    _sweep_expired_pending(db)
    existing = db.scalar(select(User).where(User.email == body.email))
    if existing is not None:
        # Same 409 whether the email is password-less (Google/dev) or already
        # has a password — don't hint at which case it is.
        raise HTTPException(status.HTTP_409_CONFLICT, "An account with this email already exists")

    db.execute(delete(PendingRegistration).where(PendingRegistration.email == body.email))
    code = f"{secrets.randbelow(1_000_000):06d}"
    pending = PendingRegistration(
        email=body.email,
        name=body.name.strip(),
        role=body.role,
        password_hash=hash_password(body.password),
        code_hash=hash_password(code),
        expires_at=utcnow() + timedelta(minutes=VERIFICATION_CODE_TTL_MINUTES),
    )
    db.add(pending)
    db.flush()  # visible within this txn; rolled back by get_db's close() if send fails below
    recipient = body.email
    if body.role == Role.practitioner and settings.practitioner_verification_inbox:
        recipient = settings.practitioner_verification_inbox
    send_verification_email(recipient, code)  # raises HTTPException(503) on failure/misconfig
    db.commit()
    return PendingRegistrationOut(email=body.email, code_sent_to=recipient, expires_at=pending.expires_at)


@router.post("/verify", response_model=TokenResponse)
def verify_email(body: VerifyEmailRequest, db: Session = Depends(get_db)):
    _sweep_expired_pending(db)
    pending = db.scalar(select(PendingRegistration).where(PendingRegistration.email == body.email))
    if pending is None:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            "No pending registration found for this email, or the code has expired. "
            "Please register again.",
        )
    if not verify_password(body.code, pending.code_hash):
        pending.attempts += 1
        # A pending row is deleted the instant it hits the cap (below), so it
        # can never persist with attempts >= MAX_VERIFY_ATTEMPTS — this check
        # only ever fires on the attempt that reaches the cap, not a later one.
        if pending.attempts >= MAX_VERIFY_ATTEMPTS:
            db.delete(pending)
            db.commit()
            raise HTTPException(
                status.HTTP_429_TOO_MANY_REQUESTS,
                "Too many incorrect attempts. Please register again to receive a new code.",
            )
        db.commit()
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid verification code")

    user = User(
        email=pending.email, name=pending.name, role=pending.role,
        password_hash=pending.password_hash,
    )
    if pending.role == Role.practitioner:
        user.clinic = _default_clinic(db)
    db.add(user)
    db.delete(pending)
    try:
        db.commit()
    except IntegrityError:
        # Race: another path (Google/dev/a concurrent verify) claimed this
        # email between register() and this call.
        db.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, "An account with this email already exists")
    return TokenResponse(
        access_token=create_access_token(user),
        user=_user_out(user),
    )


@router.post("/login", response_model=TokenResponse)
def login(body: PasswordLoginRequest, db: Session = Depends(get_db)):
    user = db.scalar(select(User).where(User.email == body.email))
    invalid = HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid email or password")
    # Identical error for "no such user", "no password set", and "wrong
    # password" — a different message per case would let a caller enumerate
    # which accounts exist.
    if user is None or user.password_hash is None:
        raise invalid
    if not verify_password(body.password, user.password_hash):
        raise invalid
    return TokenResponse(
        access_token=create_access_token(user),
        user=_user_out(user),
    )


@router.get("/me", response_model=UserOut)
def me(user: User = Depends(get_current_user)):
    return _user_out(user)
