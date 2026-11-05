"""Seed the database with an example clinic and the dev practitioner
account — no patients. A fresh clinic starts with
an empty patient list; patients are added afterward through the app itself
(POST /patients, or by registering a headband to their email), the same
way a real clinic's roster would grow.

Usage: python seed.py  (from the server/ directory, venv active)
Reseeding: rm smartring.db && python seed.py — then RESTART uvicorn, which
holds the deleted file open.

Practitioner dev login: dr.rivera@example.com (POST /auth/dev, no password —
see AUTH_DEV_MODE), or via the Android app's password sign-in with
DEV_PRACTITIONER_PASSWORD below (POST /auth/login).
"""

from app.database import SessionLocal
from app.migrate import upgrade_to_head
from app.models import Clinic, Role, User
from app.security import hash_password

# Local-only dev password for the seeded practitioner account, so the
# Android app's password sign-in works against a freshly seeded DB without
# a separate registration step. Not a secret worth protecting — this is
# seed data for local development.
DEV_PRACTITIONER_PASSWORD = "abcd1234"


def seed():
    upgrade_to_head()
    db = SessionLocal()
    if db.query(Clinic).first() is not None:
        print("Database already seeded — delete smartring.db to reseed.")
        db.close()
        return

    clinic = Clinic(name="Example Clinic")
    db.add(clinic)
    doctor = User(
        email="dr.rivera@example.com", name="Dr. Sam Rivera",
        role=Role.practitioner, clinic=clinic,
        password_hash=hash_password(DEV_PRACTITIONER_PASSWORD),
    )
    db.add(doctor)
    db.commit()
    print(f"Seeded: 1 clinic, 1 practitioner, 0 patients. "
          f"Practitioner password sign-in: dr.rivera@example.com / "
          f"{DEV_PRACTITIONER_PASSWORD}")
    db.close()


if __name__ == "__main__":
    seed()
