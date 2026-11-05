"""Artifact detectors for the EEG pipeline (analysis.py runs them): slow
eye-movement scoring, the electrode check, and large slow deflections.

Each answers "how much of this half is something other than brain signal?",
which the pipeline then flags or excludes; none of them changes the spectrum
fit itself.
"""

import logging
from dataclasses import dataclass

import numpy as np
from scipy.signal import butter, find_peaks, sosfiltfilt, welch

from .params import (
    BAD_SAMPLE_FRACTION_REJECT,
    BANDPASS_HIGH_HZ,
    BANDPASS_LOW_HZ,
    CHANNEL_NAMES,
    EPOCH_SECONDS,
)

logger = logging.getLogger("smartring.eeg_analysis")

# ---- EOG-derived arousal scoring (slow rolling eye movements) ----
#
# Slow rolling eye movements (SEMs) — slow (roughly 0.2-1Hz), large, conjugate
# deflections from the corneo-retinal dipole — are a well-established marker
# of the wake -> drowsiness transition, appearing in EOG/frontal-EEG
# derivations well before any change in the classic delta/theta/alpha/beta/
# gamma bands (Ogilvie, "The process of falling asleep," Sleep Medicine
# Reviews 2001; De Gennaro & Ferrara 2003). The Muse has no dedicated EOG
# electrode, but its two frontal channels sit close enough to the eyes to
# pick up the same signal as a proxy — same "not the real thing, but the best
# this hardware offers" caveat as the rest of this pipeline (e.g. reading
# gamma despite muscle (EMG) contamination, or alpha from frontal-heavy
# placement; see docs/archive/eeg-lab-notebook.md).
#
# Assumed channel order EEG1..EEG4 = TP9, AF7, AF8, TP10 — the standard,
# widely-documented Muse S montage (used identically by muse-lsl and
# published Muse research), NOT verifiable from the libmuse SDK itself (its
# Eeg enum is just EEG1..EEG4 with no electrode names attached). Indirectly
# confirmed by August session 28: a deliberate eyes-closed slow-eye-movement phase
# rose clearly on indices (1, 2) and passed the localization/conjugate
# checks, which a wrong order would likely have failed. If the assumption is
# ever found wrong anyway, only these two index tuples need to change.
EOG_FRONTAL_CHANNEL_INDICES = (1, 2)  # AF7, AF8 — closest channels to the eyes
EOG_TEMPORAL_CHANNEL_INDICES = (0, 3)  # TP9, TP10 — used as a same-head "control" location, far from the eyes

