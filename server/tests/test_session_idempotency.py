"""POST /sessions is idempotent on client_session_id (routers/sessions.
create_session). The phone resends the same id on every attempt, so a retry
of a save that already reached the server - the phone timed out waiting for
the answer - must get that session back, never a second copy."""
import app.routers.sessions as sessions_router


def _count(client, headers, patient_id):
    return len(client.get(f"/patients/{patient_id}/sessions", headers=headers).json())


def test_a_repeat_returns_the_session_already_saved(client, login, add_patient, session_body):
    h = login()
    p = add_patient(h)
    first = client.post("/sessions", json=session_body(p), headers=h)
    again = client.post("/sessions", json=session_body(p), headers=h)
    assert first.status_code == 201
    assert again.status_code == 200
    assert again.json()["id"] == first.json()["id"]
    assert len(again.json()["markers"]) == 1 and len(again.json()["samples"]) == 1
    assert _count(client, h, p) == 1


def test_the_stored_copy_wins_over_a_differing_repeat(client, login, add_patient, session_body):
    h = login()
    p = add_patient(h)
    client.post("/sessions", json=session_body(p), headers=h)
    extra = [{"offset_seconds": 5, "label": "needles in"}, {"offset_seconds": 60, "label": "added later"}]
    again = client.post("/sessions", json=session_body(p, markers=extra), headers=h)
    assert again.status_code == 200
    assert [m["label"] for m in again.json()["markers"]] == ["needles in"]


def test_the_same_id_for_another_patient_is_refused(client, login, add_patient, session_body):
    h = login()
    p1, p2 = add_patient(h, "A"), add_patient(h, "B")
    client.post("/sessions", json=session_body(p1), headers=h)
    clash = client.post("/sessions", json=session_body(p2), headers=h)
    assert clash.status_code == 409
    assert _count(client, h, p2) == 0


def test_ids_are_per_practitioner(client, login, add_patient, session_body):
    h1, h2 = login("one@example.com"), login("two@example.com")
    mine = client.post("/sessions", json=session_body(add_patient(h1)), headers=h1)
    theirs = client.post("/sessions", json=session_body(add_patient(h2)), headers=h2)
    assert theirs.status_code == 201
    assert theirs.json()["id"] != mine.json()["id"]


def test_clients_without_an_id_are_not_deduplicated(client, login, add_patient, session_body):
    h = login()
    p = add_patient(h)
    a = client.post("/sessions", json=session_body(p, client_session_id=None), headers=h)
    b = client.post("/sessions", json=session_body(p, client_session_id=None), headers=h)
    assert a.status_code == b.status_code == 201
    assert a.json()["id"] != b.json()["id"]


def test_losing_an_insert_race_returns_the_winner(client, login, add_patient, session_body, monkeypatch):
    """Two copies of one save both miss the lookup; the second insert hits
    the unique index and must answer with the first one's session."""
    h = login()
    p = add_patient(h)
    first = client.post("/sessions", json=session_body(p), headers=h)

    real_lookup = sessions_router._session_by_client_id
    lookups = []

    def miss_the_first_lookup(*args):
        lookups.append(args)
        return None if len(lookups) == 1 else real_lookup(*args)

    monkeypatch.setattr(sessions_router, "_session_by_client_id", miss_the_first_lookup)
    raced = client.post("/sessions", json=session_body(p), headers=h)
    assert raced.status_code == 200
    assert raced.json()["id"] == first.json()["id"]
    assert len(lookups) == 2  # the insert really did collide
    assert _count(client, h, p) == 1


def test_an_empty_id_is_rejected(client, login, add_patient, session_body):
    h = login()
    r = client.post("/sessions", json=session_body(add_patient(h), client_session_id=""), headers=h)
    assert r.status_code == 422
