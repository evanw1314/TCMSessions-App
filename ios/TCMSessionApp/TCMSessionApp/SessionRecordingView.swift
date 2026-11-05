import SwiftUI

/// In-clinic session recording. Port of SessionActivity: connect a Muse, buffer
/// band-power samples + markers, upload once at End. A thin, reattachable view
/// onto the patient's dedicated PatientSessionRecorder — leaving and returning here
/// reattaches to whatever it's already doing for this patient.
struct SessionRecordingView: View {
    let patientId: Int
    let patientName: String
    let headbandDeviceId: String?

    @EnvironmentObject private var sessionService: MuseSessionService

    @State private var recorder: PatientSessionRecorder?

    var body: some View {
        Group {
            if let recorder {
                SessionRecordingContentView(
                    patientId: patientId,
                    patientName: patientName,
                    headbandDeviceId: headbandDeviceId,
                    recorder: recorder
                )
            } else {
                ProgressView()
            }
        }
        // Resolved on appear rather than inside `body`: obtaining a recorder
        // publishes into the service's dictionary and, for an idle one, runs
        // attachOrReset — neither may happen during a view update, and a reset
        // per render would wipe the markers and error of a screen just returned
        // to. This is the same once-per-screen timing as Android's onCreate.
        .onAppear {
            if recorder == nil {
                recorder = sessionService.recorder(
                    forPatient: patientId, name: patientName, headband: headbandDeviceId)
            }
        }
    }
}

private struct SessionRecordingContentView: View {
    let patientId: Int
    let patientName: String
    let headbandDeviceId: String?

    @ObservedObject var recorder: PatientSessionRecorder
    @EnvironmentObject private var sessionService: MuseSessionService
    @Environment(\.dismiss) private var dismiss

    private let modalities = ["Acupuncture", "Tai chi", "Herbs"]

    @State private var selectedDevice: String?
    @State private var modality = "Acupuncture"
    @State private var note = ""

    // Typed-marker entry.
    @State private var showMarkerEntry = false
    @State private var markerLabel = ""
    @State private var markerCapturedOffset = 0

    // Marker edit/delete.
    @State private var editingMarker: SessionMarker?
    @State private var editLabel = ""

    // Voice marker.
    @State private var voiceRecorder = VoiceMarkerRecorder()
    @State private var voiceRecording = false
    @State private var voiceBusy = false
    @State private var voiceStatus = ""
    @State private var voiceLanguage = VoiceLanguage.english
    @State private var voiceCapturedOffset = 0

    // End-of-session arousal prompt (see arousalSheet).
    @State private var showArousalPrompt = false
    @State private var dozedAnswer = false
    @State private var alertnessAnswer = 0   // 0 = skip, else 1...7

