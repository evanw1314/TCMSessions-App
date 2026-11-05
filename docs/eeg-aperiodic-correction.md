# EEG aperiodic correction

How the server turns a session's raw EEG into numbers, what those numbers can
and can't support, and the decisions that look wrong but aren't. Read this
before touching `server/app/eeg/`, the analysis card, or the AI summary's EEG
rules.

The evidence behind every threshold here (the sessions cited by number in
code comments) is in the archived
[lab notebook](archive/eeg-lab-notebook.md), with the dated record of how the
pipeline got to its current shape. Session numbers restarted once, so the
notebook's dates are what tell an August session from a September one.

## The problem

A raw EEG spectrum is two signals stacked together: a non-rhythmic **aperiodic
background** that falls off with frequency (delta always looks "loudest",
oscillation or not), and **real oscillations** sitting on top of it. Relative
band power — what the Muse SDK reports and the app records every second —
sums both and can't tell them apart.

The background's steepness (the **aperiodic exponent**) moves with arousal:
it changes from alert wakefulness through drowsiness toward sleep. A resting
treatment (dim room, 20+ minutes lying still) is exactly what shifts it, and
when it shifts it produces `delta↑ theta↑ alpha↑, beta↓ gamma↓` — the
"relaxation signature" a wellness readout wants to show — with no
oscillatory change at all. Uncorrected band power can't distinguish "the
treatment changed something" from "the patient got sleepy" from "an electrode
drifted".

The pipeline separates the two with specparam (formerly FOOOF), and then has
to be honest about how much the corrected numbers still move for reasons that
have nothing to do with treatment.

## How it works

### Capture and upload (Android)

- `SessionRecorder` subscribes to the SDK's raw EEG packets (~256 Hz, four
  channels: TP9, AF7, AF8, TP10) alongside the once-a-second band powers, and
  `SessionJournal` writes both to disk as they arrive, so a crash doesn't lose
  the recording.
