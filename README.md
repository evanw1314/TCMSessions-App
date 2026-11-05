# TCM Session App

Prototype for practitioner-run Muse EEG sessions in a TCM clinic. The
practitioner's phone connects to a Muse headband, records brainwave band
powers, raw EEG and timestamped markers (tapped or spoken) during a
treatment, and uploads the session to a FastAPI server. The server runs an
aperiodic-corrected EEG analysis and keeps a per-patient session journal that
both the practitioner and the patient can read.

This README is how to get running.
[`ARCHITECTURE.md`](ARCHITECTURE.md) has the design decisions and the
invariants you must not break, [`CONTRIBUTING.md`](CONTRIBUTING.md) the
workflow, and `docs/` the deeper reference.

> The project began as a YuCheng smart-ring monitoring app, since removed.

## Layout

```
server/                 FastAPI + SQLAlchemy (SQLite in dev, Postgres-ready)
android/TcmSessionApp/  Android app (Kotlin) — the primary client
ios/TCMSessionApp/      SwiftUI port of the Android app
testdata/               raw-EEG upload fixture shared by server and app tests
docs/                   reference, EEG design, cloud deploy, archive
```

Full map and API surface: [`docs/reference.md`](docs/reference.md).

## Run the server

Python 3.13. Works on any OS.

```sh
cd server
python3 -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
cp .env.example .env          # defaults work for local dev
.venv/bin/python seed.py      # creates smartring.db with a dev practitioner
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

On Windows, use `.venv\Scripts\` in place of `.venv/bin/`;
`server/start_server.ps1` starts the server hidden in the background (for a
Task Scheduler autostart).

`--host 0.0.0.0` lets a phone on the same network reach it. Interactive API
docs are at http://127.0.0.1:8000/docs, and http://127.0.0.1:8000/pair shows a
QR code of the server address for the apps' login screen.

Sign in as `dr.rivera@example.com` with the password in `seed.py`
(`DEV_PRACTITIONER_PASSWORD`).

Optional services (leave blank and the related endpoint answers 503):
`SMTP_*` for verification emails, `ANTHROPIC_API_KEY` for AI summaries,
`ASSEMBLYAI_API_KEY` for voice markers. See `server/.env.example`.

Lint and tests (offline; they fake every outside service). GitHub Actions
runs both on every push that touches `server/` or `testdata/`:

```sh
.venv/bin/ruff check .
.venv/bin/python -m pytest
```

## Run the apps

Both need the Muse SDK, which is gitignored because its license forbids
redistribution:

- **Android:** [`android/TcmSessionApp/README.md`](android/TcmSessionApp/README.md)
- **iOS:** [`ios/XCODE_SETUP.md`](ios/XCODE_SETUP.md)

On first launch, enter the server address (or scan the `/pair` QR code) and
sign in.

**Google Sign-In** only works for addresses added as test users on the OAuth
consent screen; ask a maintainer. Password sign-in needs none of that.

## Credits

Built by Evan Wescott and Aiden Chow at TCMlab, as a prototype for a TCM
clinic. This public copy is a snapshot of the team's private repository,
without its commit history or internal notes.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md): branch from `main` for anything
non-trivial, get a review from someone who didn't write the code before every
push, keep commit subjects imperative, and run the server tests before a
server push.
