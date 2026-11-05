import SwiftUI

/// Tabbed patient profile — Overview / TCM sessions / Notes. Port of
/// PatientDetailActivity. Also doubles as the patient's own self-view: the exact
/// same screen a patient lands on after signing in (isSelfView), swapping every
/// data source for its self-scoped "/me" equivalent and hiding every write action
/// a patient shouldn't have (remove, start a session, add/delete a note, assign
/// email, AI summary). Reading + export stay available either way.
struct PatientDetailView: View {
    let initialName: String
    let isSelfView: Bool
    let onSignOut: () -> Void

    @EnvironmentObject private var session: MuseSessionService

    @State private var patientId: Int
    @State private var patientName: String
    @State private var patientEmail: String?
    @State private var headbandDeviceId: String?
    @State private var lastSessionAt: String?

    @State private var selectedTab = 0
    @State private var sessions: [SessionSummary] = []
    @State private var notes: [PatientNote] = []
    @State private var summaryText = ""
    @State private var isSummarizing = false
    @State private var newNote = ""
    @State private var showAssignEmail = false
    @State private var assignEmailText = ""
    @State private var showRemoveConfirm = false
    @State private var noteToDelete: PatientNote?
    @State private var errorText = ""
    @State private var exportItem: ExportItem?
    // Owned here, not inside EegTrendTab: the `switch` below destroys the
    // unselected tab's view and all its @State on every switch, so the trend's
    // cache and metric selection have to outlive it. See EegTrendModel.
    @StateObject private var eegTrend = EegTrendModel()

    init(patientId: Int, initialName: String, initialEmail: String?,
         initialHeadband: String?, initialLastSession: String?,
         isSelfView: Bool, onSignOut: @escaping () -> Void) {
        self.initialName = initialName
        self.isSelfView = isSelfView
        self.onSignOut = onSignOut
        _patientId = State(initialValue: patientId)
        _patientName = State(initialValue: initialName)
        _patientEmail = State(initialValue: initialEmail)
        _headbandDeviceId = State(initialValue: initialHeadband)
        _lastSessionAt = State(initialValue: initialLastSession)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Tab", selection: $selectedTab) {
                    Text("Overview").tag(0)
                    Text("Sessions").tag(1)
                    Text("Notes").tag(2)
                    // Practitioner-only (GET /patients/{id}/eeg-trend is
                    // practitioner-scoped server-side), so tab 3 never exists —
                    // and is never selectable — in self-view.
                    if !isSelfView { Text("EEG Trend").tag(3) }
                }
                .pickerStyle(.segmented)

