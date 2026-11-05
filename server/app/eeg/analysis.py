"""Server-side aperiodic-correction pipeline. docs/eeg-aperiodic-correction.md
has the full "why". Runs on demand (POST /sessions/{id}/eeg/analyze), never
automatically.

Pipeline:
    1. Interpolate bad-contact (NaN) gaps, then notch mains and bandpass
       ~1-45 Hz.
    2. Electrode check (_check_electrodes): a poorly seated electrode picks
       up narrow interference lines that the channel average would let
       dominate the spectrum (a false ~8.6 Hz "alpha" peak in sessions
       27/28). Such channels are excluded from both halves.
    3. Eye-activity measures (slow eye movements and large slow
       deflections, see artifacts.py), computed per half so a half with
       more eye activity can be FLAGGED rather than compared as if clean.
    4. Overlapping epochs, bad-contact and amplitude rejection, Welch PSD.
    5. One specparam fit per half: aperiodic offset/exponent, plus whether a
       real oscillatory peak exists per band.

Halves split on the recorded baseline cutoff (TcmSession.
baseline_duration_seconds) when there is one inside the recording, labeled
"baseline"/"treatment", so a session is compared against its own resting
state rather than population norms. Otherwise they split at the midpoint,
labeled "first"/"second".

specparam is pinned in requirements.txt to a 2.0 pre-release (specparam is
fooof's current name); see that file for why the version matters.

There is a frontal eye-movement CORRECTION here (regressing AF7/AF8 out of
the ear channels), but it is OFF: EOG_CORRECTION_ENABLED explains why.
"""

import logging
from dataclasses import dataclass

import mne
import numpy as np
from scipy.signal import welch
from specparam import SpectralModel
from specparam.modutils.errors import SpecParamError

from ..config import settings
from .artifacts import (
    EOG_AMPLITUDE_MAD_MULTIPLE,
    EOG_AMPLITUDE_REFERENCE_PERCENTILE,
    EOG_FRONTAL_CHANNEL_INDICES,
    EOG_TEMPORAL_CHANNEL_INDICES,
    ElectrodeFinding,
    _check_electrodes,
    _deflection_rate,
    _deflection_times,
    _eog_candidate_windows,
    _score_eog_half,
)
from .binary import EegBlob
from .params import (
    BAD_SAMPLE_FRACTION_REJECT,
    BAND_RANGES,
    BANDPASS_HIGH_HZ,
    BANDPASS_LOW_HZ,
    CHANNEL_NAMES,
    EPOCH_OVERLAP,
    EPOCH_SECONDS,
    MIN_EPOCHS_PER_HALF,
    PTP_REJECT_MAD_MULTIPLE,
)

logger = logging.getLogger("smartring.eeg_analysis")

# ---- Frontal-channel correction for sustained eye movement (OFF) ----
#
# Sustained rhythmic eye movement can steepen the aperiodic exponent with no
# drowsiness involved: the movement's harmonics (a ~0.5 Hz sweep puts energy
# at 1.0/1.5 Hz) land inside the 1-45 Hz analysis band. A uniformly elevated
# half has no outlier epochs relative to its own median, so the amplitude
# rejection in _epoch_and_reject can't catch it.
#
# The correction regresses both frontal channels out of the ear channels
# (_regress_out_channels) and drops the frontal pair from the spectrum, for
# BOTH halves once either half's flagged-SEM fraction reaches
# EOG_CONTAMINATION_FLAGGED_FRACTION; one channel set for both halves, since
# a before/after comparison across two channel sets is its own confound.
# 0.30 separated August sessions 30 and 32 (~0.78 of windows flagged,
# triggered) from 31 (0.18, not triggered). (Session numbers restarted
# after a database reset, so the notebook dates them: these three are
# August's, the ones below September's.)
#
# It is OFF because on hardware it failed: it damaged a half with no eye
# movement (September session 26's still half fit at r² 0.416 with it, 0.907
# without), made September session 30's blink shift worse (+0.74 to +1.09),
# and couldn't remove
# blinks that showed mainly on the ear channels. Eye activity is FLAGGED
# instead (interpretation.eye_activity_differs). The code stays only so
# tools/eeg_variants.py can patch this True and reproduce the old output on
# stored recordings. Don't switch it back on without new evidence that it
# removes eye artifact without damaging clean halves.
EOG_CONTAMINATION_FLAGGED_FRACTION = 0.30
EOG_CORRECTION_ENABLED = False


