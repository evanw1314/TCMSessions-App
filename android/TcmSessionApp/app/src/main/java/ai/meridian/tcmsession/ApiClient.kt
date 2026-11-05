package ai.meridian.tcmsession

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** [httpCode] is set when the server answered with an error status, and null
 * for anything that failed before a response (no network, a timeout). */
class ApiException(message: String, val httpCode: Int? = null) : Exception(message)

/** Whether the server rejected what was sent, so sending the same thing again
 * can't help: 400 (malformed), 403 (not this practitioner's), 404 (the
 * patient or session is gone), 422 (fails validation). Deliberately NOT
 * anything a fix elsewhere could cure, since SessionJournal deletes a
 * recording on a refusal: 401 (log in again), 408/429 (later), 413 (a
 * proxy's size limit), 501 (routers/sessions.py's "use the EEG address from
 * your login"), or any other 5xx. */
fun isServerRefusal(e: Exception): Boolean =
    (e as? ApiException)?.httpCode in setOf(400, 403, 404, 422)

/**
 * Thin HTTP client for the FastAPI server — no Retrofit/OkHttp dependency,
 * just HttpURLConnection + org.json, to keep the dependency surface
 * small. Field names match server/app/schemas.py exactly (the
 * server does not use camelCase conversion).
 */
class ApiClient(private val context: android.content.Context) {

    private fun baseUrl(): String {
        val host = AppConfig.serverHost(context)
        if (host.isBlank()) throw ApiException("Set the server address first")
        return host
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authed: Boolean = true,
    ): JSONObject? = doRequest(method, path, body, authed) as? JSONObject

    private fun requestArray(
        method: String,
        path: String,
        authed: Boolean = true,
    ): JSONArray = doRequest(method, path, null, authed) as JSONArray

    private fun doRequest(
        method: String,
        path: String,
        body: JSONObject?,
        authed: Boolean,
    ): Any? {
        val url = URL(baseUrl() + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (authed) {
                val token = AppConfig.token(context)
                    ?: throw ApiException("Not logged in")
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000

            if (body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                    it.write(body.toString())
                }
            }

            return readJsonResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** Shared response tail for both doRequest (JSON body) and
     * transcribeMarker (multipart body) below — same status-code/error-detail
     * handling either way, only how the request body gets written differs. */
    private fun readJsonResponse(conn: HttpURLConnection): Any? {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { r -> r.readText() }
        } ?: ""

        if (code !in 200..299) {
            val detail = try {
                JSONObject(text).optString("detail", "HTTP $code")
            } catch (e: Exception) {
                "HTTP $code"
            }
            throw ApiException(detail, code)
        }
        if (text.isBlank()) return null
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    /** POST /auth/login */
    suspend fun login(email: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("email", email).put("password", password)
            val res = request("POST", "/auth/login", body, authed = false)
                ?: throw ApiException("Empty login response")
            loginResultFrom(res)
        }

    /** POST /auth/register. `role` is "practitioner" or "patient", per
     * LoginActivity's role toggle. Starts a signup: the account isn't real
     * yet, a code was emailed to `email`, finish with verify(). Calling this
     * again for the same still-pending email is also how "resend code"
     * works server-side. */
    suspend fun register(email: String, password: String, name: String, role: String = "practitioner"): PendingRegistration =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .put("name", name)
                .put("role", role)
            val res = request("POST", "/auth/register", body, authed = false)
                ?: throw ApiException("Empty register response")
            PendingRegistration(
                email = res.getString("email"),
                codeSentTo = res.optStringOrNull("code_sent_to") ?: email,
                expiresAt = res.getString("expires_at"),
            )
        }

    /** POST /auth/google — links a Google sign-in to an existing account by
     * email (sets google_id server-side if it wasn't set yet) or just logs
     * in if already linked. The server never creates an account from Google
     * alone: for an email with no account it 422s "role is required on
     * first sign-in", which LoginActivity turns into a "create an account
     * first" message. */
    suspend fun googleLogin(idToken: String): LoginResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("id_token", idToken)
            val res = request("POST", "/auth/google", body, authed = false)
                ?: throw ApiException("Empty login response")
            loginResultFrom(res)
        }

