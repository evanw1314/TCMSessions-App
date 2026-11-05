import Combine
import Foundation

/// Manages per-patient EEG recording sessions (`PatientSessionRecorder`) and the
/// shared IXNMuseManagerIos BLE scanner. Port of Android's `SessionRecorder` manager.
///
/// A single instance of `MuseSessionService` is owned at app scope (in TCMSessionAppApp)
/// and injected via `.environmentObject`. It holds a dictionary of active per-patient
/// recorders (`recorders[patientId]`), allowing multiple patients to record EEG sessions
/// concurrently on separate Muse headbands.
@MainActor
final class MuseSessionService: NSObject, ObservableObject {

    typealias Phase = PatientSessionRecorder.Phase
    typealias SaveOutcome = PatientSessionRecorder.SaveOutcome

    // The five relative band powers the Muse exposes natively.
    static let bands = ["alpha", "beta", "delta", "theta", "gamma"]

    /// Every band series the recorder buffers: the five relative ones plus their
    /// absolute counterparts. Seeded up front (not added when the first packet of
    /// each arrives) so every tick from second 0 emits the same ten rows — a
    /// series that only starts once a packet lands would leave the early seconds
    /// shaped differently from the rest.
    static let allBandKeys = bands + bands.map { $0 + "_absolute" }

    // MARK: - Published UI state
    @Published private(set) var discoveredDevices: [String] = []
    @Published private(set) var recorders: [Int: PatientSessionRecorder] = [:]

    // MARK: - SDK objects
    private let manager = IXNMuseManagerIos()
    private var museByName: [String: IXNMuse] = [:]
    private lazy var museListShim = MuseListShim(owner: self)

    override init() {
        super.init()
        manager.setMuseListener(museListShim)
    }

    // MARK: - Scanning

    func startScan() {
        manager.stopListening()
        manager.startListening()
    }

    func stopScan() {
        manager.stopListening()
    }

    fileprivate func museListChanged() {
        let muses = manager.getMuses()
        museByName = Dictionary(muses.map { ($0.getName(), $0) }, uniquingKeysWith: { first, _ in first })
        discoveredDevices = muses.map { $0.getName() }
    }

    // MARK: - Per-patient recorder factory & access

    /// Obtains (or creates) the dedicated `PatientSessionRecorder` for a patient.
    func recorder(forPatient patientId: Int, name: String, headband: String?) -> PatientSessionRecorder {
        if let existing = recorders[patientId] {
            existing.attachOrReset(patientName: name, headbandDeviceId: headband)
            return existing
        }
        let created = PatientSessionRecorder(patientId: patientId, patientName: name, headbandDeviceId: headband)
        created.onPhaseChanged = { [weak self] in self?.refreshRecordingKeepAlive() }
        recorders[patientId] = created
        return created
    }

    /// Returns the active recorder for a patient if one exists and is not idle.
    func activeRecorder(forPatient patientId: Int) -> PatientSessionRecorder? {
        guard let r = recorders[patientId], r.phase != .idle else { return nil }
        return r
    }

    /// True when an active (non-idle) recording exists for the given patient.
    func isActive(forPatient patientId: Int) -> Bool {
        activeRecorder(forPatient: patientId) != nil
    }

    /// Connects a patient's recorder to a discovered Muse headband, ensuring
    /// the headband isn't already claimed by another active patient session.
    func connect(recorder: PatientSessionRecorder, deviceName: String) {
        guard let selectedMuse = museByName[deviceName] else { return }
        if let existingOwner = ownerOf(muse: selectedMuse), existingOwner.patientId != recorder.patientId {
            recorder.setError("This headband is already connected for \(existingOwner.patientName) — pick a different one")
            return
        }
        manager.stopListening()
        recorder.connect(muse: selectedMuse, deviceName: deviceName)
    }

    /// Whichever recorder (if any) is currently connected to `targetMuse`.
    private func ownerOf(muse targetMuse: IXNMuse) -> PatientSessionRecorder? {
        recorders.values.first { $0.connectedMuse === targetMuse }
    }

    /// Keeps the app alive while any patient is recording or saving — the iOS
    /// counterpart of Android's RecordingService. Called on every recorder phase
    /// change (see `onPhaseChanged`).
    private func refreshRecordingKeepAlive() {
        let recording = recorders.values.filter { $0.phase == .recording }.map(\.patientName)
        let saving = recorders.values.filter { $0.phase == .saving || $0.phase == .saveFailed }.map(\.patientName)
        RecordingKeepAlive.update(recording: recording, saving: saving)
    }
}

