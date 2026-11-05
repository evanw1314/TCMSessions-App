import logging
from collections import defaultdict

import anthropic
from fastapi import HTTPException, status

from .config import settings
from .eeg.interpretation import LOW_FIT_R_SQUARED, eye_activity_differs
from .models import PatientNote, SessionEegMetric, SessionMarker, SessionOscillationPeak, SessionSample, TcmSession

logger = logging.getLogger("smartring.ai_summary")

# The two text summaries (patient overview, single session) run on Claude
# Opus 5. Thinking is on by default on this model; effort "low" keeps a 2-5
# sentence summary to ~5 s, inside the
# Android client's 15 s read timeout (ApiClient.kt). Voice-marker
# transcription is separate, in transcription.py.
CLAUDE_MODEL = "claude-opus-5"
# Opt-in (beta): if Opus 5's safety classifiers decline a request, the API
# re-runs it on Anthropic's recommended substitute model instead of
# returning the refusal. The "default" form picks that model by refusal
# category, so there's no fallback model id here to go stale.
_CLAUDE_FALLBACK_BETA = "server-side-fallback-2026-07-01"

# Mirrors Android's Models.kt NO_SIGNAL_VALUE: relative band power is always
# >= 0, so the client writes -1.0 for "no valid reading this tick" (bad
# electrode contact or an SDK-reported NaN). Excluded from aggregation below
# the same way SessionDetailActivity/BandChartView exclude it on-device.
NO_SIGNAL_VALUE = -1.0

# Low frequency to high — matches Android's BAND_ORDER / schemas.BAND_TYPES.
BAND_ORDER = ["delta", "theta", "alpha", "beta", "gamma"]

# Plain-words reasons for each electrode-check sign (eeg.artifacts.
# ElectrodeFinding.sign), shared wording with the session card (Android's
# EegWording.kt sensorSignWords).
_ELECTRODE_SIGN_WORDS = {
    "interference_lines": "electrical noise, a sign of a loose sensor",
    "upside_down_spectrum": "more fast activity than slow, a sign of a loose sensor",
    "no_signal": "no signal at all (flat)",
    "much_noisier_than_partner": "much noisier than the matching sensor on the other side",
}

# Plain names for eeg.params.CHANNEL_NAMES (not imported, see eeg/interpretation.py on
# why), same as Android's EegWording.kt sensorName. The summary is read by
# someone without neuroscience training, who can't be expected to know TP9.
_SENSOR_NAMES = ("left ear sensor", "left forehead sensor", "right forehead sensor", "right ear sensor")
_SENSOR_NAME_BY_CODE = dict(zip(("TP9", "AF7", "AF8", "TP10"), _SENSOR_NAMES, strict=True))


def _sensor_list(names: list[str]) -> str:
    """"left forehead and right forehead sensors" - EegWording.kt sensorList."""
    sides = [n.removesuffix(" sensor") for n in names]
    if len(sides) == 1:
        return f"{sides[0]} sensor"
    return ", ".join(sides[:-1]) + f" and {sides[-1]} sensors"


def _all_electrodes_flagged(session: TcmSession) -> bool:
    """Every electrode showed an excluding sign, so none could be left out -
    same test as routers/sessions._all_electrodes_flagged (inlined, not
    imported: this module must not import eeg.analysis, see eeg/interpretation.py)."""
    findings = session.eeg_electrode_findings
    if not findings:
        return False
    return any(f["sign"] != "much_noisier_than_partner" for f in findings) and not session.eeg_excluded_channels

# Shared framing for both prompts below: deliberately descriptive-only, never
# a recommendation about scheduling or clinical status — even hedged language
# like "consider a follow-up" is a clinical judgment this app has no business
# making. State the pattern, let the practitioner draw the conclusion.
_NON_CLINICAL_RULES = """You are not a clinician and must never:
- recommend, or hint, whether the patient should schedule another visit
- diagnose, assess severity, or offer any clinical opinion
- speculate about anything not explicitly stated in the input"""

