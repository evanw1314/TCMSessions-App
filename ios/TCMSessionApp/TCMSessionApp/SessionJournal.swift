import Foundation

/// An on-disk copy of one recording, so a session survives the app process dying
/// mid-recording (a crash, iOS reclaiming memory, a force-quit). Direct port of
/// Android's SessionJournal.kt.
///
/// PatientSessionRecorder's in-memory buffers are still what a normal End session
/// uploads; this is the backup. PatientListView offers to save any journal
/// nothing in this process is using (see `orphans`).
///
/// One directory per recording under Application Support/recordings/ (excluded
/// from iCloud/iTunes backup — patient data, and it's transient by design):
///  - meta.json: patient, modality, start time, baseline, markers, the note as
///    it's typed (within a second), and once End session is tapped the end time
///    and answers, plus the server's session id once it has one. Rewritten whole
///    (write, then rename) on every change.
///  - samples.csv: one line per band per recorded second, appended each tick.
///  - eeg.bin: raw EEG rows, `[int64 timestampUs, float32 × 4]` little-endian,
///    appended about once a second (EegBinaryWriter.packStoredRows reads them).
///
/// Two rules it exists to keep:
///  - Upload-before-clear: deleted only after the server has the session AND its
///    raw EEG, so a failed upload never loses a recording.
///  - Never create a session twice. meta.json carries a client_session_id, made
///    when the recording starts and sent with every attempt, and POST /sessions
///    answers a repeat of it with the session it already made (a repeated POST
///    /sessions/{id}/eeg just replaces the blob). The session id is still written
///    to meta.json, and confirmed written, before anything else happens. If the
///    id can't be written, the journal is deleted once the session exists: losing
///    the raw EEG beats a duplicate session.
///
/// File writes for a live journal run on one serial queue, in call order, so a
/// delete can't overtake a pending write. Writes are flushed to the OS each time,
/// which survives the app process dying (the case this exists for), not a power loss.
///
/// `nonisolated`: this build defaults types to @MainActor, and every part of
/// this one is deliberately off it — appendEeg runs on the SDK's callback thread
/// at ~256Hz, and recovery scans/uploads run in a detached task. Its own serial
/// queue and locks are what make that safe.
nonisolated final class SessionJournal: @unchecked Sendable {

    /// Muse EEG channels per raw row (EEG1-EEG4) — what PatientSessionRecorder
    /// captures. Inside the type rather than at file scope: a file-scope `let`
    /// would pick up this build's implicit @MainActor isolation.
    private static let eegChannels = 4
    private static let eegRowBytes = 8 + eegChannels * 4
    fileprivate static let trashPrefix = ".trash-"

    /// Everything about a recording except its samples and raw EEG.
    struct Meta {
        let patientId: Int
        let patientName: String
        // Who recorded it: only they can save it (sessions belong to the
        // practitioner who created them), so only they are offered it.
        let practitionerId: Int?
        let headbandDeviceId: String?
        let modality: String
        let startedAtIso: String
        var baselineDurationSeconds: Int?
        var markers: [SessionMarker] = []
        // Set when End session is tapped (nil if the app died first).
        var endedAtIso: String?
        var practitionerNote: String?
        var patientDozed: Bool?
        var alertnessRating: Int?
        // Set as soon as POST /sessions returns: from then on only the raw EEG
        // is left to upload.
        var sessionId: Int?
        // Made when the recording starts and sent with every attempt to create
        // the session, so the server can recognise a repeat (see
        // APIClient.createSession). Nil only in a journal from before it existed.
        var clientSessionId: String?

        func toJsonObject() -> [String: Any] {
            [
                "version": 1,
                "patient_id": patientId,
                "patient_name": patientName,
                "practitioner_id": practitionerId ?? NSNull(),
                "headband_device_id": headbandDeviceId ?? NSNull(),
                "modality": modality,
                "started_at": startedAtIso,
                "baseline_duration_seconds": baselineDurationSeconds ?? NSNull(),
                "markers": markers.map { ["offset_seconds": $0.offsetSeconds, "label": $0.label] },
                "ended_at": endedAtIso ?? NSNull(),
                "practitioner_note": practitionerNote ?? NSNull(),
                "patient_dozed": patientDozed ?? NSNull(),
                "alertness_rating": alertnessRating ?? NSNull(),
                "session_id": sessionId ?? NSNull(),
                "client_session_id": clientSessionId ?? NSNull(),
            ]
        }

        static func fromJsonObject(_ o: [String: Any]) -> Meta? {
            guard let patientId = jsonInt(o["patient_id"]),
                  let modality = o["modality"] as? String,
                  let startedAt = o["started_at"] as? String
            else { return nil }
            return Meta(
                patientId: patientId,
                patientName: o["patient_name"] as? String ?? "Patient",
                practitionerId: jsonInt(o["practitioner_id"]),
                headbandDeviceId: jsonString(o["headband_device_id"]),
                modality: modality,
                startedAtIso: startedAt,
                baselineDurationSeconds: jsonInt(o["baseline_duration_seconds"]),
                markers: (o["markers"] as? [[String: Any]] ?? []).compactMap { m in
                    guard let offset = jsonInt(m["offset_seconds"]),
                          let label = m["label"] as? String else { return nil }
                    return SessionMarker(offsetSeconds: offset, label: label)
                },
                endedAtIso: jsonString(o["ended_at"]),
                practitionerNote: jsonString(o["practitioner_note"]),
                patientDozed: jsonBool(o["patient_dozed"]),
                alertnessRating: jsonInt(o["alertness_rating"]),
                sessionId: jsonInt(o["session_id"]),
                clientSessionId: jsonString(o["client_session_id"])
            )
        }
    }

    /// What a recovery upload managed. It throws instead when it failed before
    /// the session existed, leaving the journal untouched.
    enum RecoveryResult: Equatable {
        case saved
        /// The session exists but its raw EEG didn't upload. `kept` means the
        /// journal stays for another try; false means trying again couldn't help
        /// (the server refused it, or the session id couldn't be recorded
        /// safely), so it was deleted.
        case savedWithoutRawEeg(reason: String, kept: Bool)
        /// Already saved, deleted, or being handled elsewhere.
        case alreadyHandled
    }

    let dir: URL
    private let metaFile: URL
    private let samplesFile: URL
    private let eegFile: URL

    private let io: DispatchQueue
    // Opened lazily on the io queue, the only thread that touches them.
    private var samplesOut: FileHandle?
    private var eegOut: FileHandle?

    // Raw EEG arrives ~256 times a second on the SDK's callback thread, so rows
    // collect here and go to disk in one write per tick.
    private let eegLock = NSLock()
    private var eegPending = Data()

    private let closedLock = NSLock()
    private var _closed = false
    private var closed: Bool {
        closedLock.lock(); defer { closedLock.unlock() }
        return _closed
    }

    /// Sets `closed` and reports whether this call is the one that did it, so
    /// close() and delete() can't both run their teardown.
    private func claimClosed() -> Bool {
        closedLock.lock(); defer { closedLock.unlock() }
        if _closed { return false }
        _closed = true
        return true
    }

    private init(dir: URL) {
        self.dir = dir
        self.metaFile = dir.appendingPathComponent("meta.json")
        self.samplesFile = dir.appendingPathComponent("samples.csv")
        self.eegFile = dir.appendingPathComponent("eeg.bin")
        self.io = DispatchQueue(label: "SessionJournal.\(dir.lastPathComponent)")
    }

    // MARK: - Writing (called by PatientSessionRecorder while it owns this journal)

    func writeMeta(_ meta: Meta) {
        guard let json = Self.encode(meta) else { return }
        runIo { _ = self.writeMetaFile(json) }
    }

    /// writeMeta, but waits and says whether it reached the disk. Used for the
    /// session id, which must be on disk before the next step. Runs on the same
    /// io queue as every other write, so it can't be overtaken by (or race) one
    /// already queued.
    func writeMetaNow(_ meta: Meta) async -> Bool {
        guard let json = Self.encode(meta) else { return false }
        return await withCheckedContinuation { continuation in
            // Not runIo: this one must report back even after close()/delete()
            // shut the queue down, and it needs the write's actual result.
            io.async { continuation.resume(returning: self.writeMetaFile(json)) }
        }
    }

    // Replacing an existing file via replaceItemAt is atomic, so a reader never
    // sees a half-written meta.json.
    private func writeMetaFile(_ json: Data) -> Bool {
        let tmp = dir.appendingPathComponent("meta.json.tmp")
        do {
            try json.write(to: tmp, options: .atomic)
            if FileManager.default.fileExists(atPath: metaFile.path) {
                _ = try FileManager.default.replaceItemAt(metaFile, withItemAt: tmp)
            } else {
                try FileManager.default.moveItem(at: tmp, to: metaFile)
            }
            return true
        } catch {
            log("couldn't write meta: \(error.localizedDescription)")
            try? FileManager.default.removeItem(at: tmp)
            return false
        }
    }

    /// Called on the SDK's callback thread for every raw EEG sample.
    func appendEeg(timestampUs: Int64, channels: [Double]) {
        if closed { return }
        var row = Data(capacity: Self.eegRowBytes)
        var ts = UInt64(bitPattern: timestampUs).littleEndian
        withUnsafeBytes(of: &ts) { row.append(contentsOf: $0) }
        for index in 0..<Self.eegChannels {
            let value = index < channels.count ? channels[index] : Double.nan
            var bits = Float(value).bitPattern.littleEndian
            withUnsafeBytes(of: &bits) { row.append(contentsOf: $0) }
        }
        eegLock.lock()
        eegPending.append(row)
        eegLock.unlock()
    }

    /// One recorded second: that tick's band samples, plus whatever raw EEG has
    /// arrived since the last one.
    func appendTick(samples: [SessionSample]) {
        let lines = samples.map { "\($0.offsetSeconds),\($0.band),\($0.value)\n" }.joined()
        let eeg = takePendingEeg()
        runIo {
            if let data = lines.data(using: .utf8), !data.isEmpty {
                let handle = self.samplesOut ?? self.openAppend(self.samplesFile)
                self.samplesOut = handle
                do {
                    try handle?.write(contentsOf: data)
                } catch {
                    self.log("sample write failed: \(error.localizedDescription)")
                }
            }
            self.writeEeg(eeg)
        }
    }

    /// Writes out any raw EEG still buffered — called when End session is tapped,
    /// since the last tick has already run by then.
    func flushEeg() {
        let eeg = takePendingEeg()
        runIo { self.writeEeg(eeg) }
    }

    private func takePendingEeg() -> Data {
        eegLock.lock()
        defer { eegPending = Data(); eegLock.unlock() }
        return eegPending
    }

    private func writeEeg(_ bytes: Data) {
        guard !bytes.isEmpty else { return }
        let handle = eegOut ?? openAppend(eegFile)
        eegOut = handle
        do {
            try handle?.write(contentsOf: bytes)
        } catch {
            // A failed write is logged, never thrown: the backup must not take
            // down the recording it's backing up (the in-memory copy is fine).
            log("raw EEG write failed: \(error.localizedDescription)")
        }
    }

    private func openAppend(_ url: URL) -> FileHandle? {
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: url) else {
            log("couldn't open \(url.lastPathComponent)")
            return nil
        }
        do {
            try handle.seekToEnd()
        } catch {
            log("couldn't seek \(url.lastPathComponent): \(error.localizedDescription)")
            try? handle.close()
            return nil
        }
        return handle
    }

    /// Deletes the journal once the server has everything. It stops being
    /// findable at once (the directory is renamed to a trash name first,
    /// atomically), and is removed after any writes still queued.
    func delete() {
        guard claimClosed() else { return }
        let trash = moveToTrash()
        // Renamed: invisible to orphans() already. Not renamed: it still has its
        // real name, so it stays counted as live until it's gone.
        if trash != nil { Self.live.remove(dir.lastPathComponent) }
        // Not runIo: this is the teardown that has to run after `closed` is set.
        io.async {
            self.closeStreams()
            do {
                try FileManager.default.removeItem(at: trash ?? self.dir)
            } catch {
                self.log("couldn't fully delete: \(error.localizedDescription)")
            }
            Self.live.remove(self.dir.lastPathComponent)
        }
    }

    /// Stops writing but keeps the files — for a journal that still has something
    /// to upload after its recorder is done with it. From here on it's an orphan,
    /// which PatientListView offers to finish.
    func close() {
        guard claimClosed() else { return }
        // Stops counting as live only after its queued writes, so orphans() never
        // offers a journal that's still being written. Not runIo, for the same
        // reason as delete(): it must run despite `closed`.
        io.async {
            self.closeStreams()
            Self.live.remove(self.dir.lastPathComponent)
        }
    }

    private func closeStreams() {
        try? samplesOut?.close()
        try? eegOut?.close()
        samplesOut = nil
        eegOut = nil
    }

    /// The trash directory, or nil if the rename failed, in which case the delete
    /// works on the original.
    private func moveToTrash() -> URL? {
        let trash = dir.deletingLastPathComponent()
            .appendingPathComponent(Self.trashPrefix + dir.lastPathComponent)
        do {
            try FileManager.default.moveItem(at: dir, to: trash)
            return trash
        } catch {
            return nil
        }
    }

    // A failed write is logged, never thrown: the backup must not take down the
    // recording it's backing up (the in-memory copy is unaffected).
    private func runIo(_ block: @escaping @Sendable () -> Void) {
        // A serial queue can't be shut down the way Kotlin's executor is, so the
        // `closed` check stands in: without it a tick arriving after delete()
        // would reopen samples.csv/eeg.bin at the just-removed path.
        // Submit-time only, deliberately — this mirrors Kotlin's io.shutdown(),
        // which rejects NEW submissions but lets already-queued work drain. A
        // second check inside the block would instead cancel a tick that close()
        // is meant to keep, silently losing the last second of a journal kept
        // for a raw-EEG retry.
        guard !closed else { return }
        io.async(execute: block)
    }

    private func log(_ message: String) {
        print("SessionJournal[\(dir.lastPathComponent)]: \(message)")
    }

    // MARK: - Recovery (a journal nothing in this process is writing to)

    func readMeta() -> Meta? {
        guard let data = try? Data(contentsOf: metaFile),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let meta = Meta.fromJsonObject(object)
        else {
            if FileManager.default.fileExists(atPath: dir.path) { log("unreadable meta") }
            return nil
        }
        return meta
    }

    /// Every sample written so far. A last line cut short by the app dying
    /// mid-write is skipped.
    func readSamples() -> [SessionSample] {
        guard let text = try? String(contentsOf: samplesFile, encoding: .utf8) else { return [] }
        return text.split(separator: "\n", omittingEmptySubsequences: true).compactMap { line in
            let parts = line.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count == 3,
                  let offset = Int(parts[0]),
                  let value = Double(parts[2])
            else { return nil }
            return SessionSample(offsetSeconds: offset, band: String(parts[1]), value: value)
        }
    }

    func hasRawEeg() -> Bool { fileSize(eegFile) >= Self.eegRowBytes }

    /// The raw EEG in POST /sessions/{id}/eeg's format, or nil if none.
    func packRawEeg() throws -> Data? {
        guard FileManager.default.fileExists(atPath: eegFile.path) else { return nil }
        let rows = try Data(contentsOf: eegFile)
        return EegBinaryWriter.packStoredRows(rows, channelCount: Self.eegChannels)
    }

    /// Whether anything was recorded into it, for a journal with no readable
    /// meta.json (the app died before its first write): one with nothing in it
    /// can simply be cleared away.
    func hasAnyData() -> Bool { fileSize(samplesFile) > 0 || fileSize(eegFile) > 0 }

    private func fileSize(_ url: URL) -> Int {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: url.path) else { return 0 }
        return (attributes[.size] as? NSNumber)?.intValue ?? 0
    }

    /// Deletes a journal found by `orphans`, unless something in this process has
    /// since started using it.
    func deleteNow() {
        // Claims the journal like uploadRecovered does, so a Delete from one
        // screen can't pull it out from under an upload started on another.
        let name = dir.lastPathComponent
        if Self.live.contains(name) || !Self.inFlight.insert(name) { return }
        defer { Self.inFlight.remove(name) }
        _ = claimClosed()
        try? FileManager.default.removeItem(at: moveToTrash() ?? dir)
    }

    /// Uploads what's left of a recovered journal, then deletes it. Reads the
    /// journal fresh from disk rather than trusting what a dialog showed earlier,
    /// and only one upload per journal can run at once, so a second screen
    /// offering the same recording can't create the session twice. Throws if it
    /// failed before the session existed, leaving the journal as it was. Don't
    /// let it be cancelled partway: between POST /sessions succeeding and the id
    /// being written is the one window where stopping would lose track of the session.
    func uploadRecovered() async throws -> RecoveryResult {
        let name = dir.lastPathComponent
        if Self.live.contains(name) || !Self.inFlight.insert(name) { return .alreadyHandled }
        defer { Self.inFlight.remove(name) }

        guard FileManager.default.fileExists(atPath: dir.path) else { return .alreadyHandled }
        guard let meta = readMeta() else {
            throw APIError(message: "The recording's details couldn't be read")
        }

        var sessionId = meta.sessionId
        var idRecorded = true
        if sessionId == nil {
            let samples = readSamples()
            let lastSecond = samples.map(\.offsetSeconds).max()
                ?? meta.markers.map(\.offsetSeconds).max() ?? 0
            // Never reached End session: say so on the chart, where anyone
            // reading the session will see it, and end it at the last second
            // actually recorded.
            let interrupted = meta.endedAtIso == nil
            let markers = interrupted
                ? meta.markers + [SessionMarker(offsetSeconds: lastSecond,
                                                label: "Recording interrupted (the app closed) - recovered up to here")]
                : meta.markers
            let created = try await APIClient.createSession(
                patientId: meta.patientId,
                headbandDeviceId: meta.headbandDeviceId,
                modality: meta.modality,
                startedAtIso: meta.startedAtIso,
                endedAtIso: meta.endedAtIso ?? Self.endTimeFrom(meta.startedAtIso, lastOffsetSeconds: lastSecond),
                practitionerNote: meta.practitionerNote,
                patientDozed: meta.patientDozed,
                alertnessRating: meta.alertnessRating,
                baselineDurationSeconds: meta.baselineDurationSeconds,
                markers: markers,
                samples: samples,
                clientSessionId: meta.clientSessionId
            )
            sessionId = created
            var withId = meta
            withId.sessionId = created
            idRecorded = Self.encode(withId).map { writeMetaFile($0) } ?? false
        }
        guard let sessionId else { return .alreadyHandled }

        // A failed read isn't "no raw EEG": the file is still there.
        let blob: Data?
        do {
            blob = try packRawEeg()
        } catch {
            if !idRecorded { try? FileManager.default.removeItem(at: moveToTrash() ?? dir) }
            return .savedWithoutRawEeg(
                reason: "the raw recording couldn't be read (\(error.localizedDescription))", kept: idRecorded)
        }
        if let blob {
            do {
                try await APIClient.uploadRawEeg(sessionId: sessionId, eegBlob: blob)
            } catch {
                let keep = idRecorded && !isServerRefusal(error)
                if !keep { try? FileManager.default.removeItem(at: moveToTrash() ?? dir) }
                return .savedWithoutRawEeg(reason: error.localizedDescription, kept: keep)
            }
        }
        try? FileManager.default.removeItem(at: moveToTrash() ?? dir)
        return .saved
    }

    // MARK: - Process-wide registry

    // Journals a recorder in this process is writing to. Added the moment one is
    // created, so a scan can never mistake a brand-new recording for an abandoned one.
    fileprivate static let live = NameSet()
    // Journals a recovery upload is working on right now.
    fileprivate static let inFlight = NameSet()
    // Journals the practitioner put off ("Later", or an upload that failed):
    // offered again on the next app launch, not every time the list reappears.
    fileprivate static let postponed = NameSet()

    private static func root() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("recordings", isDirectory: true)
    }

    private static func encode(_ meta: Meta) -> Data? {
        try? JSONSerialization.data(withJSONObject: meta.toJsonObject())
    }

    /// Starts a journal for a new recording, or returns nil if the directory
    /// can't be created — the recording then runs as before, in memory only.
    static func create(meta: Meta, startedAtMs: Int64) -> SessionJournal? {
        createIn(root: root(), meta: meta, startedAtMs: startedAtMs)
    }

    /// `create` under any directory; internal so unit tests can use a temporary one.
    static func createIn(root: URL, meta: Meta, startedAtMs: Int64) -> SessionJournal? {
        let dir = root.appendingPathComponent("p\(meta.patientId)_\(startedAtMs)", isDirectory: true)
        // Live before it exists on disk, so no scan can ever see it without also
        // seeing that it's live.
        live.insertAlways(dir.lastPathComponent)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            live.remove(dir.lastPathComponent)
            print("SessionJournal: couldn't create \(dir.path): \(error.localizedDescription)")
            return nil
        }
        // Patient data, and transient by design — keep it out of iCloud/iTunes backups.
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var mutableDir = dir
        try? mutableDir.setResourceValues(excluded)

        let journal = SessionJournal(dir: dir)
        journal.writeMeta(meta)
        return journal
    }

    static func postpone(_ journal: SessionJournal) {
        postponed.insertAlways(journal.dir.lastPathComponent)
    }

    /// The process-wide sets are keyed by directory name alone; unit tests reuse
    /// names across temporary roots.
    static func resetForTests() {
        live.removeAll()
        inFlight.removeAll()
        postponed.removeAll()
    }

    /// Journals nothing in this process is using, oldest first, minus any
    /// postponed until the next launch: left behind by an app process that died,
    /// or kept because an upload failed. Also clears out deletes a dying process
    /// didn't finish. Call off the main thread.
    static func orphans() -> [SessionJournal] {
        orphansIn(root: root())
    }

    static func orphansIn(root: URL) -> [SessionJournal] {
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: root, includingPropertiesForKeys: [.isDirectoryKey])) ?? []
        let dirs = contents.filter { url in
            (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
        }
        for stale in dirs where stale.lastPathComponent.hasPrefix(Self.trashPrefix) {
            try? FileManager.default.removeItem(at: stale)
        }
        return dirs
            .filter { url in
                let name = url.lastPathComponent
                return !name.hasPrefix(Self.trashPrefix)
                    && !live.contains(name) && !inFlight.contains(name) && !postponed.contains(name)
            }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .map { SessionJournal(dir: $0) }
    }

    /// Recovered end time: the recording's last saved second, since the app died
    /// without an End session.
    static func endTimeFrom(_ startedAtIso: String, lastOffsetSeconds: Int) -> String {
        guard let start = parseServerIso(startedAtIso) else { return startedAtIso }
        return formatServerIso(start.addingTimeInterval(Double(lastOffsetSeconds + 1)))
    }
}

// MARK: - Helpers

/// A thread-safe set of directory names — the Swift stand-in for Android's
/// Collections.synchronizedSet. `insert` reports whether it actually added,
/// which is how inFlight claims a journal exactly once.
nonisolated final class NameSet {
    private var names = Set<String>()
    private let lock = NSLock()

    @discardableResult
    func insert(_ name: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return names.insert(name).inserted
    }

    func insertAlways(_ name: String) { _ = insert(name) }

    func remove(_ name: String) {
        lock.lock(); names.remove(name); lock.unlock()
    }

    func contains(_ name: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return names.contains(name)
    }

    func removeAll() {
        lock.lock(); names.removeAll(); lock.unlock()
    }
}

private nonisolated func jsonString(_ value: Any?) -> String? {
    if value == nil || value is NSNull { return nil }
    return value as? String
}

private nonisolated func jsonInt(_ value: Any?) -> Int? {
    if value == nil || value is NSNull { return nil }
    if let i = value as? Int { return i }
    return (value as? NSNumber)?.intValue
}

private nonisolated func jsonBool(_ value: Any?) -> Bool? {
    if value == nil || value is NSNull { return nil }
    if let b = value as? Bool { return b }
    return (value as? NSNumber)?.boolValue
}
