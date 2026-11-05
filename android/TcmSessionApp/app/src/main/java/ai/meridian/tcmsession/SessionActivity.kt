package ai.meridian.tcmsession

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import ai.meridian.tcmsession.databinding.ActivitySessionBinding

// Voice-marker silence auto-stop: MediaRecorder has no voice-activity API, so
// this is derived from getMaxAmplitude() polled on a timer. THRESHOLD is a
// heuristic (ambient noise floor vs. speech level isn't universal — a room
// fan/AC/HVAC hum sits well above quiet-room silence; 1500 let fan noise
// alone suppress the auto-stop). Tune with the "VoiceMarkerAmplitude" logcat
// tag (watchForSilence) if it doesn't trip, or cuts speech off in a quiet room.
private const val VOICE_SILENCE_THRESHOLD = 4500
private const val VOICE_SILENCE_TIMEOUT_MS = 3000L
private const val VOICE_SILENCE_POLL_MS = 200L

/**
 * Cycled by the voice-marker language toggle; `bcp47Tag` null means English.
 * Mandarin uses the script-qualified "cmn-Hans-CN" / "cmn-Hant-TW" tags
 * rather than "zh-CN" / "zh-TW": android.speech.SpeechRecognizer (still used
 * for live captions) treats the region-only "zh-TW" as ambiguous. The tag is
 * also forwarded to ApiClient.transcribeMarker; the server picks the language
 * from it and, for 繁體, converts the text to Traditional characters (see
 * server/app/transcription.py _LANGUAGES).
 */
private enum class VoiceLanguage(val buttonLabel: String, val bcp47Tag: String?) {
    ENGLISH("EN", null),
    MANDARIN_SIMPLIFIED("简体", "cmn-Hans-CN"),
    MANDARIN_TRADITIONAL("繁體", "cmn-Hant-TW");

    fun next(): VoiceLanguage = entries[(ordinal + 1) % entries.size]
}

/**
 * In-clinic session recording: connect to a Muse S, record band-power samples
 * and markers for the session's duration, and upload the whole thing once via
 * POST /sessions when the practitioner ends it — no server round-trips while
 * recording.
 *
 * This Activity is a thin, reattachable VIEW onto this patient's
 * SessionRecorder, which owns the BLE connection, the recording buffers and
 * the upload (see its docstring). Leaving the screen doesn't stop a recording;
 * reopening it for the same patient reattaches to whatever the recorder is
 * doing. Another patient's concurrent session lives in a separate recorder
 * this Activity never touches.
 */
class SessionActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySessionBinding

    // This patient's recorder — obtained (never constructed directly) in
    // onCreate. A DIFFERENT patient's session, if one happens to be running
    // concurrently, lives in a completely separate SessionRecorder instance
    // this Activity never touches.
    private lateinit var recorder: SessionRecorder

    private var patientId: Int = -1
    private var patientName: String = "Patient"

    // Voice marker capture: record a short clip to a cache file, then upload
    // it for transcription (ApiClient.transcribeMarker -> AssemblyAI; see
    // server/app/transcription.py's docstring for why the on-device
    // recognizer isn't used for this). The clip itself is
    // deleted right after the upload completes (success or failure) — only
    // the transcribed text is ever kept. Purely an Activity-local interaction
    // (you have to be looking at the screen to talk into it), so unlike the
    // recording state this stays here rather than moving into SessionRecorder.
    private var mediaRecorder: MediaRecorder? = null
    private var voiceRecordingFile: File? = null
    private var voiceRecording = false // mic actively capturing
    private var voiceBusy = false // clip uploaded, waiting on the transcription response
    private var voiceMarkerElapsedSeconds = 0
    private var silenceWatcherJob: Job? = null // polls maxAmplitude; auto-stops after VOICE_SILENCE_TIMEOUT_MS of quiet
    private var speechRecognizer: SpeechRecognizer? = null // on-screen live captions only, see startLiveCaptions

    // Typed markers already support Mandarin for free (whatever IME the
    // practitioner has installed); voice markers don't auto-detect language,
    // so this toggle picks which BCP-47 tag is forwarded as a transcription hint.
    private var voiceLanguage = VoiceLanguage.ENGLISH

    // Remembers the practitioner's answers across a SAVE_FAILED retry.
    // endSession() re-shows showArousalDialog() on every tap of "End
    // session" (including the retry after a failed save), and a fresh
    // CheckBox/NumberPicker starts back at unchecked/"Skip" — without this,
    // a network blip would silently discard whatever was answered the first
    // time instead of resubmitting it. binding.noteInput doesn't need this
    // (it's mirrored in SessionRecorder.draftNote).
    private var lastDozedAnswer = false
    private var lastAlertnessAnswer: Int? = null

    // Only rebuilt when it actually changes (reference/value compared against
    // SessionRecorder's current copies) — renderState() runs on every tick
    // while recording, and rebuilding the spinner adapter or marker views
    // that often would reset the spinner's selection / cause needless churn.
    private var renderedMuseOptions: List<com.choosemuse.libmuse.Muse> = emptyList()
    private var renderedMarkers: List<SessionMarker> = emptyList()

    private val sessionListener = SessionRecorder.Listener { renderState() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            SessionRecorder.startScan()
        } else {
            recorder.setError("Bluetooth/location permission is required to scan for Muse")
        }
    }

    // Only decides whether RecordingService's "recording" notification is
    // visible; the recording runs either way, so the answer isn't acted on.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceMarkerRecording()
        } else {
            recorder.setError("Microphone permission is required for voice markers — use Add marker instead")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        patientId = intent.getIntExtra("patient_id", -1)
        patientName = intent.getStringExtra("patient_name") ?: "Patient"
        val headbandDeviceId = intent.getStringExtra("headband_device_id")
        binding.patientNameText.text = patientName

        recorder = SessionRecorder.obtain(this, patientId, patientName, headbandDeviceId)
        // The note lives in the recorder (SessionRecorder.draftNote): put back
        // what was typed before this screen was left, then mirror every edit
        // so it reaches the recording's backup copy. Android's own saved copy
        // of the box is switched off so draftNote is the only one: restored
        // after this, it could put an already-saved note back into the draft
        // and onto the patient's next session.
        binding.noteInput.isSaveEnabled = false
        binding.noteInput.setText(recorder.draftNote)
        binding.noteInput.doAfterTextChanged { recorder.updateDraftNote(it?.toString() ?: "") }

        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.scanButton.setOnClickListener { startScan() }
        binding.connectButton.setOnClickListener { connectSelected() }
        binding.startSessionButton.setOnClickListener { startSession() }
        binding.baselineEndButton.setOnClickListener { recorder.markBaselineEnd() }
        binding.treatmentEndButton.setOnClickListener { recorder.markTreatmentEnd() }
        binding.addMarkerButton.setOnClickListener { addMarker() }
        binding.voiceMarkerButton.setOnClickListener { onVoiceMarkerTapped() }
        binding.voiceLanguageToggle.setOnClickListener { toggleVoiceLanguage() }
        binding.endSessionButton.setOnClickListener { endSession() }

        // Show whatever's already known immediately (e.g. a headband seen in
        // an earlier scan, or a recording already in progress for this
        // patient if this is a reattach) rather than waiting on the next event.
        renderedMuseOptions = SessionRecorder.museOptions
        renderMuseOptions(renderedMuseOptions)
        renderState()
    }

    override fun onResume() {
        super.onResume()
        recorder.setListener(sessionListener)
        // Catch up on anything that changed while this Activity wasn't the
        // one attached (including a save that finished or failed elsewhere).
        renderState()
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen is when the app is most likely to be killed:
        // don't wait for the next tick to back up the note.
        recorder.flushDraftNote()
        // Same reasoning as the SDK example: avoid a LibMuse resource leak.
        // Only stops the SCAN — no recorder's connection is touched, this
        // patient's or any other's.
        SessionRecorder.stopScanListening()
        recorder.setListener(null)
        // Can't reliably hold the mic once this screen isn't visible, so an
        // in-progress voice marker is abandoned rather than left recording
        // into the background.
        abortVoiceRecording()
    }

    override fun onDestroy() {
        // Deliberately no muse disconnect: the recorder owns the connection
        // and keeps it alive across this Activity's lifecycle.
        abortVoiceRecording()
        super.onDestroy()
    }

    private fun startScan() {
        recorder.setError("")
        if (!isBluetoothEnabled()) {
            recorder.setError("Turn on Bluetooth first")
            return
        }
        if (hasScanPermissions()) {
            SessionRecorder.startScan()
        } else {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    private fun renderMuseOptions(items: List<com.choosemuse.libmuse.Muse>) {
        val labels = items.map { "${it.name} - ${it.macAddress}" }
        binding.museSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                // Only the open dropdown list needs more room to tap — the closed
                // spinner button keeps the compact system default, so a headband
                // isn't a cramped tap target among several discovered devices.
                setDropDownViewResource(R.layout.spinner_dropdown_item)
            }
    }

    private fun connectSelected() {
        recorder.setError("")
        SessionRecorder.stopScanListening()
        val position = binding.museSpinner.selectedItemPosition
        if (!recorder.connect(position)) {
            // connect() already set a more specific error (e.g. "already
            // connected for <other patient>") when it returns false for a
            // valid index — only override it for the "nothing picked" case.
            if (position < 0 || position >= SessionRecorder.museOptions.size) {
                recorder.setError("Scan and pick a headband first")
            }
        }
    }

    private fun startSession() {
        recorder.startSession(selectedModality())
        // Asked here, when the notification first matters (Android 13+
        // needs permission to show one). After it's been refused twice,
        // Android stops showing the prompt on its own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun addMarker() {
        // Capture the moment the button is tapped, not the moment typing
        // finishes — the practitioner shouldn't have to rush a label just to
        // keep the timestamp accurate.
        val elapsedSeconds = recorder.currentElapsedSeconds()
        val input = android.widget.EditText(this).apply {
            hint = "Marker label"
        }
        AlertDialog.Builder(this)
            .setTitle("Marker at %02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60))
            .setView(input)
            .setPositiveButton("Confirm") { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotBlank()) {
                    recorder.addMarker(elapsedSeconds, label)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun renderMarkerList(markers: List<SessionMarker>) {
        binding.markerListContainer.removeAllViews()
        markers.forEachIndexed { index, marker ->
            val row = android.widget.TextView(this).apply {
                text = "%02d:%02d  %s".format(marker.offsetSeconds / 60, marker.offsetSeconds % 60, marker.label)
                setPadding(0, 12, 0, 12)
                isClickable = true
                isFocusable = true
                val outValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { showEditMarkerDialog(index) }
            }
            binding.markerListContainer.addView(row)
        }
    }

    /** Tap-to-edit/delete — the "editable after" half of the save-immediately voice flow. */
    private fun showEditMarkerDialog(index: Int) {
        val marker = recorder.markersSnapshot().getOrNull(index) ?: return
        val input = android.widget.EditText(this).apply {
            setText(marker.label)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Marker at %02d:%02d".format(marker.offsetSeconds / 60, marker.offsetSeconds % 60))
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val label = input.text.toString().trim()
                if (label.isNotBlank()) {
                    recorder.updateMarker(index, label)
                }
            }
            .setNegativeButton("Delete") { _, _ -> recorder.deleteMarker(index) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun toggleVoiceLanguage() {
        voiceLanguage = voiceLanguage.next()
        binding.voiceLanguageToggle.text = voiceLanguage.buttonLabel
    }

    /** Tap to start recording, tap again to stop and send early. Also
     * auto-stops on its own after ~3s of silence (watchForSilence) — MediaRecorder
     * has no built-in end-of-speech detection, so that's rolled by hand from
     * getMaxAmplitude(). The
     * manual tap is kept as the reliable path (a clinician pausing mid-sentence
     * to think will trip the silence timer; tapping again to resume was
     * rejected as unnecessary complexity — they just re-tap "Speak marker" for
     * a follow-up marker instead). */
    private fun onVoiceMarkerTapped() {
        if (voiceRecording) {
            stopVoiceMarkerRecording()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceMarkerRecording()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceMarkerRecording() {
        // Same rationale as addMarker(): capture the timestamp at the moment
        // they start talking, not whenever the upload/transcription finishes.
        voiceMarkerElapsedSeconds = recorder.currentElapsedSeconds()
        recorder.setError("")

        val file = File(cacheDir, "voice_marker_${System.currentTimeMillis()}.aac")
        // AAC_ADTS: a raw ADTS-framed AAC stream, not wrapped in an MP4/3GP
        // container, sent as "audio/aac" (AssemblyAI works out the format
        // from the bytes; see server/app/transcription.py).
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (e: IOException) {
            mr.release()
            recorder.setError("Couldn't start the microphone — try again or use Add marker")
            return
        }

        mediaRecorder = mr
        voiceRecordingFile = file
        voiceRecording = true
        binding.voiceStatusText.text = "Listening… tap to stop, or pause and it'll send itself"
        silenceWatcherJob = lifecycleScope.launch { watchForSilence(mr) }
        startLiveCaptions()
        renderState()
    }

    /** Live, on-screen-only captions shown WHILE recording — cosmetic best
     * effort, never the source of truth for the saved marker (that's always
     * the server's transcription, which is saved as the marker directly —
     * see stopVoiceMarkerRecording — and can be fixed up afterward via
     * showEditMarkerDialog if it's misheard).
     * Runs android.speech.SpeechRecognizer purely for its partial-results
     * callback, listening to the SAME mic MediaRecorder is already capturing
     * to a file — NOT verified on hardware that two concurrent capture
     * sessions coexist cleanly on a given device/OEM. If the OS refuses the
     * second session, onError below just gives up quietly and the static
     * "Listening…" text stays put instead of live words; recording and the
     * real transcription are unaffected either way. */
    private fun startLiveCaptions() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            return
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) = showLiveCaption(partialResults)
            override fun onResults(results: Bundle) {
                showLiveCaption(results)
                // The recognizer's own end-of-speech detector (separate from,
                // and not necessarily in sync with, watchForSilence's) stops
                // it after a pause — restart so captions keep going for the
                // rest of the recording rather than freezing after the first
                // sentence.
                restartLiveCaptions()
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartLiveCaptions()
                    // Anything else (ERROR_AUDIO in particular, if the mic is
                    // already claimed by MediaRecorder on this device) — stop
                    // trying rather than looping on a permanent failure.
                    else -> stopLiveCaptions()
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer = sr
        sr.startListening(liveCaptionIntent())
    }

    private fun liveCaptionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // See VoiceLanguage's doc comment for the tag format; null (English)
        // just omits the hint.
        voiceLanguage.bcp47Tag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
    }

    private fun showLiveCaption(bundle: Bundle) {
        if (!voiceRecording) return // stopped/aborted since this callback was queued — don't clobber "Transcribing…"
        val heard = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!heard.isNullOrBlank()) {
            binding.voiceStatusText.text = heard
        }
    }

    private fun restartLiveCaptions() {
        if (!voiceRecording) return
        speechRecognizer?.startListening(liveCaptionIntent())
    }

    private fun stopLiveCaptions() {
        val sr = speechRecognizer
        speechRecognizer = null
        if (sr != null) {
            try { sr.stopListening() } catch (e: Exception) {}
            try { sr.destroy() } catch (e: Exception) {}
        }
    }

    /** Auto-stop backstop for voice markers — see onVoiceMarkerTapped's doc
     * comment. Doesn't start the 3s countdown until speech has actually been
     * heard once, so a beat of hesitation before talking doesn't cut the
     * recording off before it begins; only silence AFTER real speech counts. */
    private suspend fun watchForSilence(mr: MediaRecorder) {
        var heardSpeech = false
        var silenceStartedAt = -1L
        while (true) {
            delay(VOICE_SILENCE_POLL_MS) // throws CancellationException once the job is cancelled below — that's the exit path
            val amplitude = try {
                mr.maxAmplitude
            } catch (e: IllegalStateException) {
                return // recorder already stopped/released elsewhere
            }
            Log.d("VoiceMarkerAmplitude", "amplitude=$amplitude threshold=$VOICE_SILENCE_THRESHOLD")
            if (amplitude > VOICE_SILENCE_THRESHOLD) {
                heardSpeech = true
                silenceStartedAt = -1L
            } else if (heardSpeech) {
                if (silenceStartedAt < 0) {
                    silenceStartedAt = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - silenceStartedAt >= VOICE_SILENCE_TIMEOUT_MS) {
                    stopVoiceMarkerRecording()
                    return
                }
            }
        }
    }

    private fun stopVoiceMarkerRecording() {
        silenceWatcherJob?.cancel()
        silenceWatcherJob = null
        stopLiveCaptions()
        val mr = mediaRecorder
        val file = voiceRecordingFile
        mediaRecorder = null
        voiceRecordingFile = null
        voiceRecording = false
        if (mr == null || file == null) {
            renderState()
            return
        }

        try {
            mr.stop()
        } catch (e: RuntimeException) {
            // stop() throws when start() never actually captured a frame of
            // audio (e.g. tapped and immediately tapped again) — there's
            // nothing usable to upload.
            mr.release()
            file.delete()
            binding.voiceStatusText.text = "Didn't catch that — try again or use Add marker"
            renderState()
            return
        }
        mr.release()

        voiceBusy = true
        binding.voiceStatusText.text = "Transcribing…"
        renderState()

        val elapsedSeconds = voiceMarkerElapsedSeconds
        val language = voiceLanguage.bcp47Tag
        lifecycleScope.launch {
            try {
                val text = ApiClient(this@SessionActivity).transcribeMarker(file.readBytes(), language)
                val label = text.trim()
                if (label.isNotBlank()) {
                    recorder.addMarker(elapsedSeconds, label)
                    binding.voiceStatusText.text = ""
                } else {
                    binding.voiceStatusText.text = "Didn't catch that — try again or use Add marker"
                }
            } catch (e: Exception) {
                binding.voiceStatusText.text = e.message ?: "Voice transcription failed — use Add marker instead"
            } finally {
                file.delete()
                voiceBusy = false
                renderState()
            }
        }
    }

    /** Abandons whatever the mic is doing right now — an in-progress
     * recording never gets a chance to upload (onPause/onDestroy), so this
     * only ever discards, never sends. Idempotent: safe to call when nothing
     * is recording. */
    private fun abortVoiceRecording() {
        silenceWatcherJob?.cancel()
        silenceWatcherJob = null
        stopLiveCaptions()
        val mr = mediaRecorder
        val file = voiceRecordingFile
        mediaRecorder = null
        voiceRecordingFile = null
        voiceRecording = false
        if (mr != null) {
            try {
                mr.stop()
            } catch (e: RuntimeException) {
                // Nothing captured — same as stopVoiceMarkerRecording()'s catch.
            }
            mr.release()
        }
        file?.delete()
    }

    private fun selectedModality(): String = when (binding.modalityGroup.checkedRadioButtonId) {
        R.id.modalityTaiChi -> "Tai chi"
        R.id.modalityHerbs -> "Herbs"
        else -> "Acupuncture"
    }

    private fun endSession() {
        val note = binding.noteInput.text.toString().trim().ifBlank { null }
        showArousalDialog { dozed, alertness -> recorder.endSession(note, dozed, alertness) }
    }

    /** Quick, optional context capture shown right before saving. The single
     * most common explanation for a resting session's low bands rising is
     * the patient simply growing drowsy or dozing off, not a treatment
     * effect (see docs/eeg-aperiodic-correction.md) — this is cheap enough
     * to ask every time that there's no reason not to, and it's what lets a
     * later reviewer (or the AI summary, see ai_summary.py) tell the two
     * apart instead of reading every session's band trend the same way. */
    private fun showArousalDialog(onDone: (dozed: Boolean?, alertness: Int?) -> Unit) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val dozedCheckbox = android.widget.CheckBox(this).apply {
            text = "Patient dozed off / fell asleep at some point"
            isChecked = lastDozedAnswer
        }
        val alertnessLabel = android.widget.TextView(this).apply {
            text = "Alertness at the end (optional)"
            setPadding(0, pad, 0, 0)
        }
        val alertnessPicker = android.widget.NumberPicker(this).apply {
            minValue = 0
            maxValue = 7
            displayedValues = arrayOf("Skip", "1 · drowsy", "2", "3", "4", "5", "6", "7 · fully alert")
            value = lastAlertnessAnswer ?: 0
            wrapSelectorWheel = false
        }
        container.addView(dozedCheckbox)
        container.addView(alertnessLabel)
        container.addView(alertnessPicker)

        AlertDialog.Builder(this)
            .setTitle("Before saving")
            .setView(container)
            .setPositiveButton("Save session") { _, _ ->
                val alertness = alertnessPicker.value.takeIf { it > 0 }
                lastDozedAnswer = dozedCheckbox.isChecked
                lastAlertnessAnswer = alertness
                onDone(dozedCheckbox.isChecked, alertness)
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    /** Whether the band power on screen is actually being SAVED, and if not,
     * why. The readout above shows the headband's live values no matter what
     * happens to them afterwards, so without this a session could save
     * nothing but "no signal" while the screen looked healthy throughout (see
     * SessionRecorder.headbandOffSinceLastTick and TickStatus). */
    private fun saveStatusText(): String {
        val tally = recorder.tickTallySnapshot()
        val total = tally.values.sum()
        if (total == 0) return ""
        val lines = mutableListOf<String>()
        when (recorder.lastTickStatus) {
            SessionRecorder.TickStatus.HEADBAND_OFF -> lines += "⚠ Not saving this second: the headband reports it isn't being worn"
            SessionRecorder.TickStatus.NO_SIGNAL -> lines += "⚠ Not saving this second: no usable signal from the headband"
            SessionRecorder.TickStatus.SAVED, null -> {}
        }
        val saved = tally[SessionRecorder.TickStatus.SAVED] ?: 0
        val dropped = listOfNotNull(
            tally[SessionRecorder.TickStatus.HEADBAND_OFF]?.let { "$it headband off" },
            tally[SessionRecorder.TickStatus.NO_SIGNAL]?.let { "$it no signal" },
        )
        lines += "Seconds saved: $saved of $total" +
            if (dropped.isEmpty()) "" else " (dropped: ${dropped.joinToString(", ")})"
        return lines.joinToString("\n")
    }

    /** Single render pass driven by this patient's recorder state — called on
     * every change to it (including once per second while recording) and
     * once up front in onCreate/onResume to catch up on anything that
     * happened while this screen wasn't attached. */
    private fun renderState() {
        val phase = recorder.phase

        binding.connectionStatusText.text = recorder.connectionStatusText
        binding.errorText.text = recorder.errorText

        binding.startSessionButton.isEnabled = recorder.isConnected() && phase == SessionRecorder.Phase.IDLE
        binding.modalityGroup.isEnabled = phase == SessionRecorder.Phase.IDLE
        binding.recordingGroup.visibility = if (phase != SessionRecorder.Phase.IDLE) View.VISIBLE else View.GONE
        binding.endSessionButton.isEnabled =
            phase == SessionRecorder.Phase.RECORDING || phase == SessionRecorder.Phase.SAVE_FAILED
        binding.addMarkerButton.isEnabled = phase == SessionRecorder.Phase.RECORDING
        // Stays enabled while actively recording so a tap can stop it — only
        // disabled once the clip has been handed off and is waiting on the server.
        binding.voiceMarkerButton.isEnabled = phase == SessionRecorder.Phase.RECORDING && !voiceBusy
        binding.voiceMarkerButton.text = when {
            voiceRecording -> "⏹ Stop & send"
            voiceBusy -> "Transcribing…"
            else -> "🎤 Speak marker"
        }

        // Only touched while actively recording, so the last-shown clock/band
        // values freeze in place (rather than drifting) once a save starts or fails.
        if (phase == SessionRecorder.Phase.RECORDING) {
            val elapsed = recorder.currentElapsedSeconds()
            binding.recordingClockText.text = "Recording · %02d:%02d".format(elapsed / 60, elapsed % 60)
            val bandLines = recorder.bandValuesSnapshot().entries.joinToString("\n") { (band, value) ->
                if (value == NO_SIGNAL_VALUE) "%-6s no signal".format(band) else "%-6s %.2f".format(band, value)
            }
            val status = saveStatusText()
            binding.bandValuesText.text = if (status.isEmpty()) bandLines else "$bandLines\n\n$status"

            val baseline = recorder.baselineDurationSeconds
            val treatmentEnd = recorder.treatmentEndSeconds
            // The baseline prompt also goes once Treatment done is tapped: a
            // baseline marked after it would end after the treatment did.
            binding.baselinePromptGroup.visibility =
                if (baseline == null && treatmentEnd == null) View.VISIBLE else View.GONE
            // Held until the baseline holds one full minute after the sensors
            // settle (see SessionRecorder.markBaselineEnd), with the same
            // countdown as Treatment done below.
            val baselineWait = BandShares.MIN_BASELINE_SECONDS - elapsed
            binding.baselineEndButton.isEnabled = baselineWait <= 0
            binding.baselineEndButton.text = if (baselineWait <= 0) {
                "Baseline done — start treatment"
            } else {
                "Baseline done (available in %d:%02d)".format(baselineWait / 60, baselineWait % 60)
            }
            binding.baselineRecordedText.visibility = if (baseline != null) View.VISIBLE else View.GONE
            if (baseline != null) {
                binding.baselineRecordedText.text =
                    "Baseline recorded: %02d:%02d".format(baseline / 60, baseline % 60)
            }
            // One Treatment done prompt, two places for it: above End session
            // while the baseline is still running (out of its way, but still
            // there for a session that skips the baseline), then up in the
            // baseline prompt's spot once the baseline is marked, where the
            // practitioner's eyes already are. Moved only when it needs to be,
            // since this runs every second.
            val treatmentEndSlot = if (baseline != null) binding.treatmentEndTopSlot else binding.treatmentEndBottomSlot
            val treatmentEndPrompt = binding.treatmentEndPromptGroup
            if (treatmentEndPrompt.parent !== treatmentEndSlot) {
                (treatmentEndPrompt.parent as? android.view.ViewGroup)?.removeView(treatmentEndPrompt)
                treatmentEndSlot.addView(treatmentEndPrompt)
            }
            treatmentEndPrompt.visibility = if (treatmentEnd == null) View.VISIBLE else View.GONE
            // Held until it can leave two separate minutes for the pies (see
            // SessionRecorder.markTreatmentEnd), which also stops a double tap
            // on Baseline done from landing on it. The countdown says why it
            // can't be tapped yet, so it doesn't look broken.
            val treatmentEndWait = BandShares.earliestTreatmentEndSeconds(baseline) - elapsed
            binding.treatmentEndButton.isEnabled = treatmentEndWait <= 0
            binding.treatmentEndButton.text = if (treatmentEndWait <= 0) {
                "Treatment done"
            } else {
                "Treatment done (available in %d:%02d)".format(treatmentEndWait / 60, treatmentEndWait % 60)
            }
            binding.treatmentEndRecordedText.visibility = if (treatmentEnd != null) View.VISIBLE else View.GONE
            if (treatmentEnd != null) {
                binding.treatmentEndRecordedText.text =
                    "Treatment done: %02d:%02d".format(treatmentEnd / 60, treatmentEnd % 60)
            }
        } else {
            // Saving, a failed save, or not started: both marks only count
            // while recording, so neither prompt stays on screen looking
            // tappable. The recorded times stay as they were.
            binding.baselinePromptGroup.visibility = View.GONE
            binding.treatmentEndPromptGroup.visibility = View.GONE
        }

        val museOptions = SessionRecorder.museOptions
        if (museOptions !== renderedMuseOptions) {
            renderedMuseOptions = museOptions
            renderMuseOptions(museOptions)
        }

        val markers = recorder.markersSnapshot()
        if (markers != renderedMarkers) {
            renderedMarkers = markers
            renderMarkerList(markers)
        }

        when (val event = recorder.consumeResultEvent()) {
            is SessionRecorder.ResultEvent.Saved -> {
                Toast.makeText(this, "Session saved", Toast.LENGTH_SHORT).show()
                // Just pop back to the PatientDetailActivity that launched this
                // screen — its onResume() already reloads the session list, so
                // there's no need to relaunch a fresh instance of it.
                finish()
            }
            is SessionRecorder.ResultEvent.Failed -> {
                // errorText (rendered above) already reflects it.
            }
            null -> {}
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val bm = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = bm?.adapter
        return adapter?.isEnabled == true
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    private fun hasScanPermissions(): Boolean =
        requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
}
