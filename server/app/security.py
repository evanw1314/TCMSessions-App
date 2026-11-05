from datetime import UTC, datetime, timedelta

import bcrypt
import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from .config import settings
from .database import get_db
from .models import Role, User

bearer = HTTPBearer(auto_error=False)


def hash_password(raw: str) -> str:
    return bcrypt.hashpw(raw.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")


def verify_password(raw: str, hashed: str) -> bool:
    return bcrypt.checkpw(raw.encode("utf-8"), hashed.encode("utf-8"))


def create_access_token(user: User) -> str:
    # Authorization always reads the role from the DB (see require_role), never
    # from the token — so no role claim is embedded. A decorative, unverified
    # claim would only invite a future contributor to trust it.
    payload = {
        "sub": str(user.id),
        "exp": datetime.now(UTC) + timedelta(minutes=settings.jwt_expire_minutes),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm="HS256")


def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
    db: Session = Depends(get_db),
) -> User:
    if credentials is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing bearer token")
    try:
        payload = jwt.decode(
            credentials.credentials,
            settings.jwt_secret,
            algorithms=["HS256"],
            # Reject a token missing these outright, rather than KeyError-ing
            # into a 500 below.
            options={"require": ["sub", "exp"]},
        )
        user_id = int(payload["sub"])
    except (jwt.PyJWTError, ValueError, TypeError):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or expired token")
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "User no longer exists")
    return user


def require_role(role: Role):
    def dependency(user: User = Depends(get_current_user)) -> User:
        if user.role != role:
            raise HTTPException(status.HTTP_403_FORBIDDEN, f"Requires {role.value} role")
        return user

    return dependency


def verify_google_id_token(id_token_str: str) -> dict:
    """Returns the verified Google token claims (sub, email, email_verified, name).

    The iOS and Android clients get their ID tokens from different OAuth
    clients (iOS client vs. Android's Web-application "server client ID"),
    so each token's `aud` differs by platform. verify_oauth2_token accepts a
    list of acceptable audiences, so both are checked here.
    """
    from google.auth.transport import requests as google_requests
    from google.oauth2 import id_token as google_id_token

    audiences = [cid for cid in (settings.google_client_id, settings.google_client_id_android) if cid]
    if not audiences:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "GOOGLE_CLIENT_ID is not configured on the server",
        )
    try:
        return google_id_token.verify_oauth2_token(
            id_token_str, google_requests.Request(), audiences
        )
    except ValueError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid Google ID token")
