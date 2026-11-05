import Combine
import SwiftUI

/// Offers to finish recordings a previous run of the app left behind — port of
/// PatientListActivity's interrupted-recording flow.
///
/// Two shapes of leftover, both held by SessionJournal:
///  - the app closed mid-recording, so no session exists on the server yet;
///  - the session saved but its raw EEG upload failed, so it can't be analyzed.
///
/// Presented one at a time; each outcome asks for the next. Nothing here is
/// silent: a recording is only ever deleted by an explicit tap, or when there's
/// demonstrably nothing left in it to save.
@MainActor
final class RecoveryCoordinator: ObservableObject {

    struct Interrupted: Identifiable {
        let journal: SessionJournal
        let meta: SessionJournal.Meta
        let recordedSeconds: Int

        var id: String { journal.dir.lastPathComponent }
        /// True when the session itself still has to be created; false when only
        /// its raw EEG is left to upload.
        var needsSessionCreate: Bool { meta.sessionId == nil }
    }

    struct Outcome: Identifiable {
        let title: String
        let message: String
        var id: String { title + message }
    }

    @Published var pending: Interrupted?
    @Published var confirmingDiscard: Interrupted?
    @Published var outcome: Outcome?
    @Published private(set) var isWorking = false

    /// A prompt from this screen is showing, or its upload/delete is running.
    private var inProgress = false
    /// Set when a recovery actually saved something, so the caller can reload.
    var onSaved: (() -> Void)?

    /// Offers the first interrupted recording, if any; each outcome calls this
    /// again for the next.
    func checkForInterruptedRecordings() {
        guard !inProgress else { return }
        inProgress = true
        let userId = AppConfig.userId
        Task {
            let next = await Task.detached { Self.firstOffer(userId: userId) }.value
            if let next {
                pending = next
            } else {
                inProgress = false
            }
        }
    }

    /// Scans the journals off the main actor and returns the first worth
    /// offering to this practitioner.
    private nonisolated static func firstOffer(userId: Int) -> Interrupted? {
        for journal in SessionJournal.orphans() {
            if let offer = describe(journal, userId: userId) { return offer }
        }
        return nil
    }

    /// Nil for a journal not worth offering to this practitioner. One with
    /// nothing left in it (the app died in the first second, or only an
    /// already-uploaded session's id is left) is deleted on the spot.
    private nonisolated static func describe(_ journal: SessionJournal, userId: Int) -> Interrupted? {
        guard let meta = journal.readMeta() else {
            // The app died before the first write: nothing to save, and nothing
            // to say whose it was. One that somehow has data but no details is
            // left alone rather than deleted unseen.
            if !journal.hasAnyData() { journal.deleteNow() }
            return nil
        }
        // Recorded by someone else on this phone: only they can save it
        // (sessions belong to the practitioner who created them), so it waits
        // for their login rather than being offered, or deleted, here.
        if let owner = meta.practitionerId, owner != userId { return nil }

        let samples = meta.sessionId == nil ? journal.readSamples() : []
        let nothingLeft: Bool
        if meta.sessionId == nil {
            nothingLeft = samples.isEmpty && meta.markers.isEmpty && !journal.hasRawEeg()
        } else {
            nothingLeft = !journal.hasRawEeg()
        }
        if nothingLeft {
            journal.deleteNow()
            return nil
        }
        let recorded = (samples.map(\.offsetSeconds).max()).map { $0 + 1 } ?? 0
        return Interrupted(journal: journal, meta: meta, recordedSeconds: recorded)
    }

    // MARK: - Actions

    func upload(_ item: Interrupted) {
        pending = nil
        isWorking = true
        Task {
            // Detached so leaving this screen mid-upload can't cancel it between
            // the session being created and its id being recorded.
            let journal = item.journal
            let result = await Task.detached { () -> Result<SessionJournal.RecoveryResult, Error> in
                do { return .success(try await journal.uploadRecovered()) }
                catch { return .failure(error) }
            }.value
            isWorking = false

            switch result {
            case .success(.savedWithoutRawEeg(let reason, let kept)):
                if kept { SessionJournal.postpone(item.journal) }
                outcome = Outcome(
                    title: "Session saved, raw recording not uploaded",
                    message: "The session is saved, but its raw brainwave recording didn't upload: \(reason)\n\n"
                        + (kept
                           ? "It's still on the phone, and you'll be asked again the next time the app starts."
                           : "Trying again couldn't help, so it has been removed from the phone. The session "
                             + "can't be analyzed.")
                )
                onSaved?()
            case .success(.saved):
                onSaved?()
                finishStep()
            case .success(.alreadyHandled):
                finishStep()
            case .failure(let error):
                SessionJournal.postpone(item.journal)
                let refused = isServerRefusal(error)
                outcome = Outcome(
                    title: "Couldn't save it",
                    message: "\(error.localizedDescription)\n\n"
                        + (refused
                           ? "The server refused this recording, so trying again probably won't help. "
                             + "It's still on the phone; you can delete it the next time the app starts."
                           : "It's still on the phone, and you'll be asked again the next time the app starts.")
                )
            }
        }
    }