# SEMs live almost entirely below the main pipeline's 1Hz high-pass (see
# BANDPASS_LOW_HZ), so this runs its own, separate filter over the
# NaN-interpolated but otherwise UNFILTERED signal, not the mne `raw` object
# _filter() below already high-passed.
EOG_LOW_HZ = 0.3
EOG_HIGH_HZ = 1.5
EOG_WINDOW_SECONDS = 2.0  # non-overlapping — SEMs are slow enough that overlap adds little
# A window's frontal deflection counts as a SEM candidate only when it is
# both LOCALIZED to the front of the head (bigger than the same-band
# deflection at the ear channels, which rules out whole-head artifacts like a
# headband shift or amplifier drift) and CONJUGATE (the two frontal channels
# move together, which rules out one noisy channel being read as an eye
# movement) — both hallmarks of a real corneo-retinal-dipole signal per the
# SEM literature above, distinguishing it from a generic slow-drift artifact.
EOG_FRONTAL_TEMPORAL_RATIO_MIN = 1.5
EOG_MIN_CORRELATION = 0.4
# Amplitude threshold is a robust, SESSION-RELATIVE one (a low percentile of
# this session's own frontal-window amplitudes, plus N MADs — see below), not
# a fixed microvolt floor — same reasoning as PTP_REJECT_MAD_MULTIPLE above:
# nobody has calibrated a fixed threshold for the Muse S's dry electrodes,
# and a self-relative threshold matches the pipeline's broader "compare a
# session to itself" stance. This means the resulting rate is a within-session index,
# not a portable clinical unit — never compare it across sessions/patients
# directly, only within one session's own halves (the same warning
# docs/eeg-aperiodic-correction.md gives for the aperiodic exponent).
#
# The MAD itself is computed relative to a LOW PERCENTILE of the session's
# windows (EOG_AMPLITUDE_REFERENCE_PERCENTILE), not the session median, and
# only from windows at or below that same percentile — not the standard
# two-sided MAD around the median. A drowsy stretch can plausibly cover a
# large fraction of a half's windows — bumping up against (or past) 50% of
# the WHOLE recording if drowsiness sets in early and stays for the rest of
# the session — and both a two-sided MAD and a median-based split break down
# well before that: a two-sided MAD gets pulled upward by exactly the
# elevated windows it's supposed to be measuring against, and even a
# median-based "lower half" hits the median's inherent 50%-breakdown point
# once contamination reaches that level (confirmed empirically against
# synthetic data on both counts: a continuous injected oscillation across an
# entire half flagged almost none of it under either approach with a ~50/50
# session split). Anchoring to a low percentile instead tolerates
# contamination up to roughly that percentile before the reference point
# itself starts being pulled from the elevated population — same principle,
# larger safety margin, confirmed across several synthetic seeds to reliably
# separate a fully-elevated half from a quiet one.
# Both constants below were swept against 10 synthetic-noise seeds crossed
# with 10 synthetic-SEM seeds (docs/archive/eeg-lab-notebook.md) — this
# combination gave the lowest false-positive rate on pure
# noise (~0.6 flagged windows/min, averaged) while still catching 100% of a
# fully-elevated half in the worst-case continuous-oscillation test. The MAD
# multiple matches PTP_REJECT_MAD_MULTIPLE above, not a coincidence — same
# "robust outlier, no fixed calibration yet" role.
EOG_AMPLITUDE_REFERENCE_PERCENTILE = 30.0
EOG_AMPLITUDE_MAD_MULTIPLE = 5.0
MIN_EOG_WINDOWS_PER_HALF = 6  # ~12s of clean frontal signal minimum before reporting a rate at all

# ---- Electrode check (poorly seated electrodes) ----
#
# Nothing in the recording's own contact reporting catches a poorly seated or
# lifted electrode: the headband keeps sending numbers, so the NaN counters
# read zero (session 24). These three signs come from comparing every stored
# recording (sessions 8-31, one wearer), measured per channel per half on the
# filtered signal:
#
# 1. INTERFERENCE LINES. A poorly seated electrode on this headband picks up
#    a set of narrow lines at whole multiples of ~1/30 of the sample rate
#    (~8.57 Hz at 257 Hz: 17.1, 25.7, 34.3, 42.9...). Score = the tallest of
#    harmonics 2-5, in log10 above the median of the bins 0.75-2.5 Hz away.
#    Harmonic 1 is left out because it sits inside the alpha band, where a
#    real alpha peak could raise it. The channels known or strongly suspected
#    bad (27/28's ears, 24-26's TP10) scored 0.79-4.05. Every channel of
#    the clean-contact sessions (9, 13, 21, 22, 29, 30, 31) scored at most
#    0.33, and the eyes-open halves of 8 and 11 reached 0.61-0.62 on one ear
#    channel each. 0.70 sits in the gap between 0.62 and 0.77. Four short
#    morning sessions with no recorded protocol (16, 17, 19, 20) had
#    channels at 0.77-3.44, so they're excluded too; whether those were bad
#    isn't known independently. It's a working threshold from one headband
#    and one wearer, and the line frequency itself may be specific to this
#    headband's electronics.
# 2. UPSIDE-DOWN SPECTRUM: a channel with at least as much power at 25-45 Hz
#    as at 1-2 Hz. Every channel NOT excluded by sign 1 ran the other way, by
#    0.34-3.70 log10; only line-flagged channels in 20, 27 and 28 did this,
#    so today it adds nothing sign 1 doesn't catch. Kept as an independent
#    second sign in case the interference looks different on another
#    headband.
# 3. MUCH NOISIER THAN ITS PARTNER: 25-45 Hz power at least 1.0 log10 (10x)
#    above the same-row partner (TP9/TP10, AF7/AF8). Clean sessions stayed
#    within 0.44. It's the only sign that caught session 24's lifted forehead
#    electrode (AF8, 1.18 above AF7), but it also fires on one-sided muscle
#    or eye activity (AF7 in session 25's blinking half, 1.81), and it can't
#    see both sides of a pair going bad together (27/28). So it WARNS and
#    never excludes on its own.
#
# Signs 1 and 2 exclude the channel from BOTH halves, same "one channel set
# for both halves" rule the old eye correction used: a before/after
# comparison from two different electrode sets would be its own confound.
# If every channel is flagged, nothing is excluded (there'd be no spectrum
# left); the result says so, and no peaks or eye-activity rates are reported
# (not evaluable - see _fit_half's report_peaks).
ELECTRODE_LINE_FUNDAMENTAL_DIVISOR = 30.0
ELECTRODE_LINE_HARMONICS = (2, 3, 4, 5)
ELECTRODE_LINE_EXCLUDE_LOG10 = 0.70
ELECTRODE_PAIR_WARN_LOG10 = 1.0
ELECTRODE_PAIRS = ((0, 3), (1, 2))  # TP9/TP10, AF7/AF8
# A channel whose median 1-45 Hz power is below this (V²/Hz, log10) is flat
# or constant - a dead or saturated electrode, not a signal - and is excluded
# as "no_signal". Real channels here sit around -13 to -8; a zeroed or
# constant one filters to ~0. Only seen in synthetic tests so far, not in a
# real recording.
ELECTRODE_FLAT_LOG10 = -18.0

