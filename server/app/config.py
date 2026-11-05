import logging
import os
import secrets
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

logger = logging.getLogger("smartring.security")

# The default below and the placeholder in .env.example both ship in the repo,
# so every collaborator knows them — a token signed with either could be forged
# by anyone. harden_settings() refuses to use these for real.
KNOWN_INSECURE_JWT_SECRETS = frozenset(
    {"dev-secret-do-not-use-in-production", "change-me-in-production"}
)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "sqlite:///./smartring.db"
    jwt_secret: str = "dev-secret-do-not-use-in-production"
    jwt_expire_minutes: int = 60 * 24 * 30
    google_client_id: str = ""
    # A second accepted audience for the same Google Sign-In flow: Android's
    # Credential Manager issues ID tokens whose `aud` is a Web-application
    # OAuth client, not the iOS client above — see verify_google_id_token.
    google_client_id_android: str = ""
    auth_dev_mode: bool = True

    # The single clinic every practitioner is auto-assigned to for now.
    seed_clinic_name: str = "Example Clinic"

    # Where practitioner signup codes go. Practitioners get clinic-wide access
    # (headband registration, every patient's data), so during the prototype
    # their code goes to the team's inbox instead of the address they typed:
    # a practitioner account can't self-verify without going through us.
    # Blank sends it to the typed address, like a patient's.
    practitioner_verification_inbox: str = "smartringapptest@gmail.com"

    # SMTP credentials for POST /auth/register's verification email. No
    # insecure-sentinel swap like jwt_secret — there's no safe fallback for
    # missing mail credentials, so an unconfigured server just 503s from
    # email_service.py when register() is actually called (same lazy-fail
    # precedent as google_client_id/verify_google_id_token).
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    email_from: str = ""

    # Claude API key for the on-demand "Summarize" buttons (patient overview
    # and single session, see ai_summary.py). Same lazy-fail precedent as the
    # SMTP settings above: an unconfigured server just 503s when a
    # practitioner actually taps "Summarize," rather than failing at startup.
    anthropic_api_key: str = ""

    # AssemblyAI API key for voice-marker transcription
    # (transcription.transcribe_marker_audio). Same lazy-fail precedent.
    assemblyai_api_key: str = ""

    # Mains hum frequency to notch out during EEG preprocessing (see
    # eeg/analysis.py) — 60 Hz for the US pilot clinic; a clinic on European
    # mains would set this to 50 in server/.env. Not derived from locale
    # automatically since the server doesn't know where a given clinic is.
    eeg_mains_hz: float = 60.0

    # Cloud Storage bucket for raw EEG recordings (eeg/storage.py). Blank
    # keeps them in eeg/storage.EEG_RAW_DIR on local disk, as on the dev Mac. Required on
    # Cloud Run, whose disk is wiped with every new revision or instance
    # (harden_settings refuses to start there without it).
    eeg_raw_bucket: str = ""


settings = Settings()

def _dev_secret_path() -> Path:
    # server/.jwt_secret — beside .env, gitignored. config.py is server/app/.
    return Path(__file__).resolve().parent.parent / ".jwt_secret"


def _load_or_create_dev_secret() -> str:
    """A random signing key persisted locally, so a dev server isn't using the
    repo-known default AND tokens still survive a restart (no re-login churn).

    Creation is atomic (O_EXCL) so that if several workers cold-start at once
    (`uvicorn --workers N`) they converge on ONE key instead of each generating
    its own and 401-ing each other's tokens."""
    path = _dev_secret_path()
    try:
        existing = path.read_text().strip()
        if existing:
            return existing
    except FileNotFoundError:
        pass

    candidate = secrets.token_urlsafe(48)
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        # Another worker created it between our read and our create. Use theirs;
        # only an empty file (a crashed writer) warrants overwriting.
        winner = path.read_text().strip()
        if winner:
            return winner
        path.write_text(candidate + "\n")
        return candidate
    with os.fdopen(fd, "w") as f:
        f.write(candidate + "\n")
    return candidate


def harden_settings(target: Settings = settings) -> None:
    """Fail closed on a publicly-known signing key; never fail *silently* open.

    Called once at startup (main.py). Two guarantees:

    - A server signing tokens with a repo-known secret is never actually
      reachable: in dev mode the secret is swapped for a locally-persisted
      random one; with AUTH_DEV_MODE off it refuses to start, because a
      non-dev deployment MUST set its own JWT_SECRET.
    - AUTH_DEV_MODE (which lets POST /auth/dev mint a token for any email with
      no password — required so the patient's phone can dev-login over the LAN)
      announces itself loudly, so its exposure is a deliberate choice, not a
      surprise.

    On Cloud Run (which sets K_SERVICE on every instance) it also refuses
    to start in any setup that would expose or quietly lose data: dev login
    on, raw EEG on the container's disk, or a SQLite database.
    """
    if os.environ.get("K_SERVICE"):
        problems = []
        if target.auth_dev_mode:
            problems.append("AUTH_DEV_MODE is on, so POST /auth/dev would sign anyone in as any email")
        if not target.eeg_raw_bucket:
            problems.append("EEG_RAW_BUCKET is unset, so raw EEG would be written to the container's disk and lost")
        if target.database_url.startswith("sqlite"):
            problems.append("DATABASE_URL is SQLite, so the database would be lost with the container")
        if problems:
            raise RuntimeError(
                "Refusing to start on Cloud Run: " + "; ".join(problems) + ". See docs/cloud-deploy.md."
            )

    if target.jwt_secret in KNOWN_INSECURE_JWT_SECRETS:
        if not target.auth_dev_mode:
            raise RuntimeError(
                "Refusing to start: JWT_SECRET is unset or the repo default "
                "while AUTH_DEV_MODE is false. A non-dev server must set its "
                "own JWT_SECRET in server/.env, e.g.\n"
                '  python -c "import secrets; print(secrets.token_urlsafe(48))"'
            )
        target.jwt_secret = _load_or_create_dev_secret()
        logger.warning(
            "No JWT_SECRET set — using a generated local dev key (%s). Fine for "
            "local prototyping; set a real JWT_SECRET before exposing this "
            "server to anyone you don't trust.",
            _dev_secret_path(),
        )

    if target.auth_dev_mode:
        logger.warning(
            "AUTH_DEV_MODE is ON: POST /auth/dev issues a token for ANY email "
            "with no password. Required for LAN dev-login from the phone — but "
            "never enable it on a shared or public network. Set "
            "AUTH_DEV_MODE=false for anything beyond local development."
        )
