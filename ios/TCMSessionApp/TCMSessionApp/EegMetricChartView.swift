import Charts
import SwiftUI

/// Line chart for a scalar aperiodic-corrected EEG metric — aperiodic exponent,
/// or eye-movement (EOG SEM) rate — plotted across a small number of ordered
/// steps: analyzed sessions across a patient's history. Swift Charts port of
/// Android's Canvas-drawn EegMetricChartView.
///
/// Unlike BandChartView's once-a-second band-power samples, this data is
/// genuinely sparse — one value per step, not a dense curve — so points are drawn
/// as visible dots joined by straight segments rather than a smooth line.
///
/// Up to two series ("earlier half" / "later half", i.e. period 0/1) are drawn in
/// the shared EEG_TREND_PERIOD_COLORS so the same period reads the same color
/// everywhere. A nil value at a step (e.g. an EOG rate with too little clean
/// signal to score — see EegHalfMetric.eogSemRatePerMin) is dropped from that
/// series rather than plotted as zero or breaking the line into pieces; the line
/// simply skips over the gap, matching BandChartView's NO_SIGNAL_VALUE handling.
///
/// A FLAGGED point is drawn hollow, and the legend gains a "Low model fit" entry
/// explaining it. Hollow rather than dropped: the number exists and the session
/// card shows it, so leaving it off here would be its own misreport — the point
/// is to mark it as one to read with caution.
struct EegMetricChartView: View {
    /// One value at one ordered step (an index into `stepLabels`). `seriesIndex`
    /// is 0 ("earlier half") or 1 ("later half"). `value` nil means "not enough
    /// signal to score this step" and is dropped, not plotted as zero. `flagged`
    /// marks a value from a poorly-fit model (see isLowFit) — drawn hollow.
    struct Point: Identifiable {
        let stepIndex: Int
        let seriesIndex: Int
        let value: Double?
        var flagged: Bool = false

        var id: String { "\(stepIndex)-\(seriesIndex)" }
    }

    let stepLabels: [String]
    let points: [Point]

    private var plotted: [Point] {
        points.filter { $0.value != nil && $0.value!.isFinite }
    }

    private var hasFlagged: Bool { plotted.contains { $0.flagged } }

    private func label(_ seriesIndex: Int) -> String {
        EEG_TREND_PERIOD_LABELS[seriesIndex] ?? "Half \(seriesIndex)"
    }

    private func color(_ seriesIndex: Int) -> Color {
        EEG_TREND_PERIOD_COLORS[seriesIndex] ?? .gray
    }

    var body: some View {
        if plotted.isEmpty {
            Text("No values to chart yet")
                .font(.footnote).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 120)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                chart
                if hasFlagged {
                    Label("Hollow points: low model fit — read with caution",
                          systemImage: "circle.dotted")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var chart: some View {
        Chart {
            ForEach(plotted) { point in
                LineMark(
                    x: .value("Session", point.stepIndex),
                    y: .value("Value", point.value!),
                    series: .value("Half", label(point.seriesIndex))
                )
                .foregroundStyle(by: .value("Half", label(point.seriesIndex)))

                PointMark(
                    x: .value("Session", point.stepIndex),
                    y: .value("Value", point.value!)
                )
                .foregroundStyle(by: .value("Half", label(point.seriesIndex)))
                // Hollow for a flagged point: the marker is drawn as a ring by
                // filling it with the chart's own background.
                .symbol {
                    Circle()
                        .strokeBorder(color(point.seriesIndex), lineWidth: 2)
                        .background(
                            Circle().fill(point.flagged ? Color.clear : color(point.seriesIndex))
                        )
                        .frame(width: 9, height: 9)
                }
            }
        }
        .chartForegroundStyleScale(
            domain: [label(0), label(1)],
            range: [color(0), color(1)]
        )
        .chartXScale(domain: -0.3...(Double(max(stepLabels.count - 1, 0)) + 0.3))
        .chartXAxis {
            AxisMarks(values: Array(0..<stepLabels.count)) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let index = value.as(Int.self), stepLabels.indices.contains(index) {
                        Text(stepLabels[index]).font(.caption2)
                    }
                }
            }
        }
        .chartLegend(position: .top, alignment: .leading, spacing: 8)
        .frame(height: 200)
    }
}