// MARK: - Raw EEG capture (off the main actor)

/// The raw-EEG buffer, written straight from the SDK's callback thread.
///
/// Deliberately NOT main-actor isolated, unlike every other bit of recorder
/// state: raw EEG arrives at ~256Hz and hopping each packet onto the main actor
/// would both backlog it behind UI work and let samples land out of order. Band
/// and artifact packets are ~10Hz and do still hop, where the ordinary
/// single-threaded reasoning is worth more than the saved hops.
///
/// `nonisolated` is load-bearing, not decoration: this build defaults types to
/// @MainActor, which would quietly put the 256Hz append path back on the main
/// actor — the exact thing this type exists to avoid.
nonisolated final class RawEegCapture {
    private let lock = NSLock()
    private var samples: [EegBinaryWriter.Sample] = []
    private var journal: SessionJournal?
    private var capturing = false
    // The headband this capture belongs to. connect() never unregisters the
    // PREVIOUS muse's listener, only the newly-picked one's, so packets from an
    // old one can still arrive and must be ignored.
    private var expectedMuse: IXNMuse?

    func attach(muse: IXNMuse?) {
        lock.lock(); expectedMuse = muse; lock.unlock()
    }

    func begin(journal: SessionJournal?) {
        lock.lock()
        samples.removeAll()
        self.journal = journal
        capturing = true
        lock.unlock()
    }

    /// Called on the SDK's callback thread for every raw EEG sample.
    func append(timestampUs: Int64, channels: [Double], from muse: IXNMuse?) {
        lock.lock()
        guard capturing, muse === expectedMuse else { lock.unlock(); return }
        samples.append(EegBinaryWriter.Sample(timestampUs: timestampUs, channels: channels))
        let journal = self.journal
        lock.unlock()
        // Outside the lock: the journal does its own locking and buffers to disk
        // on its own queue, so it must not be serialized behind this one.
        journal?.appendEeg(timestampUs: timestampUs, channels: channels)
    }

    /// Stops capturing and hands back everything buffered — the snapshot
    /// endSession uploads.
    func stopAndSnapshot() -> [EegBinaryWriter.Sample] {
        lock.lock(); defer { lock.unlock() }
        capturing = false
        journal = nil
        return samples
    }

    func clear() {
        lock.lock(); samples.removeAll(); journal = nil; capturing = false; lock.unlock()
    }
}

// MARK: - Per-Patient Session Recorder

/// Owner of a single patient's EEG recording session: the Muse BLE connection,
/// band-power buffer, markers, raw EEG, the on-disk journal, and the upload at
/// the end. Port of Android's `SessionRecorder`.
@MainActor
final class PatientSessionRecorder: NSObject, ObservableObject {

    enum Phase: Equatable {
        case idle
        case recording
        case saving
        case saveFailed
    }

    enum SaveOutcome: Equatable { case saved, failed }

    /// What happened to each recorded second's band power — shown live on the
    /// recording screen so seconds being thrown away can't go unnoticed.
    enum TickStatus: String, Equatable {
        case saved
        case headbandOff
        case noSignal
    }

    // Auto-reconnect tuning: a Muse S that loses its BLE link powers itself off
    // after roughly a minute of staying disconnected, and once it's off nothing
    // short of the physical button wakes it — so the whole point of retrying is
    // to land a fresh connect() well inside that window, not after it. 20
    // attempts * 1.5s ≈ 30s of retrying, comfortably inside the ~60s budget.
    private static let reconnectMaxAttempts = 20
    private static let reconnectDelay: UInt64 = 1_500_000_000 // 1.5s in ns

    let patientId: Int
    @Published private(set) var patientName: String
    @Published private(set) var headbandDeviceId: String?

    // MARK: - Published UI state
    @Published private(set) var connectionStatus = "Not connected"
    @Published private(set) var phase: Phase = .idle {
        didSet { if oldValue != phase { onPhaseChanged?() } }
    }
    @Published private(set) var latestBands: [String: Double] =
        Dictionary(uniqueKeysWithValues: MuseSessionService.allBandKeys.map { ($0, NO_SIGNAL_VALUE) })
    @Published private(set) var sampleCount = 0
    @Published private(set) var markers: [SessionMarker] = []
    @Published private(set) var elapsedSeconds = 0
    @Published var errorText: String?
    @Published private(set) var saveOutcome: SaveOutcome?
    @Published private(set) var startedAtIso = ""
    @Published private(set) var lastTickStatus: TickStatus?
    @Published private(set) var tickTally: [TickStatus: Int] = [:]
    /// Elapsed seconds from session start to "baseline done, treatment started",
    /// or nil when no baseline phase was marked.
    @Published private(set) var baselineDurationSeconds: Int?
    @Published private(set) var draftNote = ""

