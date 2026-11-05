"""The raw-EEG upload format is a contract with the apps' EegBinaryWriter.

testdata/eeg_blob_v1.bin is checked by both sides: Android's
EegBinaryWriterContractTest asserts the writer produces exactly these bytes,
and this asserts the server reads them back as exactly these samples. A
format change on one side alone fails one of the two.
"""
import json
from pathlib import Path

import numpy as np

from app.eeg.binary import parse_eeg_blob

TESTDATA = Path(__file__).resolve().parents[2] / "testdata"


def test_the_shared_fixture_parses_to_its_samples():
    expected = json.loads((TESTDATA / "eeg_blob_v1.json").read_text(encoding="utf-8"))
    blob = parse_eeg_blob((TESTDATA / "eeg_blob_v1.bin").read_bytes())

    rows = expected["samples"]
    assert blob.channel_count == expected["channel_count"]
    assert blob.sample_count == len(rows)
    assert blob.first_timestamp_us == rows[0]["timestamp_us"]
    assert blob.last_timestamp_us == rows[-1]["timestamp_us"]
    values = np.array([[np.nan if v is None else v for v in r["values"]] for r in rows], dtype=np.float32)
    np.testing.assert_array_equal(blob.samples, values)  # NaN == NaN here
