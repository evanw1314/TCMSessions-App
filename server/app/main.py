from fastapi import FastAPI

from .config import harden_settings
from .migrate import upgrade_to_head
from .routers import auth, eeg, headbands, pairing, patients, sessions

# Validate the security posture before serving a single request: never sign
# tokens with the repo-known default, and announce dev-mode exposure loudly.
harden_settings()

upgrade_to_head()

app = FastAPI(
    title="TCM Session API",
    description="Prototype backend for practitioner-run Muse EEG sessions.",
    version="0.1.0",
)

app.include_router(auth.router)
app.include_router(patients.router)
app.include_router(headbands.router)
app.include_router(sessions.router)
app.include_router(eeg.router)
app.include_router(pairing.router)


@app.get("/health")
def health():
    return {"status": "ok"}