PATIENT_SYSTEM_PROMPT = f"""You summarize a TCM (Traditional Chinese Medicine) patient's \
recent history for the practitioner who already treats them, from that \
practitioner's own session records and notes. {_NON_CLINICAL_RULES}

Write 3-5 sentences of plain prose — no headers, no bullet points. Stick to \
observable facts: how many sessions, which modalities, the approximate interval \
between them, and themes that recur across the notes (e.g. a symptom mentioned \
more than once). Describe patterns; never conclude what they mean."""

# Used only when the session has NO aperiodic-corrected EEG analysis
# (docs/eeg-aperiodic-correction.md) — i.e. raw EEG was never captured/
# uploaded for it, or the practitioner hasn't tapped "Analyze EEG" yet. See
# _CORRECTED_EEG_RULES below for the case where corrected numbers exist;
# _format_session_context sends exactly one of these two sections, never
# both, so this block and that one are mutually exclusive in practice.
_RELATIVE_BAND_RULES = """Relative EEG band power is NOT a validated measure of relaxation, calm, or response \
to treatment — do not use words like "relaxing," "calming," or "responding to the \
modality" for it. In a resting, eyes-closed, lying-still recording, a rise in \
delta/theta/alpha together with a fall in beta/gamma is the single most common \
pattern in EEG generally, and it is produced just as readily by the patient growing \
drowsy, an electrode losing contact, or muscle tension releasing in the jaw/scalp as \
by anything specific to this modality — this software has no way to tell those \
apart from the band-power numbers alone. So: report the direction each band moved, \
plainly, as a numeric fact ("beta power was lower in the second half than the \
first") — never as evidence of relaxation, treatment response, or any other \
interpretation of what caused it. Band-power trends are exploratory data here, not \
a validated clinical measure."""

# Used when the session HAS a corrected analysis (SessionEegMetric/
# SessionOscillationPeak rows from POST /sessions/{id}/eeg/analyze). Unlike
# relative band power, these numbers have already had the non-rhythmic 1/f
# background separated out by specparam — see docs/eeg-aperiodic-correction.md
# for why that distinction matters. That earns real oscillatory claims
# ("a genuine alpha rhythm was present"), but a single uncontrolled session
# still can't support a causal treatment claim, so the "no clinical opinion,
# no cause" framing carries over even though the underlying numbers are more
# trustworthy now.
_CORRECTED_EEG_RULES = f"""The EEG numbers below are aperiodic-corrected: specparam has already separated \
genuine brain oscillations from the non-rhythmic background that makes raw band \
power misleading, so you may describe a listed peak, or a change in one, as a real \
finding rather than dismissing it as arithmetic noise. Still:
- Never call a peak, a peak change, or an exponent shift "relaxing," "calming," or \
"a response to treatment" — a single session with no sham/control condition cannot \
establish that the modality caused a change, even though the change itself is real. \
State what shifted; do not state why.
- The aperiodic exponent is associated with arousal in research (it climbs from alert \
wakefulness toward drowsiness and sleep), but on this headband eye activity and \
electrode contact move it just as much. So report an exponent change as a change in \
the recorded signal, never as a change in the patient's arousal or alertness. You may \
say arousal is one possible reason among others, and if the patient's own \
self-reported dozing/alertness is present below, report that alongside it.
- Eye activity moves the exponent on its own. In controls on this headband, blinking \
through one period steepened the exponent by about 0.7 and slow eye movements by about \
0.6, with no drowsiness (the eye-movement session fit well in both periods, so a weak \
fit may not flag it) — larger than the whole change expected \
between periods with nothing changing. If the data below includes the note that the \
periods recorded different amounts of eye-type activity, say plainly that the \
exponent change can't be separated from that difference. Do not let an exponent shift \
override a patient's "not dozed" / high alertness self-report.
- A period may list "blinks and eye or head movements" per minute — large slow bumps \
counted from the signal, and not specific to any one of those causes. Like \
the slow-eye-movement rate, it is only meaningful compared against this SAME \
session's other period.
- If the data says sensors were left out, the numbers come from the remaining \
sensors only; mention that briefly. If it says every sensor showed signs of a loose \
fit, say the numbers for this session are unreliable, and don't describe rhythms, \
the absence of rhythms, or exponent changes as findings. Describe sensor problems as \
facts about the recording, never about the patient.
- A band with no rhythm listed means specparam found no real oscillation there — say \
so plainly ("no real theta rhythm was detected in either period"). This is an \
informative absence, not a failed measurement and not a finding that something is \
wrong with the patient.
- If a period's fit quality (r²) is below {LOW_FIT_R_SQUARED:.2f}, mention that period's numbers are \
less certain rather than stating them as flatly as a well-fit period's.
- If the two periods are labeled "baseline" and "treatment," the practitioner \
recorded a real eyes-closed resting baseline before starting — you may describe the \
treatment period as compared against this session's own baseline. If instead they're \
labeled "first"/"second," there was no recorded baseline — it's just the session's \
own timeline split in half, a weaker comparison since the patient's state at the \
very start wasn't captured on its own; do not describe it as a baseline.
- A period may also list a "slow eye movement rate" (SEM/min), counted from the \
signal (the frontal channels used as an eye-movement proxy, since this headband has \
no dedicated eye electrode). Slow eye movements occur when people get drowsy, but \
deliberate or ordinary eye movement produces them just as readily (in controls, \
looking side to side gave 7-13 per minute with no drowsiness). So report it as a count \
that rose, fell or stayed level — never as evidence the patient was drowsy or alert — \
and read it alongside, never in place of, the patient's self-reported dozing/alertness \
when that is present. "Not scored (not enough clean signal)" means exactly that for \
that period — no reading, never a rate of zero. This rate is only meaningful compared \
against this SAME session's other period — never state or imply a specific number is \
high or low in any absolute sense."""