# ---- Large slow deflections (blinks, eye and head movement) ----
#
# Counts large slow deflections per minute, per half - the eye-activity
# measure that stands in for the switched-off correction (see
# analysis.EOG_CORRECTION_ENABLED). It is deliberately NOT
# called a blink count: on this headband a blink showed mainly on the EAR
# channels in one session (30) and on the forehead too in others (25, 27),
# plausibly depending on how the headband sits against its mid-forehead
# reference, and a head movement or a hand on the headband produces the same
# kind of deflection (session 24's lift). So it runs on both an ear-pair and
# a forehead-pair average and counts a deflection on either.
#
# Method: 0.5-10 Hz bandpass on the unfiltered (NaN-interpolated) signal,
# then peaks of either sign above DEFLECTION_MAD_MULTIPLE robust standard
# deviations of that same average over the whole session, 50-600 ms wide,
# at least 300 ms apart; hits within DEFLECTION_MERGE_SECONDS of the last
# counted one are the same event. The rate is per minute of time with
# readings (NaN gaps left out). Not measured at all when every electrode is
# flagged.
# Electrodes excluded by the electrode check are left out of the averages.
# Session-relative threshold, so, like the SEM rate, the number means
# something compared between one session's halves, not across sessions.
DEFLECTION_LOW_HZ = 0.5
DEFLECTION_HIGH_HZ = 10.0
DEFLECTION_MAD_MULTIPLE = 5.0
DEFLECTION_MIN_WIDTH_SECONDS = 0.05
DEFLECTION_MAX_WIDTH_SECONDS = 0.6
DEFLECTION_MIN_SPACING_SECONDS = 0.3
# One blink produces several hits 0.2-0.6 s apart: the bandpass turns it
# into an up-then-down swing, and it can show on both pair averages. With
# hits merged against the last one kept, 0.8 s counts one blink once:
# session 30's blinking half read 141 raw hits/min, 95 at 0.4 s, 57 at 0.8 s,
# while the quiet halves barely move (22: 6.3 at 0.4 s, 5.3 at 0.8 s). It
# also caps the count at 75/min, which is plenty for a between-halves
# comparison.
DEFLECTION_MERGE_SECONDS = 0.8
DEFLECTION_MIN_HALF_SECONDS = 20.0  # below this a per-minute rate is too noisy to report


