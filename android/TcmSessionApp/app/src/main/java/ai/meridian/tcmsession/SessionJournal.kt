package ai.meridian.tcmsession

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Muse EEG channels per raw row (EEG1-EEG4) — what SessionRecorder captures. */
private const val EEG_CHANNELS = 4
private const val EEG_ROW_BYTES = 8 + EEG_CHANNELS * 4
private const val TAG = "SessionJournal"
private const val TRASH_PREFIX = ".trash-"

/**
 * An on-disk copy of one recording, so a session survives the app process
 * dying mid-recording (a crash, Android reclaiming memory, a force-stop).
 *
 * SessionRecorder's in-memory buffers are still what a normal End session
 * uploads; this is the backup. PatientListActivity offers to save any journal
 * nothing in this process is using (see [orphans]).
 *
 * One directory per recording under filesDir/recordings/ (excluded from
 * Android's backups, see res/xml/backup_rules.xml):
 *  - meta.json: patient, modality, start time, baseline, markers, the note as
 *    it's typed (within a second), and once End session is tapped the end
 *    time and answers, plus the server's session id once it has one.
 *    Rewritten whole (write, then rename) on every change.
 *  - samples.csv: one line per band per recorded second, appended each tick.
 *  - eeg.bin: raw EEG rows, `[int64 timestampUs, float32 × 4]` little-endian,
 *    appended about once a second (EegBinaryWriter.packStoredRows reads them).
 *
 * Two rules it exists to keep:
 *  - Upload-before-clear: deleted only after the server has the session AND
 *    its raw EEG, so a failed upload never loses a recording.
 *  - Never create a session twice. meta.json carries a client_session_id,
 *    made when the recording starts and sent with every attempt, and POST
 *    /sessions answers a repeat of it with the session it already made (a
 *    repeated POST /sessions/{id}/eeg just replaces the blob). The session id
 *    is still written to meta.json, and confirmed written, before anything
 *    else happens: a server too old to read client_session_id ignores it, and
 *    a journal from before it existed has none. If the id can't be written,
 *    the journal is deleted once the session exists: losing the raw EEG
 *    beats a duplicate session.
 *
 * File writes for a live journal run on one background thread, in call
 * order, so a delete can't overtake a pending write. Writes are flushed to
 * the OS each time, which survives the app process dying (the case this
 * exists for), not a power loss.
 */
class SessionJournal private constructor(val dir: File) {