    /// Fired whenever `phase` changes, so the service can start/stop the
    /// keep-alive that holds the app up while a recording is live.
    var onPhaseChanged: (() -> Void)?

    private(set) var connectedMuse: IXNMuse?
    private var connectedDeviceName: String?

    private lazy var connectionShim = ConnectionShim(owner: self)
    private lazy var dataShim = DataShim(owner: self, capture: rawCapture)

    // Recording buffer + session context
    private let rawCapture = RawEegCapture()
    private var samples: [SessionSample] = []
    // ContinuousClock, not systemUptime/Date: it keeps counting while the device
    // is asleep (matching Android's SystemClock.elapsedRealtime) and, unlike a
    // wall clock, can't jump if the time zone or clock changes mid-session.
    private var sessionStartInstant: ContinuousClock.Instant?
    private var tickTimer: Timer?
    private var selectedModality = "Acupuncture"
    // Bumped every time attachOrReset() resets this instance to a genuinely
    // fresh session. Structurally this should never fire while an upload for
    // THIS instance is still in flight — kept as a cheap defensive guard.
    private var generation = 0

    // Crash-safe on-disk backup of the recording in progress.
    private var journal: SessionJournal?
    private var journalMeta: SessionJournal.Meta?
    private var draftNoteUnsaved = false
    // Shown for the whole recording when there's no journal, so "it's only in
    // memory" can't be cleared away by an unrelated setError("").
    private var backupWarning = ""

    // Set by the artifact packet, consumed and cleared by the next tick: the
    // headband reporting it isn't seated corrupts every band's reading for that
    // instant, so a tick that saw it is recorded as NO_SIGNAL_VALUE across all
    // bands rather than a plausible-looking but meaningless number.
    //
    // The SDK's other two artifact flags are deliberately NOT used to drop ticks:
    // - blink: on-device it showed in 48% of 1-second ticks of a calm
    //   baseline, so gating on it discarded most of a good recording.
    // - jawClench: this headband's detector is unreliable — it reported a
    //   clench in every packet for minutes with the wearer sitting still,
    //   switched itself off for 20-45s stretches, and missed a sustained real
    //   clench. Session 13 lost all 249 of its ticks to it while its raw EEG
    //   showed a clean alpha peak.
    // So the per-second band-power series includes muscle artifact unfiltered.
    // The raw-EEG analysis doesn't use these flags; it runs its own
    // amplitude-based epoch rejection (eeg.analysis._epoch_and_reject).
    private var headbandOffSinceLastTick = false
    // Whether a relative band-power packet with a usable value arrived since the
    // last tick. latestBands keeps its last value forever (nothing clears it on
    // disconnect or all-NaN contact), so without this a dropped connection would
    // keep saving the same frozen numbers every second and count them as healthy.
    private var bandPacketSinceLastTick = false

    // Auto-reconnect
    private var autoReconnectArmed = false
    private var reconnectAttempts = 0

    // Artifact-flag debug counters — see logArtifactFlags.
    private var logPackets = 0
    private var logHeadbandOff = 0
    private var logJawClench = 0
    private var logBlink = 0
    private var logWindowStart: TimeInterval = 0

    init(patientId: Int, patientName: String, headbandDeviceId: String?) {
        self.patientId = patientId
        self.patientName = patientName
        self.headbandDeviceId = headbandDeviceId
        super.init()
    }

    var isConnected: Bool { connectedMuse?.getConnectionState() == .connected }