@dataclass
class ElectrodeFinding:
    """One sign of a poorly seated electrode, in one half - see the
    ELECTRODE_* constants. `excluded` is whether the channel was left out of
    the spectrum (for this or any other finding), so a warning-only finding
    on an excluded channel doesn't read as "flagged but still used"."""
    channel: int
    channel_name: str
    half: str
    sign: str  # "interference_lines" | "upside_down_spectrum" | "no_signal" | "much_noisier_than_partner"
    value: float  # log10: line height, 25-45 minus 1-2 Hz power, or the gap to the partner
    excluded: bool
    partner_name: str | None = None  # set for much_noisier_than_partner only


# The two signs strong enough to exclude a channel on their own.
EXCLUDING_SIGNS = frozenset({"interference_lines", "upside_down_spectrum", "no_signal"})


@dataclass
class _EogWindow:
    center_seconds: float
    frontal_ptp: float
    is_localized: bool
    is_conjugate: bool


def _bandpass_eog(samples: np.ndarray, sfreq: float) -> np.ndarray:
    """4th-order Butterworth bandpass (EOG_LOW_HZ-EOG_HIGH_HZ), zero-phase
    (sosfiltfilt, so a detected SEM's timing isn't shifted) — deliberately
    separate from _filter()'s 1-45Hz pass above, since that high-pass would
    remove exactly the sub-1Hz content SEMs live in. `samples` is
    (n_samples, n_channels), same orientation as the wire format / the
    NaN-interpolated array analyze_session_eeg already has on hand — filtered
    along axis 0."""
    sos = butter(4, [EOG_LOW_HZ, EOG_HIGH_HZ], btype="bandpass", fs=sfreq, output="sos")
    return sosfiltfilt(sos, samples, axis=0)


