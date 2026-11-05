import Foundation

/// `httpCode` is set when the server answered with an error status, and nil for
/// anything that failed before a response (no network, a timeout).
struct APIError: LocalizedError {
    let message: String
    var httpCode: Int?
    var errorDescription: String? { message }
}

/// Whether the server rejected what was sent, so sending the same thing again
/// can't help: 400 (malformed), 403 (not this practitioner's), 404 (the patient
/// or session is gone), 422 (fails validation). Deliberately NOT anything a fix
/// elsewhere could cure, since SessionJournal deletes a recording on a refusal:
/// 401 (log in again), 408/429 (later), 413 (a proxy's size limit), 501
/// (routers/sessions.py's "use the EEG address from your login"), or any other 5xx.
nonisolated func isServerRefusal(_ error: Error) -> Bool {
    guard let code = (error as? APIError)?.httpCode else { return false }
    return [400, 403, 404, 422].contains(code)
}

/// Thin async HTTP client for the FastAPI server — URLSession + JSONSerialization,
/// a direct port of Android's ApiClient.kt. Request/response field names are
/// explicit snake_case to match server/app/schemas.py exactly; there is no
/// camelCase conversion on either side.
/// `nonisolated`: this build defaults types to @MainActor, which would put
/// request-body construction — including mapping and serializing a long
/// session's ~40,000 samples — on the main thread. Kotlin runs the equivalent
/// inside `withContext(Dispatchers.IO)`; this is that.
nonisolated struct APIClient {

    // MARK: - Core request plumbing

    private static func baseURL() throws -> String {
        let host = AppConfig.serverHost
        if host.isEmpty { throw APIError(message: "Set the server address first") }
        return host
    }

    private static func makeRequest(
        _ method: String,
        _ path: String,
        body: [String: Any]? = nil,
        authed: Bool = true,
        timeout: TimeInterval = 15
    ) throws -> URLRequest {
        guard let url = URL(string: try baseURL() + path) else {
            throw APIError(message: "Invalid server address")
        }
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if authed {
            guard let token = AppConfig.token else { throw APIError(message: "Not logged in") }
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        return req
    }

    /// Runs the request and returns the parsed JSON body (object, array, or nil
    /// for an empty 2xx). Mirrors Android's readJsonResponse: on a non-2xx it
    /// pulls `detail` out of the error body and throws it as the message.
    private static func send(_ request: URLRequest) async throws -> Any? {
        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw APIError(message: error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else {
            throw APIError(message: "No response from server")
        }
        if !(200...299).contains(http.statusCode) {
            let detail = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?
                .flatMap { $0["detail"] as? String }
            throw APIError(message: detail ?? "HTTP \(http.statusCode)", httpCode: http.statusCode)
        }
        if data.isEmpty { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    private static func requestObject(
        _ method: String, _ path: String, body: [String: Any]? = nil, authed: Bool = true,
        timeout: TimeInterval = 15
    ) async throws -> [String: Any] {
        let request = try makeRequest(method, path, body: body, authed: authed, timeout: timeout)
        guard let obj = try await send(request) as? [String: Any] else {
            throw APIError(message: "Empty response")
        }
        return obj
    }

    private static func requestArray(
        _ method: String, _ path: String, authed: Bool = true
    ) async throws -> [[String: Any]] {
        let result = try await send(try makeRequest(method, path, authed: authed))
        return (result as? [[String: Any]]) ?? []
    }

    @discardableResult
    private static func requestOptional(
        _ method: String, _ path: String, body: [String: Any]? = nil, authed: Bool = true
    ) async throws -> Any? {
        try await send(try makeRequest(method, path, body: body, authed: authed))
    }

    // MARK: - Auth

    /// POST /auth/login
    static func login(email: String, password: String) async throws -> LoginResult {
        let res = try await requestObject("POST", "/auth/login",
                                          body: ["email": email, "password": password], authed: false)
        return loginResult(from: res)
    }

    /// POST /auth/register. `role` is "practitioner" or "patient". Starts a
    /// signup — the account isn't real yet, a code was emailed; finish with
    /// verify(). Re-calling for the same pending email is also the "resend code"
    /// path server-side.
    static func register(email: String, password: String, name: String, role: String = "practitioner") async throws -> PendingRegistration {
        let res = try await requestObject("POST", "/auth/register",
                                          body: ["email": email, "password": password, "name": name, "role": role],
                                          authed: false)
        return PendingRegistration(email: res["email"] as? String ?? email,
                                   codeSentTo: res["code_sent_to"] as? String ?? email,
                                   expiresAt: res["expires_at"] as? String ?? "")
    }

    /// POST /auth/verify — completes registration once the emailed code is
    /// entered; returns the same shape login() does.
    static func verify(email: String, code: String) async throws -> LoginResult {
        let res = try await requestObject("POST", "/auth/verify",
                                          body: ["email": email, "code": code], authed: false)
        return loginResult(from: res)
    }

    /// POST /auth/google — links a Google sign-in to an existing account by email
    /// (or logs in if already linked). Deliberately never sends `role`: this app
    /// only links Google to an account created earlier with a password, never
    /// creates one from Google alone (the server 422s "role is required on first
    /// sign-in" otherwise). Kept for the deferred Google Sign-In work; unused now.
    static func googleLogin(idToken: String) async throws -> LoginResult {
        let res = try await requestObject("POST", "/auth/google",
                                          body: ["id_token": idToken], authed: false)
        return loginResult(from: res)
    }

    private static func loginResult(from res: [String: Any]) -> LoginResult {
        let user = res["user"] as? [String: Any] ?? [:]
        return LoginResult(
            token: res["access_token"] as? String ?? "",
            userId: user["id"] as? Int ?? -1,
            userName: user["name"] as? String ?? "",
            clinicName: optString(user["clinic_name"]),
            role: user["role"] as? String ?? "practitioner"
        )
    }

    // MARK: - Patients

    /// GET /patients
    static func listPatients() async throws -> [Patient] {
        try await requestArray("GET", "/patients").map(patient(from:))
    }

    /// POST /patients — adds a patient to this practitioner's roster. Exactly one
    /// of email/name should be non-blank: `email` links an EXISTING account,
    /// `name` creates a name-only patient (email assigned later).
    static func addPatient(name: String?, email: String?) async throws -> Patient {
        let body: [String: Any] = [
            "patient_email": email?.nilIfBlank ?? NSNull(),
            "patient_name": name?.nilIfBlank ?? NSNull(),
        ]
        return patient(from: try await requestObject("POST", "/patients", body: body))
    }

    /// PATCH /patients/{id}/email — assigns an email to a name-only patient. Only
    /// works once (409s if already set or if the email belongs to another account).
    static func assignPatientEmail(patientId: Int, email: String) async throws -> Patient {
        patient(from: try await requestObject("PATCH", "/patients/\(patientId)/email", body: ["email": email]))
    }

    /// DELETE /patients/{id} — removes the patient from this practitioner's list
    /// and ends any device assignment they hold. Account + history are untouched.
    static func deletePatient(patientId: Int) async throws {
        try await requestOptional("DELETE", "/patients/\(patientId)")
    }

    private static func patient(from o: [String: Any]) -> Patient {
        Patient(
            id: o["id"] as? Int ?? -1,
            name: o["name"] as? String ?? "",
            email: optString(o["email"]),
            headbandDeviceId: optString(o["headband_device_id"]),
            lastSessionAt: optString(o["last_session_at"])
        )
    }

    // MARK: - Notes

    /// GET /patients/{id}/notes — this practitioner's own notes, newest first.
    static func listNotes(patientId: Int) async throws -> [PatientNote] {
        try await requestArray("GET", "/patients/\(patientId)/notes").map(note(from:))
    }

    /// POST /patients/{id}/notes
    static func addNote(patientId: Int, body: String) async throws -> PatientNote {
        note(from: try await requestObject("POST", "/patients/\(patientId)/notes", body: ["body": body]))
    }

    /// DELETE /notes/{id} — soft delete server-side.
    static func deleteNote(noteId: Int) async throws {
        try await requestOptional("DELETE", "/notes/\(noteId)")
    }

    private static func note(from o: [String: Any]) -> PatientNote {
        PatientNote(
            id: o["id"] as? Int ?? -1,
            body: o["body"] as? String ?? "",
            createdAt: o["created_at"] as? String ?? "",
            practitionerName: o["practitioner_name"] as? String ?? ""
        )
    }

    // MARK: - Self ("/me") views

    /// GET /me/profile — the patient's own self-view profile.
    static func myProfile() async throws -> Patient {
        patient(from: try await requestObject("GET", "/me/profile"))
    }

    /// GET /me/sessions — the patient's own full session history.
    static func mySessions() async throws -> [SessionSummary] {
        try await requestArray("GET", "/me/sessions").map(summary(from:))
    }

    /// GET /me/notes — every note written about this patient, by any practitioner.
    static func myNotes() async throws -> [PatientNote] {
        try await requestArray("GET", "/me/notes").map(note(from:))
    }

    // MARK: - AI summaries (on-demand only — each is a real API call)

    /// POST /patients/{id}/summary
    static func summarizePatient(patientId: Int) async throws -> String {
        try await requestObject("POST", "/patients/\(patientId)/summary")["summary"] as? String ?? ""
    }

    /// POST /sessions/{id}/summary
    static func summarizeSession(sessionId: Int) async throws -> String {
        try await requestObject("POST", "/sessions/\(sessionId)/summary")["summary"] as? String ?? ""
    }

    // MARK: - Sessions

    /// POST /sessions — the buffer-then-upload-at-end write.
    @discardableResult
    static func createSession(
        patientId: Int,
        headbandDeviceId: String?,
        modality: String,
        startedAtIso: String,
        endedAtIso: String,
        practitionerNote: String?,
        patientDozed: Bool?,
        alertnessRating: Int?,
        baselineDurationSeconds: Int?,
        markers: [SessionMarker],
        samples: [SessionSample],
        // The recording's id from SessionJournal.Meta, the same on every
        // attempt: the server answers a repeat with the session it already made
        // instead of a second copy. Nil only for a journal from before this existed.
        clientSessionId: String?
    ) async throws -> Int {
        let body: [String: Any] = [
            "patient_id": patientId,
            "client_session_id": clientSessionId ?? NSNull(),
            "headband_device_id": headbandDeviceId ?? NSNull(),
            "modality": modality,
            "started_at": startedAtIso,
            "ended_at": endedAtIso,
            "practitioner_note": practitionerNote ?? NSNull(),
            "patient_dozed": patientDozed ?? NSNull(),
            "alertness_rating": alertnessRating ?? NSNull(),
            "baseline_duration_seconds": baselineDurationSeconds ?? NSNull(),
            "markers": markers.map { ["offset_seconds": $0.offsetSeconds, "label": $0.label] },
            "samples": samples.map { sample -> [String: Any] in
                // JSONSerialization refuses NaN/Infinity — band-power values can
                // legitimately go non-finite (absolute power from a zero-power
                // channel logs out to -Infinity) and a single bad sample must not
                // take down the whole session's save. Boundary guard: coerce here
                // regardless of where the bad value came from, so it also rescues
                // anything already sitting in a buffered recorder from before the
                // tick-time guard existed.
                ["offset_seconds": sample.offsetSeconds, "band": sample.band,
                 "value": sample.value.isFinite ? sample.value : NO_SIGNAL_VALUE]
            },
        ]
        // Deliberately not `?? -1`: a 2xx body with no integer id would then
        // reach uploadRawEeg as session -1, 404 (a "server refusal"), and the
        // journal would be deleted as unrecoverable while the UI said "saved".
        // Kotlin's getInt throws here; so does this.
        guard let id = try await requestObject("POST", "/sessions", body: body)["id"] as? Int else {
            throw APIError(message: "The server didn't return a session id")
        }
        return id
    }

    /// GET /patients/{id}/sessions
    static func listSessions(patientId: Int) async throws -> [SessionSummary] {
        try await requestArray("GET", "/patients/\(patientId)/sessions").map(summary(from:))
    }

    /// GET /sessions/{id}
    static func getSession(sessionId: Int) async throws -> SessionDetail {
        let o = try await requestObject("GET", "/sessions/\(sessionId)")
        let markers = (o["markers"] as? [[String: Any]] ?? []).map {
            SessionMarker(offsetSeconds: $0["offset_seconds"] as? Int ?? 0, label: $0["label"] as? String ?? "")
        }
        let samples = (o["samples"] as? [[String: Any]] ?? []).map {
            SessionSample(offsetSeconds: $0["offset_seconds"] as? Int ?? 0,
                          band: $0["band"] as? String ?? "",
                          value: doubleValue($0["value"]))
        }
        return SessionDetail(
            id: o["id"] as? Int ?? -1,
            modality: o["modality"] as? String ?? "",
            startedAt: o["started_at"] as? String ?? "",
            endedAt: o["ended_at"] as? String ?? "",
            practitionerNote: optString(o["practitioner_note"]),
            patientDozed: optBool(o["patient_dozed"]),
            alertnessRating: optInt(o["alertness_rating"]),
            baselineDurationSeconds: optInt(o["baseline_duration_seconds"]),
            markers: markers,
            samples: samples
        )
    }

    /// PATCH /sessions/{id} — set/edit the practitioner note after the fact.
    static func updateSessionNote(sessionId: Int, note: String?) async throws {
        try await requestOptional("PATCH", "/sessions/\(sessionId)", body: ["practitioner_note": note ?? NSNull()])
    }

    /// DELETE /sessions/{id} — soft delete server-side.
    static func deleteSession(sessionId: Int) async throws {
        try await requestOptional("DELETE", "/sessions/\(sessionId)")
    }

    private static func summary(from o: [String: Any]) -> SessionSummary {
        SessionSummary(
            id: o["id"] as? Int ?? -1,
            modality: o["modality"] as? String ?? "",
            startedAt: o["started_at"] as? String ?? "",
            endedAt: o["ended_at"] as? String ?? ""
        )
    }

    // MARK: - Raw EEG capture + analysis

    /// POST /sessions/{id}/eeg (multipart/form-data) — the raw EEG blob packed by
    /// EegBinaryWriter.
    static func uploadRawEeg(sessionId: Int, eegBlob: Data) async throws {
        let boundary = "TcmSessionBoundary\(Int(Date().timeIntervalSince1970 * 1000))"
        guard let url = URL(string: try baseURL() + "/sessions/\(sessionId)/eeg") else {
            throw APIError(message: "Invalid server address")
        }
        // A 45-minute session's blob is several MB — longer than the plain-JSON
        // default, just for upload time (not a round-trip through a model).
        var req = URLRequest(url: url, timeoutInterval: 60)
        req.httpMethod = "POST"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let token = AppConfig.token else { throw APIError(message: "Not logged in") }
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        var data = Data()
        func append(_ string: String) { data.append(string.data(using: .utf8)!) }
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"eeg_data\"; filename=\"session_\(sessionId).bin\"\r\n")
        append("Content-Type: application/octet-stream\r\n\r\n")
        data.append(eegBlob)
        append("\r\n--\(boundary)--\r\n")
        req.httpBody = data

        _ = try await send(req)
    }

    /// POST /sessions/{id}/eeg/analyze — runs the mne/specparam pipeline on
    /// whatever raw EEG blob uploadRawEeg() uploaded (see eeg/analysis.py),
    /// synchronously. Callable REPEATEDLY for the same session: the server keeps
    /// the blob and recomputes on every call, so a session can be re-run as the
    /// correction pipeline improves — that's what the "Re-analyze" button does.
    ///
    /// 404s with a clear message when there's nothing to analyze — a real,
    /// expected outcome, not just an error path to swallow. The server words two
    /// cases differently: no raw EEG was ever uploaded (upload is
    /// best-effort/non-fatal), or a blob existed once and is no longer on the
    /// server. Both are shown to
    /// the practitioner as-is.
    ///
    /// The pipeline is slower than a plain read, so it gets a longer timeout
    /// than the 15s default.
    static func analyzeEeg(sessionId: Int) async throws -> EegAnalysisResult {
        let res = try await requestObject("POST", "/sessions/\(sessionId)/eeg/analyze", timeout: 120)
        return eegAnalysisResult(from: res)
    }

    /// GET /sessions/{id}/eeg-metrics — rereads whatever analyzeEeg() last
    /// computed without re-running the pipeline (e.g. reopening this session's
    /// detail screen later). Empty halves + nil analyzedAt both mean "never analyzed."
    static func getEegMetrics(sessionId: Int) async throws -> EegAnalysisResult {
        eegAnalysisResult(from: try await requestObject("GET", "/sessions/\(sessionId)/eeg-metrics"))
    }

    private static func eegAnalysisResult(from o: [String: Any]) -> EegAnalysisResult {
        let halves = (o["halves"] as? [[String: Any]] ?? []).map { h -> EegHalfMetric in
            EegHalfMetric(
                half: h["half"] as? String ?? "",
                aperiodicOffset: doubleValue(h["aperiodic_offset"]),
                aperiodicExponent: doubleValue(h["aperiodic_exponent"]),
                rSquared: doubleValue(h["r_squared"]),
                epochCount: optInt(h["epoch_count"]) ?? 0,
                peaks: (h["peaks"] as? [[String: Any]] ?? []).map { p in
                    OscillationPeak(
                        band: p["band"] as? String ?? "",
                        centerFrequency: doubleValue(p["center_frequency"]),
                        power: doubleValue(p["power"]),
                        bandwidth: doubleValue(p["bandwidth"])
                    )
                },
                eogSemRatePerMin: optDouble(h["eog_sem_rate_per_min"]),
                eogWindowsAnalyzed: optInt(h["eog_windows_analyzed"]) ?? 0,
                epochsRejectedBadContact: optInt(h["epochs_rejected_bad_contact"]),
                epochsRejectedAmplitude: optInt(h["epochs_rejected_amplitude"]),
                epochsPartialContact: optInt(h["epochs_partial_contact"]),
                // An absent key and JSON null alike mean the analysis predates the field.
                spectrum: eegSpectrum(from: h["spectrum"] as? [String: Any]),
                deflectionRatePerMin: optDouble(h["deflection_rate_per_min"])
            )
        }
        return EegAnalysisResult(
            halves: halves,
            analyzedAt: optString(o["analyzed_at"]),
            observedSampleRateHz: optDouble(o["observed_sample_rate_hz"]),
            interpolatedSampleFraction: optDouble(o["interpolated_sample_fraction"]),
            // Absent/null means "this analysis predates the field"; an empty array
            // means it ran and excluded nothing. Preserved as nil vs [] rather
            // than flattened into one "no exclusions."
            excludedChannelIndices: (o["excluded_channel_indices"] as? [Any])?.compactMap { optInt($0) },
            lowFitRSquared: optDouble(o["low_fit_r_squared"]),
            // Same nil-vs-empty distinction: nil predates the electrode check.
            electrodeFindings: (o["electrode_findings"] as? [[String: Any]])?.map { f in
                ElectrodeFinding(
                    channelName: f["channel_name"] as? String ?? "",
                    half: f["half"] as? String ?? "",
                    sign: f["sign"] as? String ?? "",
                    value: doubleValue(f["value"]),
                    excluded: (f["excluded"] as? Bool) ?? false,
                    partnerName: optString(f["partner_name"])
                )
            },
            allElectrodesFlagged: optBool(o["all_electrodes_flagged"]),
            eyeActivityDiffers: optBool(o["eye_activity_differs"])
        )
    }

    /// Nil on a malformed spectrum rather than throwing: a throw here would fail
    /// the whole EegAnalysisResult parse and blank the analysis card over what is
    /// only its picture. Nil degrades to the card's "not recorded" line with the
    /// numbers intact.
    private static func eegSpectrum(from o: [String: Any]?) -> EegSpectrum? {
        guard let o else { return nil }
        func doubles(_ key: String) -> [Double]? {
            guard let raw = o[key] as? [Any] else { return nil }
            return raw.map { doubleValue($0) }
        }
        guard let freqs = doubles("freqs_hz"), let power = doubles("log_power"),
              let model = doubles("log_model_fit"), let aperiodic = doubles("log_aperiodic_fit")
        else { return nil }
        return EegSpectrum(freqsHz: freqs, logPower: power, logModelFit: model, logAperiodicFit: aperiodic)
    }

    /// GET /patients/{id}/eeg-trend — cross-session corrected-EEG metrics for one
    /// patient. Practitioner-only server-side, same as the single-session EEG
    /// analysis endpoints; server-ordered oldest-first (chronological — the
    /// natural reading order for a trend), unlike every other list endpoint here.
    static func getPatientEegTrend(patientId: Int) async throws -> [EegTrendPoint] {
        try await requestArray("GET", "/patients/\(patientId)/eeg-trend").map { o in
            EegTrendPoint(
                sessionId: optInt(o["session_id"]) ?? -1,
                sessionStartedAt: o["session_started_at"] as? String ?? "",
                half: o["half"] as? String ?? "",
                period: optInt(o["period"]) ?? 0,
                aperiodicOffset: doubleValue(o["aperiodic_offset"]),
                aperiodicExponent: doubleValue(o["aperiodic_exponent"]),
                rSquared: doubleValue(o["r_squared"]),
                epochCount: optInt(o["epoch_count"]) ?? 0,
                eogSemRatePerMin: optDouble(o["eog_sem_rate_per_min"]),
                eogWindowsAnalyzed: optInt(o["eog_windows_analyzed"]) ?? 0,
                lowFitRSquared: optDouble(o["low_fit_r_squared"]),
                deflectionRatePerMin: optDouble(o["deflection_rate_per_min"]),
                eyeActivityDiffers: optBool(o["eye_activity_differs"]),
                excludedChannelNames: (o["excluded_channel_names"] as? [Any])?.compactMap { $0 as? String },
                allElectrodesFlagged: optBool(o["all_electrodes_flagged"])
            )
        }
    }

    // MARK: - Voice marker transcription (multipart)

    /// POST /sessions/transcribe (multipart/form-data) — turns a short voice-marker
    /// recording into text via AssemblyAI (server/app/transcription.py). Built by
    /// hand since it's the one endpoint that uploads a file. We record WAV (Linear
    /// PCM), which AssemblyAI accepts. `language` is a BCP-47 tag (nil means
    /// English), same as Android's VoiceLanguage.bcp47Tag.
    static func transcribeMarker(audio: Data, language: String?) async throws -> String {
        let boundary = "TcmSessionBoundary\(Int(Date().timeIntervalSince1970 * 1000))"
        guard let url = URL(string: try baseURL() + "/sessions/transcribe") else {
            throw APIError(message: "Invalid server address")
        }
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = "POST"
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let token = AppConfig.token else { throw APIError(message: "Not logged in") }
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        var data = Data()
        func append(_ s: String) { data.append(s.data(using: .utf8)!) }
        if let language {
            append("--\(boundary)\r\n")
            append("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
            append("\(language)\r\n")
        }
        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"audio\"; filename=\"marker.wav\"\r\n")
        append("Content-Type: audio/wav\r\n\r\n")
        data.append(audio)
        append("\r\n--\(boundary)--\r\n")
        req.httpBody = data

        guard let obj = try await send(req) as? [String: Any] else {
            throw APIError(message: "Empty response")
        }
        return obj["text"] as? String ?? ""
    }
}

// MARK: - JSON helpers

/// nil for JSON null (NSNull) or a missing key; the string otherwise.
private nonisolated func optString(_ value: Any?) -> String? {
    if value == nil || value is NSNull { return nil }
    return value as? String
}

/// nil for JSON null or a missing key — the "this analysis predates the field"
/// case the EEG models distinguish from a real zero/false.
private nonisolated func optInt(_ value: Any?) -> Int? {
    if value == nil || value is NSNull { return nil }
    if let i = value as? Int { return i }
    return (value as? NSNumber)?.intValue
}

private nonisolated func optDouble(_ value: Any?) -> Double? {
    if value == nil || value is NSNull { return nil }
    if let d = value as? Double { return d }
    return (value as? NSNumber)?.doubleValue
}

private nonisolated func optBool(_ value: Any?) -> Bool? {
    if value == nil || value is NSNull { return nil }
    if let b = value as? Bool { return b }
    return (value as? NSNumber)?.boolValue
}

/// Server band values arrive as JSON numbers, which JSONSerialization may hand
/// back as Int or Double depending on the value — normalize to Double.
private nonisolated func doubleValue(_ value: Any?) -> Double {
    if let d = value as? Double { return d }
    if let i = value as? Int { return Double(i) }
    if let n = value as? NSNumber { return n.doubleValue }
    return NO_SIGNAL_VALUE
}

private nonisolated extension String {
    var nilIfBlank: String? { trimmingCharacters(in: .whitespaces).isEmpty ? nil : self }
}