class EegAnalysisError(ValueError):
    """Data-quality problem (not enough clean signal, degenerate rate, ...) —
    distinct from eeg_binary.EegBinaryError's "the upload itself is
    malformed". Routers turn this into a 422, not a 400."""


@dataclass
class BandPeak:
    band: str
    center_frequency: float
    power: float
    bandwidth: float


@dataclass
class HalfResult:
    half: str  # "first" | "second" | "baseline" | "treatment"
    aperiodic_offset: float
    aperiodic_exponent: float
    r_squared: float
    epoch_count: int
    peaks: list[BandPeak]
    # Signal-derived arousal marker, independent of self-report — see the
    # EOG_* constants above. None means "not enough clean signal this half to
    # score" (fewer than MIN_EOG_WINDOWS_PER_HALF, or the electrode check
    # excluded a forehead electrode or both ear electrodes), which is an
    # informative absence (same pattern as an empty `peaks` list), not a rate
    # of zero. eog_windows_analyzed is 0 in that case too, so a reader can
    # tell "not enough data" from "scored, and the rate happens to be low"
    # without re-deriving it.
    eog_sem_rate_per_min: float | None = None
    eog_windows_analyzed: int = 0
    # How much of this half didn't make it into the spectrum, and why. Kept
    # as three numbers rather than one "rejected" total because they mean
    # different things to whoever reads the card: bad_contact is the
    # headband sending empty (NaN) readings - a lifted electrode that still
    # sends numbers is NOT counted (session 24) - amplitude is the patient
    # moving or clenching (or a loose electrode being handled),
    # and partial_contact is neither — those epochs were KEPT, just measured
    # by fewer electrodes than the headband has. Summing them would throw
    # away the diagnosis along with the count.
    epochs_rejected_bad_contact: int = 0
    epochs_rejected_amplitude: int = 0
    epochs_partial_contact: int = 0
    # The spectrum this half's numbers were read off, and the fit to it — see
    # _spectrum_arrays. Always set by _fit_half; the default exists only
    # because a defaulted field can't precede the ones above.
    spectrum: dict[str, list[float]] | None = None
    # Large slow deflections per minute (blinks, eye or head movement) - see
    # the DEFLECTION_* constants. None when the half is too short to rate or
    # no electrode pair was left to measure on, not a rate of zero.
    deflection_rate_per_min: float | None = None


@dataclass
class AnalysisResult:
    """Both halves plus the session-wide quality context they were computed
    in. Separate from HalfResult because these are properties of the
    RECORDING, not of either half: the sample rate the headband actually
    achieved, how much of the signal had to be interpolated over bad
    contact, which electrodes were excluded (from both halves at once, by
    design), and the electrode-check findings behind that. Reported so a
    practitioner can see when, say, two of four channels were left out.

    excluded_channel_indices comes from the electrode check (plus, only if
    EOG_CORRECTION_ENABLED is patched on, the old eye correction's frontal
    pair). all_electrodes_flagged means every channel showed an excluding
    sign, so none could be excluded."""

    halves: list[HalfResult]
    observed_sample_rate_hz: float
    interpolated_sample_fraction: float
    excluded_channel_indices: list[int]
    electrode_findings: list[ElectrodeFinding]
    all_electrodes_flagged: bool = False
    # Whether the old eye correction ran - only ever True when
    # EOG_CORRECTION_ENABLED is patched on (tools/eeg_variants.py).
    eye_correction_applied: bool = False