                switch selectedTab {
                case 0: overviewTab
                case 1: sessionsTab
                case 2: notesTab
                default: EegTrendTab(patientId: patientId, patientName: patientName, model: eegTrend)
                }
            }
            .padding()
        }
        .navigationTitle(patientName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if isSelfView {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Sign out") { AppConfig.clearSession(); onSignOut() }
                }
            }
        }
        .alert("Error", isPresented: .constant(!errorText.isEmpty)) {
            Button("OK") { errorText = "" }
        } message: { Text(errorText) }
        .sheet(item: $exportItem) { item in ActivityView(url: item.url) }
        .onAppear { Task { await onAppearLoad() } }
        .task(id: selectedTab) { if selectedTab == 2 && notes.isEmpty { await loadNotes() } }
    }

    // MARK: - Overview

    private var overviewTab: some View {
        VStack(alignment: .leading, spacing: 16) {
            emailRow
            HStack(spacing: 12) {
                tile("Last TCM session", lastSessionAt.map(formatTimestamp) ?? "None yet")
                tile("Sessions logged", "\(sessions.count)")
            }
            card("Recent activity") {
                if sessions.isEmpty {
                    Text("No sessions recorded yet.").font(.footnote).foregroundStyle(.secondary)
                } else {
                    ForEach(sessions.prefix(5)) { s in
                        NavigationLink(value: Route.sessionDetail(sessionId: s.id, patientName: patientName, selfView: isSelfView)) {
                            Text("\(formatSessionRange(s.startedAt, s.endedAt)) — \(s.modality)")
                                .font(.footnote).foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            if !isSelfView {
                card("AI overview") {
                    Text(summaryText.isEmpty ? "Tap Summarize for a plain-prose description of this patient's recent notes and sessions." : summaryText)
                        .font(.footnote)
                        .foregroundStyle(summaryText.isEmpty ? .secondary : .primary)
                    Button {
                        summarize()
                    } label: {
                        Label(isSummarizing ? "Generating…" : "Summarize", systemImage: "sparkles")
                    }
                    .disabled(isSummarizing)
                    .padding(.top, 4)
                }
                Button(role: .destructive) { showRemoveConfirm = true } label: {
                    Label("Remove patient", systemImage: "person.badge.minus")
                }
                .confirmationDialog("Remove \(patientName)?", isPresented: $showRemoveConfirm, titleVisibility: .visible) {
                    Button("Remove", role: .destructive) { removePatient() }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    Text("This takes \(patientName) off your patient list and ends any device assignment you hold for them. Their account, sessions, and notes are kept.")
                }
            }
        }
    }

    @ViewBuilder private var emailRow: some View {
        if let email = patientEmail {
            Text(email).font(.subheadline).foregroundStyle(.secondary)
        } else if isSelfView {
            EmptyView()
        } else {
            Button("No account yet — tap to assign an email") { assignEmailText = ""; showAssignEmail = true }
                .font(.subheadline)
                .alert("Assign email", isPresented: $showAssignEmail) {
                    TextField("patient@example.com", text: $assignEmailText)
                        .textInputAutocapitalization(.never)
                    Button("Assign") { assignEmail() }
                    Button("Cancel", role: .cancel) {}
                } message: {
                    Text("This chooses which account will receive this patient's session data. It can only be set once.")
                }
        }
    }

    // MARK: - Sessions

    private var sessionsTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !isSelfView, let activeRecorder = session.activeRecorder(forPatient: patientId) {
                NavigationLink(value: Route.recordSession(patientId: patientId, patientName: patientName, headband: headbandDeviceId)) {
                    OngoingSessionRow(phase: activeRecorder.phase, startedAtIso: activeRecorder.startedAtIso)
                }
                .buttonStyle(.plain)
            }
            if sessions.isEmpty {
                Text("No sessions recorded yet.").font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(sessions) { s in
                NavigationLink(value: Route.sessionDetail(sessionId: s.id, patientName: patientName, selfView: isSelfView)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.modality).font(.body)
                        Text(formatSessionRange(s.startedAt, s.endedAt)).font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Divider()
            }
            HStack {
                if !isSelfView {
                    NavigationLink(value: Route.recordSession(patientId: patientId, patientName: patientName, headband: headbandDeviceId)) {
                        Label("New session", systemImage: "waveform.path.ecg")
                    }
                    .buttonStyle(.borderedProminent)
                }
                Button { exportAll() } label: { Label("Export all", systemImage: "square.and.arrow.up") }
                    .buttonStyle(.bordered)
            }
            .padding(.top, 8)
        }
    }

    // MARK: - Notes

    private var notesTab: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !isSelfView {
                HStack(alignment: .bottom) {
                    TextField("Add a note", text: $newNote, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                    Button("Add") { addNote() }.disabled(newNote.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            if notes.isEmpty {
                Text("No notes yet.").font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(notes) { note in
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(formatTimestamp(note.createdAt))  ·  \(note.practitionerName)")
                        .font(.caption2).foregroundStyle(.secondary)
                    Text(note.body).font(.subheadline)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { if !isSelfView { noteToDelete = note } }
                Divider()
            }
        }
        .confirmationDialog("Delete this note?", isPresented: .constant(noteToDelete != nil), titleVisibility: .visible) {
            Button("Delete", role: .destructive) { if let n = noteToDelete { deleteNote(n) } }
            Button("Cancel", role: .cancel) { noteToDelete = nil }
        } message: { Text(noteToDelete?.body ?? "") }
    }

    // MARK: - Building blocks

    private func tile(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.headline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func card<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.subheadline.weight(.semibold))
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Data

    private func onAppearLoad() async {
        if isSelfView { await loadMyProfile() }
        await loadSessions()
        if selectedTab == 2 { await loadNotes() }
    }

    private func loadMyProfile() async {
        do {
            let p = try await APIClient.myProfile()
            patientName = p.name
            patientEmail = p.email
            headbandDeviceId = p.headbandDeviceId
            lastSessionAt = p.lastSessionAt
        } catch { errorText = error.localizedDescription }
    }

    private func loadSessions() async {
        do {
            sessions = isSelfView ? try await APIClient.mySessions() : try await APIClient.listSessions(patientId: patientId)
        } catch { errorText = error.localizedDescription }
    }

    private func loadNotes() async {
        do {
            notes = isSelfView ? try await APIClient.myNotes() : try await APIClient.listNotes(patientId: patientId)
        } catch { errorText = error.localizedDescription }
    }

    private func summarize() {
        isSummarizing = true
        summaryText = ""
        Task {
            defer { isSummarizing = false }
            do { summaryText = try await APIClient.summarizePatient(patientId: patientId) }
            catch { summaryText = error.localizedDescription }
        }
    }

    private func assignEmail() {
        let email = assignEmailText.trimmingCharacters(in: .whitespaces)
        guard !email.isEmpty else { return }
        Task {
            do {
                let updated = try await APIClient.assignPatientEmail(patientId: patientId, email: email)
                // A different id back means the server merged this name-only
                // patient into an existing account — switch to it and reload.
                let merged = updated.id != patientId
                patientId = updated.id
                patientName = updated.name
                patientEmail = updated.email
                // The trend cache is keyed to the patient id (see EegTrendModel),
                // so it invalidates itself when `merged` changes it.
                if merged { await loadSessions(); await loadNotes() }
            } catch { errorText = error.localizedDescription }
        }
    }

    private func removePatient() {
        Task {
            do { try await APIClient.deletePatient(patientId: patientId) }
            catch { errorText = error.localizedDescription }
        }
    }

    private func addNote() {
        let body = newNote.trimmingCharacters(in: .whitespaces)
        guard !body.isEmpty else { return }
        Task {
            do {
                _ = try await APIClient.addNote(patientId: patientId, body: body)
                newNote = ""
                await loadNotes()
            } catch { errorText = error.localizedDescription }
        }
    }

    private func deleteNote(_ note: PatientNote) {
        noteToDelete = nil
        Task {
            do { try await APIClient.deleteNote(noteId: note.id); await loadNotes() }
            catch { errorText = error.localizedDescription }
        }
    }

    private func exportAll() {
        Task {
            do {
                let summaries = isSelfView ? try await APIClient.mySessions() : try await APIClient.listSessions(patientId: patientId)
                if summaries.isEmpty { errorText = "No sessions to export"; return }
                var details: [SessionDetail] = []
                for s in summaries { details.append(try await APIClient.getSession(sessionId: s.id)) }
                let url = try ExportUtil.writeAllSessionsZip(details, patientName: patientName)
                exportItem = ExportItem(url: url)
            } catch { errorText = "Export failed: \(error.localizedDescription)" }
        }
    }
}

/// The synthetic "ongoing" row for a session the shared recorder is currently
/// holding for this patient (mirrors PatientDetailActivity's SessionRow.Ongoing).
private struct OngoingSessionRow: View {
    let phase: MuseSessionService.Phase
    let startedAtIso: String

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("In-progress session").font(.body)
                Text("Started \(formatTimestamp(startedAtIso))").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            switch phase {
            case .saveFailed: BadgeLabel(text: "Not saved", color: .orange)
            case .saving: BadgeLabel(text: "Saving…", color: .accentColor)
            default: BadgeLabel(text: "● Ongoing", color: .green)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}
