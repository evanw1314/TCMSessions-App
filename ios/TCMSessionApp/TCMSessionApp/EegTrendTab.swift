import Combine
import SwiftUI

/// Which scalar metric the trend chart plots. Wording matches Android's spinner.
enum EegTrendMetric: String, CaseIterable {
    case exponent = "Background steepness"
    case eogRate = "Slow eye movements"
}

/// The trend pane's state, owned by PatientDetailView rather than by the pane.
///
/// PatientDetailView selects its tab with a `switch` in `body`, so EegTrendTab is
/// a branch of a _ConditionalContent: leaving the tab destroys the view and every
/// `@State` in it. Android keeps `eegTrendLoadedOnce`, `loadedEegTrendPoints` and
/// `selectedEegTrendMetric` as Activity fields for exactly this reason — its pane
/// is only set View.GONE, never destroyed. Holding them here is the equivalent:
/// without it the list is refetched (and the metric picker snaps back to its
/// default) on every tab switch.
@MainActor
final class EegTrendModel: ObservableObject {
    @Published private(set) var points: [EegTrendPoint] = []
    @Published var metric: EegTrendMetric = .exponent
    @Published private(set) var isLoading = true
    @Published private(set) var errorText = ""

    /// Which patient `points` belongs to. Keying the cache to the id rather than
    /// to a bare "loaded" flag means the screen changing patient — it does, on
    /// the assign-email merge path, where the server can return a different id —
    /// refetches on its own, instead of relying on every such call site
    /// remembering to invalidate.
    private var loadedPatientId: Int?

    func load(patientId: Int) async {
        guard loadedPatientId != patientId else { isLoading = false; return }
        isLoading = true
        do {
            points = try await APIClient.getPatientEegTrend(patientId: patientId)
            loadedPatientId = patientId
            errorText = ""
        } catch {
            errorText = error.localizedDescription
        }
        isLoading = false
    }
}

/// The patient's cross-session EEG trend — port of PatientDetailActivity's EEG
/// Trend pane. Practitioner-only, the same gating as the single-session EEG
/// analysis card: GET /patients/{id}/eeg-trend is practitioner-scoped
/// server-side, so this tab is never added in self-view.
///
/// Two halves, matching Android: the chart (one step per analyzed session, one
/// point per period), and below it the exact-numbers list, grouped by session.
/// Warnings use the SHORT forms from EegWarnings — one entry per session, with
/// the full card a tap away.
struct EegTrendTab: View {
    let patientId: Int
    let patientName: String
    @ObservedObject var model: EegTrendModel

    private var points: [EegTrendPoint] { model.points }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if model.isLoading {
                ProgressView()
            } else if points.isEmpty {
                Text("No analyzed sessions yet. Open a session and tap Analyze EEG to add it here.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                Picker("Metric", selection: $model.metric) {
                    ForEach(EegTrendMetric.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)

                EegMetricChartView(stepLabels: stepLabels, points: chartPoints)

                ForEach(sessionGroups, id: \.sessionId) { group in
                    Divider()
                    sessionBlock(group)
                }
            }
            if !model.errorText.isEmpty {
                Text(model.errorText).font(.footnote).foregroundStyle(.red)
            }
        }
        .task(id: patientId) { await model.load(patientId: patientId) }
    }

    // MARK: - Derived

    /// Distinct session ids in the order they first appear — the server already
    /// hands them back oldest-first (see APIClient.getPatientEegTrend).
    private var sessionIds: [Int] {
        var seen = Set<Int>()
        return points.map(\.sessionId).filter { seen.insert($0).inserted }
    }

    private var stepLabels: [String] {
        sessionIds.compactMap { id in
            points.first { $0.sessionId == id }.map { formatShortDate($0.sessionStartedAt) }
        }
    }

    /// One step per analyzed session (x-axis, in the server's oldest-first order
    /// — session identity, not raw elapsed time, since sessions aren't evenly
    /// spaced), one point per period (0/1) per step.
    private var chartPoints: [EegMetricChartView.Point] {
        let stepIndexBySession = Dictionary(uniqueKeysWithValues: sessionIds.enumerated().map { ($1, $0) })
        return points.compactMap { point in
            guard let step = stepIndexBySession[point.sessionId] else { return nil }
            let value: Double? = model.metric == .exponent ? point.aperiodicExponent : point.eogSemRatePerMin
            // Low-fit halves are drawn hollow — exponent only: the eye-movement
            // rate comes from a separate detector, not the specparam fit, so r²
            // says nothing about it.
            let flagged = model.metric == .exponent && isLowFit(point.rSquared, point.lowFitRSquared)
            return EegMetricChartView.Point(stepIndex: step, seriesIndex: point.period,
                                            value: value, flagged: flagged)
        }
    }

    private struct SessionGroup {
        let sessionId: Int
        let points: [EegTrendPoint]
        var first: EegTrendPoint { points[0] }
    }

    private var sessionGroups: [SessionGroup] {
        sessionIds.compactMap { id in
            let group = points.filter { $0.sessionId == id }.sorted { $0.period < $1.period }
            return group.isEmpty ? nil : SessionGroup(sessionId: id, points: group)
        }
    }

    // MARK: - One session's block

    @ViewBuilder
    private func sessionBlock(_ group: SessionGroup) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            NavigationLink(value: Route.sessionDetail(sessionId: group.sessionId,
                                                      patientName: patientName, selfView: false)) {
                Text(formatTimestamp(group.first.sessionStartedAt))
                    .font(.footnote.weight(.semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)

            // Session-level facts, the same ones the session card shows, so a
            // session whose halves can't be compared doesn't read as two plain
            // numbers here.
            if let excluded = group.first.excludedChannelNames, !excluded.isEmpty {
                Text("Left out of this analysis: the \(sensorList(excluded)) - see the session for why.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            // Shorter forms of the session card's warnings — one entry per
            // session, and the full card is a tap away.
            if group.first.allElectrodesFlagged == true {
                PlainWarningView(warning: EegWarnings.allSensorsFlaggedShort())
            }
            if group.first.eyeActivityDiffers == true {
                PlainWarningView(warning: EegWarnings.eyeActivityShort())
            }

            ForEach(group.points, id: \.half) { point in
                halfLine(point)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private func halfLine(_ point: EegTrendPoint) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(summaryLine(point)).font(.footnote).foregroundStyle(.secondary)
            // The precise terms, kept for a specialist — see EegWording.
            Text(String(format: "Details: aperiodic exponent %.2f, offset %.2f, fit r² %.3f",
                        point.aperiodicExponent, point.aperiodicOffset, point.rSquared))
                .font(.caption2).foregroundStyle(.tertiary)
            // Same rule and wording as the session card's banner (see isLowFit),
            // so a half flagged there doesn't read as a plain number here. Names
            // only the steepness: this list shows no rhythms.
            if isLowFit(point.rSquared, point.lowFitRSquared) {
                PlainWarningView(warning: EegWarnings.lowFit(
                    rSquared: point.rSquared, threshold: point.lowFitRSquared, withRhythms: false))
            }
        }
        .padding(.top, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func summaryLine(_ point: EegTrendPoint) -> String {
        let eog = point.eogSemRatePerMin
            .map { String(format: "slow eye movements %.1f/min", $0) }
            ?? "slow eye movements not counted"
        let deflection = point.deflectionRatePerMin
            .map { String(format: " · blinks and movements %.1f/min", $0) } ?? ""
        return String(format: "\(point.half.capitalized) — \(STEEPNESS) %.2f · \(eog)\(deflection)",
                      point.aperiodicExponent)
    }
}
