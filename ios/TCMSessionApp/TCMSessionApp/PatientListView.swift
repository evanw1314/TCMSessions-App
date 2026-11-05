import SwiftUI

/// Shared navigation destinations for the practitioner and patient stacks. Any
/// pushed screen can `NavigationLink(value:)` these and they resolve against the
/// one enclosing NavigationStack.
enum Route: Hashable {
    case patientDetail(Patient)
    case sessionDetail(sessionId: Int, patientName: String, selfView: Bool)
    case recordSession(patientId: Int, patientName: String, headband: String?)
}

extension View {
    /// Registers every in-app navigation destination. Apply once at each
    /// NavigationStack root.
    func tcmDestinations() -> some View {
        navigationDestination(for: Route.self) { route in
            switch route {
            case let .patientDetail(patient):
                PatientDetailView(
                    patientId: patient.id,
                    initialName: patient.name,
                    initialEmail: patient.email,
                    initialHeadband: patient.headbandDeviceId,
                    initialLastSession: patient.lastSessionAt,
                    isSelfView: false,
                    onSignOut: {}
                )
            case let .sessionDetail(sessionId, patientName, selfView):
                SessionDetailView(sessionId: sessionId, patientName: patientName, isSelfView: selfView)
            case let .recordSession(patientId, patientName, headband):
                SessionRecordingView(patientId: patientId, patientName: patientName, headbandDeviceId: headband)
            }
        }
    }
}

/// The practitioner's roster. Port of PatientListActivity.
struct PatientListView: View {
    let onSignOut: () -> Void

    @State private var allPatients: [Patient] = []
    @State private var searchQuery = ""
    @State private var isLoading = true
    @State private var loadError = ""
    @State private var showAddPatient = false
    @StateObject private var recovery = RecoveryCoordinator()

    private var filtered: [Patient] {
        guard !searchQuery.isEmpty else { return allPatients }
        return allPatients.filter {
            $0.name.localizedCaseInsensitiveContains(searchQuery) ||
            ($0.email?.localizedCaseInsensitiveContains(searchQuery) ?? false)
        }
    }

    var body: some View {
        Group {
            if isLoading && allPatients.isEmpty {
                ProgressView()
            } else if !loadError.isEmpty && allPatients.isEmpty {
                VStack(spacing: 12) {
                    Text(loadError).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    Button("Retry") { Task { await load() } }
                }
                .padding()
            } else {
                List {
                    if filtered.isEmpty {
                        Text(emptyMessage).foregroundStyle(.secondary)
                    }
                    ForEach(filtered) { patient in
                        NavigationLink(value: Route.patientDetail(patient)) {
                            PatientRow(patient: patient)
                        }
                    }
                }
                .searchable(text: $searchQuery, prompt: "Search name or email")
                .refreshable { await load() }
            }
        }
        .navigationTitle(AppConfig.clinicName.isEmpty ? "Patients" : AppConfig.clinicName)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Sign out") { AppConfig.clearSession(); onSignOut() }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAddPatient = true } label: { Image(systemName: "plus") }
            }
        }
        .sheet(isPresented: $showAddPatient) {
            AddPatientSheet { await load() }
        }
        .recoveryPrompts(recovery)
        .task { await load() }
        .onAppear {
            // Offers anything a previous run left unsaved (see
            // RecoveryCoordinator). Re-armed on every appearance, but a scan
            // already in progress is a no-op.
            recovery.onSaved = { Task { await load() } }
            recovery.checkForInterruptedRecordings()
        }
    }

    private var emptyMessage: String {
        if allPatients.isEmpty { return "No patients yet — tap + to add one." }
        return "No patients match \"\(searchQuery)\""
    }

    private func load() async {
        if allPatients.isEmpty { isLoading = true }
        do {
            allPatients = try await APIClient.listPatients()
            loadError = ""
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }
}

private struct PatientRow: View {
    let patient: Patient

    var body: some View {
        HStack(spacing: 12) {
            Text(initials)
                .font(.subheadline.weight(.semibold))
                .frame(width: 40, height: 40)
                .background(Color.accentColor.opacity(0.15), in: Circle())
                .foregroundStyle(Color.accentColor)
            VStack(alignment: .leading, spacing: 2) {
                Text(patient.name).font(.body)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            badge
        }
    }

    private var subtitle: String {
        if let last = patient.lastSessionAt { return "Last session \(formatTimestamp(last))" }
        if let hb = patient.headbandDeviceId { return "Headband \(hb) · no sessions yet" }
        return "No device assigned"
    }

    @ViewBuilder private var badge: some View {
        if patient.headbandDeviceId != nil {
            BadgeLabel(text: "Headband", color: .accentColor)
        } else {
            BadgeLabel(text: "No device", color: .secondary)
        }
    }

    private var initials: String {
        let parts = patient.name.split(whereSeparator: { $0.isWhitespace })
        switch parts.count {
        case 0: return "?"
        case 1: return parts[0].prefix(1).uppercased()
        default: return (parts.first!.prefix(1) + parts.last!.prefix(1)).uppercased()
        }
    }
}

struct BadgeLabel: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundStyle(color)
    }
}

/// Add a patient by name (creates a name-only patient) and/or email (links an
/// existing account). Mirrors PatientListActivity.showAddPatientDialog.
private struct AddPatientSheet: View {
    let onAdded: () async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var email = ""
    @State private var errorText = ""
    @State private var isSubmitting = false

    var body: some View {
        NavigationStack {
            Form {
                Section(footer: Text("A device or account isn't required yet — you can assign an email later, once the patient is ready for one.")) {
                    TextField("Patient name", text: $name)
                        .textInputAutocapitalization(.words)
                    TextField("Email (optional — if they already have an account)", text: $email)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)
                }
                if !errorText.isEmpty {
                    Section { Text(errorText).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Add patient")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { add() }.disabled(isSubmitting)
                }
            }
        }
    }

    private func add() {
        let n = name.trimmingCharacters(in: .whitespaces)
        let e = email.trimmingCharacters(in: .whitespaces)
        if n.isEmpty && e.isEmpty { errorText = "Enter a name"; return }
        errorText = ""
        isSubmitting = true
        Task {
            defer { isSubmitting = false }
            do {
                _ = try await APIClient.addPatient(name: n.isEmpty ? nil : n, email: e.isEmpty ? nil : e)
                await onAdded()
                dismiss()
            } catch {
                errorText = error.localizedDescription
            }
        }
    }
}