# The rules above reason in the precise terms; this says what the OUTPUT
# may use. The reader has no neuroscience training and found the technical
# terms "pure jargon", and the data below is already labelled in these plain
# names so the model has nothing technical to echo. Same names as Android's
# EegWording.kt.
_PLAIN_LANGUAGE_RULES = """Plain language — the practitioner reading this has no \
neuroscience training:
- The "background steepness" in the data is the aperiodic exponent the rules above \
discuss; always call it "background steepness". Call a peak or oscillation a "rhythm", \
and a weak fit "the app's model doesn't match that period well".
- Name sensors by position ("left ear sensor", "right forehead sensor"), never by code \
(TP9, TP10, AF7, AF8).
- Never write "aperiodic", "exponent", "specparam", "r²", "offset", "epochs", \
"spectrum", "oscillation" or "deflections" — say what they mean instead. A plain word \
that's slightly less precise beats a precise term the reader can't follow."""

SESSION_SYSTEM_PROMPT = f"""You summarize a single TCM (Traditional Chinese Medicine) \
session for the practitioner who ran it, from the modality, timeline markers, \
recorded EEG data, and their own note for that session. \
{_NON_CLINICAL_RULES}

The EEG data below is in exactly ONE of two forms — follow the rules that match \
the form actually present:

Form A — "Band power" (relative, uncorrected): {_RELATIVE_BAND_RULES}

Form B — "Corrected EEG analysis" (aperiodic-corrected): {_CORRECTED_EEG_RULES}

{_PLAIN_LANGUAGE_RULES}

Write 2-4 sentences of plain prose — no headers, no bullet points. Describe what \
the record shows: the modality and duration, what the markers indicate about the \
session's structure, and what the EEG data shows moving from the first period to \
the second, following whichever rule set above matches the form present."""