    func postpone(_ item: Interrupted) {
        SessionJournal.postpone(item.journal)
        pending = nil
        finishStep()
    }

    func discard(_ item: Interrupted) {
        confirmingDiscard = nil
        let journal = item.journal
        Task {
            await Task.detached { journal.deleteNow() }.value
            finishStep()
        }
    }

    /// "Cancel" on the delete confirmation goes back to the original offer
    /// rather than dropping it silently.
    func cancelDiscard(_ item: Interrupted) {
        confirmingDiscard = nil
        pending = item
    }

    func dismissOutcome() {
        outcome = nil
        finishStep()
    }

    private func finishStep() {
        inProgress = false
        checkForInterruptedRecordings()
    }
}

/// The prompts themselves, attached once to the patient list. Uses the
/// multi-button alert API rather than the two-button `Alert` value, so the
/// offer keeps all three of Android's choices (Save / Delete / Later).
struct RecoveryPromptModifier: ViewModifier {
    @ObservedObject var coordinator: RecoveryCoordinator

    func body(content: Content) -> some View {
        content
            .overlay {
                if coordinator.isWorking {
                    // A recovered recording is several MB with a 60s timeout —
                    // without this the alert just vanishes and nothing visibly
                    // happens. Android shows a "Saving…" toast here.
                    VStack(spacing: 10) {
                        ProgressView()
                        Text("Saving…").font(.footnote)
                    }
                    .padding(20)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
            }
            .alert(offerTitle, isPresented: presenting($coordinator.pending),
                   presenting: coordinator.pending) { item in
                Button(item.needsSessionCreate ? "Save" : "Upload") { coordinator.upload(item) }
                Button("Delete", role: .destructive) {
                    // Presented on the next runloop turn: setting the second
                    // alert's item in the same update pass that dismisses this
                    // one can drop it, which would strand `inProgress` and stall
                    // the whole recovery queue until the next launch.
                    coordinator.pending = nil
                    Task { @MainActor in coordinator.confirmingDiscard = item }
                }
                Button("Later", role: .cancel) { coordinator.postpone(item) }
            } message: { item in
                Text(offerMessage(item))
            }
            .alert("Delete it?", isPresented: presenting($coordinator.confirmingDiscard),
                   presenting: coordinator.confirmingDiscard) { item in
                Button("Delete", role: .destructive) { coordinator.discard(item) }
                Button("Cancel", role: .cancel) { coordinator.cancelDiscard(item) }
            } message: { item in
                Text(item.needsSessionCreate
                     ? "Delete this recording? This can't be undone."
                     : "Delete the raw recording? The saved session stays, but it can't be analyzed "
                       + "without it. This can't be undone.")
            }
            .alert(coordinator.outcome?.title ?? "", isPresented: presenting($coordinator.outcome),
                   presenting: coordinator.outcome) { _ in
                Button("OK") { coordinator.dismissOutcome() }
            } message: { outcome in
                Text(outcome.message)
            }
    }

    private var offerTitle: String {
        guard let pending = coordinator.pending else { return "" }
        return pending.needsSessionCreate ? "Unsaved recording found" : "Raw recording not uploaded"
    }

    private func offerMessage(_ item: RecoveryCoordinator.Interrupted) -> String {
        let meta = item.meta
        let started = formatTimestamp(meta.startedAtIso)
        if item.needsSessionCreate {
            let length = String(format: "%d:%02d", item.recordedSeconds / 60, item.recordedSeconds % 60)
            return "The app closed before this session was saved:\n\n"
                + "\(meta.patientName) · \(meta.modality)\nStarted \(started) · \(length) recorded\n\n"
                + "Save it now? Everything up to the last recorded second is kept"
                + (meta.endedAtIso == nil ? ", and the chart will show where it was cut off." : ".")
        }
        return "\(meta.patientName)'s \(meta.modality) session from \(started) was saved, but its "
            + "raw brainwave recording didn't upload, so it can't be analyzed yet.\n\nUpload it now?"
    }

    /// A presence binding for an optional: the alert only drives dismissal, and
    /// each button sets the underlying value itself.
    private func presenting<T>(_ binding: Binding<T?>) -> Binding<Bool> {
        Binding(get: { binding.wrappedValue != nil },
                set: { if !$0 { binding.wrappedValue = nil } })
    }
}

extension View {
    func recoveryPrompts(_ coordinator: RecoveryCoordinator) -> some View {
        modifier(RecoveryPromptModifier(coordinator: coordinator))
    }
}