    /// Reattaches to a recorder that's already doing something for this patient,
    /// or resets a fully-idle one for a genuinely fresh session. Port of
    /// Android's attachOrReset.
    func attachOrReset(patientName newName: String, headbandDeviceId newHeadband: String?) {
        if phase != .idle || connectedMuse != nil {
            patientName = newName
            headbandDeviceId = newHeadband
            return
        }
        generation += 1
        autoReconnectArmed = false
        reconnectAttempts = 0
        connectionStatus = "Not connected"
        sessionStartInstant = nil
        startedAtIso = ""
        latestBands = Dictionary(uniqueKeysWithValues: MuseSessionService.allBandKeys.map { ($0, NO_SIGNAL_VALUE) })
        headbandOffSinceLastTick = false
        bandPacketSinceLastTick = false
        samples.removeAll()
        markers.removeAll()
        sampleCount = 0
        elapsedSeconds = 0
        baselineDurationSeconds = nil
        lastTickStatus = nil
        tickTally.removeAll()
        rawCapture.clear()
        // Only reachable from idle, where endSession has already let go of its
        // journal; if one were somehow still attached, close it rather than
        // delete it, so PatientListView can still offer it.
        journal?.close()
        journal = nil
        journalMeta = nil
        draftNote = ""
        draftNoteUnsaved = false
        backupWarning = ""
        errorText = nil
        saveOutcome = nil
        patientName = newName
        headbandDeviceId = newHeadband
    }

    func currentElapsedSeconds() -> Int { liveElapsedSeconds() }

    func connect(muse: IXNMuse, deviceName: String) {
        connectedMuse = muse
        connectedDeviceName = deviceName
        rawCapture.attach(muse: muse)
        errorText = nil
        reconnectAttempts = 0
        autoReconnectArmed = true

        muse.unregisterAllListeners()
        muse.register(connectionShim)
        for type in Self.registeredPacketTypes {
            muse.register(dataShim, type: type)
        }
        // PRESET_21 matches the SDK's own example.
        muse.setPreset(.preset21)
        muse.runAsynchronously()
        connectionStatus = "Connecting…"
    }

    /// Drops this patient's BLE link. Deliberately does NOT stop the tick or
    /// touch `phase`: a recording survives a disconnect (that's what
    /// auto-reconnect is for), and stopping the tick here would leave it in
    /// .recording saving nothing.
    func disconnect() {
        autoReconnectArmed = false
        if let connectedMuse, connectedMuse.getConnectionState() != .disconnected {
            connectedMuse.disconnect()
        }
        self.connectedMuse = nil
        self.connectedDeviceName = nil
        rawCapture.attach(muse: nil)
    }

    // MARK: - Session lifecycle

    func startSession(modality: String) {
        guard phase == .idle else { return }
        selectedModality = modality
        sessionStartInstant = ContinuousClock.now
        startedAtIso = isoNowUTC()
        samples.removeAll()
        markers.removeAll()
        sampleCount = 0
        elapsedSeconds = 0
        baselineDurationSeconds = nil
        errorText = nil
        saveOutcome = nil

        let meta = SessionJournal.Meta(
            patientId: patientId,
            patientName: patientName,
            practitionerId: AppConfig.userId >= 0 ? AppConfig.userId : nil,
            headbandDeviceId: headbandDeviceId,
            modality: modality,
            startedAtIso: startedAtIso,
            // Kept in journalMeta even with no journal on disk, so a retried End
            // session resends the same one.
            clientSessionId: UUID().uuidString
        )
        journal = SessionJournal.create(meta: meta, startedAtMs: Int64(Date().timeIntervalSince1970 * 1000))
        journalMeta = meta
        backupWarning = journal == nil
            ? "Couldn't save a backup copy of this recording on the phone — it's only kept in "
              + "memory, so keep the app open until you tap End session"
            : ""
        errorText = backupWarning.isEmpty ? nil : backupWarning

        rawCapture.begin(journal: journal)
        lastTickStatus = nil
        tickTally.removeAll()
        // A flag left over from before recording would let second 0 save a
        // reading from whenever it was set; start every session with a clean one.
        bandPacketSinceLastTick = false
        headbandOffSinceLastTick = false
        // Set last, once the journal exists: the phase change starts the
        // keep-alive, and the first EEG packet can arrive right after.
        phase = .recording
        startTick()
    }