    /** Everything about a recording except its samples and raw EEG. */
    data class Meta(
        val patientId: Int,
        val patientName: String,
        // Who recorded it: only they can save it (sessions belong to the
        // practitioner who created them), so only they are offered it.
        val practitionerId: Int?,
        val headbandDeviceId: String?,
        val modality: String,
        val startedAtIso: String,
        val baselineDurationSeconds: Int? = null,
        val treatmentEndSeconds: Int? = null,
        val markers: List<SessionMarker> = emptyList(),
        // Set when End session is tapped (null if the app died first).
        val endedAtIso: String? = null,
        val practitionerNote: String? = null,
        val patientDozed: Boolean? = null,
        val alertnessRating: Int? = null,
        // Set as soon as POST /sessions returns: from then on only the raw
        // EEG is left to upload.
        val sessionId: Int? = null,
        // Made when the recording starts and sent with every attempt to
        // create the session, so the server can recognise a repeat (see
        // ApiClient.createSession). Null only in a journal from before it
        // existed.
        val clientSessionId: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("version", 1)
            put("patient_id", patientId)
            put("patient_name", patientName)
            put("practitioner_id", practitionerId ?: JSONObject.NULL)
            put("headband_device_id", headbandDeviceId ?: JSONObject.NULL)
            put("modality", modality)
            put("started_at", startedAtIso)
            put("baseline_duration_seconds", baselineDurationSeconds ?: JSONObject.NULL)
            put("treatment_end_seconds", treatmentEndSeconds ?: JSONObject.NULL)
            put("markers", JSONArray().apply {
                markers.forEach { put(JSONObject().put("offset_seconds", it.offsetSeconds).put("label", it.label)) }
            })
            put("ended_at", endedAtIso ?: JSONObject.NULL)
            put("practitioner_note", practitionerNote ?: JSONObject.NULL)
            put("patient_dozed", patientDozed ?: JSONObject.NULL)
            put("alertness_rating", alertnessRating ?: JSONObject.NULL)
            put("session_id", sessionId ?: JSONObject.NULL)
            put("client_session_id", clientSessionId ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(o: JSONObject): Meta {
                val markersJson = o.optJSONArray("markers") ?: JSONArray()
                return Meta(
                    patientId = o.getInt("patient_id"),
                    patientName = o.optString("patient_name", "Patient"),
                    practitionerId = o.optIntOrNull("practitioner_id"),
                    headbandDeviceId = o.optStringOrNull("headband_device_id"),
                    modality = o.getString("modality"),
                    startedAtIso = o.getString("started_at"),
                    baselineDurationSeconds = o.optIntOrNull("baseline_duration_seconds"),
                    // Absent from a journal written before Treatment done
                    // existed; isNull() reads a missing key as null.
                    treatmentEndSeconds = o.optIntOrNull("treatment_end_seconds"),
                    markers = (0 until markersJson.length()).map {
                        val m = markersJson.getJSONObject(it)
                        SessionMarker(m.getInt("offset_seconds"), m.getString("label"))
                    },
                    endedAtIso = o.optStringOrNull("ended_at"),
                    practitionerNote = o.optStringOrNull("practitioner_note"),
                    patientDozed = if (o.isNull("patient_dozed")) null else o.optBoolean("patient_dozed"),
                    alertnessRating = o.optIntOrNull("alertness_rating"),
                    sessionId = o.optIntOrNull("session_id"),
                    clientSessionId = o.optStringOrNull("client_session_id"),
                )
            }

            private fun JSONObject.optStringOrNull(key: String): String? =
                if (isNull(key)) null else optString(key)

            private fun JSONObject.optIntOrNull(key: String): Int? =
                if (isNull(key)) null else optInt(key)
        }
    }

    /** What a recovery upload managed. It throws instead when it failed
     * before the session existed, leaving the journal untouched. */
    sealed class RecoveryResult {
        object Saved : RecoveryResult()
        /** The session exists but its raw EEG didn't upload. [kept] means the
         * journal stays for another try; false means trying again couldn't
         * help (the server refused it, or the session id couldn't be recorded
         * safely), so it was deleted. */
        data class SavedWithoutRawEeg(val reason: String, val kept: Boolean) : RecoveryResult()
        /** Already saved, deleted, or being handled elsewhere. */
        object AlreadyHandled : RecoveryResult()
    }

    private val metaFile = File(dir, "meta.json")
    private val samplesFile = File(dir, "samples.csv")
    private val eegFile = File(dir, "eeg.bin")

    private val io = Executors.newSingleThreadExecutor()
    // Opened lazily on the io thread, the only thread that touches them.
    private var samplesOut: BufferedWriter? = null
    private var eegOut: FileOutputStream? = null

