"""Patient roster and headband coverage (routers/patients.py, routers/headbands.py).

A practitioner "covers" a patient through their roster or an active headband
assignment, and every per-patient write is gated on that. These pin both
paths, and that another practitioner gets the same 404 as an unknown id.
"""


def _patient_account(client, email="pat@example.com", name="Pat"):
    r = client.post("/auth/dev", json={"email": email, "role": "patient", "name": name})
    r.raise_for_status()
    return r.json()["user"]["id"], {"Authorization": f"Bearer {r.json()['access_token']}"}


def _assign_headband(client, headers, email, device_id="MuseS-1234"):
    return client.post("/headbands", json={"device_id": device_id, "patient_email": email}, headers=headers)


def test_list_unions_roster_and_headband_patients(client, login, add_patient):
    h = login()
    add_patient(h, "Rostered")
    _patient_account(client, "band@example.com", "Banded")
    assert _assign_headband(client, h, "band@example.com").status_code == 201

    rows = client.get("/patients", headers=h).json()
    assert [r["name"] for r in rows] == ["Banded", "Rostered"]
    banded = rows[0]
    assert banded["headband_device_id"] == "MuseS-1234"
    assert set(banded) == {"id", "name", "email", "headband_device_id", "last_session_at"}


def test_another_practitioner_cannot_write_against_your_patient(client, login, add_patient):
    mine, theirs = login("a@example.com"), login("b@example.com")
    p = add_patient(mine)
    assert client.post(f"/patients/{p}/notes", json={"body": "x"}, headers=theirs).status_code == 404
    assert client.post(f"/patients/{p}/summary", headers=theirs).status_code == 404
    assert client.delete(f"/patients/{p}", headers=theirs).status_code == 404
    assert client.post(f"/patients/{p}/notes", json={"body": "x"}, headers=mine).status_code == 201


def test_headband_alone_grants_coverage(client, login):
    h = login()
    p, _ = _patient_account(client)
    assert client.post(f"/patients/{p}/notes", json={"body": "x"}, headers=h).status_code == 404
    _assign_headband(client, h, "pat@example.com")
    assert client.post(f"/patients/{p}/notes", json={"body": "x"}, headers=h).status_code == 201


def test_removing_a_patient_ends_your_headband_assignment(client, login):
    h = login()
    _patient_account(client)
    _assign_headband(client, h, "pat@example.com")
    p = client.get("/patients", headers=h).json()[0]["id"]

    assert client.delete(f"/patients/{p}", headers=h).status_code == 204
    assert client.get("/patients", headers=h).json() == []
    [band] = client.get("/headbands", headers=h).json()
    assert band["in_use"] is False


def test_a_colleagues_headband_cannot_be_taken(client, login):
    mine, theirs = login("a@example.com"), login("b@example.com")
    _patient_account(client, "one@example.com")
    _patient_account(client, "two@example.com")
    assert _assign_headband(client, mine, "one@example.com").status_code == 201
    assert _assign_headband(client, theirs, "two@example.com").status_code == 403
    assert client.delete("/headbands/MuseS-1234/assignment", headers=theirs).status_code == 403


def test_patient_profile_reflects_their_headband(client, login):
    h = login()
    _, patient_headers = _patient_account(client)
    _assign_headband(client, h, "pat@example.com")
    profile = client.get("/me/profile", headers=patient_headers).json()
    assert profile["headband_device_id"] == "MuseS-1234"
    assert profile["last_session_at"] is None


def test_a_colleague_reads_nothing_of_your_work(client, login, add_patient, session_body):
    mine, theirs = login("a@example.com"), login("b@example.com")
    p = add_patient(mine)
    s = client.post("/sessions", json=session_body(p), headers=mine).json()["id"]
    note = client.post(f"/patients/{p}/notes", json={"body": "x"}, headers=mine).json()["id"]

    assert client.get(f"/patients/{p}/sessions", headers=theirs).json() == []
    assert client.get(f"/patients/{p}/notes", headers=theirs).json() == []
    for path in (f"/sessions/{s}", f"/sessions/{s}/eeg-metrics"):
        assert client.get(path, headers=theirs).status_code == 404
    assert client.patch(f"/sessions/{s}", json={"notes": "y"}, headers=theirs).status_code == 404
    assert client.delete(f"/sessions/{s}", headers=theirs).status_code == 404
    assert client.delete(f"/notes/{note}", headers=theirs).status_code == 404
    assert client.post("/sessions", json=session_body(p, "rec-2"), headers=theirs).status_code == 404
    assert client.get(f"/sessions/{s}", headers=mine).status_code == 200


def test_a_patient_sees_their_own_record_and_nothing_else(client, login, session_body):
    pid, patient = _patient_account(client)
    _, other = _patient_account(client, "other@example.com", "Other")
    a, b = login("a@example.com"), login("b@example.com")
    _assign_headband(client, a, "pat@example.com")
    client.post("/patients", json={"patient_email": "pat@example.com"}, headers=b).raise_for_status()
    for h in (a, b):
        client.post("/sessions", json=session_body(pid), headers=h).raise_for_status()

    assert len(client.get("/me/sessions", headers=patient).json()) == 2  # from both practitioners
    assert client.get("/me/sessions", headers=other).json() == []
    for path in ("/patients", f"/patients/{pid}/sessions", f"/patients/{pid}/notes"):
        assert client.get(path, headers=patient).status_code == 403
    assert client.get("/me/sessions", headers=a).status_code == 403
