# Deploying the server to Google Cloud

The server runs on
**Cloud Run** from `server/Dockerfile`, with **Cloud SQL for PostgreSQL** as
the database, a **Cloud Storage** bucket for raw EEG recordings, and **Secret
Manager** for the secrets. Practitioners then just install the app and log in,
from any network; nobody runs uvicorn.

The code is ready for it:
- `app/eeg/storage.py` keeps raw EEG in the bucket named by `EEG_RAW_BUCKET`
  (Cloud Run's disk is wiped with every new revision or instance).
- `config.harden_settings` refuses to start on Cloud Run with `AUTH_DEV_MODE`
  on (`POST /auth/dev` would sign anyone in as any email), without
  `EEG_RAW_BUCKET`, or with a SQLite database. A deploy that gets one of these
  wrong fails loudly in the logs instead of running unsafely.
- The Android app takes its starting server address from `defaultServerUrl` in
  `android/TcmSessionApp/gradle.properties` (step 9).

Commands are for a Mac or Linux shell with the
[gcloud CLI](https://cloud.google.com/sdk/docs/install) installed, run from the
repo root. Every value in `<angle brackets>` is yours to fill in.

## 0. What the project owner sets up

1. A **new, dedicated project** for this app (isolated from anything else in
   their account).
2. A **billing account linked** to it. Owner access alone can't create
   resources without billing.
3. You as **Owner** (or Editor) on that project.
4. Ideally a **budget alert** (Billing → Budgets & alerts), e.g. $50/month.
5. If the pilot will hold **real patient data**: someone with the rights to
   do so accepts Google Cloud's healthcare data agreement (BAA). It covers the
   whole account or organization, not one project, and has to be in place
   before any real data goes in. Anthropic and AssemblyAI then need the same
   question asked.

## 1. Settings for this shell

```sh
PROJECT=<project-id>            # from the project owner
REGION=us-west1                 # a region near the clinic
SERVICE=smartring-api
DB_INSTANCE=smartring-db
BUCKET=$PROJECT-eeg-raw         # bucket names are global; add a suffix if taken
SA=smartring-api@$PROJECT.iam.gserviceaccount.com

gcloud auth login
gcloud config set project $PROJECT
gcloud config set run/region $REGION
```

## 2. Turn on the services

```sh
gcloud services enable run.googleapis.com sqladmin.googleapis.com \
  artifactregistry.googleapis.com cloudbuild.googleapis.com \
  secretmanager.googleapis.com storage.googleapis.com
```

## 3. An identity for the server

The server runs as its own service account, with only the access it needs.

```sh
gcloud iam service-accounts create smartring-api --display-name="Smart ring API"
gcloud projects add-iam-policy-binding $PROJECT \
  --member=serviceAccount:$SA --role=roles/cloudsql.client
gcloud projects add-iam-policy-binding $PROJECT \
  --member=serviceAccount:$SA --role=roles/secretmanager.secretAccessor
```

## 4. The database

The smallest shared-core instance is plenty for a pilot (about $10/month);
shared-core tiers need the `enterprise` edition. Daily backups are on (the
start time is in UTC).

```sh
gcloud sql instances create $DB_INSTANCE --database-version=POSTGRES_16 \
  --edition=enterprise --tier=db-f1-micro --region=$REGION \
  --storage-auto-increase --backup-start-time=10:00   # UTC, i.e. ~3 am Pacific
gcloud sql databases create smartring --instance=$DB_INSTANCE
DB_PASSWORD=$(python3 -c "import secrets; print(secrets.token_urlsafe(24))")
gcloud sql users create smartring --instance=$DB_INSTANCE --password="$DB_PASSWORD"
CONN=$(gcloud sql instances describe $DB_INSTANCE --format='value(connectionName)')
```

The server creates its tables itself on first start (it applies the Alembic
migrations in `server/migrations/`), so an empty database is all it needs.

## 5. The bucket for raw EEG

Private, in the same region, and only the server's account can use it.

```sh
gcloud storage buckets create gs://$BUCKET --location=$REGION \
  --uniform-bucket-level-access --public-access-prevention
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member=serviceAccount:$SA --role=roles/storage.objectAdmin
```

## 6. Secrets

Each secret is typed at a hidden prompt (`read -rs`), so it never lands in
your shell history. Use the **new** Claude and AssemblyAI keys, and the Gmail
app password from the Mac's `server/.env`.

```sh
printf '%s' "postgresql+psycopg://smartring:$DB_PASSWORD@/smartring?host=/cloudsql/$CONN" \
  | gcloud secrets create DATABASE_URL --data-file=-
python3 -c "import secrets; print(secrets.token_urlsafe(48), end='')" \
  | gcloud secrets create JWT_SECRET --data-file=-

for NAME in ANTHROPIC_API_KEY ASSEMBLYAI_API_KEY SMTP_PASSWORD; do
  printf '%s: ' "$NAME"; read -rs VALUE; echo
  printf '%s' "$VALUE" | gcloud secrets create $NAME --data-file=-
done
unset VALUE DB_PASSWORD
```

## 7. Deploy

Run the server tests first (`cd server && .venv/bin/python -m pytest`), then
check what the deploy will upload:

```sh
gcloud meta list-files-for-upload server
```

It should list the server code (`app/`), `Dockerfile`, `requirements.txt`,
`seed.py` and a few small config files, and never `.env`, a `.db` file,
`backups/` or `data/`. `server/.gcloudignore` is what keeps those on your
machine: without it gcloud uploads the whole folder, secrets and patients'
raw EEG included.

Then, from the repo root, this builds `server/Dockerfile` with Cloud Build and
starts it. The first time, it asks to create an Artifact Registry repository;
say yes.

```sh
gcloud run deploy $SERVICE --source server \
  --service-account $SA \
  --add-cloudsql-instances $CONN \
  --set-secrets DATABASE_URL=DATABASE_URL:latest,JWT_SECRET=JWT_SECRET:latest,ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest,ASSEMBLYAI_API_KEY=ASSEMBLYAI_API_KEY:latest,SMTP_PASSWORD=SMTP_PASSWORD:latest \
  --set-env-vars AUTH_DEV_MODE=false,EEG_RAW_BUCKET=$BUCKET,SMTP_HOST=smtp.gmail.com,SMTP_PORT=587,SMTP_USER=<gmail address>,EMAIL_FROM=<gmail address>,GOOGLE_CLIENT_ID=<from server/.env.example>,GOOGLE_CLIENT_ID_ANDROID=<from server/.env.example> \
  --memory 2Gi --cpu 1 --cpu-boost --timeout 300 \
  --min-instances 1 --max-instances 2 \
  --allow-unauthenticated
```

Why these flags:
- `--allow-unauthenticated`: the app calls the server directly and has its own
  login; this only lets requests reach it.
- `--memory 2Gi`: the EEG analysis (mne, scipy, specparam) needs more than
  Cloud Run's default 512 MiB.
- `--min-instances 1`: keeps one copy ready (about $20/month, since an idle
  copy is billed at a lower rate). With
  `0` it's nearly free when idle, but the first request after a quiet spell
  waits for the server to start, and the heavy science libraries can make that
  longer than the app's 15-second timeout, so a login fails once. Start with
  `1`; revisit once there's a sense of real usage.
- `--max-instances 2`: plenty for a pilot, a ceiling on cost, and within the
  smallest database's connection limit (each copy uses at most 10, see
  `app/database.py`).

If the deploy fails, `gcloud run services logs read $SERVICE --limit 50`
shows why. A "Refusing to start on Cloud Run" line names the setting to fix.
Two other common first-deploy problems:
- **The build is refused for permissions.** In newer projects the account
  Cloud Build uses may be missing a role:
  `gcloud projects add-iam-policy-binding $PROJECT --member=serviceAccount:$(gcloud projects describe $PROJECT --format='value(projectNumber)')-compute@developer.gserviceaccount.com --role=roles/run.builder`
- **`--allow-unauthenticated` fails with a policy error.** The organization
  blocks public services ("domain restricted sharing"); the
  organization admin has to allow it for this project.

## 8. Check it

```sh
URL=$(gcloud run services describe $SERVICE --format='value(status.url)')
curl $URL/health                                  # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' -X POST $URL/auth/dev \
  -H 'content-type: application/json' -d '{"email":"x@example.com"}'   # 404: dev login is off
```

Then, in the app:
1. On the login screen, set the server address to `$URL`.
2. Create the practitioner account (the code goes to the shared test inbox)
   and a patient.
3. Record a short session with a voice marker, end it, tap **Summarize**, and
   tap **Analyze EEG**.
4. Confirm the recording is in the bucket:
   `gcloud storage ls gs://$BUCKET/eeg_raw/`

## 9. Point the app at it

In `android/TcmSessionApp/gradle.properties`, set
`defaultServerUrl=<the $URL from step 8>`, rebuild, and share that build. A
fresh install then starts with the cloud address filled in. A phone that
already saved a LAN address keeps it until it's changed once on the login
screen.

The server's QR pairing page (`/pair`) shows a LAN address, which means
nothing on Cloud Run. Use the pre-filled address instead.

## Later

- **Updating the server:** run the tests, then
  `gcloud run deploy $SERVICE --source server` again. The other settings are
  kept.
- **Schema changes:** ship them as an Alembic migration (see
  `docs/reference.md`, "Database migrations"); the new server applies it on
  start. Back up first: `gcloud sql backups create --instance=$DB_INSTANCE`.
- **Rotating a key:** `printf '%s' "<new>" | gcloud secrets versions add
  <NAME> --data-file=-`, then redeploy so the server reads the new version.
- **AssemblyAI copies:** the server deletes each transcript once the text is
  back, but one still processing when the app gives up may never be deleted
  on Cloud Run (it throttles work left for after a response). If the
  AssemblyAI account offers a data retention limit, set it as a backstop.
- **Research copies:** `tools/eeg_variants.py` reads recordings from local
  disk. To run it on cloud recordings, copy them down first with
  `gcloud storage cp gs://$BUCKET/eeg_raw/... .`