def _interpolate_nan_gaps(samples: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Linearly interpolates NaN (bad-contact) samples per channel, and
    returns a `bad_mask` (n_samples, n_channels) — PER CHANNEL, not
    collapsed to "any channel bad" — marking every sample/channel that was
    originally NaN.

    This has to happen BEFORE filtering, not after: mne's FIR filter
    convolves a wide kernel across the signal, and convolving across even one
    NaN sample turns it into NaN's math contagion — it doesn't skip the gap,
    it propagates it forward and backward across the whole kernel width (a
    few thousand samples at a 1Hz high-pass), which at real Muse S dropout
    rates (isolated short runs of NaNs) wipes out nearly every epoch.
    Interpolating first
    keeps the filter well-behaved; `bad_mask` (computed from the ORIGINAL
    data, before interpolation) is what actually gates epoch rejection in
    _epoch_and_reject — kept per-channel (rather than "any channel bad ->
    whole sample bad") so one permanently-poor-contact channel (plausible
    with the Muse S's dry electrodes) doesn't reject every epoch outright;
    _epoch_and_reject instead drops just that channel from epochs it
    contaminates, mirroring the same "drop the bad channel, average what's
    left" pattern SessionRecorder.kt already uses for band power."""
    bad_mask = np.isnan(samples)
    if not bad_mask.any():
        return samples, bad_mask
    out = samples.copy()
    idx = np.arange(samples.shape[0])
    for c in range(samples.shape[1]):
        col = out[:, c]
        col_bad = np.isnan(col)
        if not col_bad.any():
            continue
        good = ~col_bad
        if good.sum() < 2:
            # Whole channel is unusable (never enough good samples to
            # interpolate from) — leave it NaN; every epoch's bad_mask
            # fraction will reflect that and get rejected below.
            continue
        col[col_bad] = np.interp(idx[col_bad], idx[good], col[good])
    return out, bad_mask


def _build_raw(samples: np.ndarray, sfreq: float) -> mne.io.RawArray:
    """`samples` is (n_samples, n_channels) as parsed from the wire format;
    mne wants (n_channels, n_times) in VOLTS — the Muse SDK documents its EEG
    channel values in microvolts, hence the 1e-6 scale (mne's own file
    readers do the same conversion for microvolt-native hardware)."""
    ch_names = [f"EEG{i + 1}" for i in range(samples.shape[1])]
    info = mne.create_info(ch_names, sfreq=sfreq, ch_types="eeg", verbose=False)
    volts = samples.T.astype(np.float64) * 1e-6
    return mne.io.RawArray(volts, info, verbose=False)


def _filter(raw: mne.io.RawArray) -> None:
    """In place: notch mains hum, then bandpass to the band range this app
    actually reports on. Notch first, so a
    mains harmonic near the passband edge doesn't need the bandpass filter's
    rolloff alone to remove it."""
    nyquist = raw.info["sfreq"] / 2.0
    mains = settings.eeg_mains_hz
    if 0 < mains < nyquist:
        raw.notch_filter(freqs=[mains], verbose=False)
    else:
        # Only possible with a very low sample rate (well under the Muse S's
        # ~256Hz) or a misconfigured EEG_MAINS_HZ — skip rather than let mne
        # raise, and let the bandpass below do what it can.
        logger.warning("Skipping mains notch: %sHz is not below Nyquist (%sHz)", mains, nyquist)
    raw.filter(l_freq=BANDPASS_LOW_HZ, h_freq=BANDPASS_HIGH_HZ, verbose=False, fir_design="firwin")


def _regress_out_channels(data: np.ndarray, regressor_idx: list[int], target_idx: list[int]) -> np.ndarray:
    """OLS-regresses each `target_idx` channel against ALL `regressor_idx`
    channels together (a multi-regressor design, not one proxy per channel —
    see below) plus an intercept, and returns a copy of `data` with those
    target channels replaced by their residual (regressor-driven component
    removed, own mean preserved via the intercept term). `data` is
    (n_channels, n_samples), mne's orientation, already filtered to the same
    band the regressors and targets are compared in — see the call site's
    comment for why that ordering matters.

    Using BOTH frontal channels as separate regressors (rather than a single
    combined "EOG proxy," which is what `_eog_candidate_windows` needs
    because it does an amplitude threshold check) means OLS finds each
    channel's own signed weight — a genuinely anti-phase (horizontal-
    eye-movement) leak into one temporal channel and an in-phase leak into
    the other are both fit correctly in the same pass, without the
    correlation-sign check `_eog_candidate_windows` needs for its combined
    proxy.

    Only runs when EOG_CORRECTION_ENABLED is patched on."""
    regressors = data[regressor_idx, :].T  # (n_samples, n_regressors)
    design = np.column_stack([regressors, np.ones(regressors.shape[0])])
    out = data.copy()
    for ch in target_idx:
        y = data[ch, :]
        beta, *_ = np.linalg.lstsq(design, y, rcond=None)
        fitted = design @ beta
        out[ch, :] = y - (fitted - beta[-1])  # remove only the regressor-driven part; keep the channel's own mean
    return out


@dataclass
class _Epoch:
    center_seconds: float
    data: np.ndarray  # (n_channels, n_epoch_samples)
    # Which channels are usable IN THIS EPOCH (bad-contact fraction below
    # BAD_SAMPLE_FRACTION_REJECT for this window specifically) — a channel
    # can be good in one epoch and bad in another. Always has at least one
    # True (an epoch with zero usable channels is dropped, never kept with
    # an all-False mask).
    channel_mask: np.ndarray
    # True when this epoch was KEPT but with fewer channels than were
    # available to it — i.e. bad contact cost it a channel without costing
    # it the whole epoch. Measured against the channels this epoch was
    # allowed (so a channel the electrode check excluded doesn't make every
    # epoch look partial). Distinct from rejection: the epoch still feeds
    # the spectrum, just from a thinner slice of the headband.
    partial_contact: bool


@dataclass
class _RejectedEpoch:
    """An epoch that did NOT make it into the spectrum, and why. Keeps its
    center timestamp so the counts can be attributed to the half it would
    have landed in — "the eyes-open half lost 9 windows to movement" is a
    usable statement about a recording; a single session-wide total isn't."""
    center_seconds: float
    reason: str  # "bad_contact" | "amplitude"


def _epoch_and_reject(
    raw: mne.io.RawArray, bad_mask: np.ndarray, excluded_channels: frozenset[int] = frozenset()
) -> tuple[list[_Epoch], list[_RejectedEpoch]]:
    """Splits the FILTERED (and pre-filter NaN-interpolated — see
    _interpolate_nan_gaps) recording into overlapping epochs. PER CHANNEL,
    drops any channel whose ORIGINAL bad-contact fraction in this window is
    too high (BAD_SAMPLE_FRACTION_REJECT) from that epoch's channel_mask,
    dropping the whole epoch only if EVERY channel fails that cut. Then a
    second pass drops high-amplitude outlier epochs by robust z-score
    (PTP_REJECT_MAD_MULTIPLE), computed only over each epoch's own usable
    channels. Deliberately simple; the client-side artifact
    packets (MuseArtifactPacket) that already gate the per-second band-power
    samples don't have raw-EEG-aligned timestamps, so this is an
    independent, signal-derived cut, not a reuse of that one.

    [excluded_channels] is normally empty — analyze_session_eeg fills it with
    the electrodes _check_electrodes judged poorly seated (and, only if
    EOG_CORRECTION_ENABLED is patched on, the frontal pair):
    those channel indices are treated as permanently unusable for every
    epoch, on top of the per-epoch bad-contact cut, so they never enter the
    PTP-outlier check below or the PSD average in _average_psd."""
    data = raw.get_data()  # (n_channels, n_samples), volts, NaN-free (interpolated pre-filter)
    sfreq = raw.info["sfreq"]
    win = int(round(EPOCH_SECONDS * sfreq))
    step = max(1, int(round(win * (1 - EPOCH_OVERLAP))))
    n_samples = data.shape[1]
    n_channels = data.shape[0]
    allowed = np.array([i not in excluded_channels for i in range(n_channels)])

    candidates: list[_Epoch] = []
    rejected: list[_RejectedEpoch] = []
    n_allowed = int(allowed.sum())
    for start in range(0, n_samples - win + 1, step):
        center_seconds = (start + win / 2.0) / sfreq
        channel_mask = (bad_mask[start:start + win].mean(axis=0) <= BAD_SAMPLE_FRACTION_REJECT) & allowed
        if not channel_mask.any():
            # Every channel too contaminated (or excluded) in this window.
            rejected.append(_RejectedEpoch(center_seconds=center_seconds, reason="bad_contact"))
            continue
        seg = data[:, start:start + win]
        candidates.append(_Epoch(
            center_seconds=center_seconds,
            data=seg,
            channel_mask=channel_mask,
            partial_contact=bool(channel_mask.sum() < n_allowed),
        ))

    if len(candidates) < MIN_EPOCHS_PER_HALF:
        # Let the caller report "not enough epochs" with the real count. The
        # PTP pass is skipped entirely, so nothing more joins `rejected`.
        return candidates, rejected

    ptp_per_epoch = np.array([np.ptp(c.data[c.channel_mask], axis=1).max() for c in candidates])
    median = np.median(ptp_per_epoch)
    mad = np.median(np.abs(ptp_per_epoch - median)) or 1e-12  # avoid a zero-MAD divide-by-zero
    threshold = median + PTP_REJECT_MAD_MULTIPLE * mad
    kept: list[_Epoch] = []
    for candidate, ptp in zip(candidates, ptp_per_epoch, strict=True):
        if ptp <= threshold:
            kept.append(candidate)
        else:
            rejected.append(_RejectedEpoch(center_seconds=candidate.center_seconds, reason="amplitude"))
    return kept, rejected


def _average_psd(epochs: list[_Epoch], sfreq: float) -> tuple[np.ndarray, np.ndarray]:
    """Welch PSD per epoch per channel, averaged across epochs AND across
    each epoch's own USABLE channels (see _Epoch.channel_mask) into one
    spectrum — same "average across the good EEG channels" simplification
    SessionRecorder.kt already makes for the live relative-band readout,
    kept here for consistency rather than fitting 4 per-channel models that
    no screen shows."""
    win = epochs[0].data.shape[1]
    psds = []
    freqs = None
    for e in epochs:
        f, p = welch(e.data, fs=sfreq, nperseg=win, axis=1)
        freqs = f
        psds.append(p[e.channel_mask].mean(axis=0))  # average this epoch's usable channels only
    return freqs, np.mean(psds, axis=0)


def _spectrum_arrays(model: SpectralModel) -> dict[str, list[float]]:
    """The fitted half's spectrum for the session card's plot, read straight
    off the model rather than recomputed: specparam's own trimmed frequency
    grid (freq_range, so 1-45Hz at the Welch resolution — 0.25Hz for a 4s
    epoch, ~177 points), the power spectrum it actually fit, the full model
    (what r_squared compares that spectrum against), and the aperiodic
    component alone (a straight line in log-log whose slope IS
    aperiodic_exponent). Power values stay log10, the space specparam fits in.

    Rounded because this lands in a JSON column read on every card load, and
    a phone-width plot resolves nothing past 4 decimals of log10 power — so
    an r² recomputed from these arrays matches the stored one to about 1e-5,
    not exactly. An index where any array is non-finite is dropped from all
    four, keeping them aligned without serializing a NaN that JSON can't
    carry. This is a backstop, not a known path: specparam's own input check
    (on by default) already rejects an inf/NaN spectrum before fitting, and
    _fit_half refuses a failed fit before reaching here."""
    freqs = model.data.freqs
    power = model.data.power_spectrum
    full = model.results.model.get_component("full")
    aperiodic = model.results.model.get_component("aperiodic")
    keep = np.isfinite(freqs) & np.isfinite(power) & np.isfinite(full) & np.isfinite(aperiodic)
    return {
        "freqs_hz": np.round(freqs[keep], 3).tolist(),
        "log_power": np.round(power[keep], 4).tolist(),
        "log_model_fit": np.round(full[keep], 4).tolist(),
        "log_aperiodic_fit": np.round(aperiodic[keep], 4).tolist(),
    }


def _fit_half(
    epochs: list[_Epoch],
    sfreq: float,
    half: str,
    eog: tuple[float | None, int],
    rejected: list[_RejectedEpoch],
    deflection_rate: float | None = None,
    report_peaks: bool = True,
) -> HalfResult:
    """`report_peaks` is False only when every electrode was flagged and none
    could be excluded (see _check_electrodes). Then no peaks are reported at
    all: the interference lines use up specparam's peak budget (a synthetic
    test lost a real 10.8 Hz alpha that way), so an empty list here means
    "not evaluable", which the card and summary say from the findings -
    never "no oscillation detected".

    [epochs]' own channel_mask already reflects any channel exclusion. This
    fits ONE model on ONE spectrum and reads BOTH the aperiodic fit and the
    peaks off it, so an excluded electrode is out of both. Fitting peaks
    from a separate, non-excluded spectrum was rejected: whatever got the
    channel excluded (interference lines, or eye-movement harmonics when the
    old correction is patched on) would then show up as a false peak, which
    is worse than a real peak on that channel going unreported. The cost: with
    a channel excluded, an empty band for a rhythm that lived mostly on that
    channel is ambiguous, not a clean "no rhythm", and isn't labeled as such."""
    if len(epochs) < MIN_EPOCHS_PER_HALF:
        raise EegAnalysisError(
            f"Not enough clean EEG in the {half} half to analyze "
            f"({len(epochs)} usable {EPOCH_SECONDS:.0f}s epoch(s), need at least {MIN_EPOCHS_PER_HALF})"
        )

    freqs, psd = _average_psd(epochs, sfreq)
    model = SpectralModel(peak_width_limits=(1.0, 8.0), max_n_peaks=6, verbose=False)
    try:
        model.fit(freqs, psd, freq_range=(BANDPASS_LOW_HZ, BANDPASS_HIGH_HZ))
    except SpecParamError as e:
        # specparam's own input check refuses e.g. an all-zero spectrum (a
        # dead channel as the only one left) - a data-quality 422 like the
        # failed fit below, not an unhandled 500.
        raise EegAnalysisError(f"The spectral model could not fit the {half} half's EEG ({e})")
    # specparam doesn't raise on a failed fit — it clears its results and
    # returns, leaving NaN parameters and no model curve. Refused here as a
    # data-quality 422, not left to fail later: _spectrum_arrays would raise
    # NoModelError (an unhandled 500), and a NaN exponent is a number the
    # card and the summary would present as if it were one.
    if not model.results.has_model:
        raise EegAnalysisError(f"The spectral model could not fit the {half} half's EEG")
    results = model.results.get_results()
    offset, exponent = results.aperiodic_fit  # "fixed" aperiodic mode: [offset, exponent]
    r_squared = results.metrics.get("gof_rsquared", float("nan"))

    peaks: list[BandPeak] = []
    for band, (lo, hi) in BAND_RANGES.items():
        if not report_peaks:
            break  # not evaluable - see this function's docstring
        in_band = [row for row in results.peak_fit if lo <= row[0] < hi]
        if not in_band:
            continue  # no oscillatory peak detected in this band — informative, not an error (see doc)
        cf, power, bandwidth = max(in_band, key=lambda row: row[1])
        peaks.append(BandPeak(band=band, center_frequency=float(cf), power=float(power), bandwidth=float(bandwidth)))

    eog_rate, eog_windows = eog
    return HalfResult(
        half=half,
        aperiodic_offset=float(offset),
        aperiodic_exponent=float(exponent),
        r_squared=float(r_squared),
        epoch_count=len(epochs),
        peaks=peaks,
        eog_sem_rate_per_min=eog_rate,
        eog_windows_analyzed=eog_windows,
        epochs_rejected_bad_contact=sum(1 for r in rejected if r.reason == "bad_contact"),
        epochs_rejected_amplitude=sum(1 for r in rejected if r.reason == "amplitude"),
        epochs_partial_contact=sum(1 for e in epochs if e.partial_contact),
        spectrum=_spectrum_arrays(model),
        deflection_rate_per_min=deflection_rate,
    )


def analyze_session_eeg(blob: EegBlob, baseline_duration_seconds: float | None = None) -> AnalysisResult:
    """Full pipeline entry point: raw parsed blob -> two HalfResults. Splits
    on the recorded baseline cutoff (see module docstring) when one exists
    and actually falls within the recording; otherwise splits at the
    wall-clock midpoint, labeled "first"/"second". An out-of-range cutoff
    (e.g. a baseline longer than a truncated raw upload) degrades to that
    same fallback rather than producing an empty half. Raises
    EegAnalysisError if either half doesn't have enough clean data to fit —
    the caller (routers/eeg.py) turns that into a 422 rather than persisting
    a half-fit result."""
    sfreq = blob.observed_sample_rate_hz
    if sfreq is None or sfreq <= 0:
        raise EegAnalysisError("EEG blob has no usable sample rate (degenerate timestamp span)")

    interpolated, bad_mask = _interpolate_nan_gaps(blob.samples)

    duration = blob.sample_count / sfreq
    if baseline_duration_seconds is not None and 0 < baseline_duration_seconds < duration:
        cutoff = float(baseline_duration_seconds)
        first_label, second_label = "baseline", "treatment"
    else:
        cutoff = duration / 2.0
        first_label, second_label = "first", "second"

    raw = _build_raw(interpolated, sfreq)
    raw.load_data()
    _filter(raw)

    # Electrode check first: its exclusions feed everything below (the SEM
    # scorer, the deflection count, and the spectrum).
    electrode_findings, excluded_channels, all_electrodes_flagged = _check_electrodes(
        raw.get_data(), sfreq, int(round(cutoff * sfreq)), (first_label, second_label)
    )
    if electrode_findings:
        logger.info(
            "Electrode check: %s; excluding %s",
            [(f.channel_name, f.half, f.sign, round(f.value, 2)) for f in electrode_findings],
            [CHANNEL_NAMES[i] for i in sorted(excluded_channels)] or "nothing",
        )

    # EOG/SEM scoring runs on the pre-filter interpolated array (see
    # _bandpass_eog's docstring) and needs a session-wide amplitude
    # distribution for its robust threshold (see EOG_AMPLITUDE_MAD_MULTIPLE),
    # so it's computed once here, before epoching, rather than inside
    # _fit_half per half.
    # An excluded forehead electrode means the SEM scorer, which needs both,
    # would be reading a channel just judged unfit - so it reports "not
    # enough clean signal" instead of a rate built on that channel.
    # An excluded ear electrode is only left out of its localization check -
    # unless both are, which would leave the scorer no way to tell an eye
    # movement from a whole-head artifact, so that's "not enough signal"
    # too. And with every electrode
    # flagged, nothing is clean enough to score.
    temporal_left = set(EOG_TEMPORAL_CHANNEL_INDICES) - excluded_channels
    if all_electrodes_flagged or excluded_channels & set(EOG_FRONTAL_CHANNEL_INDICES) or not temporal_left:
        eog_windows = []
    else:
        eog_windows = _eog_candidate_windows(interpolated, bad_mask, sfreq, excluded_channels)
    if eog_windows:
        ptp_values = np.array([w.frontal_ptp for w in eog_windows])
        # Low-percentile reference, not the median — see
        # EOG_AMPLITUDE_MAD_MULTIPLE's comment for why: a genuinely elevated
        # (drowsy) stretch can plausibly be a large fraction of the whole
        # recording, and a median-based reference gets contaminated well
        # before that, undercounting the very thing this is meant to detect.
        reference = np.percentile(ptp_values, EOG_AMPLITUDE_REFERENCE_PERCENTILE)
        lower = ptp_values[ptp_values <= reference]
        mad = np.median(np.abs(lower - reference)) or 1e-12  # avoid a zero-MAD divide-by-zero
        amplitude_threshold = reference + EOG_AMPLITUDE_MAD_MULTIPLE * mad
    else:
        amplitude_threshold = float("inf")  # no windows to threshold; every half will just score "not enough data"
    eog_first_rate, eog_first_windows, eog_first_frac = _score_eog_half(eog_windows, amplitude_threshold, 0.0, cutoff)
    eog_second_rate, eog_second_windows, eog_second_frac = _score_eog_half(
        eog_windows, amplitude_threshold, cutoff, duration
    )

    # Large slow deflections, from the electrodes the check kept. Counted
    # before the old correction below could drop the forehead pair, so a
    # patched-on correction doesn't change what this measures. Not counted
    # at all when every electrode is flagged: the interference inflates the
    # noise estimate the threshold is relative to, giving a false "no eye
    # activity".
    deflections = None if all_electrodes_flagged else _deflection_times(interpolated, sfreq, excluded_channels)
    # The channels the count actually read (see _deflection_times): not
    # excluded, and not an entirely-empty channel, whose gaps would
    # otherwise make every second look unusable.
    used_channels = [
        ch for ch in range(interpolated.shape[1])
        if ch not in excluded_channels and not bad_mask[:, ch].all()
    ]

    # The old eye correction - OFF, see EOG_CORRECTION_ENABLED. Applied to
    # both halves once either crosses the bar, never decided per half.
    contaminated = EOG_CORRECTION_ENABLED and (
        eog_first_frac >= EOG_CONTAMINATION_FLAGGED_FRACTION
        or eog_second_frac >= EOG_CONTAMINATION_FLAGGED_FRACTION
    )
    if contaminated:
        excluded_channels = excluded_channels | frozenset(EOG_FRONTAL_CHANNEL_INDICES)
        # After _filter, so regressors and targets are compared in the same
        # 1-45 Hz band the leakage lives in. The frontal pair is still left
        # out of the spectrum: it IS the eye-movement signal.
        n_channels = raw.get_data().shape[0]
        target_channels = [
            i for i in range(n_channels) if i not in EOG_FRONTAL_CHANNEL_INDICES and i not in excluded_channels
        ]
        corrected = _regress_out_channels(raw.get_data(), list(EOG_FRONTAL_CHANNEL_INDICES), target_channels)
        raw = mne.io.RawArray(corrected, raw.info, verbose=False)
        logger.info(
            "Sustained eye-movement contamination detected (flagged fractions %.2f/%.2f) — "
            "regressing frontal channels %s out of channels %s for both halves",
            eog_first_frac, eog_second_frac, sorted(EOG_FRONTAL_CHANNEL_INDICES), target_channels,
        )

    epochs, rejected = _epoch_and_reject(raw, bad_mask, excluded_channels)

    # A window that spans a recorded baseline's end holds signal from both
    # periods, so it belongs to neither: assigned by its centre, it carried
    # the start of the treatment into the baseline. The midpoint fallback
    # marks no event, so there it would only cost epochs.
    def straddles(center_seconds: float) -> bool:
        return first_label == "baseline" and abs(center_seconds - cutoff) < EPOCH_SECONDS / 2

    epochs = [e for e in epochs if not straddles(e.center_seconds)]
    rejected = [r for r in rejected if not straddles(r.center_seconds)]
    first_half = [e for e in epochs if e.center_seconds < cutoff]
    second_half = [e for e in epochs if e.center_seconds >= cutoff]
    # Rejected epochs are attributed to the half they WOULD have landed in,
    # on the same cutoff the kept ones use.
    rejected_first = [r for r in rejected if r.center_seconds < cutoff]
    rejected_second = [r for r in rejected if r.center_seconds >= cutoff]

    report_peaks = not all_electrodes_flagged
    return AnalysisResult(
        halves=[
            _fit_half(
                first_half, sfreq, first_label, (eog_first_rate, eog_first_windows), rejected_first,
                _deflection_rate(deflections, bad_mask, used_channels, sfreq, 0.0, cutoff), report_peaks,
            ),
            _fit_half(
                second_half, sfreq, second_label, (eog_second_rate, eog_second_windows), rejected_second,
                _deflection_rate(deflections, bad_mask, used_channels, sfreq, cutoff, duration), report_peaks,
            ),
        ],
        observed_sample_rate_hz=float(sfreq),
        # Fraction of all (sample, channel) readings that arrived as
        # bad-contact NaN and were interpolated over before filtering — the
        # single number for "how much of this recording is reconstruction
        # rather than measurement."
        interpolated_sample_fraction=float(bad_mask.mean()),
        excluded_channel_indices=sorted(excluded_channels),
        electrode_findings=electrode_findings,
        all_electrodes_flagged=all_electrodes_flagged,
        eye_correction_applied=contaminated,
    )
