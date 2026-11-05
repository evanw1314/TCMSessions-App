"""What the summary model is given (ai_summary._format_session_context) and
the rules it's given with. The practitioner reading the summary has no
neuroscience training, so the data arrives already in plain words - the model
has no technical term to echo - and the two forms of EEG data never mix."""
from datetime import datetime
from types import SimpleNamespace as NS

from app import ai_summary
from app.eeg.interpretation import eye_activity_differs

TECHNICAL = ["aperiodic", "exponent", "specparam", "epoch", "oscillation", "TP9", "TP10", "AF7", "AF8"]


def _session(**overrides):
    fields = dict(
        id=1, modality="Acupuncture", started_at=datetime(2026, 9, 11, 10, 0), ended_at=datetime(2026, 9, 11, 10, 4),
        practitioner_note=None, patient_dozed=False, alertness_rating=None,
        eeg_electrode_findings=[], eeg_excluded_channels="",
    )
    fields.update(overrides)
    return NS(**fields)


def _metrics():
    """Session 31's real numbers: slow eye movements 0 -> 7/min."""
    return [
        NS(half="baseline", aperiodic_exponent=1.08, r_squared=0.988,
           eog_sem_rate_per_min=0.0, deflection_rate_per_min=1.46),
        NS(half="treatment", aperiodic_exponent=1.66, r_squared=0.995,
           eog_sem_rate_per_min=7.0, deflection_rate_per_min=8.80),
    ]


_PEAKS = [NS(half="treatment", band="theta", center_frequency=6.1, power=0.2)]


def _finding(channel_name, sign, excluded, half="baseline", partner_name="TP10"):
    return {"channel": 0, "channel_name": channel_name, "half": half, "sign": sign,
            "value": 1.0, "excluded": excluded, "partner_name": partner_name}


def test_corrected_analysis_is_given_in_plain_words():
    session = _session(
        eeg_excluded_channels="0",
        eeg_electrode_findings=[
            _finding("TP9", "interference_lines", excluded=True),
            _finding("AF8", "much_noisier_than_partner", excluded=False, half="treatment", partner_name="AF7"),
        ],
    )
    context = ai_summary._format_session_context(session, [], [], _metrics(), _PEAKS)
    assert "background steepness" in context
    assert "left ear sensor" in context and "right forehead sensor" in context
    for word in TECHNICAL:
        assert word.lower() not in context.lower(), word


def test_a_difference_in_eye_activity_is_spelled_out():
    context = ai_summary._format_session_context(_session(), [], [], _metrics(), _PEAKS)
    assert "different amounts of eye-type activity" in context


def test_the_two_forms_never_mix():
    samples = [NS(offset_seconds=10, band="alpha", value=0.2)]
    corrected = ai_summary._format_session_context(_session(), [], samples, _metrics(), _PEAKS)
    assert "Corrected EEG analysis" in corrected and "Band power" not in corrected
    late_alpha = NS(offset_seconds=200, band="alpha", value=0.4)
    band_power = ai_summary._format_session_context(_session(), [], samples + [late_alpha])
    assert "Band power" in band_power and "Corrected EEG analysis" not in band_power


def test_no_signal_seconds_are_left_out_of_band_power():
    samples = [
        NS(offset_seconds=10, band="alpha", value=0.2),
        NS(offset_seconds=20, band="alpha", value=ai_summary.NO_SIGNAL_VALUE),
        NS(offset_seconds=200, band="alpha", value=0.4),
    ]
    context = ai_summary._format_session_context(_session(), [], samples)
    assert "- alpha: 0.200 -> 0.400" in context


def test_all_sensors_loose_means_no_findings():
    session = _session(
        eeg_electrode_findings=[_finding("TP9", "interference_lines", excluded=False)], eeg_excluded_channels=""
    )
    context = ai_summary._format_session_context(session, [], [], _metrics(), _PEAKS)
    assert "unreliable" in context
    assert "can't be checked" in context
    assert "no real rhythm detected" not in context  # an unchecked band isn't an absent rhythm


def test_the_prompts_keep_the_non_clinical_rules():
    rules = ai_summary._NON_CLINICAL_RULES
    assert "must never" in rules
    assert "recommend, or hint, whether the patient should schedule another visit" in rules
    assert "diagnose, assess severity, or offer any clinical opinion" in rules
    for prompt in (ai_summary.PATIENT_SYSTEM_PROMPT, ai_summary.SESSION_SYSTEM_PROMPT):
        assert rules in prompt
    assert ai_summary._PLAIN_LANGUAGE_RULES in ai_summary.SESSION_SYSTEM_PROMPT
    assert 'always call it "background steepness"' in ai_summary._PLAIN_LANGUAGE_RULES


def test_eye_activity_thresholds():
    """eeg.interpretation.eye_activity_differs, on the numbers its comment cites."""
    assert eye_activity_differs(1.46, 8.80, 0.0, 7.0) is True     # session 31: slow eye movements
    assert eye_activity_differs(0.0, 9.0, None, None) is False    # session 13, eyes closed throughout
    assert eye_activity_differs(0.0, 13.8, None, None) is True    # the smallest deliberate-movement gap
    assert eye_activity_differs(None, None, None, None) is None   # couldn't check, not "they matched"
