"""Re-runs the EEG analysis on stored raw recordings several ways, to see how
much each step of the frontal eye-movement correction changes each half.

Written after the September blink and slow-eye-movement controls
(sessions 25 and 26), the first deliberate eye-movement controls where the
correction fired, and session 26's STILL half came back at r² 0.416, the worst fit on record,
while its eye-movement half fit fine. The correction is decided and fitted
session-wide, then applied to both halves (see eeg.analysis.analyze_session_eeg
and EOG_CONTAMINATION_FLAGGED_FRACTION), so one candidate cause is regression
weights fitted mostly on the eye-movement half being applied to a clean one.
This tool separates that from the alternatives.

On this tool's evidence the correction is switched OFF in the pipeline
(eeg.analysis.EOG_CORRECTION_ENABLED), replaced by an electrode check and a
deflection count. `shipped` is the current pipeline; the old correction is
reproduced by patching the flag back on.

For each session it prints:

  VARIANTS, one row per half each:
    shipped        the current pipeline, unchanged (matches what the server
                   stored if the session was last analyzed with this code):
                   electrode check on, eye correction off
    old-corr       the pipeline before the electrode check: eye correction
                   on, electrode check off (so it matches what was stored
                   then)
    old-no-regress old-corr's frontal exclusion without the regression
    old-per-half   old-corr with regression weights fitted per half
  The last two only differ from old-corr when the old correction fired;
  when it didn't, the tool says so and skips them. Each row
  also shows the half's deflection and slow-eye-movement rates, and the
  electrode check's findings are listed under the rows.

  PER CHANNEL, per half, on the filtered signal before any regression or
  epoch rejection: mean log10 power (V²/Hz; add 12 for µV²/Hz) at 1-2 Hz,
  8-13 Hz and 25-45 Hz, and "line", the log10 ratio of the biggest bin in
  42-43.5 Hz to the 38-45 Hz median (+1 = 10x). A narrow electrical line near
  42.8 Hz showed up in sessions 23-26. Plain noise alone gives about +0.05 to
  +0.15, so only read well above that as a line.

  VARIANCE REMOVED from each temporal channel in each half by the regression,
  using session-wide weights (what `old-corr` does) versus weights fitted in
  that half alone. Printed only when the old correction would have fired. The right column
  is the best possible fit on that half, so it always wins or ties by
  construction; what matters is the GAP. A NEGATIVE left value means the
  session-wide correction injected frontal signal into that half instead of
  removing any. That's the strongest sign for the hypothesis above, not a
  bug. The raw weights aren't printed: AF7 and AF8 carry largely the same eye
  signal, so their individual weights are unstable and hard to read.

Run from anywhere (it chdirs to server/ like schema_diff.py), passing each
session as ID:SPLIT, where SPLIT is its baseline_duration_seconds, or a bare
ID for a session with no baseline marked:

    cd server && .venv/bin/python tools/eeg_variants.py 25:121 26:120 23:126 24:126

On Windows: .venv\\Scripts\\python.exe tools\\eeg_variants.py ..., with the
.bin files copied into server/data/eeg_raw/.

The split is resolved exactly as analyze_session_eeg does: a SPLIT outside
(0, recording length) falls back to the midpoint, and the header says which
applied.

READ-ONLY: it reads data/eeg_raw/session_<id>.bin and prints. It never opens
the database and never writes anything, so it is safe to run with the server
up. The per-variant runs patch module globals in eeg.analysis for the length
of one call and restore them afterwards; that only affects this process.
"""

import os
import sys
from contextlib import contextmanager
from pathlib import Path

# config.Settings reads `.env` from the current directory; pin it to server/
# before importing the app, same as schema_diff.py. Hence the E402s.
SERVER_DIR = Path(__file__).resolve().parent.parent
os.chdir(SERVER_DIR)
sys.path.insert(0, str(SERVER_DIR))

import numpy as np  # noqa: E402
from scipy.signal import welch  # noqa: E402

from app.eeg import analysis as ea  # noqa: E402
from app.eeg.binary import EegBinaryError, parse_eeg_blob  # noqa: E402
from app.eeg.interpretation import LOW_FIT_R_SQUARED  # noqa: E402
from app.eeg.params import CHANNEL_NAMES  # noqa: E402
from app.eeg.storage import EEG_RAW_DIR  # noqa: E402


@contextmanager
def patched(**overrides):
    """Temporarily replaces eeg.analysis module globals, restoring them even
    if the analysis raises."""
    saved = {name: getattr(ea, name) for name in overrides}
    try:
        for name, value in overrides.items():
            setattr(ea, name, value)
        yield
    finally:
        for name, value in saved.items():
            setattr(ea, name, value)


def _no_regression(data, regressor_idx, target_idx):
    return data.copy()


