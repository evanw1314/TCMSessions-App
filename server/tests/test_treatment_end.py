"""TcmSession.treatment_end_seconds: the "Treatment done" tap that the session
card's before/after band pies take their "after" minute from. Optional (every
older session lacks it), and never before the baseline mark when both are
set - a "before" window that starts after the "after" one is meaningless."""


def _count(client, headers, patient_id):
    return len(client.get(f"/patients/{patient_id}/sessions", headers=headers).json())


def test_it_round_trips(client, login, add_patient, session_body):
    h = login()
    p = add_patient(h)
    r = client.post(
        "/sessions", json=session_body(p, baseline_duration_seconds=60, treatment_end_seconds=200), headers=h
    )
    assert r.status_code == 201
    assert r.json()["treatment_end_seconds"] == 200
    got = client.get(f"/sessions/{r.json()['id']}", headers=h).json()
    assert got["baseline_duration_seconds"] == 60
    assert got["treatment_end_seconds"] == 200


def test_it_is_optional(client, login, add_patient, session_body):
    h = login()
    r = client.post("/sessions", json=session_body(add_patient(h)), headers=h)
    assert r.status_code == 201
    assert r.json()["treatment_end_seconds"] is None


def test_it_needs_no_baseline(client, login, add_patient, session_body):
    h = login()
    r = client.post("/sessions", json=session_body(add_patient(h), treatment_end_seconds=200), headers=h)
    assert r.status_code == 201
    assert r.json()["treatment_end_seconds"] == 200


def test_the_same_second_as_the_baseline_is_accepted(client, login, add_patient, session_body):
    h = login()
    body = session_body(add_patient(h), baseline_duration_seconds=60, treatment_end_seconds=60)
    assert client.post("/sessions", json=body, headers=h).status_code == 201


def test_before_the_baseline_is_refused(client, login, add_patient, session_body):
    h = login()
    p = add_patient(h)
    body = session_body(p, baseline_duration_seconds=120, treatment_end_seconds=60)
    assert client.post("/sessions", json=body, headers=h).status_code == 422
    assert _count(client, h, p) == 0


def test_a_negative_value_is_refused(client, login, add_patient, session_body):
    h = login()
    body = session_body(add_patient(h), treatment_end_seconds=-1)
    assert client.post("/sessions", json=body, headers=h).status_code == 422


def test_a_repeat_save_keeps_it(client, login, add_patient, session_body):
    """The phone resends the whole body on a retry; the stored session,
    Treatment done included, is what comes back."""
    h = login()
    p = add_patient(h)
    body = session_body(p, baseline_duration_seconds=60, treatment_end_seconds=200)
    first = client.post("/sessions", json=body, headers=h)
    again = client.post("/sessions", json=body, headers=h)
    assert again.status_code == 200
    assert again.json()["id"] == first.json()["id"]
    assert again.json()["treatment_end_seconds"] == 200
    assert _count(client, h, p) == 1
