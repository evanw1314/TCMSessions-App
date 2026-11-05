"""Raw EEG end to end through the API: upload -> analyze -> reread -> trend,
on a synthetic recording whose answer is known - 1/f background on all four
channels, plus a 10 Hz rhythm in the treatment half only."""
import struct

import numpy as np
import pytest

RATE = 256.0
SECONDS = 120
BASELINE = 60


def _recording(seconds: float = SECONDS, alpha_after: float | None = BASELINE) -> bytes:
    """eeg/binary.py's format: header, then little-endian float32 samples (µV)."""
    rng = np.random.default_rng(7)
    n = int(seconds * RATE)
    spectrum = rng.normal(size=(4, n // 2 + 1)) + 1j * rng.normal(size=(4, n // 2 + 1))
    freqs = np.fft.rfftfreq(n, 1 / RATE)
    spectrum /= np.maximum(freqs, 1.0)  # 1/f amplitude -> 1/f^2 power
    signal = np.fft.irfft(spectrum, n)
    signal *= 10.0 / signal.std(axis=1, keepdims=True)
    if alpha_after is not None:
        t = np.arange(n) / RATE
        signal += np.where(t >= alpha_after, 8.0 * np.sin(2 * np.pi * 10.0 * t), 0.0)

    first = 1_757_584_800_000_000
    last = first + int((n - 1) / RATE * 1_000_000)
    header = struct.pack("<4sBBHIqq", b"TEEG", 1, 4, 0, n, first, last)
    return header + signal.T.astype("<f4").tobytes()


def _upload(client, headers, session_id, data):
    return client.post(
        f"/sessions/{session_id}/eeg", headers=headers,
        files={"eeg_data": ("eeg.bin", data, "application/octet-stream")},
    )


@pytest.fixture
def recorded(client, login, add_patient, session_body):
    """(headers, patient_id, session_id) for a saved session with a 60 s baseline."""
    h = login()
    p = add_patient(h)
    body = session_body(p, baseline_duration_seconds=BASELINE, ended_at="2026-09-11T10:02:00Z")
    return h, p, client.post("/sessions", json=body, headers=h).json()["id"]


def test_upload_analyze_reread_and_trend(client, recorded):
    h, p, s = recorded
    up = _upload(client, h, s, _recording())
    assert up.status_code == 200
    assert up.json()["sample_count"] == SECONDS * RATE
    assert up.json()["sample_rate_hz"] == pytest.approx(RATE, rel=1e-3)

    r = client.post(f"/sessions/{s}/eeg/analyze", headers=h)
    assert r.status_code == 200, r.text
    analysis = r.json()
    halves = {half["half"]: half for half in analysis["halves"]}
    assert set(halves) == {"baseline", "treatment"}
    for half in halves.values():
        assert half["epoch_count"] >= 3
        assert half["r_squared"] > 0.8
        assert half["aperiodic_exponent"] > 0

    def alpha_peaks(half):
        return [pk for pk in half["peaks"] if pk["band"] == "alpha"]

    [peak] = alpha_peaks(halves["treatment"])
    assert peak["center_frequency"] == pytest.approx(10.0, abs=0.5)
    # The rhythm starts exactly at the cutoff; a window spanning it would
    # leak a 10 Hz peak into the baseline. What's left there is noise.
    assert all(pk["power"] < 0.2 * peak["power"] for pk in alpha_peaks(halves["baseline"]))

    reread = client.get(f"/sessions/{s}/eeg-metrics", headers=h).json()
    assert reread == analysis

    trend = client.get(f"/patients/{p}/eeg-trend", headers=h).json()
    assert [(pt["session_id"], pt["half"]) for pt in trend] == [(s, "baseline"), (s, "treatment")]


def test_a_reupload_clears_the_old_analysis(client, recorded):
    h, _, s = recorded
    _upload(client, h, s, _recording())
    assert client.post(f"/sessions/{s}/eeg/analyze", headers=h).status_code == 200
    _upload(client, h, s, _recording())
    reread = client.get(f"/sessions/{s}/eeg-metrics", headers=h).json()
    assert reread["halves"] == [] and reread["analyzed_at"] is None


def test_analyze_before_any_upload_is_not_found(client, recorded):
    h, _, s = recorded
    r = client.post(f"/sessions/{s}/eeg/analyze", headers=h)
    assert r.status_code == 404
    assert "No raw EEG" in r.json()["detail"]


def test_a_malformed_upload_is_refused(client, recorded):
    h, _, s = recorded
    assert _upload(client, h, s, b"").status_code == 400
    assert _upload(client, h, s, b"not an eeg recording").status_code == 400
    assert _upload(client, h, s, _recording()[:-2]).status_code == 400  # truncated mid-sample


def test_too_little_data_to_fit_is_reported_not_stored(client, recorded):
    h, _, s = recorded
    _upload(client, h, s, _recording(seconds=6, alpha_after=None))
    r = client.post(f"/sessions/{s}/eeg/analyze", headers=h)
    assert r.status_code == 422
    assert client.get(f"/sessions/{s}/eeg-metrics", headers=h).json()["halves"] == []


def test_a_colleague_cannot_upload_analyze_or_read(client, recorded, login):
    h, p, s = recorded
    theirs = login("other@example.com")
    assert _upload(client, theirs, s, _recording()).status_code == 404
    _upload(client, h, s, _recording())
    assert client.post(f"/sessions/{s}/eeg/analyze", headers=theirs).status_code == 404
    client.post(f"/sessions/{s}/eeg/analyze", headers=h).raise_for_status()
    assert client.get(f"/patients/{p}/eeg-trend", headers=h).json() != []
    assert client.get(f"/patients/{p}/eeg-trend", headers=theirs).json() == []


def test_without_a_baseline_the_midpoint_split_keeps_every_window(client, login, add_patient, session_body):
    h = login()
    s = client.post("/sessions", json=session_body(add_patient(h)), headers=h).json()["id"]
    _upload(client, h, s, _recording(alpha_after=None))
    halves = client.post(f"/sessions/{s}/eeg/analyze", headers=h).json()["halves"]
    assert [half["half"] for half in halves] == ["first", "second"]
    # 120 s of 4 s windows every 2 s is 59; none is lost at the midpoint.
    assert sum(half["epoch_count"] + half["epochs_rejected_amplitude"] for half in halves) == 59