def _eog_candidate_windows(
    interpolated: np.ndarray, bad_mask: np.ndarray, sfreq: float, excluded_channels: frozenset[int] = frozenset()
) -> list[_EogWindow]:
    """Splits the WHOLE recording (both halves at once — the amplitude
    threshold in analyze_session_eeg needs a session-wide distribution to be
    relative to, same reasoning as _epoch_and_reject's PTP_REJECT_MAD_MULTIPLE)
    into non-overlapping EOG_WINDOW_SECONDS windows and computes, per window,
    the frontal peak-to-peak amplitude plus the two independent checks
    (localization to the front of the head, conjugate movement between the
    two frontal channels) that distinguish a real SEM candidate from a
    generic artifact — see the EOG_* constants' comments for why each check
    exists. Does NOT itself decide which windows count as SEMs (no amplitude
    threshold applied here) — that's the caller's job, once it has this
    window's amplitude alongside every other window's.

    Returns [] if this recording's channel layout doesn't have two frontal
    channels to cross-check, or is too short for the low-frequency filter's
    padding requirement — both treated as "nothing to score," not an error,
    since this is an ancillary marker and must never fail the whole
    analysis."""
    n_channels = interpolated.shape[1]
    frontal_idx = [i for i in EOG_FRONTAL_CHANNEL_INDICES if i < n_channels]
    # An ear electrode the electrode check excluded would make every frontal
    # deflection look small next to its interference, so it's left out of
    # the localization check (analyze_session_eeg doesn't call this at all
    # when both are excluded).
    temporal_idx = [i for i in EOG_TEMPORAL_CHANNEL_INDICES if i < n_channels and i not in excluded_channels]
    win = int(round(EOG_WINDOW_SECONDS * sfreq))
    if len(frontal_idx) < 2 or interpolated.shape[0] < win:
        return []

    try:
        filtered = _bandpass_eog(interpolated, sfreq)
    except ValueError:
        logger.warning("EOG bandpass failed (recording too short for the filter's padding) — skipping SEM scoring")
        return []

    windows: list[_EogWindow] = []
    for start in range(0, filtered.shape[0] - win + 1, win):
        end = start + win
        # Same BAD_SAMPLE_FRACTION_REJECT cut _epoch_and_reject uses, reused
        # here for consistency — skip a window where the COMBINED bad-contact
        # fraction across the frontal OR temporal channels (in the ORIGINAL,
        # pre-interpolation data) is too high. Temporal is checked too, not
        # just frontal: a mostly-bad temporal channel
        # comes out low-variance after NaN interpolation, which would
        # otherwise trip the temporal_ptp<=1e-12 fallback below and
        # auto-pass is_localized regardless of whether the frontal deflection
        # is actually localized — exactly the situation (bad contact) most
        # likely to also produce the whole-head drift artifact that check
        # exists to catch.
        frontal_bad_frac = bad_mask[start:end][:, frontal_idx].mean()
        temporal_bad_frac = bad_mask[start:end][:, temporal_idx].mean() if temporal_idx else 0.0
        if frontal_bad_frac > BAD_SAMPLE_FRACTION_REJECT or temporal_bad_frac > BAD_SAMPLE_FRACTION_REJECT:
            continue

        af7, af8 = filtered[start:end, frontal_idx[0]], filtered[start:end, frontal_idx[1]]

        if np.std(af7) < 1e-12 or np.std(af8) < 1e-12:
            correlation = 0.0  # a flat (near-constant) channel can't be conjugate with anything
        else:
            correlation = float(np.corrcoef(af7, af8)[0, 1])
        is_conjugate = abs(correlation) >= EOG_MIN_CORRELATION

        # Phase-aware combination. Real SEMs can be IN-PHASE (roughly
        # vertical movement) or ANTI-PHASE (roughly horizontal — the
        # corneo-retinal dipole moves toward one frontal electrode and away
        # from the other), which is why is_conjugate above accepts
        # |correlation| in EITHER direction. A naive (af7+af8)/2 average
        # CANCELS an anti-phase pair, silently undercounting horizontal SEMs.
        # Flipping af8's sign when the two are anti-correlated makes them
        # combine constructively either way.
        sign = -1.0 if correlation < 0 else 1.0
        frontal_combined = (af7 + sign * af8) / 2.0
        frontal_ptp = float(np.ptp(frontal_combined))

        if temporal_idx:
            temporal_ptp = float(np.ptp(filtered[start:end, temporal_idx].mean(axis=1)))
        else:
            temporal_ptp = 0.0
        is_localized = temporal_ptp <= 1e-12 or (frontal_ptp / temporal_ptp) >= EOG_FRONTAL_TEMPORAL_RATIO_MIN

        windows.append(_EogWindow(
            center_seconds=(start + win / 2.0) / sfreq,
            frontal_ptp=frontal_ptp,
            is_localized=is_localized,
            is_conjugate=is_conjugate,
        ))
    return windows


def _score_eog_half(
    windows: list[_EogWindow], amplitude_threshold: float, start_seconds: float, end_seconds: float
) -> tuple[float | None, int, float]:
    """Rate (SEMs/minute) for one half's windows, using a threshold the
    caller already derived from the WHOLE session (see EOG_AMPLITUDE_MAD_
    MULTIPLE's comment on why it has to be session-wide, not per-half).
    Returns (None, windows_analyzed, 0.0) rather than (0.0, ..., ...) below
    MIN_EOG_WINDOWS_PER_HALF — too little clean frontal signal to trust a
    rate, distinct from "trusted, and it came out low."

    The third element (flagged_fraction) feeds the switched-off eye
    correction's EOG_CONTAMINATION_FLAGGED_FRACTION trigger — kept 0.0
    whenever windows_analyzed is too low to trust a rate at all, same
    "informative absence, not zero" reasoning as the rate itself: too little
    signal to score is not evidence of a clean half either, so it must not
    look identical to a half that was actually scored and found clean."""
    half_windows = [w for w in windows if start_seconds <= w.center_seconds < end_seconds]
    if len(half_windows) < MIN_EOG_WINDOWS_PER_HALF:
        return None, len(half_windows), 0.0
    flagged = sum(
        1 for w in half_windows
        if w.is_localized and w.is_conjugate and w.frontal_ptp >= amplitude_threshold
    )
    minutes = (len(half_windows) * EOG_WINDOW_SECONDS) / 60.0
    rate = flagged / minutes if minutes > 0 else None
    return rate, len(half_windows), flagged / len(half_windows)