def _call_claude(system_prompt: str, context: str, log_label: str) -> str:
    """Request path for the two text summaries below. Synchronous: routes in
    this app are plain `def` (FastAPI runs them in a threadpool). Every
    failure is a 503, naming Claude and ANTHROPIC_API_KEY where it helps."""
    if not settings.anthropic_api_key:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "AI summaries are not configured on the server (ANTHROPIC_API_KEY)",
        )
    # Both apps stop waiting after 15 s (ApiClient.kt, APIClient.swift), so
    # one attempt that ends before that: the SDK defaults (10 min timeout,
    # 2 retries honouring a retry-after of up to 60 s) would keep a worker
    # busy, and possibly billed twice, long after the practitioner saw a
    # timeout. They can simply tap Summarize again.
    client = anthropic.Anthropic(api_key=settings.anthropic_api_key, timeout=13.0, max_retries=0)
    try:
        response = client.beta.messages.create(
            model=CLAUDE_MODEL,
            # Room for adaptive thinking as well as the few sentences of
            # output; billed on what's generated, not on this ceiling.
            max_tokens=16000,
            output_config={"effort": "low"},
            betas=[_CLAUDE_FALLBACK_BETA],
            fallbacks="default",
            system=system_prompt,
            messages=[{"role": "user", "content": context}],
        )
    except anthropic.AuthenticationError:
        logger.exception("Claude rejected the API key for %s", log_label)
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "AI summaries are unavailable — the configured Claude API key is invalid",
        )
    except anthropic.PermissionDeniedError:
        # A valid key whose account can't use this model or beta.
        logger.exception("Claude refused permission for %s", log_label)
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "AI summaries are unavailable — the Claude API key isn't permitted to use this model",
        )
    except anthropic.RateLimitError:
        logger.exception("Claude rate-limited %s", log_label)
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "AI summaries are temporarily rate-limited — try again shortly"
        )
    except anthropic.APIStatusError:
        logger.exception("Claude call failed for %s", log_label)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Failed to generate summary")
    except anthropic.APIConnectionError:  # includes APITimeoutError
        logger.exception("Could not reach the Claude API for %s", log_label)
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "Could not reach the Claude API — try again shortly"
        )
    except anthropic.APIError:  # anything else the SDK raises, e.g. an unparseable response
        logger.exception("Claude call failed for %s", log_label)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Failed to generate summary")

    # With fallbacks on, a refusal here means the substitute declined too.
    # Checked before reading content, which can hold partial text.
    if response.stop_reason == "refusal":
        details = response.stop_details
        logger.warning("Claude declined %s (category=%s)", log_label, details.category if details else None)
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Failed to generate summary")
    # Text blocks only: the response also carries a thinking block, and a
    # fallback block when a substitute model answered.
    text = "".join(b.text for b in response.content if b.type == "text").strip()
    if not text:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Failed to generate summary")
    return text


def _format_patient_context(
    patient_name: str, notes: list[PatientNote], sessions: list[TcmSession]
) -> str:
    lines = [f"Patient: {patient_name}", ""]
    if sessions:
        lines.append("TCM sessions (most recent first):")
        for s in sessions:
            entry = f"- {s.started_at:%Y-%m-%d} {s.modality}"
            if s.practitioner_note:
                entry += f": {s.practitioner_note}"
            lines.append(entry)
        lines.append("")
    if notes:
        lines.append("Practitioner notes (most recent first):")
        for n in notes:
            lines.append(f"- {n.created_at:%Y-%m-%d}: {n.body}")
    return "\n".join(lines)


def generate_patient_summary(
    patient_name: str, notes: list[PatientNote], sessions: list[TcmSession]
) -> str:
    context = _format_patient_context(patient_name, notes, sessions)
    return _call_claude(PATIENT_SYSTEM_PROMPT, context, log_label=f"patient {patient_name!r}")


# Orders halves oldest-period-first regardless of label — "baseline"/"first"
# before "treatment"/"second" — since dict iteration order from the DB query
# isn't guaranteed to match recording order.
_HALF_ORDER = {"baseline": 0, "first": 0, "treatment": 1, "second": 1}


def _format_electrode_context(session: TcmSession) -> list[str]:
    """The electrode check's result, in the words the session card uses.
    Empty when there's nothing to say. An analysis predating the check (null
    findings) with an exclusion got it from the old, switched-off eye
    correction - said as that, not as a poor-contact finding."""
    excluded_raw = session.eeg_excluded_channels
    excluded = [int(i) for i in excluded_raw.split(",") if i.strip()] if excluded_raw else []
    findings = session.eeg_electrode_findings
    sensor = lambda code: _SENSOR_NAME_BY_CODE.get(code, code)  # noqa: E731
    if findings is None:
        names = [_SENSOR_NAMES[i] for i in excluded if i < len(_SENSOR_NAMES)]
        if names:
            return [
                f"The {_sensor_list(names)} {'was' if len(names) == 1 else 'were'} left out by an "
                "older eye-movement correction (since switched off)."
            ]
        return []
    lines = []
    excluding = [f for f in findings if f["sign"] != "much_noisier_than_partner"]
    if _all_electrodes_flagged(session):
        lines.append(
            "Every sensor showed signs of a loose fit, so none could be left out — "
            "the numbers for this session are unreliable."
        )
    elif excluded:
        reasons = {}
        for f in excluding:
            reasons.setdefault(sensor(f["channel_name"]), _ELECTRODE_SIGN_WORDS.get(f["sign"], f["sign"]))
        lines.append(
            "Sensors left out of both periods: "
            + "; ".join(f"{name} ({reason})" for name, reason in reasons.items())
            + "."
        )
    for f in findings:
        if f["sign"] == "much_noisier_than_partner" and not f["excluded"]:
            lines.append(
                f"In the {f['half']} period, the {sensor(f['channel_name'])} was much noisier than "
                f"the {sensor(f['partner_name'])} (possibly a loose fit, or muscle or eye activity on "
                "one side; not left out)."
            )
    return lines


