"""The password sign-up flow (routers/auth.py): register -> emailed code ->
verify -> login, and the guards around it - the attempt cap, expiry, and the
dev login being off unless AUTH_DEV_MODE is set."""
from datetime import timedelta

import pytest
from sqlalchemy import update

import app.routers.auth as auth_router
from app.config import settings
from app.database import SessionLocal
from app.models import PendingRegistration, User, utcnow

PASSWORD = "pw-123456"


@pytest.fixture
def codes(monkeypatch):
    """email -> the last code the server mailed for it."""
    sent: dict[str, str] = {}
    monkeypatch.setattr(auth_router, "send_verification_email", lambda to, code: sent.__setitem__(to, code))
    return sent


def _register(client, email="pat@example.com", role="patient"):
    return client.post("/auth/register", json={"email": email, "password": PASSWORD, "name": "Pat", "role": role})


def _verify(client, code, email="pat@example.com"):
    return client.post("/auth/verify", json={"email": email, "code": code})


def _login(client, password=PASSWORD, email="pat@example.com"):
    return client.post("/auth/login", json={"email": email, "password": password})


def _wrong(code: str) -> str:
    return f"{(int(code) + 1) % 1_000_000:06d}"


def test_register_verify_login(client, codes):
    assert _register(client).status_code == 201
    assert _login(client).status_code == 401  # no account until the code is verified

    verified = _verify(client, codes["pat@example.com"])
    assert verified.status_code == 200
    assert verified.json()["user"]["role"] == "patient"

    logged_in = _login(client)
    assert logged_in.status_code == 200
    token = logged_in.json()["access_token"]
    me = client.get("/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert me.json()["email"] == "pat@example.com"


def test_a_verified_practitioner_joins_the_clinic(client, codes, monkeypatch):
    monkeypatch.setattr(settings, "practitioner_verification_inbox", "")
    _register(client, "doc@example.com", "practitioner")
    user = _verify(client, codes["doc@example.com"], "doc@example.com").json()["user"]
    assert user["clinic_name"] == settings.seed_clinic_name


def test_a_code_is_used_once(client, codes):
    _register(client)
    code = codes["pat@example.com"]
    assert _verify(client, code).status_code == 200
    assert _verify(client, code).status_code == 400
    assert _register(client).status_code == 409  # the email now has an account


def test_five_wrong_codes_end_the_registration(client, codes):
    _register(client)
    code = codes["pat@example.com"]
    for _ in range(auth_router.MAX_VERIFY_ATTEMPTS - 1):
        assert _verify(client, _wrong(code)).status_code == 400
    assert _verify(client, _wrong(code)).status_code == 429
    # The right code no longer works: the pending row is gone.
    assert _verify(client, code).status_code == 400
    assert _login(client).status_code == 401


def test_registering_again_replaces_the_code(client, codes, monkeypatch):
    draws = iter([111111, 222222])
    monkeypatch.setattr(auth_router.secrets, "randbelow", lambda _n: next(draws))
    _register(client)
    _register(client)
    assert _verify(client, "111111").status_code == 400
    assert _verify(client, "222222").status_code == 200


def test_an_expired_code_is_refused(client, codes):
    _register(client)
    with SessionLocal() as db:
        db.execute(update(PendingRegistration).values(expires_at=utcnow() - timedelta(minutes=1)))
        db.commit()
    assert _verify(client, codes["pat@example.com"]).status_code == 400


def test_login_does_not_say_which_part_was_wrong(client, codes):
    _register(client)
    _verify(client, codes["pat@example.com"])
    wrong_password = _login(client, password="not-it")
    unknown_email = _login(client, email="nobody@example.com")
    assert wrong_password.status_code == unknown_email.status_code == 401
    assert wrong_password.json() == unknown_email.json()


def test_dev_login_is_off_unless_dev_mode(client, monkeypatch):
    monkeypatch.setattr(settings, "auth_dev_mode", False)
    r = client.post("/auth/dev", json={"email": "x@example.com", "role": "practitioner", "name": "X"})
    assert r.status_code == 404


def test_a_bad_token_is_refused(client):
    assert client.get("/auth/me").status_code == 401
    assert client.get("/auth/me", headers={"Authorization": "Bearer not-a-jwt"}).status_code == 401


def _google(client, monkeypatch, email, email_verified=True, **extra):
    claims = {"email": email, "email_verified": email_verified, "sub": "g-123", "name": "G"}
    monkeypatch.setattr(auth_router, "verify_google_id_token", lambda _t: claims)
    return client.post("/auth/google", json={"id_token": "fake", **extra})


def test_google_sign_in_never_creates_an_account(client, monkeypatch):
    # Even with a role, which would otherwise skip the emailed-code gate on
    # who becomes a practitioner.
    for extra in ({}, {"role": "practitioner", "name": "Mallory"}):
        r = _google(client, monkeypatch, "new@example.com", **extra)
        assert r.status_code == 422
        assert "role is required" in r.json()["detail"]  # what both apps match on
    assert _login(client, email="new@example.com").status_code == 401


def test_google_sign_in_links_an_existing_account(client, codes, monkeypatch):
    _register(client)
    _verify(client, codes["pat@example.com"])
    r = _google(client, monkeypatch, "pat@example.com", role="practitioner")
    assert r.status_code == 200
    assert r.json()["user"]["role"] == "patient"  # a sent role changes nothing
    with SessionLocal() as db:
        assert db.query(User).filter_by(email="pat@example.com").one().google_id == "g-123"


def test_google_sign_in_needs_a_verified_email(client, codes, monkeypatch):
    _register(client)
    _verify(client, codes["pat@example.com"])
    # Missing counts as unverified too.
    for verified in (False, None):
        r = _google(client, monkeypatch, "pat@example.com", email_verified=verified)
        assert r.status_code == 401
    with SessionLocal() as db:
        assert db.query(User).filter_by(email="pat@example.com").one().google_id is None