    /// Kicks off (or retries, from `saveFailed`) the upload. Runs in a detached
    /// task rather than a view's lifetime so the upload survives the screen that
    /// triggered it going away mid-request.
    func endSession(note: String?, patientDozed: Bool? = nil, alertnessRating: Int? = nil) {
        guard phase == .recording || phase == .saveFailed else { return }
        let endedAtIso = isoNowUTC()
        autoReconnectArmed = false
        stopTick()
        phase = .saving

        let myGeneration = generation
        let savedPatient = patientId
        let savedHeadband = headbandDeviceId
        let savedModality = selectedModality
        let savedStarted = startedAtIso
        let savedMarkers = markers
        let savedSamples = samples
        let savedRawEeg = rawCapture.stopAndSnapshot()
        let savedBaseline = baselineDurationSeconds
        // The journal keeps the end-of-session answers too, so a save that fails
        // and is never retried (the app closed) is recovered with them.
        let savedJournal = journal
        savedJournal?.flushEeg()
        updateJournalMeta {
            $0.endedAtIso = endedAtIso
            $0.practitionerNote = note
            $0.patientDozed = patientDozed
            $0.alertnessRating = alertnessRating
        }
        let savedClientSessionId = journalMeta?.clientSessionId

        Task { @MainActor in
            // Only a failure of createSession itself offers a retry. The retry
            // resends the same client_session_id, so if the failed attempt had in
            // fact reached the server (a timeout after it saved), the server
            // hands that session back instead of making a second. Once the server
            // has the session, nothing below may land in that path.
            let sessionId: Int
            do {
                sessionId = try await APIClient.createSession(
                    patientId: savedPatient,
                    headbandDeviceId: savedHeadband,
                    modality: savedModality,
                    startedAtIso: savedStarted,
                    endedAtIso: endedAtIso,
                    practitionerNote: note,
                    patientDozed: patientDozed,
                    alertnessRating: alertnessRating,
                    baselineDurationSeconds: savedBaseline,
                    markers: savedMarkers,
                    samples: savedSamples,
                    clientSessionId: savedClientSessionId
                )
            } catch {
                guard self.generation == myGeneration else { return }
                self.phase = .saveFailed
                self.errorText = "Failed to save session: \(error.localizedDescription) — data is still buffered, try again"
                self.saveOutcome = .failed
                return
            }

            // On disk, confirmed, before anything else, so a recovery can never
            // create the session a second time (see SessionJournal). journalMeta
            // is updated BEFORE waiting on the write: a marker edited meanwhile
            // (the list stays tappable while saving, and a voice marker's
            // transcription can land now) rewrites meta.json from journalMeta,
            // and must not write the id back out.
            var idRecorded = false
            if var withId = self.journalMeta {
                withId.sessionId = sessionId
                if self.journal === savedJournal { self.journalMeta = withId }
                idRecorded = await savedJournal?.writeMetaNow(withId) ?? false
            }
            let rawError = await Self.uploadRawEegBestEffort(sessionId: sessionId, rawEeg: savedRawEeg)
            // Upload-before-clear: the journal goes only once the server has
            // everything. If the raw EEG didn't make it, the journal stays
            // (closed) and PatientListView offers to retry it. Unless a retry
            // couldn't help (the server refused it) or couldn't be done safely
            // (the id isn't on disk, so a recovery would create the session again).
            let keepForRetry = rawError != nil && idRecorded && !isServerRefusal(rawError!)
            if keepForRetry { savedJournal?.close() } else { savedJournal?.delete() }
            if self.journal === savedJournal {
                self.journal = nil
                self.journalMeta = nil
            }

            guard self.generation == myGeneration else { return }
            self.samples.removeAll()
            self.markers.removeAll()
            self.sampleCount = 0
            self.baselineDurationSeconds = nil
            // Saved with the session; the next one starts with an empty box.
            self.draftNote = ""
            self.draftNoteUnsaved = false
            self.rawCapture.clear()
            self.backupWarning = ""
            self.errorText = nil
            self.phase = .idle
            self.saveOutcome = .saved
        }
    }

    /// Packs and uploads the raw EEG buffer via POST /sessions/{id}/eeg.
    /// Deliberately best-effort and non-fatal: the session's markers and
    /// band-power samples are already saved by the time this runs, so a raw-EEG
    /// upload failure (a flaky connection right after a big JSON POST, a
    /// server-side hiccup) must not flip a successfully-saved session into
    /// saveFailed. Returns why it failed, or nil once the server
    /// has the raw EEG (or there was none to send), which decides whether the
    /// journal can go.
    private static func uploadRawEegBestEffort(sessionId: Int, rawEeg: [EegBinaryWriter.Sample]) async -> Error? {
        do {
            // pack() is inside the do too, not just the network call — it builds
            // a multi-MB buffer for a long session and a failure there must be
            // just as non-fatal as a network failure.
            guard let blob = EegBinaryWriter.pack(rawEeg) else { return nil }
            try await APIClient.uploadRawEeg(sessionId: sessionId, eegBlob: blob)
            return nil
        } catch {
            print("raw EEG upload failed (session saved anyway): \(error.localizedDescription)")
            return error
        }
    }

    func clearSaveOutcome() { saveOutcome = nil }