def _format_eeg_metrics_context(
    eeg_metrics: list[SessionEegMetric],
    eeg_peaks: list[SessionOscillationPeak],
    session: TcmSession | None = None,
) -> str:
    """Form B (see SESSION_SYSTEM_PROMPT): the aperiodic-corrected analysis,
    used INSTEAD OF relative band power once a session has one. Feeding the
    model both would be self-contradictory: one section says the numbers are
    unreliable, the other says the same period's numbers are real."""
    ordered = sorted(eeg_metrics, key=lambda m: _HALF_ORDER.get(m.half, 2))
    peaks_by_half: dict[str, dict[str, SessionOscillationPeak]] = defaultdict(dict)
    for p in eeg_peaks:
        peaks_by_half[p.half][p.band] = p

    has_baseline = any(m.half in ("baseline", "treatment") for m in ordered)
    comparison = (
        "the treatment period compared against this session's own recorded baseline)"
        if has_baseline
        else "the session's first half compared against its second, no baseline recorded)"
    )
    lines = [
        "Corrected EEG analysis (separates real brain rhythms from the steady background "
        f"activity that dominates raw band power; {comparison}:"
    ]
    if session is not None:
        lines.extend(_format_electrode_context(session))
    # Labelled in the plain names _PLAIN_LANGUAGE_RULES asks the output to
    # use, so the model has no technical term to echo. The offset and epoch
    # count are left out: no rule uses them, so they were only jargon to
    # repeat.
    for m in ordered:
        fit_note = (
            "" if m.r_squared >= LOW_FIT_R_SQUARED
            else f" — the app's model doesn't match this period well (r²={m.r_squared:.2f}), treat cautiously"
        )
        eog_note = (
            f", slow eye movements {m.eog_sem_rate_per_min:.1f}/min"
            if m.eog_sem_rate_per_min is not None
            else ", slow eye movements: not scored (not enough clean signal)"
        )
        deflection_note = (
            f", blinks and eye or head movements {m.deflection_rate_per_min:.1f}/min"
            if m.deflection_rate_per_min is not None
            else ", blinks and eye or head movements: not measured"
        )
        lines.append(
            f"- {m.half}: background steepness {m.aperiodic_exponent:.2f}"
            f"{fit_note}{eog_note}{deflection_note}"
        )
        half_peaks = peaks_by_half.get(m.half, {})
        if session is not None and _all_electrodes_flagged(session):
            # No peaks were reported because none could be evaluated - never
            # let that read as "no real rhythm detected" below.
            lines.append("    rhythms: can't be checked (every sensor showed signs of a loose fit)")
            continue
        for band in BAND_ORDER:
            peak = half_peaks.get(band)
            if peak:
                lines.append(
                    f"    {band}: real rhythm at {peak.center_frequency:.1f}Hz, "
                    f"strength {peak.power:.2f}"
                )
            else:
                lines.append(f"    {band}: no real rhythm detected")
    if len(ordered) == 2:
        delta = ordered[1].aperiodic_exponent - ordered[0].aperiodic_exponent
        lines.append(
            f"Background steepness changed by {delta:+.2f} between periods "
            "(see the rules above on how to report it)."
        )
        # Computed here with the session card's rule (eeg.interpretation.
        # eye_activity_differs), not left for the model to derive, so the
        # card's warning and the summary's hedge can't disagree.
        if eye_activity_differs(
            ordered[0].deflection_rate_per_min, ordered[1].deflection_rate_per_min,
            ordered[0].eog_sem_rate_per_min, ordered[1].eog_sem_rate_per_min,
        ):
            lines.append(
                "Note: the periods recorded different amounts of eye-type activity (blinks and "
                "eye or head movements, and/or slow eye movements), so the background steepness "
                "change can't be separated from that difference, per the rules above."
            )
    return "\n".join(lines)


