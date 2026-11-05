"""POST /auth/register sends the code where config says, and says where.

Practitioner codes go to the team inbox (config.practitioner_verification_
inbox) unless it is blank; the apps show code_sent_to instead of guessing.
"""
import pytest

import app.routers.auth as auth_router
from app.config import settings


@pytest.fixture
def sent(monkeypatch):
    outbox = []
    monkeypatch.setattr(auth_router, "send_verification_email", lambda to, code: outbox.append(to))
    return outbox


def _register(client, email, role):
    return client.post("/auth/register", json={"email": email, "password": "pw-123456", "name": "N", "role": role})


def test_a_patient_code_goes_to_their_own_address(client, sent):
    r = _register(client, "pat@example.com", "patient")
    assert r.status_code == 201
    assert r.json()["code_sent_to"] == "pat@example.com" == sent[0]


def test_a_practitioner_code_goes_to_the_team_inbox(client, sent, monkeypatch):
    monkeypatch.setattr(settings, "practitioner_verification_inbox", "team@example.com")
    r = _register(client, "doc@example.com", "practitioner")
    assert r.json()["code_sent_to"] == "team@example.com" == sent[0]


def test_a_blank_inbox_sends_practitioners_their_own_code(client, sent, monkeypatch):
    monkeypatch.setattr(settings, "practitioner_verification_inbox", "")
    r = _register(client, "doc@example.com", "practitioner")
    assert r.json()["code_sent_to"] == "doc@example.com" == sent[0]
