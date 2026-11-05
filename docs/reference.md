# Reference — layout, running the server, API surface

The file map and the endpoint catalogue. The decisions and invariants are in
[`ARCHITECTURE.md`](../ARCHITECTURE.md), the workflow in
[`CONTRIBUTING.md`](../CONTRIBUTING.md). The interactive version of the
API surface is always at `/docs` on a running server.

## Layout

```
server/
  app/main.py              FastAPI entry; migrates the DB on startup
  app/config.py            pydantic-settings (reads server/.env) + startup guards
  app/migrate.py           applies Alembic migrations on startup
  app/models.py            Clinic, User(role), PatientRoster, PatientNote,
                           PendingRegistration, Headband(+Assignment),
                           TcmSession, SessionMarker/Sample/EegMetric/
                           OscillationPeak
  app/schemas.py           Pydantic I/O models + UTC datetime normalization
  app/security.py          JWT, password hashing, role guards, Google token check
  app/routers/             auth, patients, headbands, sessions, eeg, pairing
  app/eeg/                 raw EEG:
    binary.py              upload format (mirrors the apps' EegBinaryWriter)
    storage.py             local disk or a Cloud Storage bucket
    params.py              bands, filtering, epochs, channel order
    artifacts.py           eye-movement, electrode and deflection detectors
    analysis.py            the pipeline: clean, epoch, specparam fit per half
    interpretation.py      thresholds every reader shares (no mne import)
  app/ai_summary.py        Claude summaries (patient + session)
  app/transcription.py     AssemblyAI voice-marker transcription
  app/email_service.py     SMTP verification codes
  seed.py                  example clinic + dev practitioner
  export_tcm_data.py       CSV export of session data (output gitignored)
  migrations/              Alembic migrations (applied on start, app/migrate.py)
  tools/schema_diff.py     pre-stamp drift check for pre-Alembic databases
  tools/eeg_variants.py    re-runs EEG analysis variants over raw blobs
  tests/                   pytest; fakes Claude/AssemblyAI, offline
  backups/                 gitignored DB snapshots — copy smartring.db here
                           before any destructive DB change
android/TcmSessionApp/     practitioner + patient Android app (Kotlin)
  app/src/main/java/ai/meridian/tcmsession/
    *Activity.kt           screens (login, patient list/detail, session, …)
    SessionRecorder.kt     Muse connection + band-power/raw-EEG capture
    SessionJournal.kt      crash-safe on-disk recording, upload-before-clear
    RecordingService.kt    foreground service keeping a recording alive
    ApiClient.kt, Models.kt, AppConfig.kt
    Eeg*/BandShares/*Chart* EEG wording and charts
ios/TCMSessionApp/         SwiftUI port of the Android app
ios/Support/Info.plist     ATS local networking + Google Sign-In client ID
ios/Vendor/                Muse.framework (gitignored; see its README)
testdata/                  raw-EEG upload fixture both sides' tests read
docs/archive/              the EEG lab notebook
```

## Running the server

```sh
cd server
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
# --host 0.0.0.0 is required for a phone on the LAN to reach it
# fresh setup: python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
# reseed: cp smartring.db backups/... first; rm smartring.db && .venv/bin/python
# seed.py  (then RESTART uvicorn — a running server holds the deleted file)
```

`seed.py` creates one clinic ("Example Clinic") and one practitioner,
`dr.rivera@example.com`, with password `DEV_PRACTITIONER_PASSWORD` from
`seed.py` (`POST /auth/login`, which the apps use). With
`AUTH_DEV_MODE=true`, `POST /auth/dev` also signs in any email with no
password, creating it on first use (`role` and `name` required then).

## Database migrations

Alembic, in `server/migrations/`. The server runs `alembic upgrade head` on
start (`app/migrate.py`), and so does `seed.py`, so a fresh database needs
nothing else. Run commands from `server/` with the venv's `alembic`.

- **Changing the schema:** edit `app/models.py`, then
  `alembic revision --autogenerate -m "add foo to bar"`. Read the generated
  file before committing: autogenerate can't tell a rename from a drop plus an
  add, and never moves data. SQLite can't alter most columns in place, so
  migrations run in batch mode (Alembic copies the table). `pytest` checks
  that migrating an empty database yields exactly the models' schema.
- **A database from before Alembic** (built by the old `create_all`, e.g. the
  dev Mac's `smartring.db` before 2026-10-08): the server refuses to start
  on it, because Alembic would try to create every table again. Once only:
  1. Stop the server and back up: `cp smartring.db backups/smartring-$(date +%Y%m%d-%H%M).db`.
  2. `python tools/schema_diff.py` and apply what it prints until it reports
     nothing (an old database may be missing columns the baseline assumes).
  3. `alembic stamp 0001` (marks it as the baseline; checks nothing).
  4. Start the server: it applies the rest, including `0002`, which drops
     the archived ring app's tables.
  5. `alembic check` should report no differences.
- **Postgres:** the same files. On start, a lock stops two Cloud Run copies
  from migrating at once.

## API surface

Auth (`routers/auth.py`)
- `POST /auth/register` → emails a 6-digit code; `POST /auth/verify` → creates
  the account and returns a token. Practitioner codes go to the team inbox
  (`PRACTITIONER_VERIFICATION_INBOX`; blank means the typed address); the
  response's `code_sent_to` says where.
- `POST /auth/login` (email + password) · `POST /auth/google` (links to an existing
  account; 422 for an unknown email, never creates one) ·
  `POST /auth/dev` (dev mode only)
- `GET /auth/me`

Patients (`routers/patients.py`) — a practitioner "covers" a patient through
their roster or an active headband assignment; every per-patient write checks
that and answers 404 otherwise.
- `GET /patients` · `POST /patients` (by existing account email, or name-only
  intake) · `DELETE /patients/{id}` (off the roster, ends your headband
  assignment; no data deleted)
- `PATCH /patients/{id}/email` — one-shot email for a name-only patient;
  merges into an existing patient account with that email
- `GET|POST /patients/{id}/notes` · `DELETE /notes/{id}` (soft delete) — notes
  are private to the authoring practitioner
- `POST /patients/{id}/summary` — on-demand Claude overview, descriptive only
- Patient self-view: `GET /me/profile` · `GET /me/notes` · `GET /me/sessions`

Headbands (`routers/headbands.py`) — not yet called by either app
- `GET /headbands` (clinic inventory) · `POST /headbands` (assign to a patient
  email) · `DELETE /headbands/{device_id}/assignment` (assigning practitioner
  only)

Sessions (`routers/sessions.py`; EEG routes in `routers/eeg.py`)
- `POST /sessions` — idempotent on `client_session_id` (a repeat returns the
  stored session with 200)
- `GET /patients/{id}/sessions` · `GET /sessions/{id}` · `PATCH
  /sessions/{id}` (practitioner note) · `DELETE /sessions/{id}` (soft delete)
- `POST /sessions/transcribe` — voice marker → text (AssemblyAI)
- `POST /sessions/{id}/summary` — on-demand Claude session summary
- `POST /sessions/{id}/eeg` (raw EEG upload) · `POST /sessions/{id}/eeg/analyze`
  · `GET /sessions/{id}/eeg-metrics` · `GET /patients/{id}/eeg-trend`

Other
- `GET /pair` — HTML page with a QR code of the server address, for the apps'
  login screen
- `GET /health` — unauthenticated liveness check