    // Raw EEG arrives ~256 times a second on the SDK's callback thread, so
    // rows collect here and go to disk in one write per tick.
    private val eegLock = Any()
    private val eegPending = ByteArrayOutputStream()
    private val eegRow = ByteBuffer.allocate(EEG_ROW_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    @Volatile private var closed = false

    // ---- writing (called by SessionRecorder while it owns this journal) ----

    fun writeMeta(meta: Meta) {
        val json = meta.toJson().toString()
        runIo { if (!writeMetaFile(json)) throw IOException("rename failed") }
    }

    /** writeMeta, but waits and says whether it reached the disk. Used for
     * the session id, which must be on disk before the next step. Runs on
     * the same io thread as every other write, so it can't be overtaken by
     * (or race) one already queued. */
    suspend fun writeMetaNow(meta: Meta): Boolean {
        val json = meta.toJson().toString()
        return withContext(Dispatchers.IO) {
            try {
                io.submit(Callable { writeMetaFile(json) }).get(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "couldn't write meta in ${dir.name}: ${e.message}")
                false
            }
        }
    }

    // rename() over an existing file is atomic on Android's filesystems, so
    // a reader never sees a half-written meta.json.
    private fun writeMetaFile(json: String): Boolean {
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(json)
        return tmp.renameTo(metaFile)
    }

    /** Called on the SDK's callback thread for every raw EEG sample. */
    fun appendEeg(timestampUs: Long, ch1: Double, ch2: Double, ch3: Double, ch4: Double) {
        if (closed) return
        synchronized(eegLock) {
            eegRow.clear()
            eegRow.putLong(timestampUs)
            eegRow.putFloat(ch1.toFloat()); eegRow.putFloat(ch2.toFloat())
            eegRow.putFloat(ch3.toFloat()); eegRow.putFloat(ch4.toFloat())
            eegPending.write(eegRow.array(), 0, EEG_ROW_BYTES)
        }
    }

    /** One recorded second: that tick's band samples, plus whatever raw EEG
     * has arrived since the last one. */
    fun appendTick(samples: List<SessionSample>) {
        val lines = samples.joinToString("") { "${it.offsetSeconds},${it.band},${it.value}\n" }
        val eeg = takePendingEeg()
        runIo {
            val out = samplesOut ?: BufferedWriter(OutputStreamWriter(FileOutputStream(samplesFile, true))).also { samplesOut = it }
            out.write(lines)
            out.flush()
            writeEeg(eeg)
        }
    }

    /** Writes out any raw EEG still buffered — called when End session is
     * tapped, since the last tick has already run by then. */
    fun flushEeg() {
        val eeg = takePendingEeg()
        runIo { writeEeg(eeg) }
    }

    private fun takePendingEeg(): ByteArray = synchronized(eegLock) {
        eegPending.toByteArray().also { eegPending.reset() }
    }

    private fun writeEeg(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val out = eegOut ?: FileOutputStream(eegFile, true).also { eegOut = it }
        out.write(bytes)
        out.flush()
    }

    /** Deletes the journal once the server has everything. It stops being
     * findable at once (the directory is renamed to a trash name first,
     * atomically), and is removed after any writes still queued. */
    fun delete() {
        if (closed) return
        closed = true
        val trash = moveToTrash()
        // Renamed: invisible to orphans() already. Not renamed: it still has
        // its real name, so it stays counted as live until it's gone.
        if (trash != null) live.remove(dir.name)
        runIo {
            closeStreams()
            if (!(trash ?: dir).deleteRecursively()) Log.w(TAG, "couldn't fully delete ${dir.name}")
            live.remove(dir.name)
        }
        io.shutdown()
    }

    /** Stops writing but keeps the files — for a journal that still has
     * something to upload after its recorder is done with it. From here on
     * it's an orphan, which PatientListActivity offers to finish. */
    fun close() {
        if (closed) return
        closed = true
        // Stops counting as live only after its queued writes, so orphans()
        // never offers a journal that's still being written.
        runIo {
            closeStreams()
            live.remove(dir.name)
        }
        io.shutdown()
    }

    /** Waits for queued writes after [close] or [delete]; for unit tests. */
    internal fun awaitClosed() {
        io.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun closeStreams() {
        runCatching { samplesOut?.close() }
        runCatching { eegOut?.close() }
        samplesOut = null
        eegOut = null
    }

    /** The trash directory, or null if the rename failed (Android renames a
     * directory with files open in it; Windows, where the unit tests run,
     * doesn't), in which case the delete works on the original. */
    private fun moveToTrash(): File? {
        val trash = File(dir.parentFile, TRASH_PREFIX + dir.name)
        return if (dir.renameTo(trash)) trash else null
    }

    // A failed write is logged, never thrown: the backup must not take down
    // the recording it's backing up (the in-memory copy is unaffected).
    private fun runIo(block: () -> Unit) {
        try {
            io.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(TAG, "write failed in ${dir.name}: ${e.message}")
                }
            }
        } catch (e: RejectedExecutionException) {
            // Already deleted or closed.
        }
    }