    func setError(_ message: String?) {
        // Clearing an error mid-recording (e.g. starting a voice marker) mustn't
        // hide that there's no backup copy.
        if (message ?? "").isEmpty && phase == .recording {
            errorText = backupWarning.isEmpty ? nil : backupWarning
        } else {
            errorText = message
        }
    }

    // MARK: - Markers, baseline and the draft note

    func addMarker(label: String) {
        addMarker(offsetSeconds: liveElapsedSeconds(), label: label)
    }

    func addMarker(offsetSeconds: Int, label: String) {
        guard phase == .recording else { return }
        markers.append(SessionMarker(offsetSeconds: offsetSeconds, label: label))
        saveMarkersToJournal()
    }

    func updateMarker(id: UUID, label: String) {
        guard let idx = markers.firstIndex(where: { $0.id == id }) else { return }
        markers[idx].label = label
        saveMarkersToJournal()
    }

    func deleteMarker(id: UUID) {
        markers.removeAll { $0.id == id }
        saveMarkersToJournal()
    }

    /// Marks the end of an eyes-closed, no-treatment baseline phase and the start
    /// of active treatment: recording a short baseline at the start of the SAME
    /// session lets the analysis pipeline compare a session against its own
    /// resting state instead of population norms. Entirely optional: without it
    /// the session has no baseline. Also drops an ordinary marker at
    /// the same offset so the transition shows up on the band-power chart like
    /// any other marker. Idempotent no-op once already marked, or outside recording.
    func markBaselineEnd() {
        guard phase == .recording, baselineDurationSeconds == nil else { return }
        let elapsed = currentElapsedSeconds()
        baselineDurationSeconds = elapsed
        addMarker(offsetSeconds: elapsed, label: "Baseline ended — treatment started")
    }

    /// Mirrors the note box (the recording screen calls this on every edit).
    func updateDraftNote(_ text: String) {
        guard text != draftNote else { return }
        draftNote = text
        draftNoteUnsaved = true
    }

    /// Writes the draft note into the journal, so a recovered session keeps it.
    /// Runs each tick while recording and when the screen is left.
    func flushDraftNote() {
        guard draftNoteUnsaved, journalMeta != nil else { return }
        draftNoteUnsaved = false
        let trimmed = draftNote.trimmingCharacters(in: .whitespacesAndNewlines)
        updateJournalMeta { $0.practitionerNote = trimmed.isEmpty ? nil : trimmed }
    }

    /// Markers and the baseline end go into the journal's meta.json as they
    /// change, so a recovered session keeps them.
    private func saveMarkersToJournal() {
        updateJournalMeta {
            $0.markers = self.markers
            $0.baselineDurationSeconds = self.baselineDurationSeconds
        }
    }

    private func updateJournalMeta(_ change: (inout SessionJournal.Meta) -> Void) {
        guard var next = journalMeta else { return }
        change(&next)
        journalMeta = next
        journal?.writeMeta(next)
    }

    // MARK: - The once-a-second tick