def _no_electrode_check(data, sfreq, cut_sample, labels):
    """The pipeline before the electrode check existed."""
    return [], frozenset(), False


def _per_half_regression(cut_sample):
    """A drop-in for ea._regress_out_channels that fits and removes the
    frontal->temporal weights separately before and after the split.

    Epochs are assigned to a half by their CENTER, so the one or two 4 s
    epochs straddling the cut include samples from both sides and see a small
    step in the corrected temporal signal. That adds a little broadband power
    to ~2 of ~60 epochs per half: small, but don't over-read a slight
    difference from `shipped` in either half."""
    original = ea._regress_out_channels

    def regress(data, regressor_idx, target_idx):
        out = data.copy()
        out[:, :cut_sample] = original(data[:, :cut_sample], regressor_idx, target_idx)
        out[:, cut_sample:] = original(data[:, cut_sample:], regressor_idx, target_idx)
        return out

    return regress


def _resolve_cutoff(blob, split):
    """The same rule as analyze_session_eeg: a split strictly inside the
    recording is used as-is, anything else falls back to the midpoint.
    Returns (cutoff_seconds, first_label, second_label, description)."""
    duration = blob.sample_count / blob.observed_sample_rate_hz
    if split is not None and 0 < split < duration:
        return float(split), "baseline", "treatment", f"split at marked baseline {split}s"
    reason = "no split given" if split is None else f"split {split}s is outside the {duration:.0f}s recording"
    return duration / 2.0, "first", "second", f"split at midpoint {duration / 2.0:.0f}s ({reason})"


def _alpha(half):
    peak = next((p for p in half.peaks if p.band == "alpha"), None)
    return f"{peak.center_frequency:5.2f}Hz@{peak.power:.2f}" if peak else "none"


def _run_variant(name, blob, split, **overrides):
    """Prints one variant's rows and returns its AnalysisResult (None if the
    pipeline refused the data)."""
    try:
        with patched(**overrides):
            result = ea.analyze_session_eeg(blob, baseline_duration_seconds=split)
    except ea.EegAnalysisError as e:
        print(f"  {name:13} FAILED: {e}")
        return None
    excluded = ",".join(CHANNEL_NAMES[i] for i in result.excluded_channel_indices) or "none"

    def rate(value):
        return "  -  " if value is None else f"{value:5.1f}"

    for half in result.halves:
        flag = " LOW-FIT" if not (half.r_squared >= LOW_FIT_R_SQUARED) else ""
        print(
            f"  {name:14} {half.half:9}  exp {half.aperiodic_exponent:6.3f}  "
            f"r2 {half.r_squared:.3f}{flag:8}  alpha {_alpha(half):13}  "
            f"epochs {half.epoch_count:3}  defl/min {rate(half.deflection_rate_per_min)}  "
            f"SEM/min {rate(half.eog_sem_rate_per_min)}  excluded {excluded}"
        )
    return result


def _print_findings(result):
    if result is None:
        return
    if not result.electrode_findings:
        print("  electrode check: nothing found")
        return
    if result.all_electrodes_flagged:
        print("  electrode check: EVERY channel flagged, so nothing was excluded")
    for f in result.electrode_findings:
        partner = f" (vs {f.partner_name})" if f.partner_name else ""
        print(
            f"  electrode check: {f.half:9} {f.channel_name:4} {f.sign}{partner} {f.value:+.2f}"
            f"{'  -> excluded' if f.excluded else ''}"
        )


def _band_log_power(freqs, psd, lo, hi):
    mask = (freqs >= lo) & (freqs < hi)
    return float(np.log10(psd[mask].mean()))


def _line_prominence(freqs, psd):
    """log10 of (biggest bin in 42-43.5 Hz) / (median of 38-45 Hz)."""
    line = psd[(freqs >= 42.0) & (freqs <= 43.5)]
    around = psd[(freqs >= 38.0) & (freqs <= 45.0)]
    return float(np.log10(line.max() / np.median(around)))


def _residual(target, regressors, beta):
    """target minus the regressor-driven part of a fit (intercept kept), the
    same thing _regress_out_channels computes."""
    design = np.column_stack([regressors.T, np.ones(regressors.shape[1])])
    return target - (design @ beta - beta[-1])


def _fit_beta(target, regressors):
    design = np.column_stack([regressors.T, np.ones(regressors.shape[1])])
    beta, *_ = np.linalg.lstsq(design, target, rcond=None)
    return beta