    // ---- recovery (a journal nothing in this process is writing to; call
    // off the main thread) ----

    fun readMeta(): Meta? = runCatching { Meta.fromJson(JSONObject(metaFile.readText())) }
        .onFailure { if (dir.exists()) Log.w(TAG, "unreadable meta in ${dir.name}: ${it.message}") }
        .getOrNull()

    /** Every sample written so far. A last line cut short by the app dying
     * mid-write is skipped. */
    fun readSamples(): List<SessionSample> {
        if (!samplesFile.exists()) return emptyList()
        return samplesFile.readLines().mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size != 3) return@mapNotNull null
            val offset = parts[0].toIntOrNull() ?: return@mapNotNull null
            val value = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            SessionSample(offset, parts[1], value)
        }
    }

    fun hasRawEeg(): Boolean = eegFile.length() >= EEG_ROW_BYTES

    /** The raw EEG in POST /sessions/{id}/eeg's format, or null if none. */
    fun packRawEeg(): ByteArray? =
        if (!eegFile.exists()) null else EegBinaryWriter.packStoredRows(eegFile.readBytes(), EEG_CHANNELS)

    /** Deletes a journal found by [orphans], unless something in this
     * process has since started using it. */
    fun deleteNow() {
        // Claims the journal like uploadRecovered does, so a Delete from one
        // screen can't pull it out from under an upload started on another.
        if (dir.name in live || !inFlight.add(dir.name)) return
        try {
            closed = true
            io.shutdown()
            (moveToTrash() ?: dir).deleteRecursively()
        } finally {
            inFlight.remove(dir.name)
        }
    }

    /** Whether anything was recorded into it, for a journal with no readable
     * meta.json (the app died before its first write): one with nothing in
     * it can simply be cleared away. */
    fun hasAnyData(): Boolean = samplesFile.length() > 0 || eegFile.length() > 0

    /** Uploads what's left of a recovered journal, then deletes it. Reads the
     * journal fresh from disk rather than trusting what a dialog showed
     * earlier, and only one upload per journal can run at once, so a second
     * screen offering the same recording can't create the session twice.
     * Throws if it failed before the session existed, leaving the journal
     * as it was. Don't let it be cancelled partway (see PatientListActivity):
     * between POST /sessions succeeding and the id being written is the one
     * window where stopping would lose track of the session. */
    suspend fun uploadRecovered(context: Context): RecoveryResult {
        if (dir.name in live || !inFlight.add(dir.name)) return RecoveryResult.AlreadyHandled
        try {
            if (!dir.exists()) return RecoveryResult.AlreadyHandled
            val meta = readMeta() ?: throw IOException("The recording's details couldn't be read")
            val api = ApiClient(context)
            var sessionId = meta.sessionId
            var idRecorded = true
            if (sessionId == null) {
                val samples = readSamples()
                val lastSecond = samples.maxOfOrNull { it.offsetSeconds }
                    ?: meta.markers.maxOfOrNull { it.offsetSeconds } ?: 0
                // Never reached End session: say so on the chart, where
                // anyone reading the session will see it, and end it at the
                // last second actually recorded.
                val interrupted = meta.endedAtIso == null
                val markers = if (interrupted) {
                    meta.markers + SessionMarker(lastSecond, "Recording interrupted (the app closed) - recovered up to here")
                } else {
                    meta.markers
                }
                sessionId = api.createSession(
                    patientId = meta.patientId,
                    headbandDeviceId = meta.headbandDeviceId,
                    modality = meta.modality,
                    startedAtIso = meta.startedAtIso,
                    endedAtIso = meta.endedAtIso ?: endTimeFrom(meta.startedAtIso, lastSecond),
                    practitionerNote = meta.practitionerNote,
                    patientDozed = meta.patientDozed,
                    alertnessRating = meta.alertnessRating,
                    baselineDurationSeconds = meta.baselineDurationSeconds,
                    treatmentEndSeconds = meta.treatmentEndSeconds,
                    markers = markers,
                    samples = samples,
                    clientSessionId = meta.clientSessionId,
                )
                idRecorded = runCatching { writeMetaFile(meta.copy(sessionId = sessionId).toJson().toString()) }
                    .getOrDefault(false)
            }
            // A failed read isn't "no raw EEG": the file is still there.
            val blob = try {
                packRawEeg()
            } catch (e: Throwable) {
                if (!idRecorded) (moveToTrash() ?: dir).deleteRecursively()
                return RecoveryResult.SavedWithoutRawEeg("the raw recording couldn't be read (${e.message})", idRecorded)
            }
            if (blob != null) {
                try {
                    api.uploadRawEeg(sessionId, blob)
                } catch (e: Exception) {
                    val keep = idRecorded && !isServerRefusal(e)
                    if (!keep) (moveToTrash() ?: dir).deleteRecursively()
                    return RecoveryResult.SavedWithoutRawEeg(e.message ?: "unknown error", keep)
                }
            }
            (moveToTrash() ?: dir).deleteRecursively()
            return RecoveryResult.Saved
        } finally {
            inFlight.remove(dir.name)
        }
    }

    companion object {
        // Journals a SessionRecorder in this process is writing to. Added the
        // moment one is created, so a scan can never mistake a brand-new
        // recording for an abandoned one.
        private val live: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        // Journals a recovery upload is working on right now.
        private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        // Journals the practitioner put off ("Later", or an upload that
        // failed): offered again on the next app launch, not every time the
        // patient list reappears.
        private val postponed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        private fun root(context: Context) = File(context.filesDir, "recordings")

        /** Starts a journal for a new recording, or returns null if the
         * directory can't be created — the recording then runs in memory
         * only. */
        fun create(context: Context, meta: Meta, startedAtMs: Long): SessionJournal? =
            createIn(root(context), meta, startedAtMs)

        /** [create] under any directory; internal so unit tests can use a
         * temporary one. */
        internal fun createIn(root: File, meta: Meta, startedAtMs: Long): SessionJournal? {
            val dir = File(root, "p${meta.patientId}_$startedAtMs")
            // Live before it exists on disk, so no scan can ever see it
            // without also seeing that it's live.
            live.add(dir.name)
            if (!dir.mkdirs() && !dir.isDirectory) {
                live.remove(dir.name)
                Log.w(TAG, "couldn't create ${dir.path}")
                return null
            }
            return SessionJournal(dir).also { it.writeMeta(meta) }
        }

        fun postpone(journal: SessionJournal) {
            postponed.add(journal.dir.name)
        }

        /** The process-wide sets are keyed by directory name alone; unit
         * tests reuse names across temporary roots. */
        internal fun resetForTests() {
            live.clear()
            inFlight.clear()
            postponed.clear()
        }

        /** Journals nothing in this process is using, oldest first, minus
         * any postponed until the next launch: left behind by an app process
         * that died, or kept because an upload failed. Also clears out
         * deletes a dying process didn't finish. Call off the main thread. */
        fun orphans(context: Context): List<SessionJournal> = orphansIn(root(context))

        internal fun orphansIn(root: File): List<SessionJournal> {
            val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
            dirs.filter { it.name.startsWith(TRASH_PREFIX) }.forEach { it.deleteRecursively() }
            return dirs.filter {
                !it.name.startsWith(TRASH_PREFIX) && it.name !in live && it.name !in inFlight && it.name !in postponed
            }
                .sortedBy { it.name }
                .map { SessionJournal(it) }
        }

        /** Recovered end time: the recording's last saved second, since the
         * app died without an End session. */
        fun endTimeFrom(startedAtIso: String, lastOffsetSeconds: Int): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val start = runCatching { fmt.parse(startedAtIso) }.getOrNull() ?: return startedAtIso
            return fmt.format(java.util.Date(start.time + (lastOffsetSeconds + 1) * 1000L))
        }
    }
}
