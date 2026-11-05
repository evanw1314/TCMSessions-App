# Architecture

How the pieces fit, the decisions behind them, and the invariants that lose
or corrupt data silently if broken. Read the invariants before touching
sessions, recording or the schema. Some of what follows looks wrong but is
deliberate; check here before "fixing" it.

The file map and the full endpoint list are in
[`docs/reference.md`](docs/reference.md).

## The pieces

```
Muse headband ──BLE──▶ practitioner's phone ──HTTPS──▶ server ──▶ database
                       (records the session,          (stores,      + raw EEG
                        uploads once at the end)       analyzes)      storage
                                                          ▲
                                     patient's phone ─────┘  (reads own record)
```

- **Server** (`server/`): FastAPI, SQLAlchemy 2.0, Alembic. SQLite in
  development, Postgres in production. Raw EEG goes to local disk or a Cloud
  Storage bucket (`app/eeg/storage.py`). Claude writes the on-demand summaries;
  AssemblyAI transcribes voice markers.
- **Android** (`android/TcmSessionApp/`): Kotlin, Views plus one Compose
  screen. The primary client.
- **iOS** (`ios/TCMSessionApp/`): a SwiftUI port of the Android app, behind it
  in features. Work happens on Android first.

## Decisions

1. **One app per platform, routed by role at login.** A practitioner gets the
   patient list and recording; a patient lands on a read-only view of their
   own record (the `/me/*` endpoints).
2. **The practitioner's phone is the headband's Bluetooth bridge.** A session
   is recorded live in the clinic and uploaded once when it ends. The
   patient's phone never touches the headband.
3. **A practitioner reaches a patient through their roster or an active
   headband assignment** (`patients._practitioner_covers_patient`). Every
   per-patient write checks it and answers **404, not 403**, so outsiders
   can't confirm a patient id exists. Sessions and notes belong to the
   practitioner who wrote them and aren't shared with other practitioners;
   the patient sees all of their own.
4. **AI output is descriptive, and only on request.** The Summarize buttons
   describe patterns ("3 sessions in 6 weeks, a recurring note theme") and
   the prompt forbids diagnosing, judging severity or recommending another
   visit. Nothing calls Claude automatically: the button is a tool the
   practitioner chose to use, not an ambient verdict, and it costs money. A
   summary takes about 5 s; the server gives Claude one 13 s attempt so it
   stays inside the apps' 15 s read timeout.
5. **Schema changes go through Alembic** (`server/migrations/`); the server
   applies pending migrations on start. Procedure:
   [`docs/reference.md`](docs/reference.md#database-migrations).
6. **Optional services fail loudly, and only when used.** With no
   `ANTHROPIC_API_KEY`, `ASSEMBLYAI_API_KEY` or `SMTP_*`, the endpoint that
   needs it answers 503 with a clear message; everything else runs.
7. **Google Sign-In only links to an existing account.** `POST /auth/google`
   refuses an email it hasn't seen ("create an account first"), so every new
   account comes through the email-verified register flow and its
   practitioner gate (decision 8). Android's Credential Manager issues tokens for a *Web*
   OAuth client, so Android and iOS use different client IDs
   (`GOOGLE_CLIENT_ID_ANDROID` and `GOOGLE_CLIENT_ID`).
8. **Practitioner sign-up codes go to a team inbox** by default
   (`PRACTITIONER_VERIFICATION_INBOX`), not the typed address: practitioners
   see patient data, so creating one is gated during the prototype.

## Invariants

- **Datetimes are naive UTC inside the server.** SQLite drops time zones, and
  mixing aware and naive values once broke de-duplication. Normalize every
  inbound datetime with `schemas.to_utc_naive` and serialize outbound ones
  with a trailing `Z` (`schemas.serialize_utc`), including in new endpoints.
  Declare new columns as plain `DateTime()`, never `timezone=True`: that's
  what makes Postgres hand back naive values just like SQLite.
- **One session per `client_session_id`** (per practitioner). The phone makes the id when a
  recording starts and resends it on every save attempt. `POST /sessions`
  answers a repeat with the session it already made (200, not a duplicate),
  so a save that reached the server but timed out on the phone is safe to
  retry. A unique index backs it.
- **Upload before clear.** `SessionJournal` writes every recording to disk as
  it happens, so a crash or a killed app doesn't lose it, and deletes that
  copy only after the server has both the session and its raw EEG. A
  recording the app didn't finish uploading is offered again from the
  patient list. Two deliberate exceptions delete it without the raw EEG: the
  server refused the upload (a retry can't help), or the session id couldn't
  be written to disk (a retry would create the session twice). See
  `SessionJournal`'s class doc.
- **One active headband per patient, and one patient per headband.** Partial
  unique indexes on `headband_assignments` enforce it even against two
  simultaneous assignments.

## Recording on Android

`SessionRecorder` owns the Muse connection and buffers band powers (once a
second), raw EEG (about 256 Hz, four channels) and markers. `SessionJournal`
mirrors all of it to disk, and `RecordingService` is a foreground service
that keeps the process alive while the screen is off. At End session the
phone creates the session, then uploads the raw EEG in the binary format
`EegBinaryWriter.kt` packs and `server/app/eeg/binary.py` parses. A shared
fixture in `testdata/` pins that format on both sides.

## EEG analysis

Relative band power (what the Muse SDK reports) can't tell a treatment effect
from the patient getting drowsy or an electrode drifting. The server
therefore separates the spectrum's aperiodic background from real rhythms
(specparam), compares each session with its own baseline, and flags what it
can't correct instead of hiding it. Standing decisions, all backed by
recordings:

- The frontal eye-movement correction is **off**
  (`eeg.analysis.EOG_CORRECTION_ENABLED`). On hardware it damaged clean
  recordings and didn't remove blinks. Eye activity is flagged instead
  (`eeg.interpretation.eye_activity_differs`), and poorly seated electrodes
  are excluded (`_check_electrodes`).
- The SDK's jaw-clench and blink flags misfire and aren't used. Neither
  headband-off nor the raw-EEG contact counters notice a lifted electrode.

Read [`docs/eeg-aperiodic-correction.md`](docs/eeg-aperiodic-correction.md)
before changing the pipeline. The dated evidence is in
[`docs/archive/eeg-lab-notebook.md`](docs/archive/eeg-lab-notebook.md).

## Not built yet

- **Headband assignment in the apps.** `POST/DELETE /headbands` exist and are
  tested, but neither app calls them, so the "this headband is assigned to
  this patient" check never runs in practice. Wire it up before more than
  one patient's headband is in circulation.
- **A live session view** (scrolling graph, draft and publish, chunked
  upload). Android records without one. The design is in
  [`docs/muse-sessions.md`](docs/muse-sessions.md); it would need new server
  work.
- **Hosting.** The target is Google Cloud (Cloud Run, Cloud SQL, a Cloud
  Storage bucket); the code is ready and the steps are in
  [`docs/cloud-deploy.md`](docs/cloud-deploy.md). The server currently runs
  on a development machine on the clinic network.

## Third-party constraints

The Muse SDK (libmuse 8.0.9) is under a restrictive Interaxon license: it may
not be redistributed, so it is never committed and there is no mirror. The
integrated binaries are gitignored (`android/TcmSessionApp/app/libs`,
`jniLibs/`, `ios/Vendor/`), and the apps can't be built in CI.
