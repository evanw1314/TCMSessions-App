package ai.meridian.tcmsession

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.choosemuse.libmuse.ConnectionState
import com.choosemuse.libmuse.Eeg
import com.choosemuse.libmuse.Muse
import com.choosemuse.libmuse.MuseArtifactPacket
import com.choosemuse.libmuse.MuseConnectionListener
import com.choosemuse.libmuse.MuseConnectionPacket
import com.choosemuse.libmuse.MuseDataListener
import com.choosemuse.libmuse.MuseDataPacket
import com.choosemuse.libmuse.MuseDataPacketType
import com.choosemuse.libmuse.MuseListener
import com.choosemuse.libmuse.MuseManagerAndroid
import com.choosemuse.libmuse.MusePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Auto-reconnect tuning: a Muse S that loses its BLE link powers itself off
// after roughly a minute of staying disconnected, and once it's off nothing
// short of the physical button wakes it — so the whole point of retrying is
// to land a fresh connect() well inside that window, not after it. 20
// attempts * 1.5s ≈ 30s of retrying, comfortably inside the ~60s budget with
// margin for the connect handshake itself.
private const val RECONNECT_MAX_ATTEMPTS = 20
private const val RECONNECT_DELAY_MS = 1500L

/**
 * Owner of "a session currently being recorded" for ONE patient — BLE
 * connection, band-power buffer, markers, and the upload at the end. One
 * instance PER PATIENT (Companion.obtain), so two patients' recordings — two
 * different Muse headbands — can run concurrently on the same phone. Each
 * instance runs on its own main-thread Handlers for as long as the app
 * process is alive, independent of which Activity (if any) is on screen, so
 * leaving the screen doesn't stop a recording; SessionActivity is a thin,
 * reattachable view onto whichever instance matches its patient.
 * RecordingService keeps the process alive while any recorder is recording or
 * saving, so backgrounding the app or locking the phone doesn't stop it, and
 * SessionJournal writes each recording to disk as it goes, so even the
 * process dying doesn't lose it.
 *
 * The BLE *scan* (Companion.startScan/museOptions) is shared across all
 * patients on purpose — it's one shared "here's what's nearby" result list
 * that every currently-open SessionActivity picks a DIFFERENT device out of.
 * Companion.ownerOf() is what stops two patients' recordings from both
 * claiming the same physical headband.
 */
class SessionRecorder private constructor(val patientId: Int) {

    enum class Phase { IDLE, RECORDING, SAVING, SAVE_FAILED }

    sealed class ResultEvent {
        object Saved : ResultEvent()
        data class Failed(val message: String) : ResultEvent()
    }

    fun interface Listener {
        fun onSessionStateChanged()
    }

    companion object {
        // One recorder per patient, created on first use and kept for the
        // life of the app process (not evicted when a session finishes —
        // an idle, disconnected entry is harmless, and keeping it around
        // lets a same-patient reattach find it instantly).
        private val instances = mutableMapOf<Int, SessionRecorder>()

        private lateinit var appContext: Context
        private lateinit var manager: MuseManagerAndroid
        private var managerReady = false

        // Shared discovered-device list — one BLE scan, visible to whichever
        // SessionActivity is currently on screen regardless of which
        // patient it's for.
        var museOptions: List<Muse> = emptyList(); private set

        // Only used to hop the SDK's museListChanged callback (which the SDK
        // may deliver off the main thread) back onto the main thread.
        private val mainPostHandler = Handler(Looper.getMainLooper())

        private fun ensureManager(context: Context) {
            if (managerReady) return
            appContext = context.applicationContext
            manager = MuseManagerAndroid.getInstance()
            manager.setContext(appContext)
            manager.setMuseListener(object : MuseListener() {
                override fun museListChanged() {
                    mainPostHandler.post {
                        museOptions = manager.muses
                        // Every currently-attached listener cares that the
                        // scan results changed, not just whichever recorder
                        // happened to trigger setup — notify all of them.
                        instances.values.forEach { it.listener?.onSessionStateChanged() }
                    }
                }
            })
            managerReady = true
        }

        /** The entry point SessionActivity uses. Returns THIS patient's
         * recorder — reattaches to it as-is if it's already recording,
         * saving, retrying a failed save, or just still connected;
         * otherwise resets that patient's own slot fresh. Never touches any
         * OTHER patient's recorder, which is what makes two concurrent
         * recordings for two different patients safe — there's no shared
         * mutable "the current session" left to collide on. */
        fun obtain(context: Context, patientId: Int, patientName: String, headbandDeviceId: String?): SessionRecorder {
            ensureManager(context)
            val recorder = instances.getOrPut(patientId) { SessionRecorder(patientId) }
            recorder.attachOrReset(patientName, headbandDeviceId)
            return recorder
        }

        /** Looked up (never created) by PatientDetailActivity's "ongoing"
         * badge — a patient with no recorder yet, or an idle one, has none. */
        fun activeForPatient(patientId: Int): SessionRecorder? =
            instances[patientId]?.takeIf { it.phase != Phase.IDLE }

        /** Whichever recorder (if any) currently has `muse` connected to —
         * used by connect() so one physical headband can't be claimed by two
         * patients' recordings at once (regardless of whether the existing
         * claim is actively recording or just sitting connected). */
        private fun ownerOf(target: Muse): SessionRecorder? =
            instances.values.firstOrNull { it.muse === target }

        fun startScan() {
            manager.stopListening()
            manager.startListening()
        }

        /** Same reasoning as the SDK example: avoid a LibMuse resource leak.
         * Only stops the SCAN — no recorder's connection is touched. */
        fun stopScanListening() {
            manager.stopListening()
        }

        /** Keeps RecordingService running exactly while some patient is
         * recording or saving. */
        private fun refreshRecordingService() {
            if (!managerReady) return
            RecordingService.update(
                appContext,
                recording = instances.values.filter { it.phase == Phase.RECORDING }.map { it.patientName },
                saving = instances.values.filter { it.phase == Phase.SAVING }.map { it.patientName },
            )
        }
    }