    var body: some View {
        Form {
            connectionSection
            if recorder.phase == .idle {
                setupSection
            } else {
                recordingSection
            }
            if let error = recorder.errorText, !error.isEmpty {
                Section { Text(error).foregroundStyle(.red) }
            }
        }
        .navigationTitle(patientName)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: recorder.saveOutcome) { _, outcome in
            if outcome == .saved { recorder.clearSaveOutcome(); dismiss() }
        }
        .onAppear {
            // The note lives in the recorder (draftNote): put back what was typed
            // before this screen was left. Without this the box comes back blank
            // on a reattach and End session would save nil over a real note.
            if note.isEmpty { note = recorder.draftNote }
        }
        .onDisappear {
            sessionService.stopScan()
            // Leaving the screen is when the app is most likely to be killed:
            // don't wait for the next tick to back up the note.
            recorder.flushDraftNote()
            // Can't reliably hold the mic once this screen isn't visible, so an
            // in-progress voice marker is abandoned rather than left recording
            // into the background.
            abortVoice()
        }
        .sheet(isPresented: $showArousalPrompt) { arousalSheet }
        .alert("Marker at \(timeString(markerCapturedOffset))", isPresented: $showMarkerEntry) {
            TextField("Marker label", text: $markerLabel)
            Button("Confirm") {
                let label = markerLabel.trimmingCharacters(in: .whitespaces)
                if !label.isEmpty { recorder.addMarker(offsetSeconds: markerCapturedOffset, label: label) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Edit marker", isPresented: .constant(editingMarker != nil)) {
            TextField("Marker label", text: $editLabel)
            Button("Save") {
                if let m = editingMarker {
                    let label = editLabel.trimmingCharacters(in: .whitespaces)
                    if !label.isEmpty { recorder.updateMarker(id: m.id, label: label) }
                }
                editingMarker = nil
            }
            Button("Delete", role: .destructive) {
                if let m = editingMarker { recorder.deleteMarker(id: m.id) }
                editingMarker = nil
            }
            Button("Cancel", role: .cancel) { editingMarker = nil }
        }
    }

    // MARK: - Connection

    private var connectionSection: some View {
        Section("Headband") {
            Text(recorder.connectionStatus).foregroundStyle(.secondary)
            Button("Scan") { sessionService.startScan() }
            if !sessionService.discoveredDevices.isEmpty {
                Picker("Device", selection: $selectedDevice) {
                    Text("None").tag(String?.none)
                    ForEach(sessionService.discoveredDevices, id: \.self) { Text($0).tag(String?.some($0)) }
                }
                Button("Connect") {
                    if let d = selectedDevice {
                        sessionService.connect(recorder: recorder, deviceName: d)
                    }
                }
                .disabled(selectedDevice == nil)
            }
        }
    }

    // MARK: - Setup (idle)

    private var setupSection: some View {
        Section("New session") {
            Picker("Modality", selection: $modality) {
                ForEach(modalities, id: \.self) { Text($0) }
            }
            Button("Start session") {
                recorder.startSession(modality: modality)
            }
            .disabled(!recorder.isConnected)
        }
    }

    // MARK: - Recording

    private var recordingSection: some View {
        Group {
            Section {
                Text(recorder.phase == .recording
                     ? "Recording · \(timeString(recorder.elapsedSeconds))"
                     : (recorder.phase == .saving ? "Saving…" : "Save failed"))
                    .font(.headline)
                ForEach(BAND_ORDER, id: \.self) { band in
                    HStack {
                        Text(band.capitalized)
                        Spacer()
                        Text(bandValueString(recorder.latestBands[band]))
                            .monospacedDigit().foregroundStyle(.secondary)
                    }
                }
                if let live = liveTickWarning {
                    Text(live).font(.caption).foregroundStyle(.orange)
                }
                if let tally = saveStatusText {
                    Text(tally).font(.caption).foregroundStyle(.secondary)
                }
            }

            baselineSection

            Section("Markers") {
                Button("Add marker") {
                    markerCapturedOffset = recorder.currentElapsedSeconds()
                    markerLabel = ""
                    showMarkerEntry = true
                }
                .disabled(recorder.phase != .recording)

                HStack {
                    Button(voiceButtonTitle) { onVoiceTapped() }
                        .disabled(recorder.phase != .recording || voiceBusy)
                    Spacer()
                    Button(voiceLanguage.buttonLabel) { voiceLanguage = voiceLanguage.next() }
                        .buttonStyle(.bordered)
                }
                if !voiceStatus.isEmpty {
                    Text(voiceStatus).font(.caption).foregroundStyle(.secondary)
                }

                ForEach(recorder.markers) { marker in
                    Button {
                        editingMarker = marker
                        editLabel = marker.label
                    } label: {
                        Text("\(timeString(marker.offsetSeconds))  \(marker.label)")
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                }
            }

            Section("Practitioner note") {
                TextField("Optional note", text: $note, axis: .vertical)
                    .onChange(of: note) { _, text in recorder.updateDraftNote(text) }
            }

            Section {
                Button(recorder.phase == .saveFailed ? "Retry save" : "End session") {
                    showArousalPrompt = true
                }
                .disabled(recorder.phase == .saving)
                .foregroundStyle(.red)
            }
        }
    }

    // MARK: - Baseline

    /// Recording a short eyes-closed baseline at the START of the same session
    /// lets the analysis compare the session against its own resting state
    /// instead of population norms. Entirely optional.
    @ViewBuilder private var baselineSection: some View {
        if recorder.phase == .recording {
            Section("Baseline") {
                if let baseline = recorder.baselineDurationSeconds {
                    Text("Baseline recorded: \(timeString(baseline))")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    Text("Recording baseline — have the patient sit still with eyes closed, no "
                         + "treatment yet. Aim for 2–3 minutes, same room, headband already on.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button("Baseline done — start treatment") { recorder.markBaselineEnd() }
                }
            }
        }
    }

    // MARK: - Save status

    /// The live "this second isn't being saved" line. The band readout above
    /// shows the headband's values no matter what happens to them afterwards, so
    /// without this a session could save nothing but "no signal" while the
    /// screen looked healthy throughout.
    private var liveTickWarning: String? {
        guard recorder.phase == .recording else { return nil }
        switch recorder.lastTickStatus {
        case .headbandOff: return "⚠ Not saving this second: the headband reports it isn't being worn"
        case .noSignal: return "⚠ Not saving this second: no usable signal from the headband"
        case .saved, nil: return nil
        }
    }

    /// The running tally of what each recorded second actually did, so seconds
    /// being thrown away can't go unnoticed.
    private var saveStatusText: String? {
        let tally = recorder.tickTally
        let total = tally.values.reduce(0, +)
        guard total > 0 else { return nil }
        let saved = tally[.saved] ?? 0
        let dropped = [
            tally[.headbandOff].map { "\($0) headband off" },
            tally[.noSignal].map { "\($0) no signal" },
        ].compactMap { $0 }
        let suffix = dropped.isEmpty ? "" : " (dropped: \(dropped.joined(separator: ", ")))"
        return "Seconds saved: \(saved) of \(total)\(suffix)"
    }

    // MARK: - Before-saving prompt

    /// Quick, optional context capture shown right before saving. The single most
    /// common explanation for a resting session's low bands rising is the patient
    /// simply growing drowsy or dozing off, not a treatment effect — this is cheap
    /// enough to ask every time, and it's what lets a later reviewer (or the AI
    /// summary, see ai_summary.py) tell the two apart instead of reading every
    /// session's band trend the same way.
    private var arousalSheet: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Patient dozed off / fell asleep at some point", isOn: $dozedAnswer)
                }
                Section("Alertness at the end (optional)") {
                    Picker("Alertness", selection: $alertnessAnswer) {
                        Text("Skip").tag(0)
                        Text("1 · drowsy").tag(1)
                        ForEach(2...6, id: \.self) { Text("\($0)").tag($0) }
                        Text("7 · fully alert").tag(7)
                    }
                    .pickerStyle(.wheel)
                }
            }
            .navigationTitle("Before saving")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showArousalPrompt = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save session") {
                        showArousalPrompt = false
                        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
                        recorder.endSession(
                            note: trimmed.isEmpty ? nil : trimmed,
                            patientDozed: dozedAnswer,
                            alertnessRating: alertnessAnswer > 0 ? alertnessAnswer : nil
                        )
                    }
                }
            }
        }
        .interactiveDismissDisabled()
    }

    // MARK: - Voice marker

    private var voiceButtonTitle: String {
        if voiceRecording { return "⏹ Stop & send" }
        if voiceBusy { return "Transcribing…" }
        return "🎤 Speak marker"
    }

    private func onVoiceTapped() {
        if voiceRecording { stopVoiceAndSend(); return }
        Task {
            let granted = VoiceMarkerRecorder.hasPermission ? true : await VoiceMarkerRecorder.requestPermission()
            guard granted else {
                recorder.setError("Microphone permission is required for voice markers — use Add marker instead")
                return
            }
            // Captions are a nicety; a refusal here still records and uploads the
            // marker, just without showing the words as they're spoken.
            let captionsAllowed = await VoiceMarkerRecorder.requestSpeechPermission()
            startVoice(withCaptions: captionsAllowed)
        }
    }

    private func startVoice(withCaptions captionsAllowed: Bool) {
        voiceCapturedOffset = recorder.currentElapsedSeconds()
        recorder.setError(nil)
        voiceRecorder.onSilenceAutoStop = { stopVoiceAndSend() }
        // Display only — what actually becomes the marker is the server's
        // AssemblyAI transcription of the uploaded clip.
        voiceRecorder.onLiveCaption = { heard in voiceStatus = heard }
        do {
            try voiceRecorder.start(captionLanguage: captionsAllowed ? voiceLanguage : nil)
            voiceRecording = true
            voiceStatus = "Listening… tap to stop, or pause and it'll send itself"
        } catch {
            // start() never set voiceRecording, so abortVoice() won't run for
            // this attempt — clear the callbacks here or the cycle they form
            // outlives the failure.
            clearVoiceCallbacks()
            voiceStatus = "Couldn't start the microphone — try again or use Add marker"
        }
    }

    /// Discards an in-progress voice marker without uploading it. Idempotent.
    private func abortVoice() {
        guard voiceRecording else { return }
        voiceRecording = false
        voiceRecorder.abort()
        clearVoiceCallbacks()
        voiceStatus = ""
    }

    /// The callbacks capture this view, which owns the recorder — leaving them
    /// set after a clip is done keeps that cycle alive for the app's lifetime.
    private func clearVoiceCallbacks() {
        voiceRecorder.onSilenceAutoStop = nil
        voiceRecorder.onLiveCaption = nil
    }

    private func stopVoiceAndSend() {
        guard voiceRecording else { return }
        voiceRecording = false
        clearVoiceCallbacks()
        guard let data = voiceRecorder.stop() else {
            voiceStatus = "Didn't catch that — try again or use Add marker"
            return
        }
        voiceBusy = true
        voiceStatus = "Transcribing…"
        let offset = voiceCapturedOffset
        let language = voiceLanguage.bcp47Tag
        Task {
            defer { voiceBusy = false }
            do {
                let text = try await APIClient.transcribeMarker(audio: data, language: language)
                let label = text.trimmingCharacters(in: .whitespaces)
                if label.isEmpty {
                    voiceStatus = "Didn't catch that — try again or use Add marker"
                } else {
                    recorder.addMarker(offsetSeconds: offset, label: label)
                    voiceStatus = ""
                }
            } catch {
                voiceStatus = error.localizedDescription
            }
        }
    }

    // MARK: - Formatting

    private func timeString(_ seconds: Int) -> String {
        String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    private func bandValueString(_ value: Double?) -> String {
        guard let value, value >= 0, !value.isNaN else { return "no signal" }
        return String(format: "%.2f", value)
    }
}
