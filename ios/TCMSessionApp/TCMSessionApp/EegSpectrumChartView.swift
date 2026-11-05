import Charts
import SwiftUI

/// One session-half's power spectrum with specparam's fit drawn over it — the
/// "look at the picture" counterpart to the exponent/r² numbers above it on the
/// EEG analysis card. Swift Charts port of Android's Canvas-drawn
/// EegSpectrumChartView. Three lines, all straight from the server's stored
/// arrays (see EegSpectrum), none recomputed here:
///
///  - Recording (solid): the spectrum the headband measured.
///  - Model (thin): the aperiodic background plus the fitted peaks. r² is how
///    closely this tracks the solid line, so a poor fit shows up as the two
///    coming apart — the low-fit badge becomes something you can check by eye.
///  - Background (dashed): the aperiodic background alone. Both axes are
///    log-scaled, so it is a straight line whose steepness is the aperiodic
///    exponent, and a bump the model counts as a peak shows as the solid line
///    rising above it. (specparam decides what counts — not every bump is one.)
///
/// The power axis carries no numbers on purpose: in log10(V²/Hz) they read as
/// about -12, which tells the practitioner nothing, and the shape is the point.
/// Both halves of a session are drawn on one SHARED power range (the caller
/// passes it — see `powerRange(of:)`) so they can be compared by eye;
/// autoscaling each half on its own would make a half with less power look the
/// same as one with more.
///
/// Not interactive — it's a picture to read, not a series to scrub.
struct EegSpectrumChartView: View {
    let spectrum: EegSpectrum
    /// In the same log10 units as `spectrum`, and should be the range across
    /// BOTH halves of the session — see the type docstring.
    let powerRange: ClosedRange<Double>

    /// Frequency grid at round numbers — on a log axis, evenly spaced Hz ticks
    /// would bunch up at the high end and say nothing at the low one.
    private static let freqTicksHz: [Double] = [2, 5, 10, 20, 40]

    /// One plotted vertex, pre-flattened across all three lines: building the
    /// chart from nested ForEach-over-series closures put the expression beyond
    /// the type-checker's budget.
    private struct Vertex: Identifiable {
        let id: String
        let series: String
        let freq: Double
        let value: Double
    }

    private enum Series: String, CaseIterable {
        case recording = "Recording"
        case model = "Model"
        case background = "Background"

        var color: Color {
            switch self {
            case .recording: return .primary
            case .model: return .accentColor
            case .background: return .secondary
            }
        }

        var width: CGFloat {
            switch self {
            case .recording: return 2
            case .model: return 1.25
            case .background: return 1.5
            }
        }

        var dash: [CGFloat] { self == .background ? [6, 4] : [] }
    }

    /// The x range actually plotted. specparam's grid starts at the bandpass
    /// floor (1Hz), so a non-positive first frequency means malformed data, not
    /// a real spectrum to draw.
    private var isDrawable: Bool {
        spectrum.freqsHz.count >= 2
            && (spectrum.freqsHz.first ?? 0) > 0
            && powerRange.upperBound > powerRange.lowerBound
    }

    private func values(for series: Series) -> [Double] {
        switch series {
        case .recording: return spectrum.logPower
        case .model: return spectrum.logModelFit
        case .background: return spectrum.logAperiodicFit
        }
    }

    /// Order matters where the model found no peak: there the full model and the
    /// background are the SAME curve, so whichever is drawn second hides the
    /// other. Background (dashed) goes last because its gaps let the model line
    /// show through, keeping both visible. Recording goes first so both fits read
    /// against it.
    private var vertices: [Vertex] {
        var out: [Vertex] = []
        let freqs = spectrum.freqsHz
        for series in Series.allCases {
            let line = values(for: series)
            for index in 0..<min(freqs.count, line.count) where line[index].isFinite {
                out.append(Vertex(id: "\(series.rawValue)-\(index)", series: series.rawValue,
                                  freq: freqs[index], value: line[index]))
            }
        }
        return out
    }

    private var styleScale: KeyValuePairs<String, Color> {
        [
            Series.recording.rawValue: Series.recording.color,
            Series.model.rawValue: Series.model.color,
            Series.background.rawValue: Series.background.color,
        ]
    }

    private func stroke(for series: String) -> StrokeStyle {
        let which = Series(rawValue: series) ?? .recording
        return StrokeStyle(lineWidth: which.width, lineJoin: .round, dash: which.dash)
    }

    var body: some View {
        if !isDrawable {
            Text("Spectrum not recorded for this half")
                .font(.caption).foregroundStyle(.secondary)
        } else {
            chart
        }
    }

    private var chart: some View {
        Chart(vertices) { vertex in
            LineMark(
                x: .value("Frequency", vertex.freq),
                y: .value("Power", vertex.value),
                series: .value("Series", vertex.series)
            )
            .foregroundStyle(by: .value("Series", vertex.series))
            .lineStyle(stroke(for: vertex.series))
        }
        .chartForegroundStyleScale(styleScale)
        .chartXScale(domain: xDomain, type: .log)
        .chartYScale(domain: powerRange)
        .chartXAxis {
            AxisMarks(values: Self.freqTicksHz) { value in
                AxisGridLine()
                AxisValueLabel { freqLabel(value.as(Double.self)) }
            }
        }
        // No power numbers on purpose — see the type docstring.
        .chartYAxis {
            AxisMarks { _ in AxisGridLine() }
        }
        .chartLegend(position: .top, alignment: .leading, spacing: 8)
        .frame(height: 170)
    }

    private var xDomain: ClosedRange<Double> {
        let lo = spectrum.freqsHz.first ?? 1
        let hi = spectrum.freqsHz.last ?? 40
        return hi > lo ? lo...hi : lo...(lo + 1)
    }

    @ViewBuilder
    private func freqLabel(_ hz: Double?) -> some View {
        if let hz {
            Text(hz == Self.freqTicksHz.last ? "\(Int(hz)) Hz" : "\(Int(hz))")
        }
    }

    /// One power range covering every line of every given spectrum, padded
    /// slightly so nothing sits on the plot edge. Nil when there's nothing to
    /// range over.
    static func powerRange(of spectra: [EegSpectrum]) -> ClosedRange<Double>? {
        let values = spectra.flatMap { $0.logPower + $0.logModelFit + $0.logAperiodicFit }
            .filter { $0.isFinite }
        guard let lo = values.min(), let hi = values.max() else { return nil }
        let span = (hi - lo) > 1e-9 ? (hi - lo) : 1.0
        let pad = span * 0.05
        return (lo - pad)...(hi + pad)
    }
}
