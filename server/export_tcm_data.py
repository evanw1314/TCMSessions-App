"""Export every recorded TCM session to CSV for offline analysis (pandas, R,
Excel, whatever) — a code-level way to get at the data without going through
the app or writing SQL by hand.

Usage: python export_tcm_data.py [output_dir]   (from server/, venv active)
Defaults to writing into server/exports/.

Writes three files:
  sessions.csv — one row per session (who, when, modality, note)
  samples.csv  — one row per band-power reading (the actual EEG data)
  markers.csv  — one row per marker tapped during a session

samples.csv's `value` column: -1.0 is NOT a real reading — it's the client's
NO_SIGNAL_VALUE sentinel for "electrode contact was too poor to compute band
power this tick" (see android/TcmSessionApp Models.kt). Filter those rows out
before any statistics — an average that includes them is wrong.
"""

import csv
import sys
from pathlib import Path

from app.database import SessionLocal
from app.models import TcmSession

out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("exports")
out_dir.mkdir(exist_ok=True)

db = SessionLocal()
sessions = db.query(TcmSession).order_by(TcmSession.started_at).all()

sessions_path = out_dir / "sessions.csv"
with sessions_path.open("w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow([
        "session_id", "patient_email", "patient_name", "practitioner_email",
        "headband_device_id", "modality", "started_at", "ended_at", "practitioner_note",
    ])
    for s in sessions:
        writer.writerow([
            s.id, s.patient.email, s.patient.name, s.practitioner.email,
            s.headband.device_id if s.headband else "",
            s.modality, s.started_at.isoformat(), s.ended_at.isoformat(),
            s.practitioner_note or "",
        ])

samples_path = out_dir / "samples.csv"
with samples_path.open("w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(["session_id", "patient_email", "modality", "offset_seconds", "band", "value"])
    for s in sessions:
        for sample in sorted(s.samples, key=lambda x: (x.offset_seconds, x.band)):
            writer.writerow([s.id, s.patient.email, s.modality, sample.offset_seconds, sample.band, sample.value])

markers_path = out_dir / "markers.csv"
with markers_path.open("w", newline="", encoding="utf-8") as f:
    writer = csv.writer(f)
    writer.writerow(["session_id", "patient_email", "modality", "offset_seconds", "label"])
    for s in sessions:
        for marker in sorted(s.markers, key=lambda x: x.offset_seconds):
            writer.writerow([s.id, s.patient.email, s.modality, marker.offset_seconds, marker.label])

db.close()
print(f"{len(sessions)} sessions -> {sessions_path}")
print(f"samples -> {samples_path}")
print(f"markers -> {markers_path}")