- At End session the app creates the session (`POST /sessions`), then uploads
  the raw EEG (`POST /sessions/{id}/eeg`) in the binary format
  `EegBinaryWriter.kt` packs and `server/app/eeg/binary.py` parses. A shared
  fixture in `testdata/` pins both sides. The raw upload is best-effort: a
  failure never marks the saved session as failed, and usually leaves the
  journal for the patient list to retry (not when the server refused the
  upload, since a retry couldn't help).
- The optional **Baseline done** tap marks the end of a short eyes-closed,
  no-treatment period at the start of the same recording, so a session is
  compared against its own resting state rather than population norms.
  **Treatment done** marks the end of the treatment itself, before the
  needles come out.

### Storage

`server/app/eeg/storage.py` keeps the recording on local disk
(`server/data/eeg_raw/`) or, when `EEG_RAW_BUCKET` is set, in Cloud Storage.
It is **kept after analysis**, so any session can be re-analyzed as the
pipeline improves; it is removed when the session is deleted.

### Analysis (`POST /sessions/{id}/eeg/analyze`)

On demand only — the practitioner taps Analyze (or Re-analyze), never
automatic. Synchronous: a few seconds of CPU at this scale. Each run replaces
the session's stored results. The code is in `server/app/eeg/`:

1. **Gaps.** NaN readings (the SDK's "no value") are interpolated per channel
   before filtering — an FIR filter would otherwise spread one NaN across its
   whole kernel — and remembered in a per-channel mask.
2. **Filtering.** Notch at mains (`EEG_MAINS_HZ`, default 60), bandpass
   1-45 Hz.
3. **Split.** At the baseline mark when there is one inside the recording
   (halves labelled baseline/treatment), otherwise at the midpoint
   (first/second). Windows that straddle a real baseline mark are dropped.
4. **Electrode check** (`artifacts._check_electrodes`). A poorly seated
   electrode on this headband picks up narrow interference lines at multiples
   of ~1/30 of the sample rate, and the channel average lets one such channel
   dominate (it produced a false ~8.6 Hz "alpha" peak). Channels with those
   lines, an upside-down spectrum, or no signal at all are excluded from
   **both** halves. A channel much noisier than its left/right partner is
   only warned about. If every channel is flagged, nothing is excluded and
   peaks and eye-activity rates are reported as not evaluable.
5. **Eye activity**, per half, for flagging (not correcting):
   - slow eye movements (`_eog_candidate_windows`): 0.3-1.5 Hz, 2 s windows,
     counted when the forehead deflection is localized (1.5× the ear
     channels) and conjugate (|r| ≥ 0.4), above a session-relative amplitude
     threshold;
   - large slow deflections (`_deflection_times`): blinks, eye or head
     movement, 0.5-10 Hz, per minute of usable time.
6. **Epochs.** 4 s, 50% overlap. A channel more than 5% gaps in a window is
   left out of that window; a window is rejected if no channel survives
   (empty readings) or its amplitude is a robust outlier (movement).
7. **Fit.** Welch PSD averaged over usable channels and epochs, one specparam
   model per half (`peak_width_limits=(1, 8)`, up to 6 peaks): aperiodic
   offset and exponent, r², and the strongest real peak per band, if any.
   A band with no peak is a finding ("no real rhythm"), not a gap, unless
   an electrode was excluded and the rhythm lived mostly on it; that case
   reads the same and isn't labeled.

Stored per half (`SessionEegMetric`, `SessionOscillationPeak`): the fit,
the peaks, rejection counts by cause, both eye-activity rates, and the
spectrum arrays the card plots. Stored per session (`TcmSession.eeg_*`): the
observed sample rate, the fraction of readings that arrived empty, excluded
channels and the electrode-check findings. `GET /sessions/{id}/eeg-metrics`
rereads them; `GET /patients/{id}/eeg-trend` lists them across sessions.

## What the numbers can support

All from one wearer and one headband. These are working figures, not
validated ones.

| measure | evidence | how to read it |
|---|---|---|
| alpha peak present/absent | appeared in the eyes-closed half in 3 of 3 runs, and followed eye state when the order was flipped | the best-behaved measurement: specparam finds a real rhythm on this headband |
| alpha peak strength | moved 0.13-0.32 between halves with nothing changing | not usable as evidence |
| exponent, between halves | nothing changing: ≤ 0.07 with good fits over 2-minute halves, 0.18 at a borderline fit, 0.25 over 1-minute halves | a shift under ~0.2 means nothing; with short halves or near the low-fit line, not even that |
| exponent and eye state | moved 2 of 3 times in the eyes-open/closed control | weak; eyes-open halves also carry muscle (below) |
| exponent and artifacts | jaw clench −0.54; blinking through a half +0.74; slow side-to-side eye movements +0.58 (both halves fitting well) | artifacts move it more than anything a treatment is expected to; hence the flags |
| exponent, across sessions | baselines of one wearer, same day, span 1.20-1.37 | compare a session's two halves, not one session with another |
| contact counters | a lifted electrode kept sending numbers: 0.0% empty readings | "0% empty" means no gaps were reported, not that contact was good |

Nothing in the pipeline can tell a treatment effect from drowsiness. The
exponent is reported as a change in the recorded signal, with arousal as one
possible reason among others.

## Standing decisions

Each of these looks like an omission or a bug and isn't. Don't change one
without new evidence.

- **The frontal eye-movement correction is OFF**
  (`eeg.analysis.EOG_CORRECTION_ENABLED = False`). It regressed the forehead
  channels out of the ear channels. On hardware it damaged a half with no eye
  movement (r² 0.907 → 0.416), made a blink shift worse (+0.74 → +1.09), and
  couldn't remove blinks that showed mainly on the ear channels. Eye activity
  is **flagged** instead. The code remains so `tools/eeg_variants.py` can
  reproduce old results.
- **The eye-activity flag** (`eeg.interpretation.eye_activity_differs`) trips
  when the halves differ by ≥ 10 deflections/min or ≥ 5 slow eye
  movements/min. The no-change sessions differed by at most 9.0 deflections;
  the blink, sweep and lift sessions by 13.8 or more, except slow sweeps,
  which only the SEM rule catches. It doesn't detect eye state itself.
- **The low-fit line is r² 0.97** (`eeg.interpretation.LOW_FIT_R_SQUARED`),
  stricter than the conventional 0.90. In the eyes-open/closed control, the
  three halves that lost the most windows fit at 0.872-0.958 and the three
  cleanest at 0.992-0.993; 0.97 sits in that gap. One definition, shipped in
  every response, so the card, the trend tab and the AI summary flag the same
  halves.
- **The SDK's jaw-clench and blink flags are not used.** On this headband the
  clench detector reported a clench in every packet for minutes with the
  wearer still (one session lost every second to it). Only "headband off"
  drops a band-power second. The raw-EEG analysis never used any of them.
- **Electrode exclusion applies to both halves.** A before/after comparison
  from two different electrode sets would be its own confound.
- **Slow-eye-movement and deflection rates are session-relative.** Their
  thresholds come from the session's own distribution, so a rate is only
  meaningful against the same session's other half.
- **Per half, not per epoch.** Matches the before/after framing everything
  else uses.
- **The uncorrected band-power chart stays**, labelled "(uncorrected)" with a
  caveat. Not every session has raw EEG or an analysis, and the chart still
  shows a session's shape and gaps.

## How results are shown

- **Session card** (practitioner only): plain-language wording shared across
  screens (`EegWording.kt` / `EegWording.swift`) — "background steepness" for
  the exponent, sensors by position — with the precise terms on a Details
  line. Warnings for excluded or noisy electrodes, differing eye activity and
  low fit. Every sentence describes the recording, never the patient.
- **Before/after pies** (practitioner and patient): one minute of
  uncorrected relative band share before and after (`BandShares.kt`). A slice
  moves up to ~10 points with nothing changing, and blinking alone moves
  delta 17-42 points, which the card says.
- **AI summary** (`server/app/ai_summary.py`): gets the corrected numbers
  instead of band power once a session is analyzed (never both), the same
  flags the card shows, and rules forbidding any claim that a change was
  caused by treatment or reflects the patient's arousal.

## Known limits and next steps

- **One wearer, one headband.** A second wearer repeating the eyes-open/closed
  and no-change protocols would test both the alpha result and every threshold
  above on a different head.
- **No drowsiness recording yet.** Whether the SEM rate or deflection count
  moves with real, involuntary drowsiness — the case they exist for — is
  untested; so far they've only seen deliberate eye movement.
- **A loud but unflagged channel still counts most** in the channel average.
  A robust average (median, or dropping an outlier) isn't done.
- **The SDK's per-electrode contact indicator** isn't read; it would need the
  same scepticism as the other SDK flags.
- **Raw EEG grows without bound** (~5-15 MB per session). Fine at pilot scale;
  needs pruning or lifecycle rules later.