    // ---- attached UI (at most one Activity instance cares about THIS
    // patient's recorder at a time) ----
    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    private fun notifyListener() {
        listener?.onSessionStateChanged()
    }

    var patientName: String = "Patient"; private set
    private var headbandDeviceId: String? = null

    // Bumped every time attachOrReset() resets this instance to a genuinely
    // fresh session. Structurally this should never fire while an upload for
    // THIS instance is still in flight (phase only leaves SAVING inside the
    // same synchronous coroutine-resumption that also sets it back to IDLE,
    // and a fresh reset only ever runs from IDLE) — kept anyway as a cheap
    // defensive guard against a future change reopening that window.
    private var generation = 0

    // ---- BLE (this patient's own connection) ----
    var muse: Muse? = null; private set
    private var autoReconnectArmed = false
    private var reconnectAttempts = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    // Handler dedicated to hopping SDK-thread callbacks back onto the main
    // thread (there's no Activity to runOnUiThread on). Per-instance so clearing
    // it (removeCallbacksAndMessages) can never cancel another patient's
    // pending callbacks.
    private val callbackHandler = Handler(Looper.getMainLooper())
    var connectionStatusText: String = "Not connected"; private set

    // ---- recording ----
    // @Volatile: the SDK's callback thread reads it to decide whether to keep
    // a raw EEG sample.
    @Volatile var phase: Phase = Phase.IDLE
        private set(value) {
            field = value
            refreshRecordingService()
        }
    // The on-disk copy of the current recording (see SessionJournal), from
    // startSession until the server has it. Null if it couldn't be created,
    // in which case the recording runs in memory only.
    // @Volatile: appended to from the SDK's callback thread (raw EEG).
    @Volatile private var journal: SessionJournal? = null
    // What journal's meta.json currently says. Main thread only.
    private var journalMeta: SessionJournal.Meta? = null
    // The note box as typed (SessionActivity mirrors every edit here), so it
    // survives leaving the screen and reaches the journal - written on the
    // next tick or when the screen is left, not on every keystroke.
    var draftNote: String = ""; private set
    private var draftNoteUnsaved = false
    // Shown for the whole recording when the journal couldn't be created.
    // Kept apart from errorText because a reconnect clears that.
    private var backupWarning = ""
    private var sessionStartElapsedMs = 0L
    // Exposed (read-only) so a patient's TCM session list can show a "started
    // at" time for the not-yet-saved session it's synthesizing client-side —
    // see PatientDetailActivity.SessionRow.Ongoing.
    var sessionStartedAtIso: String = ""; private set
    private var selectedModality = "Acupuncture"
    private val latestBand = mutableMapOf(
        "alpha" to NO_SIGNAL_VALUE, "beta" to NO_SIGNAL_VALUE, "delta" to NO_SIGNAL_VALUE,
        "theta" to NO_SIGNAL_VALUE, "gamma" to NO_SIGNAL_VALUE,
        "alpha_absolute" to NO_SIGNAL_VALUE, "beta_absolute" to NO_SIGNAL_VALUE,
        "delta_absolute" to NO_SIGNAL_VALUE, "theta_absolute" to NO_SIGNAL_VALUE,
        "gamma_absolute" to NO_SIGNAL_VALUE,
    )
    private val samples = mutableListOf<SessionSample>()
    private val markers = mutableListOf<SessionMarker>()
    // Elapsed seconds at "baseline done, treatment started" — see
    // markBaselineEnd(). Null means no baseline phase was marked.
    var baselineDurationSeconds: Int? = null; private set
    // Elapsed seconds at "Treatment done" — see markTreatmentEnd(). Null
    // means it wasn't tapped.
    var treatmentEndSeconds: Int? = null; private set
    // Raw EEG capture buffer — [muse timestamp, ch1, ch2, ch3, ch4] per
    // sample, ~256Hz. See receiveMuseDataPacket's EEG case. Packed by
    // EegBinaryWriter and uploaded via POST /sessions/{id}/eeg at session end
    // (see endSession/uploadRawEegBestEffort) — NOT folded into `samples`,
    // which stays the once-a-second band-power series the screens, export
    // and AI summary read.
    private val rawEegSamples = mutableListOf<DoubleArray>()
    // Set by receiveMuseArtifactPacket, consumed and cleared by the next
    // tick: the headband reporting it isn't seated corrupts every band's
    // reading for that instant, so a tick that saw it is recorded as
    // NO_SIGNAL_VALUE across all bands rather than a plausible-looking but
    // meaningless number. @Volatile: written on the SDK's callback thread,
    // consumed on the main thread by the tick.
    //
    // The SDK's other two artifact flags are deliberately NOT used to drop
    // ticks:
    // - blink: on-device it showed in 48% of 1-second ticks of a calm
    //   baseline, so gating on it discarded most of a good recording.
    // - jawClench: this headband's detector is unreliable. With the wearer
    //   sitting still it reported a clench in every packet (~10 a second) for
    //   minutes at a time, and switched itself off for 20-45 s stretches and
    //   back on with nothing changing. Session 13 lost all 249 of its ticks to
    //   it while its raw EEG showed a clean alpha peak. It also missed most of
    //   a sustained jaw clench and fired on a lifted electrode (sessions 23
    //   and 24). Detecting a "stuck" detector doesn't work either: its usual
    //   start-up behavior (often ~100% for the first 10-30 s) looks the same.
    // So the per-second band-power series includes muscle artifact
    // unfiltered. The raw-EEG analysis doesn't use these flags; it runs its
    // own amplitude-based epoch rejection
    // (eeg.analysis._epoch_and_reject). All three flags are still logged once
    // a second (logArtifactFlags), in case a firmware or SDK update makes
    // them trustworthy again.
    @Volatile private var headbandOffSinceLastTick = false
    // Whether a relative band-power packet with a usable value arrived since
    // the last tick. latestBand keeps its last value forever (nothing clears
    // it on disconnect or all-NaN contact), so without this a dropped
    // connection would keep saving the same frozen numbers every second and
    // count them as healthy. See the tick.
    @Volatile private var bandPacketSinceLastTick = false
    var errorText: String = ""; private set
    private var pendingResultEvent: ResultEvent? = null

