# Portable container for the FastAPI server — works unchanged on Render,
# Railway, Google Cloud Run, or any other container host. No provider-specific
# assumptions: reads $PORT (every one of those platforms injects it) rather
# than hardcoding a port, and all real config (DATABASE_URL, JWT_SECRET, etc.)
# comes from environment variables the host sets, not from a baked-in .env
# (see .dockerignore — .env never makes it into the image). Deploying to
# Cloud Run: docs/cloud-deploy.md (config.harden_settings refuses to start
# there with AUTH_DEV_MODE on, no EEG_RAW_BUCKET, or a SQLite database).
FROM python:3.13-slim

WORKDIR /app

# Install dependencies first so this layer stays cached across code-only
# changes (faster rebuilds/deploys).
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/
COPY migrations/ ./migrations/
COPY alembic.ini seed.py ./

# Runs as non-root — most hosts (including Cloud Run) expect/require this.
# chown, not just --chown on the COPYs above: AUTH_DEV_MODE (on by default,
# same as .env.example) makes config._load_or_create_dev_secret() write
# server/.jwt_secret into /app at startup when JWT_SECRET isn't set — appuser
# needs write access to the /app DIRECTORY to create that file, not just
# ownership of the files already in it. Without this the container
# crash-loops on first boot with an unhandled PermissionError the moment
# someone forgets to set JWT_SECRET, which is exactly the likely first-deploy
# mistake this is guarding against.
RUN useradd --create-home appuser && chown -R appuser:appuser /app
USER appuser

# Cloud Run/Render set $PORT at runtime (commonly 8080/10000); 8000 is only
# the fallback for `docker run` without one set. Shell form so $PORT expands.
ENV PORT=8000
CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT}