    private func startTick() {
        // Android posts the first tick immediately, so second 0 is recorded;
        // match that rather than waiting a full interval.
        tick()
        let timer = Timer(timeInterval: 1.0, repeats: true) { _ in
            Task { @MainActor [weak self] in self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        tickTimer = timer
    }

    private func stopTick() {
        tickTimer?.invalidate()
        tickTimer = nil
    }

    private func tick() {
        guard phase == .recording else { return }
        let elapsed = liveElapsedSeconds()
        elapsedSeconds = elapsed
        // Consume-and-clear: headband-off flagged any time since the last tick
        // taints every band's reading for THIS tick only — the next starts clean.
        let headbandOff = headbandOffSinceLastTick
        // No fresh band packet this second means latestBands holds an old reading
        // (a dropped connection, or every channel NaN), so it's saved as
        // no-signal rather than a frozen repeat.
        let stale = !bandPacketSinceLastTick
        headbandOffSinceLastTick = false
        bandPacketSinceLastTick = false
        let dropTick = headbandOff || stale

        let tickSamples = latestBands.keys.map { band -> SessionSample in
            let value = latestBands[band] ?? NO_SIGNAL_VALUE
            // !isFinite catches NaN AND ±Infinity — absolute band power on a
            // zero-power channel logs out to -Infinity.
            let safe = (dropTick || !value.isFinite) ? NO_SIGNAL_VALUE : value
            return SessionSample(offsetSeconds: elapsed, band: band, value: safe)
        }
        samples.append(contentsOf: tickSamples)
        sampleCount = samples.count
        journal?.appendTick(samples: tickSamples)
        flushDraftNote()

        // A tick with no fresh or no usable relative-band value saved nothing
        // either, even though no artifact flag fired (e.g. the first second,
        // before any band packet has arrived).
        let noSignal = stale || BAND_ORDER.allSatisfy { band in
            let value = latestBands[band] ?? NO_SIGNAL_VALUE
            return value == NO_SIGNAL_VALUE || !value.isFinite
        }
        let status: TickStatus = headbandOff ? .headbandOff : (noSignal ? .noSignal : .saved)
        lastTickStatus = status
        tickTally[status, default: 0] += 1
    }

    private func liveElapsedSeconds() -> Int {
        guard let sessionStartInstant else { return 0 }
        return Int(sessionStartInstant.duration(to: .now).components.seconds)
    }

    // MARK: - SDK Callbacks

    fileprivate func handleConnection(_ packet: IXNMuseConnectionPacket, muse incoming: IXNMuse?) {
        // Ignore packets from a headband that isn't the one currently selected —
        // connect() never unregisters the PREVIOUS muse's listener.
        guard incoming === connectedMuse else { return }
        switch packet.currentConnectionState {
        case .connected:
            reconnectAttempts = 0
            connectionStatus = "Connected"
            if phase == .recording { setError(nil) }
        case .disconnected:
            // The error text is recording-specific, but the retry is not: a link
            // that drops before Start must still be recovered inside the Muse S's
            // ~60s self-power-off window (see reconnectMaxAttempts).
            if phase == .recording {
                errorText = "Headband disconnected — samples so far are kept, attempting to reconnect"
            } else {
                connectionStatus = "Disconnected"
            }
            attemptAutoReconnect()
        case .connecting:
            connectionStatus = "Connecting…"
        default:
            break
        }
    }

    private func attemptAutoReconnect() {
        guard autoReconnectArmed, let target = connectedMuse else { return }
        if reconnectAttempts >= Self.reconnectMaxAttempts {
            connectionStatus = "Disconnected — auto-reconnect gave up, press the headband's button and tap Connect"
            return
        }
        reconnectAttempts += 1
        connectionStatus = "Reconnecting… (attempt \(reconnectAttempts)/\(Self.reconnectMaxAttempts))"
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: Self.reconnectDelay)
            guard autoReconnectArmed, target === self.connectedMuse,
                  target.getConnectionState() != .connected else { return }
            target.runAsynchronously()
        }
    }

    fileprivate func handleData(_ packet: IXNMuseDataPacket, muse incoming: IXNMuse?) {
        guard incoming === connectedMuse else { return }
        guard let band = Self.bandName(for: packet.packetType()) else { return }
        // Average across the 4 EEG channels (not AUX_LEFT/AUX_RIGHT). NaN means
        // contact quality was too poor to compute band power for that channel;
        // skip those rather than poisoning the average. Also skip non-finite
        // values (absolute band power on a zero-power channel logs out to
        // -Infinity) — one infinite channel would drag the whole average to
        // ±Infinity, which JSONSerialization refuses at upload time.
        let channels: [IXNEeg] = [.EEG1, .EEG2, .EEG3, .EEG4]
        let values = channels.map { packet.getEegChannelValue($0) }.filter { $0.isFinite }
        guard !values.isEmpty else { return }
        latestBands[band] = values.reduce(0, +) / Double(values.count)
        if BAND_ORDER.contains(band) { bandPacketSinceLastTick = true }
    }

    fileprivate func handleArtifact(_ packet: IXNMuseArtifactPacket, muse incoming: IXNMuse?) {
        guard incoming === connectedMuse else { return }
        // Only headbandOn drops ticks — see headbandOffSinceLastTick for why
        // blink and jawClench don't.
        if !packet.headbandOn { headbandOffSinceLastTick = true }
        logArtifactFlags(packet)
    }

    // A once-a-second debug line of all three SDK artifact flags. It is what
    // showed the jaw-clench detector to be unreliable (see
    // headbandOffSinceLastTick), and it stays so that a firmware or SDK update
    // that fixes the detector can be recognized.
    private func logArtifactFlags(_ packet: IXNMuseArtifactPacket) {
        let now = ProcessInfo.processInfo.systemUptime
        if logWindowStart == 0 { logWindowStart = now }
        logPackets += 1
        if !packet.headbandOn { logHeadbandOff += 1 }
        if packet.jawClench { logJawClench += 1 }
        if packet.blink { logBlink += 1 }
        guard now - logWindowStart >= 1 else { return }
        print("SessionRecorder artifacts/1s: packets=\(logPackets) headbandOff=\(logHeadbandOff) "
              + "jawClench=\(logJawClench) blink=\(logBlink) phase=\(phase)")
        logPackets = 0; logHeadbandOff = 0; logJawClench = 0; logBlink = 0
        logWindowStart = now
    }

