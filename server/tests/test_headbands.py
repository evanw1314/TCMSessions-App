"""Headband assignment (routers/headbands.py): one active headband per
patient, one active patient per headband, and only the assigning
practitioner can end an assignment. Coverage through a headband is pinned in
test_patients.py."""


def _patient(client, email):
    client.post("/auth/dev", json={"email": email, "role": "patient", "name": email.split("@")[0]}).raise_for_status()


def _assign(client, headers, email, device_id="MuseS-A"):
    return client.post("/headbands", json={"device_id": device_id, "patient_email": email}, headers=headers)


def _bands(client, headers):
    return {b["device_id"]: b for b in client.get("/headbands", headers=headers).json()}


def test_moving_a_headband_ends_the_previous_patients_assignment(client, login):
    h = login()
    _patient(client, "one@example.com")
    _patient(client, "two@example.com")
    assert _assign(client, h, "one@example.com").status_code == 201
    assert _assign(client, h, "two@example.com").status_code == 201

    assert _bands(client, h)["MuseS-A"]["patient"]["email"] == "two@example.com"
    # "one" was covered only through the headband, so they leave the list too.
    assert [p["email"] for p in client.get("/patients", headers=h).json()] == ["two@example.com"]


def test_a_new_headband_replaces_the_patients_old_one(client, login):
    h = login()
    _patient(client, "pat@example.com")
    _assign(client, h, "pat@example.com", "MuseS-A")
    assert _assign(client, h, "pat@example.com", "MuseS-B").status_code == 201
    bands = _bands(client, h)
    assert bands["MuseS-A"]["in_use"] is False
    assert bands["MuseS-B"]["patient"]["email"] == "pat@example.com"


def test_assigning_the_same_pair_twice_is_a_conflict(client, login):
    h = login()
    _patient(client, "pat@example.com")
    _assign(client, h, "pat@example.com")
    assert _assign(client, h, "pat@example.com").status_code == 409


def test_only_patient_accounts_can_be_assigned(client, login):
    h = login()
    login("colleague@example.com")
    assert _assign(client, h, "nobody@example.com").status_code == 404
    assert _assign(client, h, "colleague@example.com").status_code == 404
    assert _bands(client, h) == {}


def test_a_colleagues_patient_cannot_be_given_a_second_headband(client, login):
    mine, theirs = login("a@example.com"), login("b@example.com")
    _patient(client, "pat@example.com")
    assert _assign(client, theirs, "pat@example.com", "MuseS-A").status_code == 201
    assert _assign(client, mine, "pat@example.com", "MuseS-B").status_code == 403
    assert _bands(client, theirs)["MuseS-A"]["patient"]["email"] == "pat@example.com"


def test_colleagues_see_a_headband_in_use_but_not_whose(client, login):
    mine, theirs = login("a@example.com"), login("b@example.com")
    _patient(client, "pat@example.com")
    _assign(client, mine, "pat@example.com")
    seen = _bands(client, theirs)["MuseS-A"]
    assert seen["in_use"] is True
    assert seen["patient"] is None and seen["assigned_since"] is None


def test_unassigning(client, login):
    h = login()
    _patient(client, "pat@example.com")
    _assign(client, h, "pat@example.com")
    r = client.delete("/headbands/MuseS-A/assignment", headers=h)
    assert r.status_code == 200 and r.json()["in_use"] is False
    assert client.delete("/headbands/MuseS-A/assignment", headers=h).status_code == 409
    assert client.delete("/headbands/MuseS-Z/assignment", headers=h).status_code == 404
    # A freed headband can be assigned again.
    assert _assign(client, h, "pat@example.com").status_code == 201


def test_patients_cannot_manage_headbands(client):
    r = client.post("/auth/dev", json={"email": "pat@example.com", "role": "patient", "name": "Pat"})
    h = {"Authorization": f"Bearer {r.json()['access_token']}"}
    assert client.get("/headbands", headers=h).status_code == 403
    assert _assign(client, h, "pat@example.com").status_code == 403
