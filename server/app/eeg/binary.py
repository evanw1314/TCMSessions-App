"""Binary wire format for raw EEG upload (POST /sessions/{id}/eeg).

A 45-minute session at ~256Hz x 4 channels is on the order of 10-16MB as
JSON-per-sample numbers — the format below stores it as packed float32 and
skips per-sample timestamps (see the last paragraph), about 1/3 of a naive
"timestamp + 4 floats per row" layout.

Layout (all little-endian, matches Kotlin's ByteBuffer.order(LITTLE_ENDIAN)
writer in Android's EegBinaryWriter.kt):

    offset  size  field
    0       4     magic = b"TEEG"
    4       1     version (=1)
    5       1     channel_count
    6       2     reserved (=0, future use)
    8       4     sample_count (uint32)
    12      8     first_timestamp_us (int64) — Muse SDK packet timestamp of
                  the first sample, in MICROSECONDS (confirmed on hardware)
    20      8     last_timestamp_us (int64) — timestamp of the last sample

    28..    sample_count * channel_count * 4 bytes: float32 samples,
            SAMPLE-MAJOR interleaved: [s0c0, s0c1, ..., s0c(C-1), s1c0, ...].
            NaN (IEEE754) means "bad electrode contact for this sample /
            channel" — the same meaning MuseDataPacket.getEegChannelValue
            already returns, passed through unchanged rather than dropped or
            zeroed, so the server's artifact handling can see it.

No per-sample timestamps: the Muse S's onboard clock is not derived from the
same crystal as the 256Hz ADC sample clock closely enough to matter here, and
storing one int64 per sample would double the payload for no benefit this
pipeline uses — every consumer of this data (mne, specparam) just wants a
constant sample rate. first/last_timestamp_us instead let the server compute
the OBSERVED rate (sample_count / span_seconds), which is what gets fed to
mne.create_info as sfreq — not a hardcoded 256.0.
"""

import struct
from dataclasses import dataclass

import numpy as np

MAGIC = b"TEEG"
VERSION = 1
HEADER_STRUCT = struct.Struct("<4sBBHIqq")  # magic, version, channels, reserved, count, first_ts, last_ts
HEADER_SIZE = HEADER_STRUCT.size  # 28 bytes
BYTES_PER_SAMPLE_VALUE = 4  # float32


class EegBinaryError(ValueError):
    """Malformed or truncated raw-EEG upload — a bad/interrupted multipart
    upload from the phone, not a data-quality problem (that's analysis.py's
    job)."""


@dataclass
class EegBlob:
    channel_count: int
    sample_count: int
    first_timestamp_us: int
    last_timestamp_us: int
    # Shape (sample_count, channel_count) — same sample-major order as the
    # wire format; eeg/analysis.py transposes to mne's (channels, samples)
    # convention itself.
    samples: np.ndarray

    @property
    def span_seconds(self) -> float:
        return (self.last_timestamp_us - self.first_timestamp_us) / 1_000_000.0

    @property
    def observed_sample_rate_hz(self) -> float | None:
        """The rate actually measured on-device, not an assumed nominal
        256Hz — see the module docstring. None only for a degenerate
        (<=1-sample or zero-span) upload, which analyze_session_eeg rejects
        long before this would matter."""
        span = self.span_seconds
        if self.sample_count < 2 or span <= 0:
            return None
        return self.sample_count / span


def parse_eeg_blob(data: bytes) -> EegBlob:
    """Parses and validates one uploaded raw-EEG blob. Raises EegBinaryError
    on anything that doesn't match the exact expected size — a truncated
    upload must fail loudly here, not silently reshape into garbage."""
    if len(data) < HEADER_SIZE:
        raise EegBinaryError(f"EEG upload too short for a header ({len(data)} bytes)")

    magic, version, channel_count, _reserved, sample_count, first_ts, last_ts = (
        HEADER_STRUCT.unpack_from(data, 0)
    )
    if magic != MAGIC:
        raise EegBinaryError(f"Bad magic bytes {magic!r} — not a TEEG blob")
    if version != VERSION:
        raise EegBinaryError(f"Unsupported EEG blob version {version}")
    if channel_count == 0:
        raise EegBinaryError("channel_count is zero")

    expected_size = HEADER_SIZE + sample_count * channel_count * BYTES_PER_SAMPLE_VALUE
    if len(data) != expected_size:
        raise EegBinaryError(
            f"EEG upload size mismatch: header declares {sample_count} samples x "
            f"{channel_count} channels ({expected_size} bytes incl. header), got {len(data)}"
        )

    flat = np.frombuffer(data, dtype="<f4", count=sample_count * channel_count, offset=HEADER_SIZE)
    samples = flat.reshape(sample_count, channel_count)
    return EegBlob(
        channel_count=channel_count,
        sample_count=sample_count,
        first_timestamp_us=first_ts,
        last_timestamp_us=last_ts,
        samples=samples,
    )