    /** POST /auth/verify — completes registration once the emailed code is
     * entered; returns the same shape login() does, so the app can log
     * straight in. */
    suspend fun verify(email: String, code: String): LoginResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("email", email).put("code", code)
            val res = request("POST", "/auth/verify", body, authed = false)
                ?: throw ApiException("Empty verify response")
            loginResultFrom(res)
        }

    private fun loginResultFrom(res: JSONObject): LoginResult {
        val user = res.getJSONObject("user")
        return LoginResult(
            token = res.getString("access_token"),
            userId = user.getInt("id"),
            userName = user.getString("name"),
            clinicName = user.optStringOrNull("clinic_name"),
            role = user.getString("role"),
        )
    }

    /** GET /patients */
    suspend fun listPatients(): List<Patient> = withContext(Dispatchers.IO) {
        val arr = requestArray("GET", "/patients")
        (0 until arr.length()).map { i -> patientFrom(arr.getJSONObject(i)) }
    }

    /** POST /patients — adds a patient to this practitioner's roster, no
     * device required (server/app/models.py's PatientRoster). Exactly one of
     * the two should be non-blank: `email` links an EXISTING patient account
     * (404s if none exists yet for that email, 409s if already on this
     * practitioner's list) — `name` instead creates a brand-new name-only
     * patient with no account yet, the email assigned later via
     * assignPatientEmail(). Mirrors AddPatientRequest server-side. */
    suspend fun addPatient(name: String?, email: String?): Patient = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("patient_email", email?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("patient_name", name?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        }
        val res = request("POST", "/patients", body) ?: throw ApiException("Empty response")
        patientFrom(res)
    }

    /** PATCH /patients/{id}/email — assigns an email to a name-only patient
     * (choosing which account should receive their session data). Only works
     * once: 409s if this patient already has an email, or if the email
     * belongs to a different account already. */
    suspend fun assignPatientEmail(patientId: Int, email: String): Patient = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email)
        val res = request("PATCH", "/patients/$patientId/email", body) ?: throw ApiException("Empty response")
        patientFrom(res)
    }

    /** DELETE /patients/{id} — removes this patient from the practitioner's
     * list: drops the roster entry and ends any headband assignment this
     * practitioner still holds for them. The patient's account and history
     * are untouched. */
    suspend fun deletePatient(patientId: Int): Unit = withContext(Dispatchers.IO) {
        request("DELETE", "/patients/$patientId")
        Unit
    }

    private fun patientFrom(o: JSONObject): Patient = Patient(
        id = o.getInt("id"),
        name = o.getString("name"),
        email = o.optStringOrNull("email"),
        headbandDeviceId = o.optStringOrNull("headband_device_id"),
        lastSessionAt = o.optStringOrNull("last_session_at"),
    )

    /** GET /patients/{id}/notes — this practitioner's own notes about the
     * patient, newest first (see PatientNote's docstring server-side). */
    suspend fun listNotes(patientId: Int): List<PatientNote> = withContext(Dispatchers.IO) {
        val arr = requestArray("GET", "/patients/$patientId/notes")
        (0 until arr.length()).map { i -> patientNoteFrom(arr.getJSONObject(i)) }
    }

    /** POST /patients/{id}/notes */
    suspend fun addNote(patientId: Int, body: String): PatientNote = withContext(Dispatchers.IO) {
        val res = request("POST", "/patients/$patientId/notes", JSONObject().put("body", body))
            ?: throw ApiException("Empty response")
        patientNoteFrom(res)
    }

    private fun patientNoteFrom(o: JSONObject): PatientNote = PatientNote(
        id = o.getInt("id"),
        body = o.getString("body"),
        createdAt = o.getString("created_at"),
        practitionerName = o.getString("practitioner_name"),
    )

    /** GET /me/profile — the patient's own self-view profile, same shape as
     * one row of GET /patients but unscoped to a single practitioner (see
     * that endpoint's docstring server-side). */
    suspend fun myProfile(): Patient = withContext(Dispatchers.IO) {
        val res = request("GET", "/me/profile") ?: throw ApiException("Empty response")
        patientFrom(res)
    }

    /** GET /me/sessions — the patient's own full session history, across
     * every practitioner who's recorded one for them. */
    suspend fun mySessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        val arr = requestArray("GET", "/me/sessions")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SessionSummary(
                id = o.getInt("id"),
                modality = o.getString("modality"),
                startedAt = o.getString("started_at"),
                endedAt = o.getString("ended_at"),
            )
        }
    }

    /** GET /me/notes — every note written about this patient, by any
     * practitioner who's treated them. */
    suspend fun myNotes(): List<PatientNote> = withContext(Dispatchers.IO) {
        val arr = requestArray("GET", "/me/notes")
        (0 until arr.length()).map { i -> patientNoteFrom(arr.getJSONObject(i)) }
    }

    /** DELETE /notes/{id} — soft delete server-side (see PatientNote in
     * models.py); the note disappears from the list immediately but isn't
     * destroyed. Not nested under /patients/{id}, same as /sessions/{id}. */
    suspend fun deleteNote(noteId: Int): Unit = withContext(Dispatchers.IO) {
        request("DELETE", "/notes/$noteId")
        Unit
    }

    /** POST /patients/{id}/summary — on-demand AI overview (notes + sessions
     * only, descriptive-only framing enforced server-side). Never call this
     * automatically; it costs a real API call each time. Error responses
     * (503 unconfigured, 400 nothing-to-summarize) surface via ApiException's
     * detail message same as every other endpoint — no special handling here. */
    suspend fun summarizePatient(patientId: Int): String = withContext(Dispatchers.IO) {
        val res = request("POST", "/patients/$patientId/summary") ?: throw ApiException("Empty response")
        res.getString("summary")
    }

    /** POST /sessions/{id}/summary — on-demand AI overview of one recorded
     * session (markers + band-power trend + this session's practitioner
     * note). Same on-demand-only, descriptive-only framing as
     * summarizePatient() above. */
    suspend fun summarizeSession(sessionId: Int): String = withContext(Dispatchers.IO) {
        val res = request("POST", "/sessions/$sessionId/summary") ?: throw ApiException("Empty response")
        res.getString("summary")
    }

    /** POST /sessions */
    suspend fun createSession(
        patientId: Int,
        headbandDeviceId: String?,
        modality: String,
        startedAtIso: String,
        endedAtIso: String,
        practitionerNote: String?,
        patientDozed: Boolean?,
        alertnessRating: Int?,
        baselineDurationSeconds: Int?,
        treatmentEndSeconds: Int?,
        markers: List<SessionMarker>,
        samples: List<SessionSample>,
        // The recording's id from SessionJournal.Meta, the same on every
        // attempt: the server answers a repeat with the session it already
        // made instead of a second copy. Null only for a journal from before
        // this existed.
        clientSessionId: String?,
    ): Int = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("patient_id", patientId)
            put("client_session_id", clientSessionId ?: JSONObject.NULL)
            put("headband_device_id", headbandDeviceId ?: JSONObject.NULL)
            put("modality", modality)
            put("started_at", startedAtIso)
            put("ended_at", endedAtIso)
            put("practitioner_note", practitionerNote ?: JSONObject.NULL)
            put("patient_dozed", patientDozed ?: JSONObject.NULL)
            put("alertness_rating", alertnessRating ?: JSONObject.NULL)
            put("baseline_duration_seconds", baselineDurationSeconds ?: JSONObject.NULL)
            put("treatment_end_seconds", treatmentEndSeconds ?: JSONObject.NULL)
            put("markers", JSONArray().apply {
                markers.forEach {
                    put(JSONObject().put("offset_seconds", it.offsetSeconds).put("label", it.label))
                }
            })
            put("samples", JSONArray().apply {
                samples.forEach {
                    // org.json's JSONObject.put(String, double) throws
                    // "Forbidden numeric value" for NaN/Infinite — band-power
                    // values can legitimately go non-finite (e.g. absolute
                    // power computed from a zero-power channel logs out to
                    // -Infinity) and a single bad sample must not take down
                    // the whole session's save. Boundary guard: coerce to
                    // NO_SIGNAL_VALUE here regardless of where the bad value
                    // came from (SessionRecorder's tick has its own guard).
                    val safeValue = if (it.value.isFinite()) it.value else NO_SIGNAL_VALUE
                    put(
                        JSONObject()
                            .put("offset_seconds", it.offsetSeconds)
                            .put("band", it.band)
                            .put("value", safeValue)
                    )
                }
            })
        }
        val res = request("POST", "/sessions", body) ?: throw ApiException("Empty response")
        res.getInt("id")
    }

    /** POST /sessions/transcribe (multipart/form-data) — turns a short
     * voice-marker recording into text via AssemblyAI (see
     * server/app/transcription.py). `language` is a BCP-47 tag from
     * SessionActivity's VoiceLanguage enum (null means English). Built by
     * hand rather than via request()/doRequest() above since those assume a
     * JSON body, and pulling in OkHttp/Retrofit for the two file uploads
     * would be a bigger dependency than writing the multipart body directly. */
    suspend fun transcribeMarker(audioBytes: ByteArray, language: String?): String =
        withContext(Dispatchers.IO) {
            val boundary = "TcmSessionBoundary${System.currentTimeMillis()}"
            val url = URL(baseUrl() + "/sessions/transcribe")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setRequestProperty("Accept", "application/json")
                val token = AppConfig.token(context) ?: throw ApiException("Not logged in")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 10_000
                // Longer than the plain-JSON default: this round-trips through
                // AssemblyAI server-side (which gives up at 20 s), not just
                // the local DB.
                conn.readTimeout = 30_000

                conn.outputStream.use { out ->
                    fun writeLine(s: String) = out.write((s + "\r\n").toByteArray(StandardCharsets.UTF_8))
                    if (language != null) {
                        writeLine("--$boundary")
                        writeLine("Content-Disposition: form-data; name=\"language\"")
                        writeLine("")
                        writeLine(language)
                    }
                    writeLine("--$boundary")
                    writeLine("Content-Disposition: form-data; name=\"audio\"; filename=\"marker.aac\"")
                    writeLine("Content-Type: audio/aac")
                    writeLine("")
                    out.write(audioBytes)
                    writeLine("")
                    writeLine("--$boundary--")
                }

                val res = readJsonResponse(conn) as? JSONObject ?: throw ApiException("Empty response")
                res.getString("text")
            } finally {
                conn.disconnect()
            }
        }

    /** POST /sessions/{id}/eeg (multipart/form-data) — uploads the raw EEG
     * blob SessionRecorder.kt buffered during the session, packed by
     * EegBinaryWriter.pack() (see that file for the wire format, which must
     * stay byte-for-byte compatible with server/app/eeg/binary.py). Written
     * by hand rather than via request()/doRequest(), same reasoning as
     * transcribeMarker(): this is a binary body, not JSON. The response
     * (sample count and rate) isn't used beyond a log line. */
    suspend fun uploadRawEeg(sessionId: Int, eegBlob: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            val boundary = "TcmSessionBoundary${System.currentTimeMillis()}"
            val url = URL(baseUrl() + "/sessions/$sessionId/eeg")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setRequestProperty("Accept", "application/json")
                val token = AppConfig.token(context) ?: throw ApiException("Not logged in")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 10_000
                // A 45-minute session's blob is several MB — longer than the
                // plain-JSON default, same reasoning as transcribeMarker's
                // extended readTimeout below (just for upload time here, not
                // a round-trip through an LLM).
                conn.readTimeout = 60_000

                conn.outputStream.use { out ->
                    fun writeLine(s: String) = out.write((s + "\r\n").toByteArray(StandardCharsets.UTF_8))
                    writeLine("--$boundary")
                    writeLine("Content-Disposition: form-data; name=\"eeg_data\"; filename=\"session_$sessionId.bin\"")
                    writeLine("Content-Type: application/octet-stream")
                    writeLine("")
                    out.write(eegBlob)
                    writeLine("")
                    writeLine("--$boundary--")
                }

                readJsonResponse(conn)
                Unit
            } finally {
                conn.disconnect()
            }
        }

    /** POST /sessions/{id}/eeg/analyze — runs the mne/specparam pipeline on
     * whatever raw EEG blob uploadRawEeg() uploaded (see eeg/analysis.py),
     * synchronously. Callable REPEATEDLY for the same session: the server
     * keeps the blob and recomputes on every call, so
     * a session can be re-run as the correction pipeline improves — that's
     * what SessionDetailActivity's "Re-analyze" button does.
     *
     * 404s with a clear ApiException message when there's nothing to
     * analyze — a real, expected outcome, not just an error path to
     * swallow. The server words two cases differently: no raw EEG was ever
     * uploaded (upload is best-effort/non-fatal), or a blob existed once and
     * is no longer on the server. Both are shown to the practitioner as-is. */
    suspend fun analyzeEeg(sessionId: Int): EegAnalysisResult = withContext(Dispatchers.IO) {
        val res = request("POST", "/sessions/$sessionId/eeg/analyze")
            ?: throw ApiException("Empty response")
        eegAnalysisResultFrom(res)
    }

    /** GET /sessions/{id}/eeg-metrics — rereads whatever analyzeEeg() last
     * computed without re-running the pipeline (e.g. reopening this session's
     * detail screen later). Empty halves + null analyzedAt both mean "never
     * analyzed." */
    suspend fun getEegMetrics(sessionId: Int): EegAnalysisResult = withContext(Dispatchers.IO) {
        val res = request("GET", "/sessions/$sessionId/eeg-metrics") ?: throw ApiException("Empty response")
        eegAnalysisResultFrom(res)
    }

    private fun eegAnalysisResultFrom(o: JSONObject): EegAnalysisResult {
        val halves = o.getJSONArray("halves")
        return EegAnalysisResult(
            halves = (0 until halves.length()).map { i ->
                val h = halves.getJSONObject(i)
                val peaks = h.getJSONArray("peaks")
                EegHalfMetric(
                    half = h.getString("half"),
                    aperiodicOffset = h.getDouble("aperiodic_offset"),
                    aperiodicExponent = h.getDouble("aperiodic_exponent"),
                    rSquared = h.getDouble("r_squared"),
                    epochCount = h.getInt("epoch_count"),
                    eogSemRatePerMin = h.optDoubleOrNull("eog_sem_rate_per_min"),
                    eogWindowsAnalyzed = h.getInt("eog_windows_analyzed"),
                    epochsRejectedBadContact = h.optIntOrNull("epochs_rejected_bad_contact"),
                    epochsRejectedAmplitude = h.optIntOrNull("epochs_rejected_amplitude"),
                    epochsPartialContact = h.optIntOrNull("epochs_partial_contact"),
                    // optJSONObject is null for an absent key and for JSON null
                    // alike — both mean the analysis predates the field.
                    spectrum = h.optJSONObject("spectrum")?.let { eegSpectrumFrom(it) },
                    deflectionRatePerMin = h.optDoubleOrNull("deflection_rate_per_min"),
                    peaks = (0 until peaks.length()).map { j ->
                        val p = peaks.getJSONObject(j)
                        OscillationPeak(
                            band = p.getString("band"),
                            centerFrequency = p.getDouble("center_frequency"),
                            power = p.getDouble("power"),
                            bandwidth = p.getDouble("bandwidth"),
                        )
                    },
                )
            },
            analyzedAt = o.optStringOrNull("analyzed_at"),
            observedSampleRateHz = o.optDoubleOrNull("observed_sample_rate_hz"),
            lowFitRSquared = o.optDoubleOrNull("low_fit_r_squared"),
            interpolatedSampleFraction = o.optDoubleOrNull("interpolated_sample_fraction"),
            // Absent/null means "this analysis predates the field"; an empty
            // array means it ran and excluded nothing. Preserved as null vs
            // emptyList() rather than flattened into one "no exclusions."
            excludedChannelIndices = o.optJSONArray("excluded_channel_indices")?.let { arr ->
                (0 until arr.length()).map { arr.getInt(it) }
            },
            // Same null-vs-empty distinction: null predates the electrode check.
            electrodeFindings = o.optJSONArray("electrode_findings")?.let { arr ->
                (0 until arr.length()).map {
                    val f = arr.getJSONObject(it)
                    ElectrodeFinding(
                        channelName = f.getString("channel_name"),
                        half = f.getString("half"),
                        sign = f.getString("sign"),
                        value = f.getDouble("value"),
                        excluded = f.getBoolean("excluded"),
                        partnerName = f.optStringOrNull("partner_name"),
                    )
                }
            },
            allElectrodesFlagged = o.optBooleanOrNull("all_electrodes_flagged"),
            eyeActivityDiffers = o.optBooleanOrNull("eye_activity_differs"),
        )
    }

    /** Null on a malformed spectrum rather than throwing: a throw here would
     * fail the whole EegAnalysisResult parse and blank the analysis card over
     * what is only its picture. Null degrades to the card's "not recorded"
     * line with the numbers intact. */
    private fun eegSpectrumFrom(o: JSONObject): EegSpectrum? {
        fun doubles(key: String): List<Double>? {
            val arr = o.optJSONArray(key) ?: return null
            return (0 until arr.length()).map { arr.optDouble(it) }
        }
        return EegSpectrum(
            freqsHz = doubles("freqs_hz") ?: return null,
            logPower = doubles("log_power") ?: return null,
            logModelFit = doubles("log_model_fit") ?: return null,
            logAperiodicFit = doubles("log_aperiodic_fit") ?: return null,
        )
    }

    /** GET /patients/{id}/sessions */
    suspend fun listSessions(patientId: Int): List<SessionSummary> = withContext(Dispatchers.IO) {
        val arr = requestArray("GET", "/patients/$patientId/sessions")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SessionSummary(
                id = o.getInt("id"),
                modality = o.getString("modality"),
                startedAt = o.getString("started_at"),
                endedAt = o.getString("ended_at"),
            )
        }
    }

    /** GET /sessions/{id} */
    suspend fun getSession(sessionId: Int): SessionDetail = withContext(Dispatchers.IO) {
        val o = request("GET", "/sessions/$sessionId") ?: throw ApiException("Empty response")
        val markers = o.getJSONArray("markers")
        val samples = o.getJSONArray("samples")
        SessionDetail(
            id = o.getInt("id"),
            modality = o.getString("modality"),
            startedAt = o.getString("started_at"),
            endedAt = o.getString("ended_at"),
            practitionerNote = o.optStringOrNull("practitioner_note"),
            patientDozed = o.optBooleanOrNull("patient_dozed"),
            alertnessRating = o.optIntOrNull("alertness_rating"),
            baselineDurationSeconds = o.optIntOrNull("baseline_duration_seconds"),
            treatmentEndSeconds = o.optIntOrNull("treatment_end_seconds"),
            markers = (0 until markers.length()).map {
                val m = markers.getJSONObject(it)
                SessionMarker(m.getInt("offset_seconds"), m.getString("label"))
            },
            samples = (0 until samples.length()).map {
                val s = samples.getJSONObject(it)
                SessionSample(s.getInt("offset_seconds"), s.getString("band"), s.getDouble("value"))
            },
        )
    }
    /** GET /patients/{id}/eeg-trend — cross-session corrected-EEG metrics for
     * one patient. Practitioner-only server-side, same as the
     * single-session EEG analysis endpoints; server-ordered oldest-first
     * (chronological — the natural reading order for a trend), unlike every
     * other list endpoint this client calls. */
    suspend fun getPatientEegTrend(patientId: Int): List<EegTrendPoint> = withContext(Dispatchers.IO) {
        val arr = requestArray("GET", "/patients/$patientId/eeg-trend")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            EegTrendPoint(
                sessionId = o.getInt("session_id"),
                sessionStartedAt = o.getString("session_started_at"),
                half = o.getString("half"),
                period = o.getInt("period"),
                aperiodicOffset = o.getDouble("aperiodic_offset"),
                aperiodicExponent = o.getDouble("aperiodic_exponent"),
                rSquared = o.getDouble("r_squared"),
                epochCount = o.getInt("epoch_count"),
                eogSemRatePerMin = o.optDoubleOrNull("eog_sem_rate_per_min"),
                eogWindowsAnalyzed = o.getInt("eog_windows_analyzed"),
                lowFitRSquared = o.optDoubleOrNull("low_fit_r_squared"),
                deflectionRatePerMin = o.optDoubleOrNull("deflection_rate_per_min"),
                eyeActivityDiffers = o.optBooleanOrNull("eye_activity_differs"),
                excludedChannelNames = o.optJSONArray("excluded_channel_names")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                allElectrodesFlagged = o.optBooleanOrNull("all_electrodes_flagged"),
            )
        }
    }

    /** PATCH /sessions/{id} — set/edit the practitioner note after the fact. */
    suspend fun updateSessionNote(sessionId: Int, note: String?): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("practitioner_note", note ?: JSONObject.NULL)
        request("PATCH", "/sessions/$sessionId", body)
        Unit
    }

    /** DELETE /sessions/{id} — soft delete server-side (see sessions.py); the
     * session disappears from every list/get immediately but isn't destroyed. */
    suspend fun deleteSession(sessionId: Int): Unit = withContext(Dispatchers.IO) {
        request("DELETE", "/sessions/$sessionId")
        Unit
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else getInt(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key) || !has(key)) null else getDouble(key)

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (isNull(key) || !has(key)) null else getBoolean(key)
