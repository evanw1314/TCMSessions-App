import SwiftUI

/// One recorded session: chart, per-band stats, marker chips, practitioner note,
/// export, delete, AI summary. Port of SessionDetailActivity. In self-view (a
/// patient viewing their own session) every write action is hidden and the note
/// is read-only.
struct SessionDetailView: View {
    let sessionId: Int
    let patientName: String
    let isSelfView: Bool

    @Environment(\.dismiss) private var dismiss

    @State private var session: SessionDetail?
    @State private var isLoading = true
    @State private var errorText = ""

    @State private var selectedBand: String?   // nil = "None" (tap-to-inspect off)
    @State private var note = ""
    @State private var summaryText = ""
    @State private var isSummarizing = false
    @State private var exportItem: ExportItem?
    @State private var showDeleteConfirm = false
    @State private var deleteConfirmText = ""

    private let bandOptions: [(label: String, key: String?)] = [
        ("None", nil), ("Delta", "delta"), ("Theta", "theta"),
        ("Alpha", "alpha"), ("Beta", "beta"), ("Gamma", "gamma"),
    ]

    var body: some View {
        Group {
            if isLoading {
                ProgressView()
            } else if let session {
                content(session)
            } else {
                Text(errorText.isEmpty ? "Session not found" : errorText).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Session")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $exportItem) { item in ActivityView(url: item.url) }
        .task { await load() }
    }

    private func content(_ session: SessionDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(session.modality).font(.title3.weight(.semibold))
                    Text(formatSessionRange(session.startedAt, session.endedAt) + arousalSuffix(session))
                        .font(.subheadline).foregroundStyle(.secondary)
                }

                Picker("Inspect band", selection: $selectedBand) {
                    ForEach(bandOptions, id: \.label) { Text($0.label).tag($0.key) }
                }
                .pickerStyle(.segmented)

                BandChartView(samples: session.samples, markers: session.markers, selectedBand: selectedBand)

                statsTable(session)

                markerChips(session.markers)

                noteSection

                if !isSelfView {
                    // Practitioner-only, same gating as the AI summary — the EEG
                    // analysis endpoints are practitioner-scoped server-side.
                    EegAnalysisCard(sessionId: session.id,
                                    baselineDurationSeconds: session.baselineDurationSeconds)
                    summarySection
                    deleteSection
                }

                Button { export(session) } label: {
                    Label("Export CSV", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.bordered)
            }
            .padding()
        }
        .alert("Error", isPresented: .constant(!errorText.isEmpty)) {
            Button("OK") { errorText = "" }
        } message: { Text(errorText) }
    }

    // MARK: - Stats table