    // Per-instance so two patients recording at once tick independently and
    // one ending its session never cancels the other's scheduled tick.
    private val tickHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else callbackHandler.post(block)
    }

    fun isConnected(): Boolean = muse?.connectionState == ConnectionState.CONNECTED

    private fun attachOrReset(newPatientName: String, newHeadbandDeviceId: String?) {
        if (phase != Phase.IDLE || muse != null) {
            patientName = newPatientName
            headbandDeviceId = newHeadbandDeviceId
            return
        }
        // muse is guaranteed null here (the check above already reattaches
        // otherwise) — nothing of THIS patient's own to disconnect. This
        // deliberately does NOT disconnect other discovered devices: that
        // would tear down a DIFFERENT patient's live recording.
        generation++
        autoReconnectArmed = false
        reconnectAttempts = 0
        reconnectHandler.removeCallbacksAndMessages(null)
        connectionStatusText = "Not connected"
        phase = Phase.IDLE
        sessionStartElapsedMs = 0L
        sessionStartedAtIso = ""
        latestBand.keys.toList().forEach { latestBand[it] = NO_SIGNAL_VALUE }
        headbandOffSinceLastTick = false
        bandPacketSinceLastTick = false
        samples.clear()
        markers.clear()
        baselineDurationSeconds = null; treatmentEndSeconds = null
        rawEegSamples.clear()
        // Only reachable from IDLE, where endSession has already let go of
        // its journal; if one were somehow still attached, close it rather
        // than delete it, so PatientListActivity can still offer it.
        journal?.close()
        journal = null
        journalMeta = null
        draftNote = ""
        draftNoteUnsaved = false
        errorText = ""
        pendingResultEvent = null
        patientName = newPatientName
        headbandDeviceId = newHeadbandDeviceId
    }

    fun setError(message: String) {
        // Clearing an error mid-recording (e.g. starting a voice marker)
        // mustn't hide that there's no backup copy.
        errorText = if (message.isEmpty() && phase == Phase.RECORDING) backupWarning else message
        notifyListener()
    }

    fun connect(museIndex: Int): Boolean {
        val options = museOptions
        if (museIndex < 0 || museIndex >= options.size) return false
        val selected = options[museIndex]

        val existingOwner = ownerOf(selected)
        if (existingOwner != null && existingOwner !== this) {
            errorText = "This headband is already connected for ${existingOwner.patientName} — pick a different one"
            notifyListener()
            return false
        }

        muse = selected
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectAttempts = 0
        autoReconnectArmed = true
        errorText = ""

        selected.unregisterAllListeners()
        selected.registerConnectionListener(connectionListener)
        selected.registerDataListener(dataListener, MuseDataPacketType.ALPHA_RELATIVE)
        selected.registerDataListener(dataListener, MuseDataPacketType.BETA_RELATIVE)
        selected.registerDataListener(dataListener, MuseDataPacketType.DELTA_RELATIVE)
        selected.registerDataListener(dataListener, MuseDataPacketType.THETA_RELATIVE)
        selected.registerDataListener(dataListener, MuseDataPacketType.GAMMA_RELATIVE)
        selected.registerDataListener(dataListener, MuseDataPacketType.ALPHA_ABSOLUTE)
        selected.registerDataListener(dataListener, MuseDataPacketType.BETA_ABSOLUTE)
        selected.registerDataListener(dataListener, MuseDataPacketType.DELTA_ABSOLUTE)
        selected.registerDataListener(dataListener, MuseDataPacketType.THETA_ABSOLUTE)
        selected.registerDataListener(dataListener, MuseDataPacketType.GAMMA_ABSOLUTE)
        selected.registerDataListener(dataListener, MuseDataPacketType.ARTIFACTS)
        selected.registerDataListener(dataListener, MuseDataPacketType.EEG)

        // PRESET_21 matches the SDK's own example.
        selected.setPreset(MusePreset.PRESET_21)
        selected.runAsynchronously()
        connectionStatusText = "Connecting..."
        notifyListener()
        return true
    }

    private val connectionListener = object : MuseConnectionListener() {
        override fun receiveMuseConnectionPacket(p: MuseConnectionPacket, m: Muse) {
            onMain {
                // Ignore packets from a headband that isn't the one currently
                // selected — connect() never unregisters the PREVIOUS muse's
                // listener, only the newly-picked one's.
                if (m !== muse) return@onMain

                when (val state = p.currentConnectionState) {
                    ConnectionState.CONNECTED -> {
                        reconnectAttempts = 0
                        reconnectHandler.removeCallbacksAndMessages(null)
                        connectionStatusText = state.toString()
                        if (phase == Phase.RECORDING) errorText = backupWarning
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (phase == Phase.RECORDING) {
                            errorText = "Headband disconnected — samples so far are kept, attempting to reconnect"
                        }
                        attemptAutoReconnect(m)
                    }
                    else -> connectionStatusText = state.toString()
                }
                notifyListener()
            }
        }
    }

    private fun attemptAutoReconnect(target: Muse) {
        if (!autoReconnectArmed) return
        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
            connectionStatusText = "Disconnected — auto-reconnect gave up, press the headband's button and tap Connect"
            notifyListener()
            return
        }
        reconnectAttempts++
        connectionStatusText = "Reconnecting… (attempt $reconnectAttempts/$RECONNECT_MAX_ATTEMPTS)"
        notifyListener()
        reconnectHandler.postDelayed({
            if (autoReconnectArmed && target === muse && target.connectionState != ConnectionState.CONNECTED) {
                target.runAsynchronously()
            }
        }, RECONNECT_DELAY_MS)
    }

    private val dataListener = object : MuseDataListener() {
        override fun receiveMuseDataPacket(p: MuseDataPacket, m: Muse) {
            // Ignore packets from a headband that isn't the one currently
            // selected — same reasoning as connectionListener above.
            if (m !== muse) return

            // Raw EEG capture. Buffered in memory during RECORDING, packed (EegBinaryWriter)
            // and uploaded via POST /sessions/{id}/eeg at session end (see
            // endSession/uploadRawEegBestEffort) for the server's mne +
            // specparam pipeline (app/eeg/analysis.py) to process on demand.
            // Arrives far faster than the 1-second band-power tick (~256Hz
            // vs ~1Hz), so it's appended directly here, not through
            // latestBand/tickRunnable.
            if (p.packetType() == MuseDataPacketType.EEG) {
                if (phase == Phase.RECORDING) {
                    val ch1 = p.getEegChannelValue(Eeg.EEG1)
                    val ch2 = p.getEegChannelValue(Eeg.EEG2)
                    val ch3 = p.getEegChannelValue(Eeg.EEG3)
                    val ch4 = p.getEegChannelValue(Eeg.EEG4)
                    synchronized(rawEegSamples) {
                        rawEegSamples.add(doubleArrayOf(p.timestamp().toDouble(), ch1, ch2, ch3, ch4))
                    }
                    journal?.appendEeg(p.timestamp(), ch1, ch2, ch3, ch4)
                }
                return
            }

            // ABSOLUTE alongside RELATIVE: relative band power is a share of
            // total power (all 5 sum to ~100%), so a rise in one band forces
            // the others down whether or not anything real changed — a
            // closed-arithmetic artifact on top of the drowsiness/aperiodic-
            // exponent confound (docs/eeg-aperiodic-correction.md). Absolute
            // power doesn't have that particular problem (each band is
            // independent), though it's still uncorrected raw band power and
            // shares the same 1/f-background confound. Captured under
            // separate "*_absolute" band names; charts/export/AI summary only
            // look at BAND_ORDER's 5 relative names, so nothing reads it yet.
            val band = when (p.packetType()) {
                MuseDataPacketType.ALPHA_RELATIVE -> "alpha"
                MuseDataPacketType.BETA_RELATIVE -> "beta"
                MuseDataPacketType.DELTA_RELATIVE -> "delta"
                MuseDataPacketType.THETA_RELATIVE -> "theta"
                MuseDataPacketType.GAMMA_RELATIVE -> "gamma"
                MuseDataPacketType.ALPHA_ABSOLUTE -> "alpha_absolute"
                MuseDataPacketType.BETA_ABSOLUTE -> "beta_absolute"
                MuseDataPacketType.DELTA_ABSOLUTE -> "delta_absolute"
                MuseDataPacketType.THETA_ABSOLUTE -> "theta_absolute"
                MuseDataPacketType.GAMMA_ABSOLUTE -> "gamma_absolute"
                else -> return
            }
            // Average across the 4 EEG channels (not AUX_LEFT/AUX_RIGHT).
            // NaN means contact quality was too poor to compute band power
            // for that channel; skip those rather than poisoning the average.
            // Also skip non-finite values (e.g. ABSOLUTE band power on a
            // zero-power channel logs out to -Infinity) — one infinite
            // channel would otherwise drag the whole average to +/-Infinity,
            // which org.json refuses to serialize at upload time (see
            // ApiClient.createSession's matching guard).
            val channelValues = listOf(
                p.getEegChannelValue(Eeg.EEG1), p.getEegChannelValue(Eeg.EEG2),
                p.getEegChannelValue(Eeg.EEG3), p.getEegChannelValue(Eeg.EEG4),
            ).filter { it.isFinite() }
            if (channelValues.isNotEmpty()) {
                latestBand[band] = channelValues.average()
                if (band in BAND_ORDER) bandPacketSinceLastTick = true
            }
        }

        override fun receiveMuseArtifactPacket(p: MuseArtifactPacket, m: Muse) {
            if (m !== muse) return
            // Only headbandOn drops ticks — see headbandOffSinceLastTick's doc
            // for why blink and jawClench don't.
            if (!p.headbandOn) headbandOffSinceLastTick = true
            logArtifactFlags(p)
        }
    }

    // A once-a-second debug line of all three SDK artifact flags, so a
    // firmware or SDK update that fixes the jaw-clench detector (see
    // headbandOffSinceLastTick) can be recognized. Runs on the SDK's callback
    // thread, hence the lock.
    private val artifactLogLock = Any()
    private var logPackets = 0
    private var logHeadbandOff = 0
    private var logJawClench = 0
    private var logBlink = 0
    private var logWindowStartMs = 0L

    private fun logArtifactFlags(p: MuseArtifactPacket) {
        synchronized(artifactLogLock) {
            val now = SystemClock.elapsedRealtime()
            if (logWindowStartMs == 0L) logWindowStartMs = now
            logPackets++
            if (!p.headbandOn) logHeadbandOff++
            if (p.jawClench) logJawClench++
            if (p.blink) logBlink++
            if (now - logWindowStartMs < 1000) return
            android.util.Log.d(
                "SessionRecorder",
                "artifacts/1s: packets=$logPackets headbandOff=$logHeadbandOff jawClench=$logJawClench " +
                    "blink=$logBlink phase=$phase",
            )
            logPackets = 0; logHeadbandOff = 0; logJawClench = 0; logBlink = 0
            logWindowStartMs = now
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (phase == Phase.RECORDING) {
                val elapsed = liveElapsedSeconds()
                // Consume-and-clear: headband-off flagged any time since the
                // last tick taints every band's reading for THIS tick only —
                // the next tick starts clean.
                val headbandOff = headbandOffSinceLastTick
                // No fresh band packet this second means latestBand holds an
                // old reading (a dropped connection, or every channel NaN),
                // so it's saved as no-signal rather than a frozen repeat.
                val stale = !bandPacketSinceLastTick
                headbandOffSinceLastTick = false
                bandPacketSinceLastTick = false
                val dropTick = headbandOff || stale
                val tickSamples = latestBand.keys.toList().map { band ->
                    val value = latestBand[band] ?: NO_SIGNAL_VALUE
                    // !isFinite() catches NaN AND +/-Infinity — see the
                    // matching note on channelValues above.
                    val safeValue = if (dropTick || !value.isFinite()) NO_SIGNAL_VALUE else value
                    SessionSample(elapsed, band, safeValue)
                }
                samples.addAll(tickSamples)
                journal?.appendTick(tickSamples)
                flushDraftNote()
                // A tick with no fresh or no usable relative-band value saved
                // nothing either, even though no artifact flag fired (e.g. the
                // first second, before any band packet has arrived).
                val noSignal = stale || BAND_ORDER.all { band ->
                    val v = latestBand[band] ?: NO_SIGNAL_VALUE
                    v == NO_SIGNAL_VALUE || !v.isFinite()
                }
                val status = when {
                    headbandOff -> TickStatus.HEADBAND_OFF
                    noSignal -> TickStatus.NO_SIGNAL
                    else -> TickStatus.SAVED
                }
                lastTickStatus = status
                tickTally[status] = (tickTally[status] ?: 0) + 1
                notifyListener()
                tickHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun liveElapsedSeconds(): Int =
        if (sessionStartElapsedMs == 0L) 0
        else ((SystemClock.elapsedRealtime() - sessionStartElapsedMs) / 1000).toInt()

    /** Only meaningful (and only rendered by SessionActivity) while
     * phase == RECORDING — SessionActivity stops reading it once recording
     * stops, so it freezes at its last value instead of drifting upward
     * while a save is in flight or retrying. */
    fun currentElapsedSeconds(): Int = liveElapsedSeconds()

    /** Relative bands only (the original 5) — the absolute-power series
     * ("alpha_absolute" etc., see receiveMuseDataPacket) is captured for
     * later analysis but deliberately left out of the live in-session
     * readout, which is meant to be five values at a glance, not ten on two
     * different scales. */
    fun bandValuesSnapshot(): Map<String, Double> = latestBand.filterKeys { it in BAND_ORDER }

    fun markersSnapshot(): List<SessionMarker> = markers.toList()

    /** What happened to each recorded second's band power — shown live on
     * the recording screen so seconds being thrown away can't go unnoticed
     * (see headbandOffSinceLastTick for the session where they did). */
    enum class TickStatus { SAVED, HEADBAND_OFF, NO_SIGNAL }

    var lastTickStatus: TickStatus? = null; private set
    private val tickTally = mutableMapOf<TickStatus, Int>()

    fun tickTallySnapshot(): Map<TickStatus, Int> = tickTally.toMap()

    fun startSession(modality: String) {
        if (phase != Phase.IDLE) return
        selectedModality = modality
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        sessionStartedAtIso = isoNow()
        samples.clear()
        markers.clear()
        baselineDurationSeconds = null; treatmentEndSeconds = null
        rawEegSamples.clear()
        errorText = ""
        val meta = SessionJournal.Meta(
            patientId = patientId,
            patientName = patientName,
            practitionerId = AppConfig.userId(appContext).takeIf { it >= 0 },
            headbandDeviceId = headbandDeviceId,
            modality = modality,
            startedAtIso = sessionStartedAtIso,
            // Kept in journalMeta even with no journal on disk, so a retried
            // End session resends the same one.
            clientSessionId = java.util.UUID.randomUUID().toString(),
        )
        journal = SessionJournal.create(appContext, meta, System.currentTimeMillis())
        journalMeta = meta
        backupWarning = if (journal == null) {
            "Couldn't save a backup copy of this recording on the phone — it's only kept in " +
                "memory, so keep the app open until you tap End session"
        } else {
            ""
        }
        errorText = backupWarning
        // Set last, once the journal exists: the phase change starts
        // RecordingService, and the first EEG packet can arrive right after.
        phase = Phase.RECORDING
        pendingResultEvent = null
        lastTickStatus = null
        tickTally.clear()
        // A flag left over from before recording would let second 0 save a
        // reading from whenever it was set; start every session with a clean one.
        bandPacketSinceLastTick = false
        notifyListener()
        tickHandler.post(tickRunnable)
    }

    fun addMarker(elapsedSeconds: Int, label: String) {
        markers.add(SessionMarker(elapsedSeconds, label))
        saveMarkersToJournal()
        notifyListener()
    }

    /** Markers, the baseline end and Treatment done go into the journal's
     * meta.json as they change, so a recovered session keeps them. */
    private fun saveMarkersToJournal() {
        updateJournalMeta {
            it.copy(
                markers = markers.toList(), baselineDurationSeconds = baselineDurationSeconds,
                treatmentEndSeconds = treatmentEndSeconds,
            )
        }
    }

    /** Mirrors the note box (SessionActivity calls this on every edit). */
    fun updateDraftNote(text: String) {
        if (text == draftNote) return
        draftNote = text
        draftNoteUnsaved = true
    }

    /** Writes the draft note into the journal, so a recovered session keeps
     * it. Runs each tick while recording and when the screen is left. (The
     * note box only shows once a session has started.) */
    fun flushDraftNote() {
        if (!draftNoteUnsaved || journalMeta == null) return
        draftNoteUnsaved = false
        updateJournalMeta { it.copy(practitionerNote = draftNote.trim().ifBlank { null }) }
    }

    private fun updateJournalMeta(change: (SessionJournal.Meta) -> SessionJournal.Meta) {
        val next = change(journalMeta ?: return)
        journalMeta = next
        journal?.writeMeta(next)
    }

    /** Marks the end of an eyes-closed, no-treatment baseline phase and the
     * start of active treatment: recording a short baseline at the start of
     * the SAME session lets the analysis pipeline compare a session against
     * its own resting state instead of population norms (see
     * eeg.analysis.analyze_session_eeg). Entirely optional: without it the
     * session has no baseline. Also drops an ordinary marker at the same
     * offset so the transition shows up on the band-power chart like any
     * other marker. Idempotent no-op once already marked, or outside
     * RECORDING (e.g. called twice, or after the session already ended).
     * Also a no-op once Treatment done is marked: the baseline would then end
     * after the treatment did, which the server refuses (and a refused save
     * would keep failing on every retry). And a no-op before
     * BandShares.MIN_BASELINE_SECONDS, so the baseline always holds a full
     * minute after the sensors settle; the recording screen counts down to
     * it, so an earlier tap can't happen from there. */
    fun markBaselineEnd() {
        if (phase != Phase.RECORDING || baselineDurationSeconds != null || treatmentEndSeconds != null) return
        val elapsed = currentElapsedSeconds()
        if (elapsed < BandShares.MIN_BASELINE_SECONDS) return
        baselineDurationSeconds = elapsed
        addMarker(elapsed, "Baseline ended — treatment started")
    }

    /** Marks the end of the treatment itself, before the needles come out and
     * the patient moves and talks. The session card's before/after band-share
     * pies take their "after" minute from just before this, because the
     * wrap-up minute is mostly movement: in the research sessions, blinking or moving the eyes alone moved the delta share by 17-42 points
     * (see models.TcmSession.treatment_end_seconds). Optional, like the
     * baseline; not tapping it leaves the pies using the recording's last
     * minute, with a warning. Drops an ordinary marker too, so it shows on
     * the band-power chart. Idempotent no-op once marked, or outside
     * RECORDING. Always at or after the baseline mark, since that one can
     * only be tapped earlier (see markBaselineEnd).
     *
     * Also a no-op before BandShares.earliestTreatmentEndSeconds: a tap that
     * early can only be a slip (the button takes Baseline done's spot, so a
     * double tap there lands on it), and it would leave the two pie minutes
     * overlapping, costing the session its pies with no way to undo it. The
     * recording screen holds the button until then too. */
    fun markTreatmentEnd() {
        if (phase != Phase.RECORDING || treatmentEndSeconds != null) return
        val elapsed = currentElapsedSeconds()
        if (elapsed < BandShares.earliestTreatmentEndSeconds(baselineDurationSeconds)) return
        treatmentEndSeconds = elapsed
        addMarker(elapsed, "Treatment done")
    }

    fun updateMarker(index: Int, label: String) {
        if (index !in markers.indices) return
        markers[index] = markers[index].copy(label = label)
        saveMarkersToJournal()
        notifyListener()
    }

    fun deleteMarker(index: Int) {
        if (index !in markers.indices) return
        markers.removeAt(index)
        saveMarkersToJournal()
        notifyListener()
    }

    /** Kicks off (or retries, from SAVE_FAILED) the upload. Uses its own
     * CoroutineScope rather than the caller's lifecycleScope — deliberately,
     * so the upload survives the Activity that triggered it being destroyed
     * mid-request. The generation guard is explained on the field itself. */
    fun endSession(practitionerNote: String?, patientDozed: Boolean? = null, alertnessRating: Int? = null) {
        if (phase != Phase.RECORDING && phase != Phase.SAVE_FAILED) return
        val endedAtIso = isoNow()
        autoReconnectArmed = false
        reconnectHandler.removeCallbacksAndMessages(null)
        tickHandler.removeCallbacks(tickRunnable)
        phase = Phase.SAVING
        notifyListener()

        val myGeneration = generation
        val savedHeadband = headbandDeviceId
        val savedModality = selectedModality
        val savedStarted = sessionStartedAtIso
        val savedMarkers = markers.toList()
        val savedSamples = samples.toList()
        // Locked: the SDK thread can still be mid-add (it read phase just
        // before SAVING), and an unlocked copy could throw
        // ConcurrentModificationException.
        val savedRawEeg = synchronized(rawEegSamples) { rawEegSamples.toList() }
        val savedBaselineDuration = baselineDurationSeconds
        val savedTreatmentEnd = treatmentEndSeconds
        // The journal keeps the end-of-session answers too, so a save that
        // fails and is never retried (the app closed) is recovered with them.
        val savedJournal = journal
        savedJournal?.flushEeg()
        updateJournalMeta {
            it.copy(
                endedAtIso = endedAtIso, practitionerNote = practitionerNote,
                patientDozed = patientDozed, alertnessRating = alertnessRating,
            )
        }
        val savedClientSessionId = journalMeta?.clientSessionId

        scope.launch {
            // Only a failure of createSession itself offers a retry. The
            // retry resends the same client_session_id, so if the failed
            // attempt had in fact reached the server (a timeout after it
            // saved), the server hands that session back instead of making a
            // second. Once the server has the session, nothing below may land
            // in that path.
            val sessionId = try {
                ApiClient(appContext).createSession(
                    patientId = patientId,
                    headbandDeviceId = savedHeadband,
                    modality = savedModality,
                    startedAtIso = savedStarted,
                    endedAtIso = endedAtIso,
                    practitionerNote = practitionerNote,
                    patientDozed = patientDozed,
                    alertnessRating = alertnessRating,
                    baselineDurationSeconds = savedBaselineDuration,
                    treatmentEndSeconds = savedTreatmentEnd,
                    markers = savedMarkers,
                    samples = savedSamples,
                    clientSessionId = savedClientSessionId,
                )
            } catch (e: Exception) {
                if (generation == myGeneration) {
                    phase = Phase.SAVE_FAILED
                    errorText = "Failed to save session: ${e.message} — data is still buffered, try again"
                    pendingResultEvent = ResultEvent.Failed(errorText)
                    notifyListener()
                }
                return@launch
            }
            var idRecorded = false
            try {
                // On disk, confirmed, before anything else, so a recovery can
                // never create the session a second time (see SessionJournal).
                // journalMeta is updated BEFORE waiting on the write: a marker
                // edited meanwhile (the list stays tappable while saving, and
                // a voice marker's transcription can land now) rewrites
                // meta.json from journalMeta, and must not write the id back
                // out.
                val withId = journalMeta?.copy(sessionId = sessionId)
                if (journal === savedJournal) journalMeta = withId
                idRecorded = withId != null && savedJournal?.writeMetaNow(withId) == true
                val rawError = uploadRawEegBestEffort(sessionId, savedRawEeg)
                // Upload-before-clear: the journal goes only once the server
                // has everything. If the raw EEG didn't make it, the journal
                // stays (closed) and PatientListActivity offers to retry it,
                // unless a retry couldn't help (the server refused it) or
                // couldn't be done safely (the id isn't on disk, so a
                // recovery would create the session again).
                val keepForRetry = rawError != null && idRecorded && !isServerRefusal(rawError)
                if (keepForRetry) savedJournal?.close() else savedJournal?.delete()
                if (journal === savedJournal) {
                    journal = null
                    journalMeta = null
                }
            } catch (e: Exception) {
                // The session is saved whatever went wrong here. Let go of the
                // journal the same way as above: kept for recovery only if its
                // id is safely on disk, since otherwise a recovery would
                // create the session again.
                android.util.Log.e("SessionRecorder", "after saving session $sessionId: ${e.message}", e)
                if (idRecorded) savedJournal?.close() else savedJournal?.delete()
                if (journal === savedJournal) {
                    journal = null
                    journalMeta = null
                }
            }
            if (generation == myGeneration) {
                samples.clear()
                markers.clear()
                baselineDurationSeconds = null; treatmentEndSeconds = null
                // Saved with the session; the next one starts with an empty box.
                draftNote = ""
                draftNoteUnsaved = false
                synchronized(rawEegSamples) { rawEegSamples.clear() }
                backupWarning = ""
                errorText = ""
                phase = Phase.IDLE
                pendingResultEvent = ResultEvent.Saved
                notifyListener()
            }
        }
    }

    /** Packs and uploads the raw EEG buffer via POST /sessions/{id}/eeg (see
     * EegBinaryWriter for the wire format). Deliberately best-effort and
     * non-fatal: the session's markers and band-power samples are already
     * saved by the time this runs, so a raw-EEG upload failure (flaky
     * connection right after a big JSON POST, server-side hiccup) must not
     * flip a successfully-saved session into SAVE_FAILED. No retry here; a
     * failed upload usually leaves the journal for PatientListActivity to
     * retry (see endSession for when it doesn't). */
    private suspend fun uploadRawEegBestEffort(sessionId: Int, rawEeg: List<DoubleArray>): Exception? {
        // pack() is INSIDE the try too, not just the network call — it
        // builds a multi-MB ByteBuffer for a long session and a failure
        // there (e.g. OOM) must be just as non-fatal as a network failure.
        // Letting it escape would flip a session whose markers/samples are
        // already saved into SAVE_FAILED, telling the practitioner the save
        // failed over a raw-EEG problem that has nothing to do with it.
        // Returns why the upload failed, or null once the server has the raw
        // EEG (or there was none to send), which decides whether the journal
        // can go.
        // Throwable, not just Exception: an OutOfMemoryError from pack() is
        // the case the comment above means. The scope is never cancelled, so
        // this can't swallow a cancellation.
        return try {
            val blob = EegBinaryWriter.pack(rawEeg) ?: return null
            ApiClient(appContext).uploadRawEeg(sessionId, blob)
            android.util.Log.d("SessionRecorder", "raw EEG uploaded: ${rawEeg.size} samples, session $sessionId")
            null
        } catch (e: Throwable) {
            android.util.Log.w("SessionRecorder", "raw EEG upload failed (session saved anyway): ${e.message}")
            e as? Exception ?: Exception(e.toString(), e)
        }
    }

    /** One-shot: returns the pending save result (if any) and clears it, so
     * it's delivered to exactly one render pass — the next Activity to
     * attach (immediately, if one's already attached; on its next onResume
     * otherwise) picks it up whenever that ends up being. */
    fun consumeResultEvent(): ResultEvent? {
        val e = pendingResultEvent
        pendingResultEvent = null
        return e
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }
}
