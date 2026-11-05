# Live session view: design (not built)

**Status: design only.** This is the agreed plan for a richer session
feature: a live brainwave graph during the treatment, then a draft the
practitioner edits and chooses whether to publish to the patient. None of it
is built. What exists today is described below, and the rest of this doc is
the plan if the feature is picked up.

## What exists today

The Android app (and the iOS port behind it) records without a live view:

- The practitioner picks a patient, connects the headband and records. Band
  powers (once a second), raw EEG and markers (tapped, or spoken and
  transcribed) are kept in memory and mirrored to disk by `SessionJournal`, so
  a crash or a killed app doesn't lose the recording.
- At End session the whole session uploads in one call, then the raw EEG in a
  second. There is no draft state: a saved session is immediately visible to
  the practitioner who recorded it and to the patient.
- The server analyzes the raw EEG with an aperiodic correction
  ([`eeg-aperiodic-correction.md`](eeg-aperiodic-correction.md)), not the
  SDK's relative band powers alone.

See [`ARCHITECTURE.md`](../ARCHITECTURE.md) for the invariants this must keep
(upload before clear, one session per `client_session_id`).

## What the feature adds

During an appointment the practitioner watches the patient's brainwave
activity **live**, drops timestamped markers at points of interest (optionally
with a note), and afterwards edits the markers and notes and decides whether
to **publish** the reading to the patient's account.

The mental model is a **journal**: a per-patient log of sessions, each a
brainwave recording plus the practitioner's annotations. It's a second way
for a TCM practitioner to evaluate a patient, and a way to see over time
whether treatment is working.

## Workflow

1. The practitioner picks the patient and taps **Start session**.
2. The app connects to the headband and switches to a **landscape-locked**
   live view.
3. A scrolling graph advances as time passes. **Mark** drops a timestamped
   marker; a text box adds an optional note to the most recent one.
4. The practitioner can delete the most recent marker (a soft delete) and
   stop at any time.
5. On stop, the session becomes a **draft**: the practitioner edits markers
   and notes, restores deleted markers, and marks each item **private**
   (hidden from the patient) or public.
6. The practitioner **publishes** it to the patient, or keeps it
   practitioner-only. The patient never sees private items.

## Data model changes

- **Session lifecycle:** `recording` → `draft` → `published`. A `recording`
  session accepts appended chunks; `draft` is editable and
  practitioner-only; `published` is visible to the patient minus private
  items.
- **Markers:** position as an offset into the recording (not wall-clock, so
  it survives edits), an optional note, `is_private`, and a soft-delete
  timestamp.
- **Privacy is per field, not per row.** The same published session shows
  public markers and notes to the patient and hides private ones. That's new
  to this server; design the authorization deliberately. Default new
  markers and notes to private, and let the practitioner make items public
  at publish time.

## Recording and upload

The phone already keeps everything on disk. The feature adds **periodic
server checkpoints**:

- Every 10-15 s, the phone appends the latest chunk to the `recording`
  session on the server.
- Markers upload as soon as they're placed (tiny and valuable).
- If the network drops, the phone keeps recording and sends the queued
  chunks when it's back.

| Failure | What saves you |
|---|---|
| App crash or force-quit | The on-disk journal (already built) |
| Network drops | Local copy keeps recording; queued chunks upload later |
| Phone lost or broken mid-session | The server has everything up to the last checkpoint |

The server needs an endpoint that appends chunks to a `recording` session
and one that finalizes it. Raw EEG (about 5-15 MB a session) should keep
going to object storage, not the database.

## Live view

- **Landscape-locked** for the session only; the rest of the app stays
  portrait. The graph needs the width, the session is a bounded mode, and the
  phone is propped up. (On iPad, forced orientation is unreliable: prefer
  landscape and keep the view usable in portrait.)
- **The graph:** one line per band, scrolling right, over a rolling recent
  window. Interpolate between samples with a **monotone** spline, which never
  overshoots the data, so it invents no peaks between samples. That matters
  in a clinical graph.
- **What a line shows** is an open choice: per channel, or averaged across
  channels (five readable lines; the leaning). Relative band power can't
  separate a treatment effect from drowsiness (see the EEG doc), so the
  live graph is for orientation, not for reading a result.
- **Mark** drops a marker with one tap, draws a vertical line on the graph
  and gives a light haptic. Notes are optional live; expect most to be
  written afterwards, since typing mid-appointment covers the graph and pulls
  attention off the patient.
- **Delete** removes the most recent marker immediately, with no
  confirmation dialog. Safety comes from the soft delete (recoverable in
  review) and a contextual **undo** that stays until the next marker action.
- Keep the live view minimal: mark, optional note, undo. Full editing lives
  in the review.

## Review and publish

- The draft allows full editing: notes, deleting any marker, restoring
  deleted ones, and setting each item private or public.
- A wide, scrubbable timeline of the whole session; landscape helps here too.
- **Publish**, or keep it practitioner-only.
- The patient's app shows published sessions with public items only. It
  already has a read-only session history to build on.

## Open questions

1. Phone or tablet for the practitioner? It affects the layout and the
   orientation lock.
2. Graph lines per channel, or averaged across channels?
3. The authorization model for per-field privacy.
4. Should existing sessions become `published` on migration, keeping today's
   behaviour that the patient sees everything?
