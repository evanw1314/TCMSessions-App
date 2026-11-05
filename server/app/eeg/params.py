"""Pipeline parameters shared by analysis.py and artifacts.py: frequency
bands, filtering, epoching and the Muse channel order."""

# Low frequency to high — matches ai_summary.BAND_ORDER / schemas.BAND_TYPES,
# so a peak found here lines up with the same band names used everywhere
# else band power is discussed in this app.
BAND_RANGES: dict[str, tuple[float, float]] = {
    "delta": (1.0, 4.0),
    "theta": (4.0, 8.0),
    "alpha": (8.0, 13.0),
    "beta": (13.0, 30.0),
    "gamma": (30.0, 45.0),
}

BANDPASS_LOW_HZ = 1.0
BANDPASS_HIGH_HZ = BAND_RANGES["gamma"][1]  # 45.0 — keep the notch/bandpass in sync with the top band

EPOCH_SECONDS = 4.0
EPOCH_OVERLAP = 0.5
# Reject an epoch whose largest peak-to-peak channel amplitude is more than
# this many median-absolute-deviations above the session-half's own median —
# a robust (not a fixed-microvolt-threshold) outlier cut, since a fixed
# threshold tuned for gelled clinical electrodes isn't necessarily right for
# the Muse S's dry ones and nobody's calibrated one for this app yet.
PTP_REJECT_MAD_MULTIPLE = 5.0
# An epoch is dropped if more than this fraction of its samples were
# bad-contact (NaN) in the ORIGINAL data before interpolation (see
# _interpolate_nan_gaps) — a few interpolated samples in a 4s/1024-sample
# epoch is a fine approximation; an epoch mostly made of fabricated values
# isn't real data.
BAD_SAMPLE_FRACTION_REJECT = 0.05
MIN_EPOCHS_PER_HALF = 3  # below this, a specparam fit isn't meaningful

# Muse channel order, see EOG_FRONTAL_CHANNEL_INDICES' comment for the
# montage assumption. Used to name electrodes on the card.
CHANNEL_NAMES = ("TP9", "AF7", "AF8", "TP10")
