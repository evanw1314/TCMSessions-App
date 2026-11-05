# EEG lab notebook (archived)

> **Archived, 2026-10-15.** This is the dated working record of how the EEG
> pipeline was scoped, built and tested, kept for its evidence: the session
> numbers cited in code comments are written up here. Session numbers
> restarted after a database reset, so August's sessions (about 25-35) and
> September's (8-31) overlap; the section dates tell them apart. It is
> NOT a description of the current pipeline — parts of it describe designs
> that were later changed or switched off. For how things work now, read
> [`docs/eeg-aperiodic-correction.md`](../eeg-aperiodic-correction.md).

---

# EEG aperiodic correction — scoping the "real fix"

Companion to `muse-sessions.md` (the feature/UX design) — this is specifically
about whether the band-power numbers this app records mean what the app
implies they mean, and what it would take to make them mean something. Origin:
a shared Claude conversation (2026-08-13, see CLAUDE.md's session log) working
through why two people's EEG readouts from the same acupuncture session looked
so different, which surfaced a real methodological problem, not a UX one.

## The problem, in one paragraph

A raw EEG spectrum is two signals stacked together: a non-rhythmic **aperiodic
background** that falls off with frequency by physics (delta always looks
"loudest," oscillation or not), and **actual oscillations** (real rhythms)
sitting on top of it. Relative band power — what this app stores — sums both
together and can't tell them apart. The aperiodic background's steepness is a
well-established arousal marker: it changes measurably from alert wakefulness
through drowsiness toward sleep. A resting acupuncture session (dim room, 20+
minutes lying still) is exactly the scenario that shifts it — and when it
shifts, it produces `delta↑ theta↑ alpha↑, beta↓ gamma↓`, which is precisely
the "relaxation signature" a wellness readout wants to show, with **zero
actual oscillatory change**. Uncorrected band power cannot distinguish
"acupuncture changed the patient's brain state" from "the patient got sleepy"
from "the electrodes drifted" — the three produce nearly identical graphs.

## What's already done (cheap guardrails, 2026-08-13)

Don't re-do these:

- **AI summary prompts** (`server/app/ai_summary.py`, `SESSION_SYSTEM_PROMPT`)
  now explicitly forbid narrating a band-power shift as "relaxing," "calming,"
  or "responding to treatment," and name drowsiness/electrode drift/EMG as
  equally likely causes.
- **Artifact rejection** (`SessionRecorder.kt`): the Muse SDK's artifact
  packets (`MuseArtifactPacket`, previously received and discarded) mark
  that tick's samples `NO_SIGNAL_VALUE` across all bands instead of storing
  a contaminated reading. **Headband-off only since 2026-09-10.** Blink was
  never used (it fired in about half of calm seconds), and jaw-clench was
  dropped after this headband's detector reported a clench in every packet
  for minutes at a time with the wearer still. Session 13 lost all 249
  seconds to it while its raw EEG was clean. See "The SDK's jaw-clench
  detector (2026-09-10)" below.
- **Absolute band power** captured alongside relative (`*_ABSOLUTE` packets,
  stored as `alpha_absolute` etc. in `SessionSample.band` — see
  `schemas.BAND_TYPES`) — removes the closed-arithmetic artifact (relative
  power's 5 bands sum to ~100%, so one rising mechanically forces others
  down) for later analysis. **Not yet charted or summarized anywhere** —
  it's inert storage today, a building block for the work below.
- **Arousal self-report** (`TcmSession.patient_dozed` / `.alertness_rating`,
  captured via a dialog at session end in `SessionActivity`): lets a later
  reviewer — or the AI summary — attribute a band shift to reported
  drowsiness instead of guessing.

None of the above fixes the core problem. They reduce the chance of reading
noise as signal; they don't produce a corrected signal. That's this doc.

## What the real fix requires

### 1. Capture raw EEG, not just derived band power

The Muse SDK already exposes `MuseDataPacketType.EEG` (and
`NOTCH_FILTERED_EEG`) — confirmed present in the bundled
`libmuse_android.jar`, just never subscribed to. No new hardware needed.

This is a bigger client-side change than it sounds:

- Raw EEG arrives at ~256 Hz × 4 channels — far too fast for the current
  1-second UI-tick sampling design (`SessionRecorder`'s `latestBand` +
  `tickRunnable`). It needs its own buffer, written directly in
  `receiveMuseDataPacket`, decoupled from the once-a-second UI refresh.
- **Volume**: a 45-minute session ≈ 256 × 4 × 2700s ≈ 2.8M raw values. As
  float32 that's ~11 MB per session, uncompressed — two to three orders of
  magnitude more than today's per-second band-power rows (a comparable
  session is currently a few hundred KB across ~17,000 `SessionSample` rows).
  `muse-sessions.md`'s original design anticipated this ("structure the
  payload so raw EEG could be added later... costs nothing now") but the data
  volume is the part that actually changes the architecture.

### 2. Upload transport has to change

The current model — buffer everything in memory, POST the whole session as
one JSON body when it ends (`TcmSessionCreate.samples`, capped at 40,000
rows) — does not scale to millions of raw floats as JSON. Options, roughly
increasing in complexity:

- **Binary multipart upload** of a compact format (e.g. a small `.npz`),
  same shape as the existing audio-upload endpoint (`POST
  /sessions/transcribe`) — the session is still recorded whole in memory and
  uploaded once at the end, just not as JSON.
- **Chunked upload during the session** — this reopens the exact
  `recording → draft → published` lifecycle CLAUDE.md's Muse backlog note
  already flagged as deferred scope when Android shipped the simpler
  buffer-and-upload-once design. Worth doing eventually (an app crash mid-
  session currently loses the whole thing either way), but it's a separate
  project from signal correction and shouldn't block it.

Recommendation: start with binary-multipart-at-the-end. It's a transport
change, not an architecture change, and doesn't require touching the crash-
loses-everything tradeoff.

### 3. Server-side processing pipeline

New Python dependencies: `mne` (filtering, epoching) and `specparam` (né
`fooof`; both permissively licensed — BSD-3 and Apache-2.0 respectively, fine
for this prototype). Runs offline/batch after upload, not live — matching
this app's existing "no live-measurement mode" decision, and its "on-demand,
not automatic" precedent for expensive work (`ai_summary.py`'s summary
button — never called automatically, both for cost and so it reads as
practitioner-invoked rather than an ambient verdict). The equivalent here
would likely be an "Analyze EEG" button, not something that runs on every
upload.

Pipeline, first-principles order (see the source conversation for why each
step matters):

1. Bandpass ~1–45 Hz, notch at mains frequency.
2. Reject high-variance epochs. As built, this cut is signal-derived:
   `_epoch_and_reject`'s robust amplitude threshold on the raw EEG. The
   client-side artifact packets were never used here, because they aren't
   aligned to the raw EEG's timestamps. Since 2026-09-10 only their
   headband-off flag is applied, and only to the per-second band-power series.
3. Window into overlapping epochs (e.g. 2–4s), compute PSD (Welch's method).
4. Fit `specparam` per epoch (or per session-half, to keep the existing
   before/after framing `ai_summary.py` already uses): aperiodic offset,
   aperiodic exponent, and — separately — whether a real oscillatory peak
   exists per band, at what center frequency, corrected power, and bandwidth.
5. **(Done, 2026-08-16 — see "Suggested phasing" item 7 below.)** Score
   arousal independently where possible (slow rolling eye movements are a
   direct drowsiness marker) — cross-check against the self-reported
   `patient_dozed`/`alertness_rating` fields already captured.

Where this runs: FastAPI's `BackgroundTasks` is plenty for this scale (single
clinic, on-demand trigger) — no need for a job queue (Celery/RQ) unless usage
grows well past a pilot.

### 4. New schema