def _line_height(freqs: np.ndarray, log_psd: np.ndarray, f0: float) -> float | None:
    """log10 height of the tallest bin within 0.5 Hz of f0 above the median of
    the bins 0.75-2.5 Hz away - a narrow line stands tall against its own
    neighbourhood, where a broad brain rhythm raises the neighbourhood too.
    None when f0 is too close to the edge of the spectrum for the
    neighbourhood to exist."""
    near = np.abs(freqs - f0) <= 0.5
    ring = (np.abs(freqs - f0) > 0.75) & (np.abs(freqs - f0) <= 2.5)
    if not near.any() or ring.sum() < 4:
        return None
    return float(log_psd[near].max() - np.median(log_psd[ring]))


def _band_log_power(freqs: np.ndarray, psd: np.ndarray, lo: float, hi: float) -> float:
    return float(np.log10(psd[(freqs >= lo) & (freqs < hi)].mean()))


def _check_electrodes(
    data: np.ndarray, sfreq: float, cut_sample: int, labels: tuple[str, str]
) -> tuple[list[ElectrodeFinding], frozenset[int], bool]:
    """The electrode check (see the ELECTRODE_* constants). `data` is the
    FILTERED recording, (n_channels, n_samples), before any epoch rejection
    - measured the same way as the recordings the thresholds came from.

    Returns (findings, channels to exclude from both halves, whether every
    channel was flagged). Channels that are entirely NaN-interpolated gaps
    are skipped: they have no spectrum to judge, and the NaN counters
    already report them."""
    n_channels = data.shape[0]
    nperseg = int(round(EPOCH_SECONDS * sfreq))
    fundamental = sfreq / ELECTRODE_LINE_FUNDAMENTAL_DIVISOR
    findings: list[ElectrodeFinding] = []
    for label, seg in ((labels[0], data[:, :cut_sample]), (labels[1], data[:, cut_sample:])):
        if seg.shape[1] < nperseg:
            continue  # shorter than one window; _fit_half will refuse this half anyway
        freqs, psd = welch(seg, fs=sfreq, nperseg=nperseg, axis=1)
        high = {}
        for ch in range(n_channels):
            if not np.all(np.isfinite(psd[ch])):
                continue  # an all-NaN channel: the NaN counters already report it
            band = (freqs >= BANDPASS_LOW_HZ) & (freqs <= BANDPASS_HIGH_HZ)
            if np.median(psd[ch][band]) < 10.0 ** ELECTRODE_FLAT_LOG10:
                # Flat or constant: nothing to judge, and no spectrum to use.
                findings.append(ElectrodeFinding(ch, CHANNEL_NAMES[ch], label, "no_signal", 0.0, False))
                continue
            log_psd_ch = np.log10(np.maximum(psd[ch], 1e-300))
            heights = [
                h for k in ELECTRODE_LINE_HARMONICS
                if (h := _line_height(freqs, log_psd_ch, k * fundamental)) is not None
            ]
            if heights and max(heights) >= ELECTRODE_LINE_EXCLUDE_LOG10:
                findings.append(
                    ElectrodeFinding(ch, CHANNEL_NAMES[ch], label, "interference_lines", max(heights), False)
                )
            high[ch] = _band_log_power(freqs, psd[ch], 25.0, 45.0)
            inversion = high[ch] - _band_log_power(freqs, psd[ch], 1.0, 2.0)
            if inversion >= 0:
                findings.append(
                    ElectrodeFinding(ch, CHANNEL_NAMES[ch], label, "upside_down_spectrum", inversion, False)
                )
        for a, b in ELECTRODE_PAIRS:
            if a in high and b in high and abs(high[a] - high[b]) >= ELECTRODE_PAIR_WARN_LOG10:
                loud, quiet = (a, b) if high[a] > high[b] else (b, a)
                findings.append(ElectrodeFinding(
                    loud, CHANNEL_NAMES[loud], label, "much_noisier_than_partner",
                    high[loud] - high[quiet], False, partner_name=CHANNEL_NAMES[quiet],
                ))

    flagged = frozenset(f.channel for f in findings if f.sign in EXCLUDING_SIGNS)
    # Counted by the same test the loop uses (a finite signal), so a flat
    # channel - flagged no_signal above - can't be the one "unflagged"
    # channel everything else gets excluded in favour of.
    measurable = sum(1 for ch in range(n_channels) if np.all(np.isfinite(data[ch])))
    all_flagged = bool(flagged) and len(flagged) >= measurable
    excluded = frozenset() if all_flagged else flagged
    for f in findings:
        f.excluded = f.channel in excluded
    return findings, excluded, all_flagged