def _format_session_context(
    session: TcmSession,
    markers: list[SessionMarker],
    samples: list[SessionSample],
    eeg_metrics: list[SessionEegMetric] | None = None,
    eeg_peaks: list[SessionOscillationPeak] | None = None,
) -> str:
    duration_minutes = round((session.ended_at - session.started_at).total_seconds() / 60)
    lines = [
        f"Modality: {session.modality}",
        f"Date: {session.started_at:%Y-%m-%d}",
        f"Duration: {duration_minutes} minutes",
        "",
    ]

    if markers:
        lines.append("Markers (session-relative time):")
        for m in sorted(markers, key=lambda x: x.offset_seconds):
            lines.append(f"- {m.offset_seconds // 60:02d}:{m.offset_seconds % 60:02d} {m.label}")
        lines.append("")

    if eeg_metrics:
        # Form B — corrected analysis exists for this session; do NOT also
        # send Form A (relative band power) for the same session, see
        # _format_eeg_metrics_context's docstring.
        lines.append(_format_eeg_metrics_context(eeg_metrics, eeg_peaks or [], session))
        lines.append("")
    else:
        # Form A — no corrected analysis yet, fall back to relative band
        # power. Split into first half /
        # second half by offset and average per band, excluding
        # NO_SIGNAL_VALUE — the same before/after framing the app's own
        # SessionDetailActivity band-stats table shows, computed server-side
        # here since raw samples (up to 40,000/session) are too many to hand
        # the model.
        valid = [s for s in samples if s.value != NO_SIGNAL_VALUE]
        if valid:
            # Plain offset-second arithmetic, not datetime.timestamp() — these
            # are naive UTC (see schemas.to_utc_naive), and .timestamp()
            # on a naive datetime silently interprets it as LOCAL time, not UTC.
            half_seconds = (session.ended_at - session.started_at).total_seconds() / 2
            by_band_half: dict[str, dict[str, list[float]]] = defaultdict(lambda: {"first": [], "second": []})
            for s in valid:
                half = "first" if s.offset_seconds < half_seconds else "second"
                by_band_half[s.band][half].append(s.value)

            lines.append(
                "Band power, first half of session -> second half (relative power, unitless; "
                "a rise in the low bands with a fall in the high bands is equally consistent "
                "with drowsiness or electrode drift as with any treatment effect):"
            )
            for band in BAND_ORDER:
                halves = by_band_half.get(band)
                if not halves or not halves["first"] or not halves["second"]:
                    continue
                first_avg = sum(halves["first"]) / len(halves["first"])
                second_avg = sum(halves["second"]) / len(halves["second"])
                lines.append(f"- {band}: {first_avg:.3f} -> {second_avg:.3f}")
            lines.append("")

    if session.patient_dozed is not None:
        lines.append(f"Patient dozed off / fell asleep during the session: {'yes' if session.patient_dozed else 'no'}")
    if session.alertness_rating is not None:
        lines.append(
            f"Patient-reported alertness at session end (1=drowsy, 7=fully alert): {session.alertness_rating}"
        )

    if session.practitioner_note:
        lines.append(f"Practitioner's note for this session: {session.practitioner_note}")

    return "\n".join(lines)


def generate_session_summary(
    session: TcmSession,
    markers: list[SessionMarker],
    samples: list[SessionSample],
    eeg_metrics: list[SessionEegMetric] | None = None,
    eeg_peaks: list[SessionOscillationPeak] | None = None,
) -> str:
    context = _format_session_context(session, markers, samples, eeg_metrics, eeg_peaks)
    return _call_claude(SESSION_SYSTEM_PROMPT, context, log_label=f"session {session.id}")