Today's `SessionSample` (one row per band per second) can't hold this. Needs
something like a `session_eeg_metrics` row per session-half (aperiodic
offset, exponent) plus a `session_oscillation_peaks` table (session_id, half,
band, center_frequency, power, bandwidth — nullable/absent when no peak was
detected, which is itself informative). Raw EEG itself: keep only long enough
to compute, or persist to cheap object storage (Cloud Storage, if/when the
GCP move in CLAUDE.md's hosting section happens) if re-processing later is
worth the cost — don't put it in Postgres as one row per sample.

### 5. UI implications

`BandChartView` currently plots 5 continuous relative-power lines on a 0–1
axis — that shape doesn't fit corrected output. Aperiodic exponent-over-time
is one continuous line (its own chart, arguably); oscillatory peaks are
sparse, discrete events (present/absent per epoch, not a smooth curve) and
want a different visual (markers or a small table), not a line graph. This is
a real chart-design task on its own, not a data-plumbing afterthought.

**(Done, 2026-08-15)** `ai_summary.py`'s `SESSION_SYSTEM_PROMPT` and
`_format_session_context` are rewritten around the new numbers instead of
relative-band trend, for any session that's been analyzed. Two mutually
exclusive framings, decided per-session by whether `SessionEegMetric` rows
exist for it: Form A (unchanged relative-band hedging, same "exploratory...
not a validated measure" caveat) for a session with no analysis yet; Form B
(new) for an analyzed one — the model gets the aperiodic exponent/offset per
period, fit quality (r²), and per-band peaks (or an explicit "no real
oscillation detected"), and is allowed to describe a real peak or exponent
shift as a genuine finding rather than dismissing it as noise. It still
can't call it a treatment *response* — one uncontrolled session doesn't
establish causation even with correction — and the exponent is still framed
as an arousal marker first, cross-checked against `patient_dozed`/
`alertness_rating` before anything else. When the session has a recorded
baseline (see the control-condition section below, also done), Form B says
so explicitly ("compared against this session's own recorded baseline")
rather than presenting a plain first/second split as if it were one.

### 6. A control condition

**(Done, 2026-08-15 — see "Suggested phasing" item 5 below for the
implementation.)** Even corrected, "the exponent steepened" only means
something against a comparison. Recording a short (2–3 min) eyes-closed
baseline before needling, same headband, same room, no treatment yet — lets
each session compare against itself instead of relying on population norms.

## Suggested phasing

1. **(Done)** Guardrails — prompt caveats, artifact rejection (headband-off
   only since 2026-09-10), absolute-power capture, arousal self-report. No
   architecture change.
2. **(Done, 2026-08-14 — pending real-hardware verification of the upload +
   analyze path specifically)** Raw EEG capture + binary-multipart
   upload-at-end + on-demand `mne`/`specparam` analysis:
   - Capture (`SessionRecorder.kt`, `MuseDataPacketType.EEG`): hardware-
     verified earlier (256.8Hz, zero dropouts, real Muse S).
   - Binary wire format: `server/app/eeg/binary.py` (parse/validate) +
     Android's `EegBinaryWriter.kt` (pack) — a small fixed header (magic,
     version, channel count, sample count, first/last Muse timestamp) then
     packed float32, sample-major, NaN passed through for bad contact. No
     per-sample timestamp (see eeg/binary.py's docstring for why) — the
     server derives the OBSERVED sample rate from first/last timestamp and
     sample count, not an assumed nominal 256Hz.
   - Transport: `POST /sessions/{id}/eeg` (upload, stores to
     `EEG_RAW_DIR` on local disk, or a Cloud Storage bucket on Cloud Run
     since 2026-09-11 — see `server/app/eeg/storage.py`) and
     `POST /sessions/{id}/eeg/analyze` (on-demand, synchronous — see that
     endpoint's docstring for why BackgroundTasks wasn't needed at this
     scale) + `GET /sessions/{id}/eeg-metrics` (reread without recompute).
     `SessionRecorder.endSession()` uploads best-effort, non-fatal — the
     session's markers/samples are already saved by the time this runs, so a
     failed raw-EEG upload doesn't flip a good save into SAVE_FAILED (see
     `uploadRawEegBestEffort`'s docstring). Replaces the old
     `dumpRawEegDebugFile()` local-CSV scaffolding entirely.
   - Pipeline (`server/app/eeg/analysis.py`): notch (mains) + bandpass
     1-45Hz, NaN-gap interpolation before filtering (filtering NaN directly
     spreads it across the whole FIR kernel — a real bug caught by synthetic
     testing, not a hypothetical), epoch + robust-outlier rejection, Welch
     PSD averaged across channels and epochs per session-half, `specparam`
     fit (`SpectralModel`, pinned to the 2.0 pre-release API) for aperiodic
     offset/exponent, and per-band peak extraction (delta/theta/alpha/beta/
     gamma) — absent when no real peak was found in a band, matching this
     doc's "informative absence" framing. Originally discarded the raw file
     once analyzed; since 2026-08-17 it keeps it, so a session can be
     re-analyzed as the pipeline changes (see "Open questions" below and
     `analyze_session_eeg_endpoint`'s docstring). DB rows in
     `SessionEegMetric`/`SessionOscillationPeak` hold the results.
   - **Verified end-to-end against synthetic data** (injected 10Hz/~8µV
     alpha oscillation in only the second half of a 4-minute recording, plus
     scattered NaN dropouts): the pipeline correctly isolated the injected
     peak to the second half with power roughly 30-60x any first-half
     spurious peak, consistent aperiodic exponent (~2.0) across both halves
     as expected (nothing about the aperiodic background was varied between
     halves in the synthetic signal), and the FastAPI upload -> analyze ->
     discard-raw-file -> reread -> cross-practitioner-404 flow all passed via
     `TestClient`. **NOT yet run against a real Muse S recording** — the
     capture rate was hardware-verified earlier, but the binary upload and
     the analysis pipeline itself have only been exercised against synthetic
     data and should be tried against a real recorded session before relying
     on the numbers clinically.
   - `specparam`'s default `peak_width_limits`/`peak_threshold` produced a
     few small spurious peaks on pure synthetic pink noise (expected — any
     spectral fit will find *something* in noise) — real EEG's 1/f falloff
     is smoother than the synthetic random-walk used for testing, but this
     is worth re-checking against a real recording before treating a
     low-power peak as meaningful.
   - Not yet built: any client UI to trigger analyze / show the numbers (no
     "Analyze EEG" button yet — reachable today only via the API directly).
     Matches this phase's own scope ("no chart redesign yet... prove the
     pipeline works" below) but is the concrete next step once verified on
     real hardware.
   - **Verified end-to-end against a real Muse S recording (2026-08-15,
     session 26, patient "Test," ~11.8 min, practitioner-worn, alertness
     self-rated 7/10, one deliberate hard-blink marker at 40s)** — capture,
     binary upload, `POST /eeg/analyze`, raw-file discard, and cached reread
     via `GET /eeg-metrics` all passed against real hardware, not just
     synthetic data. The result is a direct demonstration of the problem
     this doc opens with: uncorrected relative band power showed delta as
     the strongest band in both halves (mean 0.275 / 0.266, ahead of every
     other band) — the exact misleading signature described above. The
     corrected pipeline found **zero real delta or theta oscillatory peaks
     in either half** (aperiodic exponent ~1.2, essentially flat between
     halves — 1.226 → 1.203 — consistent with the self-reported alertness),
     meaning that "delta strongest" reading was pure aperiodic background,
     not a real oscillation. The only detected peaks were beta (~28Hz) and
     gamma (~39–44Hz); given Muse's frontal electrode placement (Fp1/Fp2/
     AF7/AF8), the gamma peak is almost certainly jaw/scalp EMG rather than
     neural signal, and the absence of any alpha peak is consistent with
     frontal electrodes being a weak proxy for the posteriorly-generated
     alpha rhythm — both exactly as flagged earlier in this doc. Good fit
     quality throughout (r² ≈ 0.88). **Phase 2 is now fully verified, not
     just synthetic-tested.**
3. **(Done, 2026-08-15)** Surface the numbers in a plain stats readout — an
   "EEG analysis (experimental)" card on Android's `SessionDetailActivity`
   (`ApiClient.analyzeEeg`/`getEegMetrics`, `Models.kt`'s `EegAnalysisResult`/
   `EegHalfMetric`/`OscillationPeak`): an "Analyze EEG" button when a session
   hasn't been analyzed yet, or — reread via `GET /eeg-metrics` on screen
   load — the per-half breakdown (aperiodic exponent/offset, fit r²,
   epoch count, detected peaks) when it has. The button is on-demand only,
   same precedent as the AI-summary button, and disappears once analyzed
   (the server discards the raw blob after a successful analysis, so a
   session can only be analyzed once). Hidden entirely in patient self-view,
   matching the AI-overview card (both endpoints are practitioner-only
   server-side). Verified on-device (Pixel 6a) against both real sessions:
   session 26 (analyzed earlier via direct API call) correctly loaded and
   displayed its cached result with the button hidden; session 25 (an older,
   no-signal test recording with no raw EEG ever uploaded) correctly showed
   the button, and tapping it surfaced the server's 404 ("No raw EEG
   uploaded for this session") as plain status text with the button
   re-enabled for retry — not a crash or a generic error toast. **Not yet
   built for iOS** — this phase covered Android only, matching where the
   Muse feature actually lives today (see CLAUDE.md's Muse backlog note).
   No chart yet — this is deliberately a plain readout, per this phase's
   original scope.
4. **(Done, 2026-08-16)** Chart redesign for aperiodic exponent + oscillation
   peaks. New Android view, `EegMetricChartView` — a compact line+dot chart
   (custom-drawn, no charting dependency, matching `BandChartView`'s existing
   approach) for a scalar metric across a small number of ordered steps, with
   points drawn as visible dots rather than a dense curve since this data is
   genuinely sparse (one value per half, not one per second). Used in two
   places:
   - **`PatientDetailActivity`'s EEG Trend tab** — this is where "exponent-
     over-time" actually applies: one step per analyzed session (x-axis,
     server's oldest-first order), two lines (period 0 "earlier half" / period
     1 "later half" — `EEG_TREND_PERIOD_LABELS`/`_COLORS` in `Models.kt`, blue
     vs amber, same colors everywhere this grouping appears), a new spinner to
     toggle the plotted metric between aperiodic exponent and eye-movement
     (EOG SEM) rate. The exact-numbers list this tab already had stays
     underneath unchanged — same "chart for the shape, table for the numbers"
     split `BandChartView` + its stats table already established, not a
     replacement.
   - **`SessionDetailActivity`'s EEG analysis card** — oscillation peaks moved
     from one joined text line ("Peaks: delta 2.1 Hz (power 0.30) · ...") to a
     small per-half table (band/Hz/power/bandwidth, band names color-matched
     via the existing `bandColor()`), the "sparse, discrete events... markers
     or a small table, not a line graph" half of this phase's original scope.
     The exponent/offset/r²/eog-rate text above it is unchanged, deliberately
     — a single session only has two halves (baseline/treatment or
     first/second), too few points for a line chart to read better than the
     existing two lines of text with exact numbers; "over time" genuinely
     means the cross-session trend above, not a two-dot chart here.
   - **Verified on-device (Pixel 6a)** against patient "Test"'s real 9-session
     history (sessions 26-30 among them, the same ones verified earlier in
     this doc): the trend chart correctly plots both period lines against
     real dates, the metric spinner switches cleanly to eye-movement rate and
     re-renders from the already-fetched data (no refetch), sessions with
     `eogSemRatePerMin == null` (pre-EOG-feature session 26) are correctly
     skipped from that line rather than plotted as zero, tap-to-inspect
     correctly surfaced session 30's real 23.0 SEM/min spike, and session 30's
     peaks table exactly reproduced this doc's own earlier real numbers for
     that session (baseline: beta 25.8Hz/gamma 44.2Hz; treatment: alpha
     10.8Hz/gamma 44.8Hz, exponent 1.22 → 2.19). One real bug found and fixed
     in this same pass: the x-axis's first/last date label was center-aligned
     and overhung the view's right edge, rendering as an illegible clipped
     sliver — fixed by anchoring the first/last labels LEFT/RIGHT instead of
     CENTER (`EegMetricChartView`'s x-label loop).
5. **(Done, 2026-08-15)** Baseline/control recording as part of the session
   flow — this doc's "control condition" section, above. Android's
   `SessionRecorder.markBaselineEnd()` (a "Baseline done — start treatment"
   button, shown while recording until tapped or skipped entirely) records
   elapsed seconds into `TcmSession.baseline_duration_seconds` and drops an
   ordinary marker at the same offset so the transition shows up on the
   band-power chart like any other marker. `eeg.analysis.analyze_session_eeg`
   splits epochs on that real cutoff instead of the wall-clock midpoint when
   one was recorded, labeling the halves `"baseline"`/`"treatment"` instead
   of `"first"`/`"second"` — a session can now compare its own resting state
   against its own treatment period instead of an arbitrary time split. No
   baseline recorded (practitioner skipped it, or the session predates this
   feature) falls back to the original midpoint split, unchanged. Entirely
   optional — doesn't block ending a session. **Sham needling is out of
   scope for the app** (a clinical-protocol matter, not something software
   controls). **Not yet verified against a real recording with an actual
   baseline phase** — built and unit-smoke-tested, same "real-hardware
   verification still pending" caveat as the rest of this phase.
6. (Separately motivated, not required for this) chunked/streaming upload —
   only worth doing for the crash-resilience reason, not for signal
   correction.
7. **(Done, 2026-08-16)** EOG-derived arousal scoring — the todo item flagged
   as "the one they'll skip and it's the most important" (source
   conversation). `eeg/analysis.py`'s `_eog_candidate_windows`/
   `_score_eog_half`: counts slow rolling eye movements (SEMs) — a
   well-established wake→drowsiness marker (Ogilvie 2001) — using the two
   frontal channels (assumed AF7/AF8, i.e. EEG2/EEG3 in the standard Muse S
   montage — see the EOG_FRONTAL_CHANNEL_INDICES comment) as an EOG proxy,
   since this headband has no dedicated eye-movement electrode. A candidate
   window (2s, non-overlapping) counts as a SEM only when it passes BOTH an
   independent localization check (frontal amplitude well above the same-band
   amplitude at the ear channels, ruling out whole-head artifacts like
   amplifier drift or a headband shift) and a conjugate-movement check
   (the two frontal channels correlated, ruling out one noisy channel being
   misread as an eye movement) — not amplitude alone. The amplitude threshold
   itself is session-relative (a low-percentile-anchored, one-sided MAD — see
   `EOG_AMPLITUDE_REFERENCE_PERCENTILE`/`EOG_AMPLITUDE_MAD_MULTIPLE`'s
   comments for why a median-based or two-sided version breaks down once a
   drowsy stretch covers a large fraction of a half), same "compare a session
   to itself" stance as the rest of this doc — the resulting rate is a
   within-session index, never a portable clinical unit.

   Runs on the pre-filter, NaN-interpolated signal with its OWN 0.3-1.5Hz
   bandpass (SEMs live below the main pipeline's 1Hz high-pass), independent
   of the specparam epoching. Stored per half on `SessionEegMetric`
   (`eog_sem_rate_per_min`, `eog_windows_analyzed`) — null rate means "not
   enough clean frontal signal to score" (fewer than
   `MIN_EOG_WINDOWS_PER_HALF`), an informative absence, not a rate of zero,
   same pattern as an empty oscillation-peaks list. Surfaced in
   `ai_summary.py`'s Form B (told to read it the same way as the aperiodic
   exponent — rising rate suggests drowsiness, never a treatment effect, and
   to prefer `patient_dozed`/`alertness_rating` as the explanation when both
   are present) and in Android's "EEG analysis (experimental)" card.

   **Verified against synthetic data across 10 RNG seeds** crossed with four
   scenarios each (a continuous full-half SEM injection, pure noise, a
   whole-head artifact, and a more realistic intermittent-burst pattern) —
   confirmed reliably separating an affected half from a quiet one (~0.6
   false-positive windows/min on pure noise, 100% detection of the
   continuous-injection worst case) once the amplitude threshold was tuned
   past two earlier failure modes caught by this same sweep: a two-sided MAD
   and a plain median-based split both silently under-detect once the
   "elevated" fraction of a recording approaches 50% (the median's own
   breakdown point) — a single continuous 2-minute injection filling an
   entire half is exactly that case, and an earlier version of this scoring
   scored it as 0 SEMs/min under both approaches before the fix.

   **Verified end-to-end against a real Muse S recording (2026-08-16, session
   28, patient "Test," ~3.3 min, practitioner-worn, alertness self-rated
   7/10, patient_dozed=false)** — a deliberate-movement protocol, not a real
   drowsiness test: 90s eyes-open/still (recorded as the baseline phase via
   `markBaselineEnd`), then ~107s eyes-closed with continuous slow rolling
   eye movements (roughly one sweep every 1.5–2s). Both the AF7/AF8
   channel-order assumption and the scoring algorithm itself had never been
   checked against anything but synthetic data before this — synthetic data
   can validate the algorithm's logic but can't validate that AF7/AF8 (index
   1/2) are really the frontal channels, since the libmuse SDK exposes no
   electrode names. Result: **baseline 2.7 SEM/min → treatment 6.2 SEM/min**
   (2.3x), aperiodic exponent essentially flat (2.21 → 2.18, consistent with
   the self-report — this was a movement test, not a drowsiness test), fit
   quality high in both halves (r²=0.99/0.98), and virtually 100% of
   candidate windows passed the bad-contact gate in both halves (45/45,
   53/53) — good real-world signal quality (2026-09-10: that gate only sees
   empty readings, not a lifted electrode, so it shows no gaps rather than
   good quality; see "Loose headband"). The exponent staying flat while
   the SEM rate rose 2.3x is itself informative: it shows the two markers are
   measuring genuinely different things, not just both reacting to the same
   confound. The margin (2.3x) is real but far more modest than the
   synthetic tests' near-total separation — expected, since real dry-
   electrode data is noisier than an idealized injected sine wave, and this
   is a single n=1 session, not a calibration study. `POST /sessions/{id}
   /summary` (Form B) correctly narrated both facts as distinct ("arousal
   state remained highly stable... even as signal-derived slow eye movements
   rose from 2.7 to 6.2 per minute") rather than conflating them, and
   Android's EEG analysis card rendered the new per-half eye-movement line
   correctly on-device (Pixel 6a). **The channel-order assumption and the
   detector's real-world sensitivity are now both verified, not just
   synthetic-tested** — though one n=1 deliberate-movement session is not the
   same as validating detection of ambient/involuntary drowsiness-driven
   SEMs, which would need a genuine drowsy-vs-alert comparison to confirm.

   **Bug found and fixed post-verification (same day, clean-context
   review):** the frontal amplitude was computed as a naive `(af7+af8)/2`
   average, which CANCELS an anti-phase (mirror-image) pair instead of
   reinforcing it — the classic EOG signature for horizontal eye movement
   (the corneo-retinal dipole moves toward one frontal electrode and away
   from the other), which `is_conjugate`'s `abs(correlation)` check was
   already written to accept in either direction. The synthetic test suite
   only ever injected an in-phase (same-sign) wave, so it never exercised
   this path — a real gap in the tests, not just the code. Fixed by aligning
   the two channels' sign (from the already-computed correlation) before
   averaging; a new synthetic anti-phase scenario now detects at the same
   rate as the in-phase one across all 5 seeds (30.0/min either way), where
   it previously would have scored ~0. **The real-hardware numbers above
   (2.7 → 6.2 SEM/min) reflect the PRE-FIX algorithm** — session 28's raw
   EEG blob was already discarded (by design, the same day, right after that
   verification) once its analysis succeeded, so there's no raw data left to
   re-run the fixed algorithm against for a direct before/after comparison.
   The verification's qualitative conclusions (channel order is right,
   baseline < treatment direction is right) still stand — the bug only
   undercounts, never fabricates a detection — but the specific 2.3x margin
   is now understood to likely be a conservative underestimate of what the
   fixed algorithm would show on genuinely mixed-phase eye movement, since
   the deliberate protocol ("roll your eyes... left-right or in slow
   circles") plausibly included anti-phase (horizontal) stretches that were
   being partly cancelled.

   **Re-verified against two fresh real recordings the same day (2026-08-16),
   after the fix.** First attempt (session 29, ~95s eyes-open baseline then
   ~80s eyes-closed horizontal eye rolling with ambiguous pacing —
   "one sweep every 1.5-2s" with no clarification of what counts as one
   sweep) came back nearly flat (0.0 → 0.77 SEM/min) — inconclusive, not a
   contradiction of the fix: `eeg_windows_analyzed` showed essentially no
   windows rejected for bad contact, so electrode contact wasn't the
   problem (2026-09-10: that counter only sees empty readings, not a lifted
   electrode, so this doesn't actually rule contact out; see "Loose
   headband"), and the most likely explanation is the movement's actual
   frequency falling outside the 0.3-1.5Hz passband (a real methodological
   lesson, not a code issue — the pacing instruction was ambiguous and the
   raw blob was already discarded by the time the weak result was noticed,
   so it couldn't be directly inspected to confirm). Second attempt (session
   30, same protocol but explicit 1-second-per-direction pacing, raw blob
   preserved to scratch BEFORE calling analyze this time) came back
   dramatic and unambiguous: **baseline 0.0 → treatment 23.0 SEM/min** — by
   far the strongest real-hardware signal seen yet, confirming the fix
   works on real anti-phase (horizontal) eye movement, not just synthetic
   injections.

   **A second, previously-undocumented confound was found and characterized
   in the same pass**: session 30's aperiodic exponent also shifted sharply
   (1.22 → 2.19) despite the patient self-reporting no drowsiness (an active
   eye-tracking task is itself hard to reconcile with dozing) and the fit
   quality staying good in both halves (r²=0.87/0.96 — ruling out "bad fit"
   as the explanation). Inspecting the preserved raw blob's Welch PSD
   directly confirmed why: delta-band power (1-4Hz) rose ~5x in the
   treatment half while beta/gamma fell to ~20-30% of baseline — a textbook
   1/f steepening, but driven by harmonic leakage from the eye movement's
   own fundamental frequency (a ~0.5Hz movement's 2nd/3rd harmonics land at
   1.0/1.5Hz, squarely inside the main pipeline's 1-45Hz analysis band,
   below the main pipeline's PTP-outlier epoch rejection catching it, since
   a UNIFORMLY elevated half has no individual outlier epochs relative to
   its own median — the same near-100%-contamination blind spot the EOG
   amplitude threshold fix above was written to avoid, just showing up here
   in `_epoch_and_reject`'s epoch rejection instead). Net: **sustained
   rhythmic eye movement can steepen the aperiodic exponent on its own,
   with no drowsiness involved** — a real, hardware-confirmed confound
   distinct from (but easily mistaken for) the arousal signal the exponent
   is meant to track. Added as a caveat to `ai_summary.py`'s
   `_CORRECTED_EEG_RULES` and as a computed fact in
   `_format_eeg_metrics_context` (fires when the exponent rises >0.3 in the
   same period the SEM rate more than doubles) — verified against session
   30's own summary, which correctly flagged the shift as "possibly...
   eye-movement artifact... rather than a genuine change in arousal" citing
   both the EOG co-occurrence and the self-report. ~~Not fixed at the
   pipeline level~~ — **fixed 2026-08-16, see below.**

   **Pipeline-level fix (2026-08-16).** `eeg/analysis.py` now excludes the
   frontal channels (AF7/AF8) from the spectrum that feeds the aperiodic fit
   whenever a session shows enough SUSTAINED eye-movement activity to
   plausibly be this mechanism — gated on the FRACTION of a half's scored
   EOG windows flagged as SEM candidates
   (`EOG_CONTAMINATION_FLAGGED_FRACTION = 0.30`), not the per-minute rate
   (the leakage mechanism is about near-continuous coverage of a half, not
   an absolute count a short recording could hit incidentally). Applied to
   BOTH halves of a session once EITHER half crosses that bar — deciding it
   per half would make a before/after comparison use two different channel
   sets, a new confound in its own right. This is the same localization
   property `_eog_candidate_windows` already established and validated
   (a real SEM's energy is concentrated on the frontal channels, by
   construction of that check) turned into a correction rather than just a
   flag.

   **Known tradeoff (caught in the clean-context review, not fixed
   separately — see `_fit_half`'s docstring in the code for the full
   reasoning):** the exclusion also applies to oscillatory PEAK detection,
   not just the aperiodic exponent/offset — one model is fit on one
   (possibly-excluded) spectrum and both are read off it. A real oscillation
   that happens to live mostly on the frontal channels during a contaminated
   half will report as "no peak detected" rather than "not evaluable due to
   eye-movement exclusion." Fitting peaks from a separate, non-excluded
   spectrum was considered and rejected: during genuine contamination the
   full-channel spectrum's own aperiodic estimate is exactly what's
   steepened by the leaked harmonic energy, so peaks fit against it risk the
   opposite failure — a FALSE low-frequency peak from the same leakage,
   rather than a missing real one. Silent under-reporting was judged the
   safer failure mode of the two, but it is a real, currently-unlabeled
   ambiguity, not a gap-free fix.

   **Verified against synthetic data** reproducing the real confound: a
   stationary 1/f background (independent per channel) plus a saccade-like
   (non-sinusoidal — a pure sine at the eye-movement's ~0.4Hz fundamental
   has zero energy above 1Hz; a real saccade's asymmetric slow-drift/
   fast-reset shape is what actually deposits harmonic energy into the
   1-45Hz analysis band) waveform injected into ONLY the frontal channels,
   ONLY in the second half, anti-phase (mirroring the real horizontal-
   eye-movement case that motivated this fix). Without the fix, the
   artifact steepened the second half's exponent by +0.142 over the first
   half's; with the fix, +0.021 (an ~85% reduction, consistent with
   near-complete removal of the injected artifact's contribution — the
   residual is fit noise, not remaining contamination, since the temporal
   channels used for the corrected fit never received the injected signal
   at all). A companion run confirmed the gate's specificity: the same
   background with only occasional (10% duty-cycle) bursts of the same
   waveform — mimicking ordinary, non-sustained eye movement — never
   crossed the 0.30 threshold, produced no exclusion (confirmed via the
   absence of the pipeline's info-level log line), and left the exponent
   shift small (-0.026), i.e. sessions with normal, non-sustained eye
   movement keep the full 4-channel average rather than losing signal
   quality to a fix they don't need.

   **Verified against real hardware (2026-08-16, sessions 31 and 32, same
   Muse S / patient "Test" setup as the rest of this doc's real-hardware
   passes).** Two takes, both with a real recorded baseline
   (`markBaselineEnd`) followed by deliberate sustained horizontal eye
   movement, raw blob preserved to scratch before each analyze call (same
   precedent as session 30) so a true before/after A/B could be run against
   the SAME real recording — call `analyze_session_eeg` once normally, once
   with `EOG_CONTAMINATION_FLAGGED_FRACTION` monkeypatched unreachable to
   reconstruct pre-fix behavior — not just a synthetic approximation of it.
   - **Session 31** (first take, ~90s eye movement, less continuous pacing):
     treatment flagged fraction only 0.184 — below the 0.30 gate, exclusion
     never fired, before/after identical (exponent 1.404 → 1.404, WITH and
     WITHOUT the fix). Not a failure: it's the gate correctly judging this
     take as not the sustained/near-continuous pattern the fix targets — a
     genuine field confirmation of the gate's SPECIFICITY (it doesn't
     over-trigger on every incidental eye movement), just not a
     demonstration of the correction itself.
   - **Session 32** (second take, same protocol but explicitly continuous,
     no pauses): treatment flagged fraction 0.783 — closely replicating
     session 30's original ~77% coverage and 23.0 SEM/min rate (this
     session: 23.5 SEM/min) — cleanly crossed the gate. **Without the fix:
     exponent shift +0.366** (baseline 1.404 → treatment 1.770 — matches
     session 30's original uncorrected pattern almost exactly). **With the
     fix: +0.319** (baseline 1.302 → treatment 1.621) — a real but modest
     ~13% reduction, far short of the synthetic test's ~85%. The gap is
     itself an expected, informative finding, not a discrepancy to explain
     away: the synthetic test injected the artifact into two fully
     independent noise channels with zero crosstalk to the other two,
     while real dry-electrode channels share a physical reference and are
     close together on the same head, so some of the leaked energy
     genuinely reaches the temporal channels too (exactly the kind of
     "real data is noisier than an idealized injection" gap this doc has
     already flagged for the EOG detector itself — see the SEM re-
     verification note above). The fix still measurably helps and never
     hurts (in neither take did it make the shift worse), but on this
     hardware it should be described as a partial mitigation, not a full
     correction, until more real sessions establish whether 13% is typical
     or session 32 was a particularly bad case for channel crosstalk.
   - **Verified end-to-end, not just at the pipeline-function level**: after
     confirming both takes' `analyze_session_eeg` results directly, session
     32's numbers were reproduced through the ACTUAL running server —
     re-uploading the preserved raw blob via `POST /sessions/32/eeg` and
     re-analyzing via `POST /sessions/32/eeg/analyze` against the live dev
     server, then confirmed the change reflected correctly in Android's
     `SessionDetailActivity` after a fresh reload from the session list.
   - **Process gotcha caught along the way, not a pipeline bug**: the local
     dev server (Task Scheduler's `SmartRingServer` task, `start_server.ps1`)
     runs uvicorn WITHOUT `--reload`, so it was still serving the
     PRE-fix `eeg/analysis.py` from before this session's edits — session
     31 and the FIRST analysis of session 32 both ran against stale code.
     This surfaced as session 32's first "Analyze EEG" tap showing 1.40/1.77
     (exactly the pre-fix numbers) instead of the expected corrected ones —
     caught by comparing the app's displayed numbers against the
     independently-run verification script rather than trusting the app
     output at face value. Fixed by restarting the scheduled task
     (`Stop-ScheduledTask` / `Start-ScheduledTask`); the re-upload-and-
     reanalyze pass above confirms the running server now serves the fixed
     code. Worth remembering for any future server-side change verified
     this way: code edits alone do NOT take effect against a
     `SmartRingServer`-launched dev instance without a restart.

   **Upgraded from exclusion to regression (2026-08-17)** — the direct
   follow-on this doc flagged: session 32's real result (a partial, ~13%
   reduction) against the synthetic test's ~85% was left unexplained beyond
   "real electrodes share crosstalk the synthetic channels didn't." Two
   things changed: (1) the synthetic test itself was rebuilt to actually
   inject that crosstalk (an attenuated, same-signed copy of the frontal
   saccade waveform also leaking into the temporal channels, swept at
   0/15/30/50% crosstalk) instead of using fully independent noise channels;
   (2) the fix itself was changed from dropping the frontal channels
   entirely to `eeg.analysis._regress_out_channels` — an OLS regression of
   the temporal channels against BOTH frontal channels (as separate
   regressors, not a combined proxy — see the function's own docstring for
   why that handles in-phase/anti-phase crosstalk without a sign-alignment
   hack), applied after the main 1-45Hz filter so regressors and targets are
   compared in the same band the leakage lives in. The frontal channels are
   still excluded from the final averaged spectrum afterward, same as
   before — this changes what happens to the temporal channels' data, not
   which channels feed the fit.

   The rebuilt synthetic test is the actual finding here: at 50% crosstalk
   (10 seeds), exclusion's reduction in the contaminating exponent shift
   dropped to ~14% — strikingly close to session 32's real ~13%, which is
   the strongest evidence yet for the crosstalk explanation and a rough
   read on where real hardware sits on that dial. Regression, across the
   SAME 0/15/30/50% sweep, held ~93-96% reduction at every level — it
   degrades far more slowly than exclusion because it directly models and
   removes the shared linear component, rather than hoping a channel
   untouched by the correction happens to be clean. A separate clean-data
   check (no injected eye movement, 10 seeds) found regression's exponent
   estimate essentially indistinguishable from exclusion's (both ~1.545,
   within a fraction of one synthetic std of each other) — no sign that
   regression introduces its own bias when there's nothing to correct. An
   end-to-end smoke test through the real `analyze_session_eeg` entry point
   (not just the standalone synthetic harness) on a synthetic contaminated
   session reproduced the same story: uncorrected shift +0.551, regression-
   fixed shift -0.014, fit quality unchanged (r²≈0.99 both halves).

   **Not yet run against real hardware** — this is a synthetic result
   only, same caveat every phase in this doc carries before its first real
   recording. The natural next step (and the other half of this doc's
   backlog item) is recording a few more real sustained-eye-movement
   sessions: each one is now both an additional data point on whether
   session 32's 13% is typical and a real-hardware test of whether
   regression actually outperforms exclusion outside simulation, using the
   same preserve-raw-blob-before-analyze A/B methodology sessions 30-32
   already established.

   **Real-hardware validation (2026-08-17, sessions 33-35, same Muse S /
   patient "Test" setup).** Three fresh sustained-eye-movement recordings,
   each preserved to scratch before analysis (same precedent as 30-32) and
   run through all three modes — none / exclude / regress — via the actual
   `eeg_analysis` internals, not a reimplementation. All three cleanly
   crossed the contamination gate (flagged fractions 0.78-0.96 in the
   contaminated half) with strong EOG confirmation (0→24-29 SEM/min).

   Headline result: **regression's estimate of the contaminated half's own
   exponent came out lower than exclusion's in all three sessions** (1.734
   vs 1.941 / 1.614 vs 2.065 / 1.722 vs 1.997 — regression lower by
   0.21-0.45 each time), closer to the ~0.8-1.2 range every uncontaminated
   half in these same sessions actually shows. Confirms the synthetic
   sweep's qualitative conclusion (regression beats exclusion) on real
   hardware, 3 for 3.

   The QUANTITATIVE size of the win was noisy and, on the original
   before/after-shift metric, sometimes ambiguous — worth recording
   precisely rather than rounding to one clean number:
   - Shift reduction vs. uncorrected: session 33 exclude ~2% / regress
     ~28%; session 34 exclude ~38% / regress ~72%; session 35 exclude ~16%
     / regress ~21%. All three sessions fall well short of the synthetic
     sweep's 93-96%, and the range across sessions (2-38% for exclude,
     21-72% for regress) is wide enough that neither session 32's original
     13% nor any single number here should be read as "the" real-world
     figure — same-rig, same-patient, same-day variance alone spans most of
     that range, so the honest answer to "was session 32 typical" is
     "there isn't yet a stable typical number, only a wide real one."
   - **A confound in the shift metric itself, found in session 35**:
     exclude's own BASELINE exponent moved up (0.832 -> 1.074) despite that
     half having ZERO flagged EOG windows — nothing there to correct. This
     is a structural side effect of averaging 2 channels instead of 4
     (removing frontal channels changes what the "average PSD" represents
     even on a segment with no contamination to remove), not a correction
     benefit — and it happened to inflate exclude's apparent shift-based
     "reduction" number by dragging the baseline up in the same direction
     as the (barely-changed) treatment value. Exclude's OWN treatment-half
     exponent in that session (1.997) actually came out HIGHER than
     uncorrected (1.932) — worse, not better — a fact the shift metric
     alone hides. Regression's baseline barely moved in the same session
     (0.832 -> 0.852), consistent with it having near-nothing to regress
     out of a genuinely clean segment. This is why the "contaminated-half
     exponent, mode vs. mode" comparison above is trusted over the
     before/after-shift-reduction framing used for the synthetic sweep and
     for session 32 — the shift metric can mask a bad correction if both
     halves move in the same direction. Only 3 real sessions have surfaced
     this so far — worth watching for on future real sessions, not yet
     established as a reliable property of exclude specifically (session
     33's baseline barely moved at all under exclude, -0.02).
   - A modest fit-quality cost was observed once: session 34's regress-mode
     first half had r²=0.910 vs. exclude's r²=0.979 — still a good fit, but
     worth knowing regression isn't free of tradeoffs either.

   **Net**: the regression fix, wired in as the pipeline default earlier
   this same day, is now real-hardware-confirmed (not just synthetic) to
   outperform the exclusion fix it replaced, consistently, though the
   absolute size of "how contaminated is too contaminated" remains
   genuinely variable session to session on this rig.

   **Verified end-to-end through the actual running server**, not just via
   direct pipeline calls (analysis above was done that way specifically to
   preserve the raw blobs for the A/B, matching sessions 30-32's pattern):
   all three sessions were subsequently re-run through the real `POST
   /sessions/{id}/eeg/analyze` endpoint and reproduced the regress-mode
   numbers above EXACTLY (session 33: 0.921 -> 1.734; session 34: 1.201 ->
   1.614; session 35: 0.852 -> 1.722).

   **A recurrence of the "stale server" process gotcha, worse than the
   2026-08-16 version, caught along the way**: the first attempt to verify
   session 35 through the live endpoint came back showing the OLD
   exclude-mode numbers (1.074 / 1.997) despite the `SmartRingServer`
   scheduled task having already been stopped and restarted earlier this
   session. Root cause: `start_server.ps1` launches uvicorn via
   `Start-Process`, which detaches the child process from the scheduled
   task's own process tree — `Stop-ScheduledTask` only kills the task's
   registered action, not that detached grandchild, so the ORIGINAL
   9:07am process (`python.exe`, still holding port 8000) kept answering
   requests the whole time, and `Start-ScheduledTask` either failed to
   bind or spawned a process nothing was talking to. `curl .../docs`
   returning 200 after the "restart" was consistent with either the stale
   process still running OR a genuinely new one — that check alone can't
   tell the two apart. Fixed by explicitly finding and killing every
   lingering `python.exe` under `server/.venv` (not just cycling the
   task), confirmed via a fresh PID and start time before re-verifying.
   This exact failure mode (and its fix) was ALREADY documented in the
   `run-server` skill's instructions — the first restart attempt just
   didn't follow them fully (task cycle only, no explicit process kill).
   Worth restating here because it silently corrupted one verification
   step's result (session 35's first live-endpoint check) before being
   caught by comparing against the independently-run script's numbers —
   same detection method the original 2026-08-16 gotcha used. Any future
   live-server verification should confirm the serving process's PID/start
   time is actually fresh, not just that the port answers.
8. **(Done, 2026-08-16)** Cross-session trending — the earlier "compare a
   session to itself" framing extended across time: `GET /patients/{id}
   /eeg-trend` (`routers/sessions.py`) returns every analyzed half
   (aperiodic offset/exponent, r², eog_sem_rate_per_min/eog_windows_analyzed)
   across a patient's history, scoped to sessions THIS practitioner
   authored for THIS patient (same filter-based scoping as the pre-existing
   `list_patient_sessions`, no separate ownership check needed — see the
   endpoint's own docstring) and ordered oldest-first, the one list endpoint
   in this app that doesn't lead with the most recent, since this is
   trend/chart data, not a record list. `period` (0 = earlier half, 1 =
   later half) normalizes across sessions that mix baseline/treatment and
   first/second labeling, so the client can group consistently regardless of
   which split a given session actually used. Android: a 4th "EEG Trend" tab
   on `PatientDetailActivity`, practitioner-only (never added in self-view,
   same gating as the single-session EEG analysis card) — deliberately a
   plain per-session list (date header, one line per half), not a chart yet,
   same "ship the plain readout first" precedent this doc's phase 3 already
   established; the chart redesign is phase 4, a separate item, not
   superseded by this. Verified against the real DB: correctly mixes a
   pre-EOG-feature analyzed session (session 26 — shows "not enough signal
   for eye-movement rate" rather than a fake 0, since eog_sem_rate_per_min is
   genuinely null for it, not just low) with a post-EOG one (session 28) in
   the same list, confirmed the authorization boundary (a second
   practitioner gets an empty list, not another practitioner's patient
   data), and confirmed the tab renders correctly on-device (Pixel 6a).

## Positive control: eyes-open vs eyes-closed (2026-09-09) — PASSED

The validation this whole pipeline rested on and had never actually had. Alpha
blocking — closing the eyes raises 8-13Hz power — is the oldest robust effect
in EEG, and it is readable without neuroscience training: if the pipeline
reports a bigger alpha peak in the eyes-closed half, it works.

**Protocol.** Three ~4.5-minute sessions, 2 minutes per half, split on a real
`markBaselineEnd` cutoff (so the halves label baseline/treatment, exercising
that path rather than the midpoint fallback). Same room, same chair, same
lighting, no talking or jaw movement. **The order was deliberately
counterbalanced** — runs 1 and 3 eyes-open first, run 2 eyes-closed first —
because three runs in the SAME order cannot distinguish "alpha follows the
eyes" from "the second half always looks like that." Muse S, recorded from a
Pixel 11 against the dev Mac's server.

| run | session | order | alpha, 1st half | alpha, 2nd half |
|-----|---------|-------|-----------------|-----------------|
| 1 | 8  | open -> closed | none | 10.61 Hz @ 0.512 |
| 2 | 9  | closed -> open | 10.74 Hz @ 0.730 | none |
| 3 | 11 | open -> closed | none | 11.07 Hz @ 0.541 |

**Result: alpha appeared in the eyes-closed half in all three runs, and the
peak moved to the FIRST half when the order was flipped.** It tracks eye
state, not position in the recording. specparam finds a real oscillation on a
headband with no occipital electrodes. (Session 10 is a deliberate failure
case, not a lost recording.)

Two secondary findings, one of which does NOT flatter the pipeline:

- **The EOG/SEM detector also tracks eye state** — rate was higher in the
  eyes-open half every time (2.50 vs 0.86, 0.97 vs 0.00, 3.39 vs 0.47
  per min). Independent of the alpha result, and evidence the detector
  responds to genuine eye activity. Note this is eye-state contrast, still
  NOT the ambient involuntary drowsiness SEMs it is ultimately meant to
  catch — same gap flagged in eeg/analysis.py's module docstring.
- **The aperiodic exponent is the weaker signal: 2 of 3, not 3 of 3.** It
  steepened with eyes closed in runs 1 and 2 (1.167 -> 1.546; 0.898 ->
  1.303) but was FLAT in run 3 (1.501 open vs 1.497 closed). On the one
  manipulation that should shift arousal most sharply, the number this app
  leans on hardest failed to move once in three tries. Do not read a single
  session's exponent shift as an arousal finding on this evidence; the
  oscillatory peak is the better-behaved measurement of the two.

**r-squared has a systematic pattern worth using.** Eyes-closed halves fit at
0.992/0.993/0.993; eyes-open halves at 0.872/0.917/0.958. Noisier data (blinks,
saccades, muscle) fits the model worse, and the fit knows it. A 0.9 cutoff
would flag exactly one of six halves here, which suggests the "don't trust
this exponent" line should sit HIGHER than the textbook 0.9 — pick it from
more sessions before hard-coding it.

(Superseded the same day: it was moved to 0.97 from these same six halves once
per-cause rejection counts gave a reason beyond the spread. See "What the
quality gates showed" below for why that was judged enough, and for what it
still doesn't establish.)

**Limits, so this is not over-claimed.** One wearer for all three runs (the
brief wanted two — headband fit and skull anatomy are not varied here, so the
tight 10.61/10.74/11.07 Hz cluster is within-person consistency, not
between-person agreement). One headband. Two-minute halves. And a deliberate
eye-state manipulation is a far larger, cleaner effect than any treatment
response this app will ever be asked to detect — passing here proves the
pipeline can find a real oscillation, NOT that it can resolve a clinical one.

Also verified in the same sitting: the re-analyze affordance (session 8
re-analyzed from the app, `eeg_analyzed_at` 00:47:58 -> 00:51:00, byte-identical
metrics — correct, the pipeline is deterministic over an unchanged blob).

## What the quality gates showed (2026-09-09, same three sessions)

Once per-cause rejection counts were surfaced (the transparency work below),
sessions 8/9/11 were **re-analyzed** — possible only because analyze keeps the
blob and the app finally has a re-analyze button. Core metrics came back
bit-identical, confirming that refactor changed no science.

**Contact was perfect and is therefore untested.** 0.00% bad-contact readings,
zero epochs dropped for contact, zero partial-channel epochs, across all three.
That verifies those paths REPORT zero; it does not verify they count correctly
when non-zero. Treat the bad-contact and partial-contact counters as unexercised.
**Update 2026-09-10: tested against a lifted electrode, and they don't see
it.** They count only readings the SDK sends as NaN, and a lifted electrode
keeps sending numbers. So these zeros never showed that contact was good. The
NaN-counting path itself is still unexercised; it has never counted anything
non-zero. See "Loose headband" below.

**Every rejection was movement, and movement tracked eye state:**

| session | half | eyes | r² | windows dropped |
|---------|------|------|-----|-----------------|
| 8  | baseline  | open   | 0.872 | 10 of 60 (17%) |
| 8  | treatment | closed | 0.993 | 5 of 69 (7%) |
| 9  | baseline  | closed | 0.992 | 9 of 65 (14%) |
| 9  | treatment | open   | 0.917 | 16 of 61 (26%) |
| 11 | baseline  | open   | 0.958 | 15 of 61 (25%) |
| 11 | treatment | closed | 0.993 | 2 of 64 (3%) |

**Every eyes-open half lost more windows than every eyes-closed half** (10/16/15
against 5/9/2). That explains the r² split recorded in the section above, which
was noted there as a pattern with no mechanism: the likely chain is blinks ->
amplitude rejections -> residual artifact in what survives -> a worse specparam
fit. Sorted by rejection rate, the three cleanest halves (3/7/14%) are exactly
the three fitting at ~0.992, and the three dirtiest (17/25/26%) are the three
fitting worst.

This is the case for splitting rejection by cause rather than reporting one
total: a summed "16 of 61 rejected" would have shown the same six numbers with
none of the diagnosis.

**Caveat on the causal story.** Every eyes-open half in this set is also a
high-rejection half, so "more rejections leave more residual artifact, which
fits worse" and "eyes-open independently causes both" cannot be separated
here. The blink chain is plausible, not established.

**LOW_FIT_R_SQUARED raised 0.90 -> 0.97** (`server/app/config.py`). At 0.90
the worst half by rejection rate — session 9's treatment, 26% dropped — passed
unflagged at 0.917 while a cleaner half was flagged. The spread has exactly one
gap in it: nothing lands between 0.958 and 0.992. 0.97 sits in the middle of
that gap, so it flags all three high-rejection halves and none of the clean
ones. This also makes `ai_summary` more conservative, since both read the same
constant.

**0.95 was tried first and was wrong.** It was chosen on the claim that it
"separates the three dirty halves from the three clean ones exactly" — but
0.958 is above 0.95, so it flagged only two of the three, and the very defect
that argued against 0.90 survived it: session 11's baseline (25% dropped)
stayed unflagged while session 8's baseline (17% dropped) was flagged. The
pre-push review caught the arithmetic. Recorded rather than quietly corrected,
because an evidence trail nobody can check is worth nothing.

Still six halves from one wearer, and 0.97 is the midpoint of the only gap in
that data rather than a validated line — a different wearer could easily put a
half inside it. Better founded than the textbook default; not settled.

## Spectrum stored and plotted (2026-09-10)

`_average_psd` computed each half's spectrum, handed it to specparam, and
dropped it — so the only view of a fit was its r² and a warning badge. Each
half now stores four index-aligned arrays in `SessionEegMetric.spectrum` (JSON,
~177 points each at 0.25Hz from 1-45Hz; `eeg.analysis._spectrum_arrays`): the
spectrum specparam fit, the full model, the aperiodic component alone, and
the frequency grid. The session card plots them under each half's numbers
(`EegSpectrumChartView`): recorded (solid), full model (thin), aperiodic
(dashed), both axes log-scaled so the aperiodic line is straight with the
exponent as its slope. Both halves share one power range so they compare by
eye; the power axis has no numbers, since log10(V²/Hz) values near -12 mean
nothing to the reader and the shape is the point.

**Stored at analyze time, not recomputed at read time.** Blobs are retained
and the pipeline keeps changing, so a read-time spectrum could come from newer
code than the numbers stored beside it, and the two would disagree silently.
Every array is read off the fitted model, not rebuilt from offset/exponent,
so the plot follows the fit even if the aperiodic mode ever changes.

**Rounded to 4 decimals of log10 power**, so an r² recomputed from the stored
arrays matches the stored r² to ~1e-5, not exactly. `ai_summary` deliberately
does not receive the spectrum — the picture is for people to look at.

**Migration:** `ALTER TABLE session_eeg_metrics ADD COLUMN spectrum JSON;`
Run it BEFORE restarting the server on the new code: every query that loads
`SessionEegMetric` selects the column, so until it exists GET eeg-metrics,
analyze, AND session summarize (the AI summary for any session) all 500.
Analyses from before this show "Spectrum: not recorded" until re-analyzed.

**A failed specparam fit is now a 422** ("could not fit the … half"). specparam
clears its results on failure rather than raising; previously that surfaced as
NaN parameters, which SQLite then refused as NULL in a NOT NULL column.

**Verified on the Mac, 2026-09-10.** Sessions 8/9/11 re-analyzed on the new
code; the stored arrays were then pulled from GET eeg-metrics and checked:

- Core metrics identical to 2026-09-09 (r², exponents, offsets, peaks).
- r² recomputed from the stored arrays matches the stored r² to within 3e-6
  (the rounding). A line fitted through the stored aperiodic curve has slope
  -exponent to within 1e-4 and intercept equal to the offset to the 3
  decimals checked. The plot and the numbers are one fit.
- Alpha: in the three eyes-closed halves the recorded spectrum rises
  +0.58 / +0.78 / +0.73 log10 (about 4-6x) above the aperiodic line, highest
  at the 10.75 / 10.50 / 11.24Hz bins. Those are single-bin maxima, so they
  differ slightly from specparam's fitted peak centres (10.61 / 10.74 /
  11.07Hz, in the positive-control table above). In the three eyes-open
  halves the highest point in 8-13Hz is -0.12 / -0.02 / -0.04, at or below
  the line. The positive control is now visible on the card, not just in a
  table.

**New: the three low-fit halves share one misfit shape.** Two views of the
same six halves, each the signed mean log10 difference of the recorded
spectrum from a fitted curve, per band (the top band runs to the end of the
data, 44.98Hz). First, from the aperiodic line (the dashed line on the card):

| session | half | eyes | r² | 1-3Hz | 8-20 | 20-25 | 25-30 | 30-35 | 35-40 | 40-45 |
|---------|------|------|-----|-------|------|-------|-------|-------|-------|-------|
| 8  | baseline  | open   | 0.872 | +0.31 | -0.17 | +0.19 | +0.35 | +0.34 | +0.15 | +0.20 |
| 9  | treatment | open   | 0.917 | +0.15 | -0.07 | +0.14 | +0.23 | +0.16 | +0.06 | +0.09 |
| 11 | baseline  | open   | 0.958 | +0.18 | -0.11 | +0.10 | +0.20 | +0.11 | +0.07 | +0.14 |
| 8  | treatment | closed | 0.993 | +0.13 | +0.11 | +0.09 | -0.01 | -0.00 | +0.03 | +0.04 |
| 9  | baseline  | closed | 0.992 | +0.01 | +0.27 | +0.18 | +0.01 | -0.02 | -0.02 | +0.01 |
| 11 | treatment | closed | 0.993 | -0.00 | +0.22 | +0.20 | +0.02 | -0.02 | -0.01 | +0.01 |

Second, from the full model (the thin line, which is what r² measures), plus
the RMS of that distance over the whole spectrum:

| session | half | eyes | r² | 1-3Hz | 8-20 | 20-25 | 25-30 | 30-35 | 35-40 | 40-45 | RMS |
|---------|------|------|-----|-------|------|-------|-------|-------|-------|-------|-----|
| 8  | baseline  | open   | 0.872 | +0.31 | -0.17 | -0.02 | +0.01 | +0.04 | +0.04 | +0.19 | 0.146 |
| 9  | treatment | open   | 0.917 | +0.15 | -0.08 | -0.02 | +0.01 | +0.02 | +0.05 | +0.07 | 0.090 |
| 11 | baseline  | open   | 0.958 | +0.18 | -0.11 | -0.02 | -0.01 | +0.00 | +0.04 | +0.14 | 0.104 |
| 8  | treatment | closed | 0.993 | +0.00 | +0.01 | -0.01 | -0.01 | -0.00 | +0.01 | +0.00 | 0.052 |
| 9  | baseline  | closed | 0.992 | -0.03 | +0.01 | +0.00 | -0.01 | -0.02 | -0.02 | +0.01 | 0.048 |
| 11 | treatment | closed | 0.993 | -0.04 | +0.01 | -0.00 | -0.01 | -0.02 | -0.01 | +0.01 | 0.049 |

What the two views show together:
- The eyes-closed halves show no systematic misfit against the full model:
  every band is within ±0.04 (RMS about 0.05, ordinary bin-to-bin scatter).
  Their bumps above the dashed line are peaks the model fits: alpha in
  8-20Hz, a ~21-23Hz beta peak in 20-25Hz, and a delta peak at 3.2 / 3.5 /
  3.6Hz, which absorbs all of session 8's +0.13 at 1-3Hz.
- Every eyes-open half rises above the dashed line across roughly 25-45Hz
  (largest at 25-35Hz), where no eyes-closed half does. The model absorbs
  most of the 25-40Hz rise by fitting beta and gamma "peaks" to it (reported
  at 25.8-29.1 and 30.9-33.9Hz), so that part costs little r². What the
  model can't absorb, and what the low r² registers, is a 1-3Hz lift (+0.15
  to +0.31; see the low-end bullet below for how much of that is the
  model's own peak choice), a sag at 8-20Hz (-0.08 to -0.17) where the
  straight line is pulled above the data by both ends, and an upturn at the
  top edge, 40-45Hz (+0.07 to +0.19). No eyes-closed half has any of these
  against the model. The upturn isn't a filter-edge effect: the eyes-closed
  halves pass through the same 45Hz low-pass and don't show it, and a
  low-pass would push power down at the edge, not up.
- The size of the misfit does NOT follow r² exactly. By RMS difference from
  the model, session 9's treatment half (0.090) sits below session 11's
  baseline (0.104) even though its r² is lower. r² also depends on the
  spectrum's overall steepness (session 9's treatment half has the flattest
  exponent, 0.90), so only the extremes are clearly ordered.

Interpretation, plausible and NOT established:
- Low end: the eyes-open/eyes-closed separation is weaker than the 1-3Hz
  column suggests. In finer bins, BOTH conditions sit about +0.1 to +0.26
  above the dashed line across 2-4Hz. specparam fits that as a delta peak in
  all three eyes-closed halves and in no eyes-open half, so against the full
  model it shows up as misfit only in the eyes-open ones — a difference
  created partly by the model's peak choices. The difference that doesn't
  depend on the model is confined to the lowest bin: at 1-1.5Hz every
  eyes-open half is above the line (+0.45 / +0.04 / +0.10) and every
  eyes-closed half below it (-0.08 / -0.22 / -0.20). Only session 8's
  eyes-open half keeps rising all the way to the 1Hz edge. That is thin
  support for the blink chain proposed above: consistent with slow eye
  artifact at the very bottom of the band, but most of the 1-3Hz lift is
  shared by both conditions.
- High end: a broad rise over 25-45Hz with an upturn at the top edge, rather
  than a narrow peak, is how muscle (EMG) typically looks at these
  frequencies, since EMG power keeps rising above 45Hz. A fixed noise floor
  from the hardware is unlikely, for the same reason as the filter: the
  eyes-closed halves use the same hardware and don't show it. Only a floor
  that changes with conditions, such as movement or contact noise, would fit,
  and that would itself be artifact. If it is muscle, the beta and gamma
  "peaks" reported for the three eyes-open halves are specparam fitting it,
  and are more likely muscle than neural beta/gamma. This doc already notes
  Muse gamma is largely EMG; this suggests the problem reaches down into
  beta.
- The exponent in those halves is a line fit between artifact at both ends,
  so part of the 2-of-3 exponent shift with eye state could be artifact
  rather than arousal. Evidence against: session 9's treatment half and
  session 11's baseline half have a similar misfit profile, yet session 9's
  exponent differs between its halves by 0.40 and session 11's by 0.004. The
  misfit shape doesn't predict which run shifted.
- Same confound as before: eyes-open is also the artifact condition here, so
  the two can't be separated. The second-wearer and loose-headband recordings
  are the next chance to.

The chart first drew the full model on top of the dashed aperiodic line.
Where there's no peak those are the same curve, so the dashed line vanished.
It is now drawn last, with square dash ends so its gaps stay open.

## The SDK's jaw-clench detector (2026-09-10)

Session 13 saved all 249 of its per-second band-power ticks as
`NO_SIGNAL_VALUE`, while the recording screen showed healthy live values
throughout. Its raw EEG was fine: all channels, an alpha peak in both
halves, and 0% bad contact (though that last one only means no empty readings;
see "Loose headband" below). A once-a-second log of `MuseArtifactPacket` then showed
what happened:

- **jawClench** was true in every packet (~10 a second) for minutes at a
  time while the wearer sat still, eyes closed or just looking at the screen.
- It also **switched itself off** for 20-45 s stretches (0%) and back on
  again, with nothing changing.
- **blink** was true in ~100% of packets throughout, including while
  jawClench was off.
- **headbandOn** looked correct; it caught the headband being adjusted
  mid-session. (Later the same day it missed a side held off the forehead
  for two minutes entirely; see "Loose headband" below.)

Power-cycling the headband cleared it briefly, but it came back within
minutes. The flag also routinely sits near 100% for the first 10-30 s of a
session, which likely explains the start-up drop-outs in sessions 8 and 11 (11
and 27 of the first 30 s). That is an inference: the old recorder didn't save
which flag dropped a second, but headband-off looked correct throughout. A "detect the stuck detector" workaround (ignore the
flag after 10 s of the stuck signature) was built and dropped the same day,
because that start-up behavior tripped it on nearly every session.

**Decision: the recorder no longer drops ticks on jawClench.** Headband-off
still drops them. A second with no fresh band packet is now saved as
no-signal rather than as a frozen repeat of the last value. The recording
screen now shows, live, how many seconds are being saved and why any aren't.

Consequences:

- The per-second band-power series includes muscle artifact unfiltered, as it
  did before 2026-08-13. The AI summary's band-power form (used for
  unanalyzed sessions) reads that series; its prompts already name EMG as a
  possible cause.
- The raw-EEG analysis is unaffected. It never used these flags.
- Sessions 13-15's raw EEG is usable despite their empty band-power charts.

Hardware: Muse S reporting `Athena_RevE`, firmware 3.1.23. Whether the
detector misbehaves on other Muse hardware, or started after a firmware or
SDK change, is unknown. Squeezing the eyes shut trips the flag legitimately,
because forehead and eye muscle activity is EMG, so close the eyes gently in
eyes-closed conditions.

**Later controls the same day.** Session 23 (below) held a deliberate,
steady jaw clench for about two minutes. jawClench flagged roughly the first
15 s of it (~100%, then ~50%, starting ~5 s after the switch), then read ~1%
for the remaining ~100 s of the hold. Session 24 lifted one side of the
headband off the forehead, and jawClench rose to 100% within ~20 s and stayed
on for 109 of the 121 lifted seconds. So it misses a sustained clench and
fires on poor contact.

Sessions 21, 22 and 23 also showed the flag ~60-100% while idle and ~0% while
recording with the eyes closed and relaxed. It went off within ~11 s either
side of Record (sessions 22 and 23 were already off before it) and came back
~5-12 s after Stop. That pattern is equally
consistent with the flag correctly tracking the wearer's face and jaw while
they use the phone, so it isn't evidence of unreliability on its own. The
evidence is the morning log (on for minutes with the wearer still), session
13 (on throughout a still, eyes-closed recording), and sessions 23 and 24
above.

Session 24 was also the first recording on the new recorder with the flag on
for most of the recording. It saved 250 of 251 seconds; the old jawClench gate
would have dropped about 128. Every recording on the new recorder (sessions
21-24) lost only offset 0, the first tick before any band reading arrives.

## No-change control, first run (2026-09-10)

Session 13: eyes closed and still in both halves, split on a baseline marked
at 123 s.

| half | r² | exponent | alpha | windows dropped (movement) |
|------|-----|----------|-------|----------------------------|
| baseline  | 0.970 | 1.371 | 10.64 Hz | 6 |
| treatment | 0.972 | 1.196 | 10.81 Hz | 9 |

**With nothing changing, the exponent moved 0.18 between halves.** That is
about half the eye-state shifts in the positive control (sessions 8 and 9:
0.38 and 0.40), and far more than session 11's 0.004. This is the first
estimate of how big a between-halves exponent difference can be while
meaning nothing.

Caveat on its own fit: both halves fit at r² 0.970 and 0.972. That is right
at `LOW_FIT_R_SQUARED` (0.97), and well below the ~0.99 that every
eyes-closed half in the positive control reached. So part of the 0.18 may be
fit noise rather than a true between-halves floor. It is one run and one
wearer, so repeat it two or three times before reading any single session's
exponent shift. (The band-power
chart for this session is blank because of the detector above; the analysis
used only the raw EEG.)

## No-change control, all three runs (2026-09-10)

Sessions 21 and 22 repeated session 13: same wearer, eyes gently closed and
still in both halves, about 2 min each, split on a marked baseline.

| session | split | r² (baseline / treatment) | exponent | shift | alpha power | windows dropped (movement) |
|---------|-------|---------------------------|----------|-------|-------------|----------------------------|
| 13 | 123 s | 0.9697 (flagged) / 0.972 | 1.371 → 1.196 | −0.175 | 0.72 → 0.89 | 6 / 9 |
| 21 | 126 s | 0.986 / 0.987 | 1.200 → 1.267 | +0.068 | 0.87 → 0.74 | 7 / 14 |
| 22 | 128 s | 0.988 / 0.990 | 1.351 → 1.346 | −0.005 | 0.82 → 0.51 | 11 / 3 |

**With both halves fitting well, nothing changing moved the exponent by at
most 0.07. The one borderline fit reached 0.18.** Working rule for this
setup: a between-halves shift under ~0.2 is indistinguishable from nothing,
and near `LOW_FIT_R_SQUARED` not even that is safe. Three runs, one wearer,
one day: a working floor, not a validated one.

**Exponent level varies more across sessions than within them.** Same wearer,
same day, same condition, and the baselines alone span 1.20-1.37. Comparing
one session's exponent with another's is noisier than comparing its two
halves.

**Alpha power is not usable as evidence.** It moved 0.13-0.32 between halves
with nothing changing, and the positive control's eyes-closed alpha powers
(0.51-0.73) sit inside the no-change halves' own spread (0.51-0.89). What the positive control established is
alpha appearing and disappearing with eye state, not how strong it was.

**This weakens the positive control's exponent result.** Its two large
shifts (0.38, 0.40) clear the floor, but each had a low-fit eyes-open half
(r² 0.872, 0.917): the condition where shifts are least trustworthy. The
muscle control below shows how much of that could be muscle. There is still
no clean evidence that eye state moves the exponent. The alpha result stands.

## Muscle control (2026-09-10)

Session 23: eyes gently closed in both halves; relaxed, then a steady jaw
clench held for the whole second half (split at 126 s).

| half | r² | exponent | alpha | other peaks | windows dropped (movement) |
|------|-----|----------|-------|-------------|----------------------------|
| relaxed  | 0.985 | 1.401 | 10.90 Hz @ 0.86 | beta 19.2 Hz, gamma 42.8 Hz | 5 |
| clenched | 0.938 | 0.861 | 11.15 Hz @ 0.62 | gamma 44.7 Hz | 21 |

**A jaw clench alone moved the exponent by −0.54: more than either eyes-open
shift, ~2.7× the ~0.2 working floor, and ~8× the best-fitting no-change
shift.** The clenched half was flagged low-fit (0.938), though every eyes-open
half on record is too, so the flag marks "something is off" rather than
muscle specifically.

Mechanism: muscle (EMG) adds broadband power that grows with frequency,
which flattens the fitted slope. Power change from relaxed to clenched, mean
log₁₀ over each band:

| | 1-4 Hz | 4-8 | 8-13 | 13-25 | 25-35 | 35-45 |
|---|---|---|---|---|---|---|
| clench (23) | −0.09 | 0.00 | 0.01 | +0.14 | **+0.39** | **+0.59** |
| eyes open − closed (8) | +0.03 | +0.06 | −0.26 | +0.23 | +0.71 | +0.55 |
| eyes open − closed (9) | −0.05 | −0.08 | −0.38 | +0.07 | +0.50 | +0.43 |
| eyes open − closed (11) | +0.36 | +0.18 | −0.23 | +0.07 | +0.40 | +0.35 |

**Opening the eyes adds a 25-45 Hz rise of the same size as a deliberate
clench**, so the positive control's eyes-open exponent shifts could largely
be facial muscle rather than arousal. What the prediction got wrong: muscle
was expected to show up as beta/gamma "peaks", as it seemed to in the
eyes-open halves. Instead the fit absorbed it into a flatter aperiodic line,
and the clenched half lost its beta peak. The clench reproduced the eyes-open
halves' 1-3 Hz misfit (+0.12 vs the model), but not their 8-20 Hz sag
(−0.03) or 40-45 Hz upturn (+0.02).

**A lead on the "misfit doesn't predict the shift" puzzle** (see "Spectrum
stored and plotted"): added 1-4 Hz power steepens the slope and added 25-45 Hz
power flattens it, so the net shift may depend on the balance. Session 11's
eyes-open half had both (+0.36 at 1-4 Hz) and its exponent didn't move (0.004).
Sessions 8 and 9 had only the high end and flattened by ~0.4. Session 13's
unexplained −0.175 came with −0.38 at 1-4 Hz, though part of that is the
3.5 Hz delta peak fitted in its baseline only. This is a pattern in four
sessions, not a test. A recording that adds low-frequency power on purpose
(slow eye movements, step 4 of the plan) could check it. (Checked: sessions
25 and 26 below support the direction; their sizes are confounded.)

**Qualified by the per-channel re-run (see "Per-channel re-run" below).**
Those per-channel numbers are taken BEFORE epoch rejection, unlike the stored
spectrum. Before rejection, the 25-45 Hz rise appears on all four channels
(+0.57 to +0.73 log₁₀ on TP9, AF7 and AF8), so the muscle reading holds. TP10
rose far more (+1.23 at 25-45 Hz, +1.92 at 1-2 Hz), asymmetric with TP9,
which fits the clench also disturbing TP10's contact. But rejection dropped
21 clenched windows against 5 relaxed, and probably removed much of that
disturbance. The stored 25-45 Hz rise (about +0.49) is below even the three
other channels' pre-rejection +0.65, and the stored 1-4 Hz change is −0.09,
against +1.68 on the pre-rejection four-channel average. TP10's extra 1-2 Hz
power would steepen the slope if it survived, not flatten it. The one way
TP10 could still add to the −0.54 is if rejection, which drops windows with
large slow swings, removed its 1-2 Hz excess but kept part of its 25-45 Hz
excess. That would flatten the slope. These numbers can't show whether that
happened, so there's no evidence either way on TP10's share.

## Loose headband (2026-09-10)

Session 24: eyes open in both halves; headband worn normally, then the right
side held off the forehead by hand for the second half (split at 126 s). The
headband was not adjusted between sessions 23 and 24.

| check | baseline | lifted half | singled out the lift? |
|-------|----------|-------------|-----------------------|
| SDK headbandOn flag | never off | never off | **no** |
| `epochs_rejected_bad_contact` | 0 | 0 | **no** |
| `epochs_partial_contact` | 0 | 0 | **no** |
| `interpolated_sample_fraction` | 0.0% (session-wide) | | **no** |
| low-fit flag (r² < 0.97) | on (0.953) | on (0.896) | **no**: on in both halves |
| r² relative to own baseline | | 0.057 lower | only by comparison |
| windows dropped (movement) | 4 | 19 | yes, as movement |

(`excluded_channel_indices` isn't a contact check; it's set only by the
eye-movement exclusion.)

**No contact check noticed an electrode that wasn't touching the skin.** The
raw-EEG counters count only NaN readings (`_interpolate_nan_gaps`'
`bad_mask = np.isnan(samples)`), and the SDK kept sending numbers from the
lifted electrode. So a 0.0% on the card only ever meant "the SDK reported no
gaps". That voids the "Contact was perfect" reading of sessions 8/9/11 above.
Session 19 is the same story: adjusting the headband mid-recording made
headbandOn fire for ~5 s while the counters read 0.00%. That 0.00% can't say
whether contact actually broke, so it neither confirms nor contradicts the
flag.

**Nothing singled it out on its own.** The low-fit flag was already on for
the eyes-open baseline, as it is for every eyes-open half on record (0.872,
0.917, 0.958, 0.953) and for the clench (0.938). So the flag says "don't
trust this fit", not "contact failed". What set the lifted half apart was
the signal compared with its own baseline: a lower r², 19 windows dropped as
"movement" against 4, and 1-4 Hz power ×4.8 (+0.68 log₁₀), 4-8 Hz ×1.9.
Because the side was held up by hand, the lift is confounded with hand
movement; a side propped still would separate them. The exponent went
0.448 → 0.565, but both halves are low-fit and +0.12 is under the ~0.2
floor, so that shift says nothing about the 1-4 Hz lead above.

**The eyes-open baseline was itself unusual.** It was the flattest eyes-open
half recorded (0.448, against 0.90-1.50 in sessions 8/9/11), with 2-5× more
35-45 Hz power than any of them. The first explanation offered was an
existing contact problem carried over from session 23's clench (the headband
wasn't re-seated), which is exactly what this section shows nothing would
detect. **The per-channel re-run partly supports it:** in this baseline, TP10
alone carried a narrow 42.8 Hz line (+0.82) and 2.6× TP9's 25-45 Hz power.
Bringing TP10 down to TP9's level would lower the four-channel 25-45 Hz
average only about ×1.5 (before rejection). The 2-5× excess was measured at
35-45 Hz in the stored spectrum, so the two numbers aren't strictly
comparable, but they suggest most of the excess is elsewhere. The lift then hit the two right-side electrodes, AF8 and TP10,
hardest: 25-45 Hz rose +1.19 and +1.35 log₁₀, against +0.39 and +0.44 on the
left pair. Every channel's 1-2 Hz rose too (+1.4 to +3.5 before epoch
rejection, not comparable with the stored +0.68 above), possibly from the
hand, which is confounded with the lift (see "Per-channel re-run" below).
Across the four eyes-open halves not
deliberately lifted (8, 9, 11 and 24's baseline), the exponent spans
0.45-1.50, which makes an eyes-open exponent close to meaningless on this
setup. The eyes-open halves of sessions 25-28 widen that to 0.17-1.91, though
each of those sessions had a bad ear electrode in at least one half.

**Changed on the card:** the readings line now says readings "arrived empty",
states that a lifted electrode isn't counted, and says it can only show up
indirectly (a low-fit warning, many windows dropped for movement), which open
eyes and muscle tension also cause. The window tally's "(bad contact)" is now
"(empty readings)", matching that line; "no signal" was avoided because the
band-power chart on the same screen already uses it for something else. The column
and API names (`epochs_rejected_bad_contact`, …) are unchanged; only the
wording a practitioner reads changed. Still open: actually detecting a bad
electrode from the recording, e.g. one channel's 1-4 Hz or broadband power
far out of line with the other three, or the SDK's per-electrode HSI
("horseshoe") indicator, which the recorder doesn't read yet and which would
need the same scepticism as the other SDK flags. The per-channel re-run below
found a concrete candidate for the first.

## Blinks and slow eye movements (2026-09-10)

Same wearer, eyes open in both halves, looking at a screen during the still
halves. The headband had still not been re-seated since session 23.

- **Session 25:** steady gaze, then frequent blinking (split at 121 s).
- **Session 26:** still gaze, then slow side-to-side eye sweeps (split at 120 s).

| session · half | exponent | r² | eye-movement rate | windows dropped (movement) |
|----------------|----------|-----|-------------------|----------------------------|
| 25 · steady | 0.785 | 0.940 | 0.5 / min | 3 |
| 25 · blinking | 1.914 | **0.988** | 10.6 / min | 0 |
| 26 · still | 0.224 | **0.416** | 0.0 / min | 11 |
| 26 · sweeping | 1.685 | 0.947 | 19.5 / min | 8 |

**The frontal eye-movement correction fired on real hardware for the first
time**, in both sessions: AF7/AF8 regressed out of TP9/TP10, then excluded
from the spectrum. Until now it had only ever fired on synthetic data. The
eye-movement rate tracked what was done (0 → 19.5 / min for the sweeps).

**A blink-heavy half can fit well enough to pass the low-fit flag.** The
blinking half fit at 0.988 and wasn't flagged, so it reads as a clean fit
with a steep exponent. (Whether blinking itself improves a fit isn't shown:
TP10 also went from bad to clean between those halves.) Only the eye-movement
rate and the channel-exclusion line on the card show it.

**The stored exponent shifts (+1.13 and +1.46) are NOT usable as sizes.**
Both still halves had a bad TP10 (next section), and every variant of the
re-run shares that confound. The best evidence for the direction is TP9, the
cleaner ear channel, alone: its 25-45 Hz minus 1-2 Hz power went −1.07 →
−3.01 (session 25) and −1.03 → −2.55 (session 26), so the spectrum got
steeper on a channel TP10 doesn't touch. In session 26 about half of that is
25-45 Hz power FALLING on all four channels (−0.66 to −0.83), not only 1-2 Hz
power being added. That supports the 1-4 Hz lead from the muscle control,
with that caveat. Redo both recordings with the headband re-seated to get
sizes.

## Per-channel re-run: `tools/eeg_variants.py` (2026-09-10)

Session 26's still half fit at r² 0.416, the worst on record. The correction
is decided and fitted session-wide and applied to both halves, so the first
guess was that weights fitted mostly on the sweeping half were being applied
to a clean one. `server/tools/eeg_variants.py` (read-only; run on the Mac,
where the raw blobs are) re-runs the analysis with the correction varied and
prints per-channel power.

| session · half | shipped | no regression (frontal still excluded) | all 4 channels, no correction | regression fitted per half |
|---|---|---|---|---|
| 25 · steady | 0.785 / 0.940 | 0.884 / 0.950 | 1.543 / 0.892 | 0.688 / 0.933 |
| 25 · blinking | 1.914 / 0.988 | 2.099 / 0.990 | 1.811 / 0.982 | 1.905 / 0.987 |
| 26 · still | 0.224 / **0.416** | 0.708 / **0.907** | 0.334 / 0.900 | 0.378 / **0.545** |
| 26 · sweeping | 1.685 / 0.947 | 1.972 / 0.950 | 2.005 / 0.957 | 1.668 / 0.945 |

(exponent / r². Sessions 23 and 24 didn't trigger the correction, so only
their per-channel rows are new.)

**The session-wide-weights guess was mostly wrong.** Fitting the weights
within each half left session 26's still half nearly as bad (0.545). The
correction wasn't injecting signal there either: it removed 8-17% of the
temporal channels' variance with session-wide weights, 14-20% with per-half
weights. Session 25 was the exception: session-wide weights did inject signal
into TP9's steady half (−24% "removed", against +11.5% with per-half weights).

**What actually hurts is running the regression on a half with no eye
movement.** Without the regression, session 26's still half fits at 0.907.
With nothing to remove, the regression strips whatever the forehead and ear
channels happen to share: real brain signal, but also the common reference
and shared noise. Which of those matters here isn't known. Part of the r²
gain is also different windows: without the regression the still half keeps
54 windows instead of 49. **Proposed fix:** apply the regression only where eye movement
was detected (per half, or per window), not session-wide. The cost is that
two halves may then be processed differently, which the card would need to
say. Not done yet.

**TP10 (right, behind the ear) looks intermittently bad from session 23's
clench on**, where it first stands out; the headband wasn't re-seated after
that. Per channel, before any regression or epoch rejection (`line` = log₁₀
ratio of the biggest bin in 42-43.5 Hz to the 38-45 Hz median; the other
three channels ranged 0.00 to +0.40 across all eight halves):

| session · half | TP10 line | TP10 − TP9, 1-2 Hz | TP10 − TP9, 25-45 Hz |
|---|---|---|---|
| 23 · relaxed | +0.19 | +0.21 | +0.29 |
| 23 · clench | +0.25 | **+1.92** | +0.88 |
| 24 · baseline | **+0.82** | +0.01 | +0.41 |
| 24 · lifted | **+1.37** | **+1.62** | +1.37 |
| 25 · steady | **+1.30** | **+2.00** | +0.87 |
| 25 · blinking | +0.13 | +0.02 | +0.07 |
| 26 · still | **+1.46** | +0.63 | +0.53 |
| 26 · sweeping | +0.09 | +0.10 | +0.54 |

- **The 42.8 Hz line in sessions 24-26 is TP10's alone.** No other channel
  exceeds +0.40 in any half. A narrow line on one electrode is consistent
  with a poorly seated electrode picking up interference, though 42.8 Hz
  isn't mains frequency and its source is unknown. Session 23's stored
  relaxed-half spectrum also shows a small 42.8 Hz peak, but no single
  channel carries a line there (TP9 +0.22, TP10 +0.19); that one is
  unexplained.
- **It comes and goes:** strong in 24 and in both still halves, gone in both
  movement halves. Why is unknown. The wearer was looking at a screen during
  the still halves, so a screen near a poorly seated electrode is one
  candidate. The line is also what sets the two still halves apart from the
  movement halves. **Weakened by sessions 27 and 28** (see "Re-seated redo"):
  there the line is strongest in the movement halves (TP9 +3.68 and +4.10),
  so "still halves only" doesn't hold, and the screen idea loses its main
  support.
- **One loud channel can dominate the spectrum.** `_average_psd` averages
  LINEAR power across usable channels, so the loudest channel counts most.
  Even when nothing is wrong, TP10 is already about half the 25-45 Hz power
  (56% in session 23's relaxed half). When it goes bad by ×10-100, the
  spectrum is effectively that one electrode: in session 25's steady half,
  with AF7/AF8 excluded, TP10 was ~99% of the 1-2 Hz power (before
  rejection). Nothing on the card flags it. Dominance alone didn't break the
  fits, though: session 26's still half fits at 0.907 without the regression
  despite TP10 being 74-81% of its power.

**This suggests a bad-electrode check to build.** The card has no way to show
a lifted or loose electrode (see "Loose headband"). Two per-channel signs
look promising:

1. A narrow ~42.8 Hz line on one channel. TP10 read +0.82 to +1.46 where it
   stood out, and every other channel stayed at or below +0.40.
2. One channel's 1-2 Hz power far above the others', e.g. TP10 at up to
   ~100× TP9's. (The 25-45 Hz version doesn't separate anything here:
   session 26's still and sweeping halves both read about +0.53.)

This is circular so far: the same numbers were used to decide TP10 was bad,
so any threshold needs checking on recordings where contact is known, e.g. a
re-seated headband and a deliberately lifted one. A robust average (median
across channels, or dropping an outlier channel) would also stop one channel
from taking over the spectrum.

**Checked and dropped: "the jawClench flag tracks high-frequency power."**
Pairing each second's flag with the SDK's own band powers over sessions
21-26 gives a pooled Spearman correlation of 0.64 with gamma. But that is
mostly differences between sessions: the flag was on more in sessions 24-26,
which also had more gamma. Ranked within each session, it correlates 0.20
with gamma, 0.17 with beta and 0.18 with alpha, so nothing specific to high
frequencies. Per session, gamma ran from −0.22 (session 24) to 0.84 (session
26). And through session 23's sustained clench, the SDK's gamma rose (mean
−0.55 → −0.04) while the flag read ~4%. What the flag responds to is still
unknown; the recorder already ignores it.

**Next, as planned after sessions 25-26** (superseded by "Re-seated redo"
below, because re-seating didn't remove the line): re-seat the headband and
redo sessions 25 and 26; build the per-channel bad-electrode check and a
robust channel average; restrict the eye-movement regression to where eye
movement was detected; a second wearer.

## Re-seated redo: sessions 27 and 28 (2026-09-10)

The headband was re-seated and sessions 25 and 26 repeated, with the same
wearer and room and nothing else changed. Both were 2-minute recordings, so
each half is 1 minute: 30-31 analysis windows instead of ~60, with 15-29 kept
after rejection (15 in 27's blinking half). Their exponent shifts couldn't be set
against the no-change floor, which was measured on 2-minute halves, even if
they were clean. They aren't clean.

- **Session 27:** steady gaze, then frequent blinking (split at 60 s).
- **Session 28:** gaze still and fixed on one point, not focused, then slow
  side-to-side sweeps (split at 63 s). The sweeps were slower than session
  26's, because the wearer's eyes were tired.

**After re-seating it was worse, not better.** That's one before/after pair,
so it doesn't show the re-seat caused it. Every half fits badly (r² 0.744 /
0.888 in 27, 0.688 / 0.894 in 28), though 28's still half isn't as bad as
26's (0.416). The 42.8 Hz line is now one of a set of narrow lines at whole
multiples of ~8.6 Hz. In the stored spectra, here is the log₁₀ height of the
biggest bin within ±0.5 Hz of each frequency, above the median of the bins
0.75-2.5 Hz away. In the first column that biggest bin is 8.75 Hz in all four
halves; specparam puts the peak at 8.50-8.64 Hz.

| session · half | r² | ~8.6 Hz | 17 Hz | 25.8 Hz | 34.3 Hz | 43 Hz |
|---|---|---|---|---|---|---|
| 27 · steady | 0.744 | +0.66 | +1.72 | +1.52 | +1.33 | +3.09 |
| 27 · blinking | 0.888 | +0.09 | +1.59 | +1.81 | +1.17 | +3.10 |
| 28 · still | 0.688 | +0.71 | +1.61 | +1.30 | +0.98 | +2.71 |
| 28 · sweeping | 0.894 | +0.63 | +2.13 | +2.05 | +1.53 | +3.40 |

On the same measure, every half of sessions 8, 9, 11, 13, 21, 22 and 23 is at
or below +0.30 at all five frequencies, and most are at or below +0.11. **So
the positive control and the no-change floor were recorded without it.** It
first shows in session 24: +0.48 at 42.7 Hz before the lift, and after it
+2.53 at 42.7 Hz plus lines at 25.5 Hz (+1.28) and 34.7 Hz (+0.67). **So the
lift may have produced part of the same set**, which would fit the idea that
contact is involved. But the lift is confounded with the hand holding it up,
and the frequencies don't line up cleanly. They're near, not exactly at,
27/28's (25.2-25.5 and 42.5-42.7 sit lower, 34.5-34.7 sits higher, and the
spacing is uneven), and 0.25 Hz bins can't say whether it's the same set. It
then shows in the steady and still halves
of 25 and 26 (+1.05 to +1.36 at 42.7 Hz, plus +0.41 at 25.7 Hz in 25's steady
half), then in all four halves here.

**It looks electrical rather than brain or muscle.** Harmonics alone wouldn't
show that, because non-sinusoidal brain rhythms have harmonics too. What
points that way:

- the lines are as narrow as the fit allows;
- the fifth harmonic, near 43 Hz, is the strongest (+2.7 to +3.4);
- they sit on the ear electrodes only (below);
- they're present whatever the eyes were doing, though their heights vary,
  and TP9's 25-45 Hz level stays flat across all four halves.

~8.57 Hz is 1/30 of the sample rate, a repeat every ~117 ms, which would point
to something locked to the headband's own sampling. But 0.25 Hz bins can't
tell 8.57 from nearby values, so that's unconfirmed. An FFT of a whole raw
recording (1/60 Hz resolution) could test it.

**It's now on both ear channels, and TP9 is the worse one.** Per channel from
`eeg_variants.py`, before regression or rejection (`line` as defined in
"Per-channel re-run" above):

| session · half | TP9 | AF7 | AF8 | TP10 |
|---|---|---|---|---|
| 27 · steady | **+2.16** | +0.49 | +0.07 | **+1.72** |
| 27 · blinking | **+3.68** | +0.08 | +0.02 | **+2.94** |
| 28 · still | **+3.15** | +0.03 | +0.15 | **+2.21** |
| 28 · sweeping | **+4.10** | +0.12 | +0.08 | **+3.00** |

In sessions 23-26 TP9 never went above +0.40. The forehead pair carries no
line (at most +0.49, AF7 in 27's steady half, against +1.72 and up on the
ears). Both ear channels carry 4-430× the forehead channels' 25-45 Hz power. In three
of the four halves (all but 27's steady half), TP9's 25-45 Hz power is above
its own 1-2 Hz power, e.g. −10.74 vs −12.46 log₁₀ in 28's sweeping half. TP10
does the same in 27's blinking half and 28's sweeping half. All eight forehead
readings run the normal way, 1-2 Hz above 25-45 Hz by 0.4-3.0 log₁₀ units.
27's steady half is the exception because it had 1-2 Hz power raised on all
four channels (−8.2 to −8.9), which hides the inversion even though its line
is strong. TP9's 25-45 Hz level barely moves across all four halves (−10.69
to −10.75), whatever the eyes were doing, which fits a steady interference
floor.

The simplest reading is that re-seating left both ear electrodes in poor
contact, and a poorly contacting electrode picks up interference that the
others don't. That's plausible, not established, and where the interference
comes from is unknown. Nothing on the card pointed at the ear electrodes. The
low-fit flag tripped on all four halves but can't say why.

**The lowest line produced a false alpha.** ~8.6 Hz is inside the alpha band,
and the analysis reported it as an alpha peak (8.50-8.64 Hz) in three
eyes-open halves: 27's steady half and both halves of 28. In session 28 it
survives six of the eight variant runs. Peak width doesn't give it away:
real alpha peaks in sessions 11, 21, 22 and 23 sit at the same minimum width
(0.5). What gives it away is the frequency, since real alpha here has been
10.6-11.2 Hz, and the harmonics that come with it. The positive control is
unaffected: its alpha peaks were at 10.6-11.1 Hz, and there are no lines in
those sessions. But on the card a false alpha looks exactly like a real one.
This is the strongest argument yet for the bad-electrode check. **Alpha isn't
the only false peak.** Every half of 27 and 28 also reports "beta" at ~25.8
Hz and "gamma" at ~42.9 Hz, both at minimum width. Both halves of 24 and the
steady and still halves of 25 and 26 report "gamma" at 42.7-42.9 Hz. Those
are the same lines.

The eye-movement correction fired in 28 but not in 27. It fires when either
half has at least 30% of its windows flagged
(`EOG_CONTAMINATION_FLAGGED_FRACTION`). 27's blinking half had 9 of 31
(0.29), just under, where session 25's blinking half had 22 of 62 (0.35). So
27's spectrum kept the forehead channels. In 28, dropping the regression
raised the still half's r² from 0.688 to 0.807. That's the same direction as
session 26 (0.416 → 0.907), but with every half contaminated it adds little.
Also in 28, the session-wide regression removed 4.8% of TP9's variance in the
still half, and −0.8% in the sweeping half (29 eye movements a minute), which
means it added a little there. With per-half weights it was 9.3% and 0.3%.
**It removed least from the half with the most eye movement.** One possible
reason is that the ear channels are so dominated by interference that the
forehead signal explains little of them. This bears on restricting the
regression (item 3 below). The stored exponent shifts (27: 0.171 → 1.092; 28:
0.298 → 0.676) aren't usable.

**What this adds to the bad-electrode check** proposed in "Per-channel
re-run":

1. **The line, now as a set.** Bad ear channels read +1.72 to +4.10, and the
   forehead channels at most +0.49 in every half of sessions 23-28. Testing
   several harmonics together should be more specific than the single 42.8
   Hz bin.
2. **An upside-down spectrum:** a channel whose 25-45 Hz power is at or above
   its own 1-2 Hz power. It's less sensitive than the line: it missed 27's
   steady half, where low-frequency power was raised on every channel.
3. **What to do on a hit:** name the electrode on the card, keep it out of the
   average, and don't label a peak that sits on one of the lines as alpha (or
   any band).

**The line is not a general lifted-electrode detector.** In session 24 the
right side was held off the forehead, so AF8 was the electrode known to be
off the skin. Yet AF8 never carried a line: nothing but TP10 went above
+0.40 in 23-26, and the "+0.49 at most" forehead figure above includes that
lifted AF8. The only sign on AF8 was a 25-45 Hz rise against its own
baseline (+1.19 log₁₀, with TP10 +1.35, so it marks the right side rather
than AF8 alone), which a single-half check can't see. So the line appears to
mark interference on the ear electrodes. A lifted forehead electrode needs a
different sign, such as one channel's power far above the others', and that
hasn't been tested yet.

It's also still circular: these channels were called bad on these same
signs. Session 24 isn't a clean anchor either: TP10 already carried a line
in its baseline (+0.82), and the lift is confounded with the hand holding it
up, so test each sign against it separately rather than labelling 24 simply
"bad". For known-good contact, sessions 8, 9, 11, 21 and 22 show no lines in
their stored spectra. Their per-channel numbers haven't been taken yet, so
run the tool on them.

**Next, in order:**

1. **Get clean ear contact and confirm it before recording anything else.**
   Re-seat with the ear sensors on skin, record about two minutes with eyes
   closed, and check with `eeg_variants.py` three things:
   - every channel's line is at or below about +0.5 (the forehead channels
     have read up to +0.49; the bad ear channels started at +0.82 in 24);
   - every channel's 1-2 Hz power is above its 25-45 Hz power;
   - there's an alpha peak near 10-11 Hz.

   This is a working rule, not a validated threshold.
2. **Build the bad-electrode check,** with the robust channel average and
   line-aware peak labels. Validate it against sessions 8, 9, 11, 21 and 22
   for good contact. Check the ear-interference signs against 25-28, and
   look for a lifted-forehead sign in session 24's AF8, sign by sign.
3. **Restrict the eye-movement regression** to where eye movement was
   detected.
4. **Redo sessions 25 and 26 with 2-minute halves,** once contact checks
   clean.
5. **A second wearer.**

(Status, same day: 1, 2 and 4 are done, and 3 was overtaken by switching
the regression off entirely. See the next two sections.)

## Clean contact restored: sessions 29-31 (2026-09-10)

**Session 29** was the contact check. The headband was re-seated with the ear
sensors on bare skin, then 2 minutes were recorded with eyes gently closed.
No baseline was marked, so it split at the midpoint.

- **Every electrode is clean:** line at most +0.39 (AF8, first half), and
  every channel has more power at 1-2 Hz than at 25-45 Hz.
- **Real alpha is back** at 10.92 and 11.08 Hz, and both halves fit above
  the warning line (r² 0.979, 0.984).
- **The exponent still moved 1.53 → 1.28** with nothing changing. That's more
  than the no-change floor, but these are 1-minute halves, and the first lost
  8 windows to movement, probably settling in. It also had more large slow
  deflections (10.5 against 3.8 per minute; see below).

**So the contact problem was fixable by careful seating.** The raw
recordings (`server/data/eeg_raw/`) were then copied to the Windows PC,
where the analysis code now runs. Since then,
every per-channel check here has run locally, with no Mac round trip.

**Sessions 30 and 31** redid 25 and 26 with clean contact and 2-minute halves
(split at 123 s):

| session · half | exponent | r² | slow eye movements / min | windows dropped |
|---|---|---|---|---|
| 30 · steady gaze | 1.599 | 0.901 | 0.0 | 0 |
| 30 · frequent blinking | 2.339 | **0.990** | 0.0 | 0 |
| 31 · still gaze | 1.079 | **0.988** | 0.0 | 4 |
| 31 · slow side-to-side sweeps | 1.658 | **0.995** | 7.0 | 1 |

**Eye activity moved the exponent +0.74 (blinks) and +0.58 (sweeps), 8-10×
the ~0.07 no-change floor, and every quality check passed.** Specifically:

- **The fits are excellent.** 31 is the first still, eyes-open half ever to
  pass 0.97.
- **The old eye correction didn't fire** in either session.
- **Blinks don't count as slow eye movements** (0.0 in 30's blinking half),
  which is right for an SEM detector but meant nothing measured blinks at all.
- **No windows were dropped for the blinks.** The movement check is relative
  to the half's own median window, and when nearly every window has a blink,
  a blink window is the median.

The signal behind this is low-frequency power: in 31's sweep half the
forehead channels' 1-2 Hz power rose +1.03 and +0.83 log₁₀ and the ears'
barely moved. In 30's blinking half, the ears' rose +0.70 and +0.73 and
the forehead's only +0.31 and +0.36. **On this headband, blinks showed mainly
on the ear channels.** A plausible reason is the reference electrode in the
middle of the forehead, which the forehead channels share the blink with,
but that's not established.

**Forcing the old correction on** (tools/eeg_variants.py, with the trigger
set to zero):

| session | shipped shift | correction forced on | frontal excluded, no regression | per-half regression |
|---|---|---|---|---|
| 30 (blinks) | +0.74 | +1.09 | +0.94 | −0.25 |
| 31 (sweeps) | +0.58 | +0.31 | +0.55 | +0.29 |
| 21 (no change) | +0.07 | +0.01 | +0.05 | +0.06 |
| 22 (no change) | −0.01 | −0.09 | −0.04 | −0.08 |

- **It halved the sweep shift, but made the blink shift worse,** or reversed
  it with per-half weights.
- **Forced on, it dropped 30's steady half from 1.60 to 0.53:** the same
  damage to a still half as session 26.
- **Excluding the forehead pair alone didn't help:** it barely changed the
  sweep shift and made the blink shift worse (+0.94), since the blinks were
  on the ear channels.
- **On the no-change sessions it moved the shift by up to 0.08,** so it adds
  noise of its own.

**So the correction doesn't reliably remove eye activity, and sometimes adds
error.** That answers the question this plan has carried since the start
(step 04 of *Ordering the EEG Fixes*: is it earning its complexity, versus
simply flagging?): it isn't.

## Correction switched off; electrode check and eye-activity flag added (2026-09-10)

Decided with the user the same day: **flag, don't correct.**
`eeg.analysis.EOG_CORRECTION_ENABLED = False`. The code stays only so
`tools/eeg_variants.py` can reproduce the old output (its `old-*` rows).
Nothing is regressed out or excluded for eye movement any more.

**Electrode check** (`_check_electrodes`), measured per channel per half on
the filtered signal:

1. **Interference lines:** the tallest of the lines at 2-5 × (sample
   rate / 30), as log₁₀ above the neighbouring bins. At 0.70 or more, the
   electrode is excluded from both halves.
   - The channels known or strongly suspected bad (27/28's ears, 24-26's
     TP10) scored 0.79-4.05.
   - The clean-contact sessions (9, 13, 21, 22, 29, 30, 31) scored at most
     0.33.
   - 8's and 11's eyes-open halves scored 0.61-0.62 on one ear channel.
   - The line at 1 × is left out because it sits in the alpha band.
2. **Upside-down spectrum:** 25-45 Hz power at or above 1-2 Hz power
   excludes the electrode. Every channel not already caught by sign 1 ran the
   normal way by 0.34-3.70, so today this adds nothing to sign 1. It's kept
   in case interference looks different on another headband.
3. **Much noisier than its partner:** 25-45 Hz power 10× (1.0 log₁₀) above
   the partner on the other side. This only **warns**.
   - Clean sessions stayed within 0.44.
   - It's the only sign that catches session 24's lifted AF8 (1.18), but it
     also fires on AF7 in 25's blinking half (1.81), which may be one-sided
     muscle or eye activity rather than contact.

A fourth sign, **no signal** (a flat or constant channel, median power below
10⁻¹⁸ V²/Hz), also excludes. The pre-push review found it with synthetic
flat channels: without it, a dead electrode could be the one channel
everything else got excluded in favour of. It hasn't been seen in a real
recording.

**If every electrode is flagged,** none is excluded, and the analysis says
the peaks and both eye-activity rates are **not evaluable**, rather than
"none detected". In the review's synthetic test the interference lines
used up the model's peak budget and a real 10.8 Hz alpha disappeared. They
also inflated the deflection count's noise estimate enough to read a false
"no eye activity".

What it does to the recordings (the pipeline run over all 21 stored
recordings with their real baseline markers, confirmed by re-analyzing them
on the Mac. Sessions 14 and 15 can't be analyzed: 14 is too short, and 15's
baseline was marked 7 s before the end, leaving too little second half.
That leaves 19):

| session | excluded | also warned |
|---|---|---|
| 8, 9, 11, 13, 21, 22, 23, 29, 30, 31 | nothing | nothing |
| 16, 17 (morning, protocol unknown) | TP9 | — |
| 19 (headband adjusted mid-recording) | AF7, AF8 | — |
| 20 (morning, protocol unknown) | TP9, AF8, TP10 | — |
| 24 | TP10 | AF8 in the lifted half |
| 25, 26 | TP10 | AF7 in 25's blinking half |
| 27, 28 | TP9, TP10 | — |

- **The false alpha is gone:** 27 and 28 report no alpha in any half.
- **Excluding electrodes can't rescue a recording where half the headband was
  bad.** With both ears out, 27's and 28's still halves come out with a
  *negative* exponent (−0.25, −0.27, r² 0.85, both low-fit). Their forehead
  spectra are U-shaped: power rises again above ~12 Hz, the model explains
  the low end as a large delta peak, and the background line tilts slightly
  upward.
- **When an electrode is excluded, the SEM rate adjusts too.** A forehead
  electrode excluded, or both ear electrodes, now means "not scored". With
  both ears gone, the SEM scorer can't tell an eye movement from a
  whole-head artifact: 27's steady half read 7.0 per minute that way. One
  ear excluded just drops it from the check.

**Large slow deflections** (`_deflection_times`): often blinks, eye
movements or head movement, counted per minute on an ear-pair and a
forehead-pair average (excluded electrodes left out). The count is peaks
above 5 robust SDs, 50-600 ms wide, with hits within 0.8 s of the last
counted one treated as the same event, per minute of time with readings.
It's deliberately not called a blink count, because a hand on the headband
produces the same thing (24's lifted half: 30.9 per minute).

The 0.8 s merge was set after the review. A draft merged against the
previous *raw* hit, which collapsed whole chains of hits: 25's blinking half
read 7.7 per minute that way. Fixed, one blink still gave several hits
0.2-0.6 s apart, since the filter turns it into an up-then-down swing seen on
both pairs. Session 30's blinking half had 141 raw hits per minute, 95 with a
0.4 s merge, and 57 at 0.8 s, while quiet halves barely changed (22: 6.3 and
5.3).

**The eye-activity flag** (`eeg.interpretation.eye_activity_differs`, one rule for the
card, the trend tab and the AI summary) trips when the halves differ by 10
or more deflections per minute, or 5 or more slow eye movements per minute:

| session | protocol | deflections / min | SEM / min | flagged |
|---|---|---|---|---|
| 21 | no change | 2.4 → 6.6 | 0 → 0 | no |
| 22 | no change | 5.2 → 5.3 | 0 → 0 | no |
| 29 | no change | 10.5 → 3.8 | 0 → 0 | no |
| 13 | no change | 14.6 → 5.6 (9.0 apart) | 0.5 → 0 | no, narrowly |
| 30 | blinks | 3.9 → 57.3 | 0 → 0 | yes |
| 31 | sweeps | 1.5 → 8.8 | **0 → 7.0** | yes |
| 25 | blinks (TP10 bad) | 3.5 → 65.1 | 1.0 → 10.2 | yes |
| 26 | sweeps (TP10 bad) | 6.0 → 33.7 | 0 → 13.3 | yes |
| 24 | lift | 8.1 → 30.9 | 0.5 → 4.3 | yes |
| 27 | blinks (ears bad) | 8.0 → 36.0 | not scored | yes |
| 28 | sweeps (ears bad) | 4.8 → 18.6 | not scored | yes |
| 8 | eyes open → closed | 21.0 → 9.8 | 2.5 → 0.9 | yes |
| 11 | eyes open → closed | 27.3 → 8.4 | 3.4 → 0.5 | yes |
| 9 | eyes closed → open | 8.3 → 11.5 | 0 → 1.0 | **no** |
| 23 | jaw clench | 3.3 → 9.5 | 0 → 0 | no |

- **The deflection line sits in a gap, but near its low edge.** The
  no-change sessions differ by at most 9.0 (session 13, eyes closed
  throughout, which also had the largest no-change exponent shift, −0.17).
  The blink, sweep and lift sessions differ by 13.8 or more, except 31's
  slow sweeps (7.3), which only the SEM rule catches.
- **9 isn't caught at all:** eye state itself isn't reliably detected, only
  eye-type movement.
- **The clench (23) isn't caught either,** correctly, since it isn't eye
  activity; its low fit flags it.
- **These are working thresholds from one wearer.** The line score is also
  noisier on short halves: session 17's 0.77 comes from a 20 s half.

**What the card, trend tab and summary now say:**

- **Session card:**
  - A warning block names excluded electrodes, why, and which electrodes
    the numbers come from. Partner warnings get their own line, which says
    the electrode wasn't left out and why.
  - A second block appears when the halves recorded different amounts of
    large slow deflections or slow eye movements. It's worded as what the
    recording picked up, not what the patient did, and says the exponent
    difference can't be separated from it.
  - With every electrode flagged, peaks read "not evaluable", never "none
    detected".
  - Each half shows its deflection rate beside the SEM rate.
  - An analysis from before today says its frontal exclusion came from the
    switched-off correction and asks for a re-analysis.
- **Trend tab:** shows each session's excluded electrodes, the
  eye-activity warning, an every-electrode-flagged warning, and the per-half
  deflection rate.
- **AI summary:**
  - It gets the electrode line, and a note whenever the eye-activity rule
    trips.
  - Its rules no longer treat the exponent or the SEM rate as evidence of
    the patient's arousal or drowsiness. Both are reported as changes in the
    recorded signal, with arousal as one possible reason among others (the
    review pointed out the old wording contradicted today's evidence:
    deliberate sweeps gave 7-13 SEM/min with no drowsiness).
  - The old "SEM rate rose AND the exponent steepened by 0.3" rule is gone.

**Not done:** the robust channel average (median, or dropping an outlier).
Exclusion now handles the one-bad-channel case the average was meant for,
but a merely loud channel (not flagged) still counts most.

**Migration** (no tooling; `tools/schema_diff.py` generates these). **Run
them BEFORE starting the new server code, with the server stopped and the DB
backed up to `server/backups/` first.** Until they're applied, every
`tcm_sessions` query fails (`no such column:
tcm_sessions.eeg_electrode_findings`, confirmed by the review). That's every
session endpoint, including creating a new session, and a recording finished
in that window could be lost, since the app keeps it only in memory.

    ALTER TABLE tcm_sessions ADD COLUMN eeg_electrode_findings JSON;
    ALTER TABLE session_eeg_metrics ADD COLUMN deflection_rate_per_min FLOAT;

Every analyzed session needs a re-analysis to pick this up; until then its
card says it predates the check. **Re-analyzing changes the numbers** of:
- every session the check excludes an electrode from (16, 17, 19, 20,
  24-28), e.g. 24's baseline exponent 0.448 → 0.709 and 27's 0.171 → −0.249;
- the sessions where the old correction had fired (25, 26, 28).

**Next, in order:**

1. **Re-analyze the Mac's sessions** and check the card on the phone.
   (Re-analysis done the same day: migrated, and all 19 analyzable sessions
   match the local run exactly, once 13 and 15 were run locally with their
   real baseline markers. The phone check is still open.)
2. **A second wearer**, eyes-open/closed and no-change, to test both the
   alpha result and these thresholds on a different head.
3. **An eyes-closed drowsiness recording** (the real use case): does the SEM
   rate or deflection count move without deliberate eye movement?
4. **Crash-safe recording** (the in-memory buffer).

## Open questions to decide before starting

- Binary multipart now, or wait and do chunked upload once (avoiding building
  the transport twice)? Leaning binary-now given phase 5 is optional.
- **(Decided, 2026-08-17 — local disk, not object storage yet)** Discard raw
  EEG after processing, or keep it for reprocessing as the correction method
  improves? **Keep it.** `POST /sessions/{id}/eeg/analyze`
  (`routers/sessions.py`) no longer deletes `TcmSession.eeg_raw_path` or the
  file on a successful analysis — motivated directly by this doc's own
  history: sessions 30-32 and 33-35's before/after comparisons only worked
  because the raw blob was manually copied to scratch before each analyze
  call, precisely so it could be re-run once the pipeline changed (exclude→
  regress). Discard-on-analyze would have made every one of those
  verifications impossible to redo. The blob is still discarded on `DELETE
  /sessions/{id}` (the session itself is gone at that point) and is still
  local disk, not object storage — `EEG_RAW_DIR` now grows unboundedly
  across all analyzed sessions instead of being self-cleaning, which is fine
  at pilot scale (~5-15MB/session) but will need revisiting (prune old blobs,
  or move to Cloud Storage per the GCP/hosting section) once volume grows.
  Calling `/eeg/analyze` again for the same session now re-runs the pipeline
  and replaces its `SessionEegMetric`/`SessionOscillationPeak` rows rather
  than 404ing — not yet exposed as a "re-analyze" affordance in Android
  (the button still hides once a session has been analyzed once), just
  reachable via the API directly for now. **Verified 2026-08-17** via a
  throwaway patient/session against the real dev server: upload → analyze →
  re-analyze (would have 404'd under the old behavior, succeeded and
  recomputed) → `GET eeg-metrics` reread → soft-delete (confirmed the raw
  file IS removed on delete, unlike on analyze) — all passed end-to-end.
- Per-session-half granularity (matches today's UI framing) or per-epoch
  (finer-grained, more work to display sensibly)?
- **(Decided, 2026-08-15)** Does the corrected view replace the relative-band
  chart, or run alongside it? **Runs alongside — the chart isn't going away,
  but it stopped being presented as if the numbers themselves mean
  something.** Full replacement isn't safe yet: the corrected analysis is
  on-demand and won't exist for every session (older sessions, or ones where
  raw EEG wasn't captured/analyzed), so the relative chart/stats are still
  the only per-second view available for those, and they're still useful for
  spotting a session's shape, gaps, and artifacts even though the values
  aren't a validity measure. What changed: both the chart and the stats
  table on Android's `SessionDetailActivity` are now labeled "(uncorrected)"
  instead of "(relative)"/unlabeled, each with a one-line caveat identical in
  spirit to `ai_summary.py`'s Form A hedging — "dominated by the non-rhythmic
  background... not a validated measure," pointing at the EEG analysis card
  below for the corrected numbers when one exists. `ai_summary.py` itself
  (see "UI implications" above) no longer has this problem at all: it picks
  one framing or the other per session, never presents both.