def _deflection_times(interpolated: np.ndarray, sfreq: float, excluded_channels: frozenset[int]) -> np.ndarray | None:
    """Times (seconds) of large slow deflections across the whole recording
    - see the DEFLECTION_* constants. `interpolated` is the NaN-interpolated,
    otherwise unfiltered (n_samples, n_channels) array, like the SEM scorer's.
    None when no electrode pair has a usable channel left or the recording is
    too short for the filter."""
    n_channels = interpolated.shape[1]
    groups = [
        [
            ch for ch in pair
            if ch < n_channels and ch not in excluded_channels and np.all(np.isfinite(interpolated[:, ch]))
        ]
        for pair in ELECTRODE_PAIRS
    ]
    groups = [g for g in groups if g]
    if not groups:
        return None
    try:
        sos = butter(4, [DEFLECTION_LOW_HZ, DEFLECTION_HIGH_HZ], btype="bandpass", fs=sfreq, output="sos")
        filtered = sosfiltfilt(sos, interpolated, axis=0)
    except ValueError:
        logger.warning("Deflection bandpass failed (recording too short for the filter's padding)")
        return None
    width = (int(round(DEFLECTION_MIN_WIDTH_SECONDS * sfreq)), int(round(DEFLECTION_MAX_WIDTH_SECONDS * sfreq)))
    spacing = max(1, int(round(DEFLECTION_MIN_SPACING_SECONDS * sfreq)))
    hits: list[float] = []
    for group in groups:
        signal = filtered[:, group].mean(axis=1)
        if not np.all(np.isfinite(signal)):
            continue
        robust_sd = 1.4826 * np.median(np.abs(signal - np.median(signal)))
        if robust_sd <= 0:
            continue
        for polarity in (1.0, -1.0):
            peaks, _ = find_peaks(
                polarity * signal, height=DEFLECTION_MAD_MULTIPLE * robust_sd, distance=spacing, width=width
            )
            hits.extend(peaks / sfreq)
    # Merged against the last hit KEPT, not the previous raw hit, so a chain
    # of hits each 0.3-0.4 s apart doesn't collapse into one.
    merged: list[float] = []
    for t in sorted(hits):
        if not merged or t - merged[-1] > DEFLECTION_MERGE_SECONDS:
            merged.append(t)
    return np.array(merged)


def _deflection_rate(
    times: np.ndarray | None,
    bad_mask: np.ndarray,
    used_channels: list[int],
    sfreq: float,
    start_seconds: float,
    end_seconds: float,
) -> float | None:
    """Deflections per minute of this half's USABLE time: seconds where any
    measured channel was an interpolated NaN gap are left out of both the
    count and the minutes, the same way the SEM rate divides by the windows
    it actually scored - otherwise a dropout-heavy half would read as calmer
    than it was."""
    if times is None:
        return None
    start, end = int(round(start_seconds * sfreq)), int(round(end_seconds * sfreq))
    usable = ~bad_mask[start:end][:, used_channels].any(axis=1) if used_channels else np.ones(end - start, bool)
    usable_seconds = usable.sum() / sfreq
    if usable_seconds < DEFLECTION_MIN_HALF_SECONDS:
        return None
    in_half = times[(times >= start_seconds) & (times < end_seconds)]
    sample_idx = np.clip((in_half * sfreq).astype(int) - start, 0, max(len(usable) - 1, 0))
    count = int(usable[sample_idx].sum()) if len(usable) else 0
    return count / (usable_seconds / 60.0)