def _print_channels(blob, cutoff, labels, correction_fired):
    sfreq = blob.observed_sample_rate_hz
    interpolated, _bad = ea._interpolate_nan_gaps(blob.samples)
    raw = ea._build_raw(interpolated, sfreq)
    raw.load_data()
    ea._filter(raw)
    data = raw.get_data()
    cut = int(round(cutoff * sfreq))
    halves = ((labels[0], data[:, :cut]), (labels[1], data[:, cut:]))
    nperseg = int(round(ea.EPOCH_SECONDS * sfreq))
    if min(seg.shape[1] for _, seg in halves) < nperseg:
        # Only reachable with a split within one epoch of either end.
        print("  per channel: skipped, one half is shorter than a single 4 s window")
        return

    print("  per channel (filtered; no regression, no rejection): mean log10 V^2/Hz | 42.8Hz line")
    for label, seg in halves:
        freqs, psd = welch(seg, fs=sfreq, nperseg=nperseg, axis=1)
        cells = []
        for ch, name in enumerate(CHANNEL_NAMES[: data.shape[0]]):
            cells.append(
                f"{name:4} 1-2:{_band_log_power(freqs, psd[ch], 1, 2):6.2f} "
                f"8-13:{_band_log_power(freqs, psd[ch], 8, 13):6.2f} "
                f"25-45:{_band_log_power(freqs, psd[ch], 25, 45):6.2f} "
                f"line:{_line_prominence(freqs, psd[ch]):+5.2f}"
            )
        print(f"    {label:9} " + "\n              ".join(cells))

    if not correction_fired:
        return
    frontal = list(ea.EOG_FRONTAL_CHANNEL_INDICES)
    temporal = [i for i in range(data.shape[0]) if i not in frontal]
    session_betas = {ch: _fit_beta(data[ch], data[frontal]) for ch in temporal}
    print("  temporal variance removed by the regression (session-wide weights | this half's own weights):")
    for label, seg in halves:
        parts = []
        for ch in temporal:
            session_beta = session_betas[ch]
            half_beta = _fit_beta(seg[ch], seg[frontal])
            original_var = seg[ch].var()
            removed = [
                1.0 - _residual(seg[ch], seg[frontal], beta).var() / original_var
                for beta in (session_beta, half_beta)
            ]
            parts.append(f"{CHANNEL_NAMES[ch]} {removed[0]:6.1%} | {removed[1]:6.1%}")
        print(f"    {label:9} " + "    ".join(parts))


def _parse_arg(arg):
    """ID or ID:SPLIT, SPLIT in whole seconds like baseline_duration_seconds.
    Returns (session_id, split_or_None), or None if malformed."""
    session_part, _, split_part = arg.partition(":")
    try:
        return int(session_part), (int(split_part) if split_part else None)
    except ValueError:
        return None


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    # Validate every argument up front, so a typo late in the list doesn't
    # exit after minutes of output for the earlier sessions.
    sessions = []
    for arg in argv:
        parsed = _parse_arg(arg)
        if parsed is None:
            print(f"Bad argument {arg!r}: expected ID or ID:SPLIT_SECONDS, e.g. 26:120")
            return 2
        sessions.append(parsed)
    for session_id, split in sessions:
        path = EEG_RAW_DIR / f"session_{session_id}.bin"
        if not path.exists():
            print(f"=== session {session_id}: no raw EEG at {path}\n")
            continue
        try:
            blob = parse_eeg_blob(path.read_bytes())
        except EegBinaryError as e:
            print(f"=== session {session_id}: unreadable raw EEG ({e})\n")
            continue
        if blob.observed_sample_rate_hz is None:
            print(f"=== session {session_id}: raw EEG has no usable sample rate\n")
            continue

        cutoff, first_label, second_label, how = _resolve_cutoff(blob, split)
        print(
            f"=== session {session_id}  {blob.sample_count} samples @ "
            f"{blob.observed_sample_rate_hz:.2f} Hz  {how}"
        )
        shipped = _run_variant("shipped", blob, split)
        # The old-* rows switch the electrode check off as well as the
        # correction on, so they reproduce what the pipeline stored before
        # the check existed. With the check on, its exclusions change the old
        # correction's input (and with both ears out it can't trigger).
        old_pipeline = {"EOG_CORRECTION_ENABLED": True, "_check_electrodes": _no_electrode_check}
        old = _run_variant("old-corr", blob, split, **old_pipeline)
        correction_fired = old is None or old.eye_correction_applied
        if not correction_fired:
            print("  (the old eye correction wouldn't have fired here, so its other variants match old-corr; skipped)")
        else:
            cut = int(round(cutoff * blob.observed_sample_rate_hz))
            _run_variant("old-no-regress", blob, split, **old_pipeline, _regress_out_channels=_no_regression)
            _run_variant("old-per-half", blob, split, **old_pipeline, _regress_out_channels=_per_half_regression(cut))
        _print_findings(shipped)
        _print_channels(blob, cutoff, (first_label, second_label), correction_fired)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