    /// ABSOLUTE alongside RELATIVE: relative band power is a share of total power
    /// (all 5 sum to ~100%), so a rise in one band forces the others down whether
    /// or not anything real changed — a closed-arithmetic artifact on top of the
    /// drowsiness/aperiodic-exponent confound. Absolute power doesn't have that
    /// particular problem (each band is independent), though it's still
    /// uncorrected raw band power and shares the same 1/f-background confound.
    /// Captured under separate "*_absolute" names so it rides alongside the
    /// existing relative series without changing it — charts/export/AI-summary
    /// still only look at BAND_ORDER's 5 relative names.
    private static func bandName(for type: IXNMuseDataPacketType) -> String? {
        switch type {
        case .alphaRelative: return "alpha"
        case .betaRelative: return "beta"
        case .deltaRelative: return "delta"
        case .thetaRelative: return "theta"
        case .gammaRelative: return "gamma"
        case .alphaAbsolute: return "alpha_absolute"
        case .betaAbsolute: return "beta_absolute"
        case .deltaAbsolute: return "delta_absolute"
        case .thetaAbsolute: return "theta_absolute"
        case .gammaAbsolute: return "gamma_absolute"
        default: return nil
        }
    }

    fileprivate static let registeredPacketTypes: [IXNMuseDataPacketType] = [
        .alphaRelative, .betaRelative, .deltaRelative, .thetaRelative, .gammaRelative,
        .alphaAbsolute, .betaAbsolute, .deltaAbsolute, .thetaAbsolute, .gammaAbsolute,
        .artifacts, .eeg,
    ]

    /// Relative bands only (the original 5) — the absolute-power series is
    /// captured for later analysis but deliberately left out of the live
    /// in-session readout, which is meant to be five values at a glance, not ten
    /// on two different scales.
    var relativeBands: [String: Double] {
        latestBands.filter { BAND_ORDER.contains($0.key) }
    }
}

// MARK: - Listener shims

// The three shims are `nonisolated` because the SDK calls them on its own
// thread; each hops to the main actor itself (except the raw-EEG path, which
// deliberately stays off it — see RawEegCapture).
private nonisolated final class MuseListShim: IXNMuseListener {
    weak var owner: MuseSessionService?
    init(owner: MuseSessionService) { self.owner = owner }
    func museListChanged() {
        Task { @MainActor in owner?.museListChanged() }
    }
}

private nonisolated final class ConnectionShim: IXNMuseConnectionListener {
    weak var owner: PatientSessionRecorder?
    init(owner: PatientSessionRecorder) { self.owner = owner }
    func receive(_ packet: IXNMuseConnectionPacket, muse: IXNMuse?) {
        Task { @MainActor in owner?.handleConnection(packet, muse: muse) }
    }
}

private nonisolated final class DataShim: IXNMuseDataListener {
    weak var owner: PatientSessionRecorder?
    // Strong, and deliberately not main-actor isolated — see RawEegCapture.
    let capture: RawEegCapture

    init(owner: PatientSessionRecorder, capture: RawEegCapture) {
        self.owner = owner
        self.capture = capture
    }

    func receive(_ packet: IXNMuseDataPacket?, muse: IXNMuse?) {
        guard let packet else { return }
        if packet.packetType() == .eeg {
            // Raw EEG capture. Arrives far faster than the 1-second band-power tick (~256Hz vs
            // ~1Hz), so it's handled here on the SDK's own thread rather than
            // hopped to the main actor per packet.
            let channels = [IXNEeg.EEG1, .EEG2, .EEG3, .EEG4].map { packet.getEegChannelValue($0) }
            capture.append(timestampUs: packet.timestamp(), channels: channels, from: muse)
            return
        }
        Task { @MainActor in owner?.handleData(packet, muse: muse) }
    }

    func receive(_ packet: IXNMuseArtifactPacket, muse: IXNMuse?) {
        Task { @MainActor in owner?.handleArtifact(packet, muse: muse) }
    }
}