    private func statsTable(_ session: SessionDetail) -> some View {
        let byBand = Dictionary(grouping: session.samples, by: \.band)
        return Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
            GridRow {
                Text("Band").fontWeight(.semibold)
                Text("Avg").fontWeight(.semibold)
                Text("Min").fontWeight(.semibold)
                Text("Max").fontWeight(.semibold)
            }
            .font(.caption).foregroundStyle(.secondary)
            ForEach(BAND_ORDER, id: \.self) { band in
                let all = (byBand[band] ?? []).map(\.value)
                let valid = all.filter { $0 != NO_SIGNAL_VALUE }
                GridRow {
                    Text(band.capitalized).foregroundStyle(bandColor(band)).fontWeight(.semibold)
                    if all.isEmpty {
                        Text("no data").foregroundStyle(.secondary).gridCellColumns(3)
                    } else if valid.isEmpty {
                        Text("no signal for entire session").foregroundStyle(.secondary).gridCellColumns(3)
                    } else {
                        Text(String(format: "%.2f", valid.reduce(0, +) / Double(valid.count)))
                        Text(String(format: "%.2f", valid.min()!))
                        Text(String(format: "%.2f", valid.max()!))
                    }
                }
                .font(.caption)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Marker chips

    @ViewBuilder
    private func markerChips(_ markers: [SessionMarker]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Markers").font(.subheadline.weight(.semibold))
            if markers.isEmpty {
                Text("(none)").font(.caption).foregroundStyle(.secondary)
            } else {
                FlowLayout(spacing: 6) {
                    ForEach(markers.sorted { $0.offsetSeconds < $1.offsetSeconds }) { m in
                        Text("\(timeString(m.offsetSeconds))  \(m.label)")
                            .font(.caption).monospaced()
                            .padding(.horizontal, 8).padding(.vertical, 4)
                            .background(Color(.tertiarySystemBackground), in: Capsule())
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Note

    private var noteSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Practitioner note").font(.subheadline.weight(.semibold))
            if isSelfView {
                Text(note.isEmpty ? "(none)" : note).font(.subheadline)
                    .foregroundStyle(note.isEmpty ? .secondary : .primary)
            } else {
                TextField("Add a note", text: $note, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                Button("Save note") { saveNote() }.buttonStyle(.bordered)
            }
        }
    }

    // MARK: - Summary

    private var summarySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("AI overview").font(.subheadline.weight(.semibold))
            Text(summaryText.isEmpty ? "Tap Summarize for a plain-prose description of this session." : summaryText)
                .font(.footnote).foregroundStyle(summaryText.isEmpty ? .secondary : .primary)
            Button {
                summarize()
            } label: {
                Label(isSummarizing ? "Generating…" : "Summarize", systemImage: "sparkles")
            }
            .disabled(isSummarizing)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Delete

    private var deleteSection: some View {
        Button(role: .destructive) { deleteConfirmText = ""; showDeleteConfirm = true } label: {
            Label("Delete session", systemImage: "trash")
        }
        .alert("Delete this session?", isPresented: $showDeleteConfirm) {
            TextField("Type DELETE to confirm", text: $deleteConfirmText)
            Button("Delete", role: .destructive) { if deleteConfirmText == "DELETE" { performDelete() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the session from the app and cannot be undone here. Type DELETE to confirm.")
        }
    }

    // MARK: - Data

    private func load() async {
        do {
            let s = try await APIClient.getSession(sessionId: sessionId)
            session = s
            note = s.practitionerNote ?? ""
        } catch {
            errorText = error.localizedDescription
        }
        isLoading = false
    }

    private func saveNote() {
        let value = note.trimmingCharacters(in: .whitespaces)
        Task {
            do { try await APIClient.updateSessionNote(sessionId: sessionId, note: value.isEmpty ? nil : value) }
            catch { errorText = error.localizedDescription }
        }
    }

    private func summarize() {
        isSummarizing = true
        summaryText = ""
        Task {
            defer { isSummarizing = false }
            do { summaryText = try await APIClient.summarizeSession(sessionId: sessionId) }
            catch { summaryText = error.localizedDescription }
        }
    }

    private func performDelete() {
        Task {
            do { try await APIClient.deleteSession(sessionId: sessionId); dismiss() }
            catch { errorText = error.localizedDescription }
        }
    }

    private func export(_ session: SessionDetail) {
        do { exportItem = ExportItem(url: try ExportUtil.writeSessionCsv(session, patientName: patientName)) }
        catch { errorText = "Export failed: \(error.localizedDescription)" }
    }

    private func timeString(_ seconds: Int) -> String {
        String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    /// Appended to the date/duration line — the one place in this screen a
    /// reviewer would look to decide whether to trust the band-power chart below
    /// as more than "the patient relaxed or dozed off."
    private func arousalSuffix(_ session: SessionDetail) -> String {
        var parts: [String] = []
        if session.patientDozed == true { parts.append("dozed off") }
        if let alertness = session.alertnessRating { parts.append("alertness \(alertness)/7") }
        if let baseline = session.baselineDurationSeconds {
            parts.append(String(format: "baseline %d:%02d", baseline / 60, baseline % 60))
        }
        return parts.isEmpty ? "" : "  ·  " + parts.joined(separator: ", ")
    }
}

/// Simple wrapping layout for the marker chips (SwiftUI has no built-in flow).
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > maxWidth {
                x = 0; y += rowHeight + spacing; rowHeight = 0
            }
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: maxWidth == .infinity ? x : maxWidth, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX {
                x = bounds.minX; y += rowHeight + spacing; rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
