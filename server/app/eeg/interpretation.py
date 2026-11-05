"""How EEG results may be read: the thresholds the session card, the trend
tab and the AI summary all share, so they can't disagree.

Deliberately free of mne/specparam (unlike analysis.py), so schemas.py and
ai_summary.py can import it cheaply.
"""

# Below this specparam r-squared, the model didn't describe that half's
# spectrum well enough for its aperiodic numbers to be read as
# measurements. THE one definition: shipped to the client in EegAnalysisOut
# (which flags those halves on the session card) and EegTrendPointOut (which
# flags them on the patient's trend tab), and used by ai_summary for both its
# prompt rule and its per-period note. A card that flags a half
# while the AI summary states that same half's exponent without hedging is
# two contradictory claims on one screen, which is the opposite of what this
# feature is for.
#
# Lives here rather than in analysis.py deliberately: this is a
# presentation judgment, not a pipeline parameter (the pipeline itself never
# reads it), and importing analysis would drag mne/specparam into
# schemas.py and ai_summary.py.
#
# 0.97, stricter than the conventional 0.90. Re-analyzing the
# eyes-open/closed control sessions (8/9/11) with per-cause rejection counts
# showed rejection rate tracking fit quality:
#
#   dropped   3%    7%    14%   17%    25%    26%
#   r-squared 0.993 0.993 0.992 0.872  0.958  0.917
#
# The three halves that lost the most windows to movement are the three that
# fit worst, and there is one clean gap in that spread: nothing lands between
# 0.958 and 0.992. 0.97 sits in the middle of it, so it flags all three
# high-rejection halves and none of the clean ones.
#
# TWO HONEST CAVEATS, because this number looks more derived than it is.
# First, six halves from one wearer is a thin basis for a cutoff, and 0.97 is
# the midpoint of the only gap in that data rather than a validated line -
# a different wearer could easily put a half inside it. Second, the causal
# story is UNRESOLVED: every eyes-open half is also a high-rejection half in
# this set, so "more rejections leave more residual artifact, which fits
# worse" and "having your eyes open independently causes both" cannot be
# told apart here. The plausible chain is blinks -> amplitude rejections ->
# residual artifact -> worse fit, but this data cannot establish it.
# See docs/eeg-aperiodic-correction.md.
LOW_FIT_R_SQUARED = 0.97

# When two halves differ this much in eye activity, their exponents can't be
# compared as a brain measurement. THE one definition, same reasoning and same
# home as LOW_FIT_R_SQUARED above: the session card, the trend tab and
# ai_summary all read eye_activity_differs() below, so they can't disagree.
#
# Why this exists (clean-contact controls, one wearer): blinking through one
# half steepened the exponent by +0.74 (session 30) and slow side-to-side eye
# movements by +0.58 (session 31), against ~0.07 between halves with nothing
# changing, and both halves of 31 fit at r² >= 0.988 - so no fit-quality
# check can catch it. Correcting for it didn't work reliably (see
# analysis.EOG_CORRECTION_ENABLED), so it is flagged instead.
#
# Two measures, either one trips it:
# - deflections (artifacts._deflection_times): large slow deflections per
#   minute - often blinks, eye or head movement. The no-change sessions (13,
#   21, 22, 29) differed between halves by 0.1-9.0/min; the deliberate
#   blink, sweep and lift sessions (24-28, 30) by 13.8-61.6, and session 31's
#   slow sweeps by only 7.3 (the slow-eye-movement rate catches that one).
#   10 sits in the 9.0-13.8 gap but near its low edge: session 13, eyes
#   closed throughout and with the largest no-change exponent shift (-0.17),
#   is at 9.0.
# - slow eye movements (eog_sem_rate_per_min): 0.0-0.5/min in every half of
#   the eyes-closed no-change sessions (13, 21, 22, 29), 7.0 and 13.3 in the
#   scored sweep halves (31, 26), and 10.2 in session 25's blinking half
#   (0.0 in 30's, which the deflection count catches; 27 and 28 aren't
#   scored because both their ear electrodes are excluded). 5 is below all
#   of those. Both rules catch 25 and 26; deflections alone catch 24, 27, 28
#   and 30, and the SEM rate alone catches 31.
# Of the eyes-open/closed control (8, 9, 11) it flags 8 (11.2) and 11 (18.9)
# but not 9 (3.2) - eye state itself isn't reliably detected, only eye-type
# movement. Working thresholds from one wearer, not validated ones.
# Direction doesn't matter:
# either half having more eye activity confounds the comparison.
EYE_ACTIVITY_DEFLECTION_DIFF_PER_MIN = 10.0
EYE_ACTIVITY_SEM_DIFF_PER_MIN = 5.0


def eye_activity_differs(
    first_deflections: float | None,
    second_deflections: float | None,
    first_sem: float | None,
    second_sem: float | None,
) -> bool | None:
    """True when the two halves recorded different enough amounts of
    eye-type activity that their exponents shouldn't be compared (see the
    constants above). None when neither measure is available for BOTH halves
    - "couldn't check", which every caller currently leaves unsaid rather
    than presenting as "checked, and they matched"."""
    checks = []
    if first_deflections is not None and second_deflections is not None:
        checks.append(abs(second_deflections - first_deflections) >= EYE_ACTIVITY_DEFLECTION_DIFF_PER_MIN)
    if first_sem is not None and second_sem is not None:
        checks.append(abs(second_sem - first_sem) >= EYE_ACTIVITY_SEM_DIFF_PER_MIN)
    if not checks:
        return None
    return any(checks)
