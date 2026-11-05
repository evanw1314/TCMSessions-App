import Charts
import SwiftUI

/// Band-power-vs-time line chart with session markers overlaid as vertical dashed
/// lines. Swift Charts port of Android's Canvas-drawn BandChartView.
///
/// NO_SIGNAL_VALUE samples are dropped before plotting — lines connect the
/// surrounding valid points rather than dipping to the sentinel. When a band is
/// selected (tap-to-inspect), dragging across the chart snaps to the nearest
/// recorded point on that band's line and shows a "mm:ss value" tooltip.
struct BandChartView: View {
    let samples: [SessionSample]
    let markers: [SessionMarker]
    let selectedBand: String?

    private struct Indicator: Equatable { let band: String; let offset: Int; let value: Double }
    @State private var indicator: Indicator?

    // Per-band valid points, low→high frequency, only bands that have any signal.
    private var seriesByBand: [(band: String, points: [(offset: Int, value: Double)])] {
        BAND_ORDER.compactMap { band in
            let points = samples
                .filter { $0.band == band && $0.value != NO_SIGNAL_VALUE }
                .sorted { $0.offsetSeconds < $1.offsetSeconds }
                .map { (offset: $0.offsetSeconds, value: $0.value) }
            return points.isEmpty ? nil : (band, points)
        }
    }

    private var maxOffset: Int {
        max(1, samples.map(\.offsetSeconds).max() ?? 1)
    }

    private var yDomain: ClosedRange<Double> {
        let valid = samples.filter { $0.value != NO_SIGNAL_VALUE }.map(\.value)
        guard let lo = valid.min(), let hi = valid.max() else { return 0...1 }
        let span = (hi - lo) > 1e-6 ? (hi - lo) : 1.0
        let pad = span * 0.1
        return max(0, lo - pad)...(hi + pad)
    }

    var body: some View {
        if seriesByBand.isEmpty {
            Text("No signal data recorded")
                .font(.footnote).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 240)
        } else {
            chart
        }
    }

    private var presentBands: [String] { seriesByBand.map(\.band) }

    private var chart: some View {
        Chart {
            ForEach(seriesByBand, id: \.band) { series in
                ForEach(series.points, id: \.offset) { point in
                    LineMark(
                        x: .value("Time", point.offset),
                        y: .value("Power", point.value),
                        series: .value("Band", series.band)
                    )
                    .foregroundStyle(by: .value("Band", series.band))
                }
            }
            ForEach(markers) { marker in
                RuleMark(x: .value("Time", marker.offsetSeconds))
                    .foregroundStyle(.gray.opacity(0.6))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .annotation(position: .top, alignment: .leading) {
                        Text(marker.label).font(.system(size: 8)).foregroundStyle(.secondary)
                    }
            }
        }
        .chartForegroundStyleScale(domain: presentBands, range: presentBands.map(bandColor))
        .chartXScale(domain: 0...maxOffset)
        .chartYScale(domain: yDomain)
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 5)) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let secs = value.as(Int.self) { Text(timeString(secs)) }
                }
            }
        }
        .frame(height: 260)
        .chartOverlay { proxy in
            GeometryReader { geo in
                indicatorOverlay(proxy: proxy, geo: geo)
            }
        }
        .onChange(of: selectedBand) { _, _ in indicator = nil }
    }

    @ViewBuilder
    private func indicatorOverlay(proxy: ChartProxy, geo: GeometryProxy) -> some View {
        if let anchor = proxy.plotFrame {
            let plot = geo[anchor]
            ZStack(alignment: .topLeading) {
                if selectedBand != nil {
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { v in updateIndicator(atX: v.location.x - plot.minX, proxy: proxy) }
                        )
                }
                if let ind = indicator,
                   let px = proxy.position(forX: ind.offset),
                   let py = proxy.position(forY: ind.value) {
                    let x = plot.minX + px
                    let y = plot.minY + py
                    Path { p in
                        p.move(to: CGPoint(x: x, y: plot.minY))
                        p.addLine(to: CGPoint(x: x, y: plot.maxY))
                    }
                    .stroke(bandColor(ind.band), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    Circle().fill(bandColor(ind.band)).frame(width: 9, height: 9).position(x: x, y: y)
                    Text("\(timeString(ind.offset))   \(String(format: "%.3f", ind.value))")
                        .font(.caption2)
                        .padding(.horizontal, 6).padding(.vertical, 3)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 6))
                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(bandColor(ind.band), lineWidth: 1))
                        .fixedSize()
                        .position(x: min(max(x, plot.minX + 30), plot.maxX - 30),
                                  y: max(y - 22, plot.minY + 10))
                }
            }
        }
    }

    private func updateIndicator(atX xInPlot: CGFloat, proxy: ChartProxy) {
        guard let band = selectedBand,
              let series = seriesByBand.first(where: { $0.band == band }),
              let rawOffset: Double = proxy.value(atX: xInPlot) else { return }
        let nearest = series.points.min { abs(Double($0.offset) - rawOffset) < abs(Double($1.offset) - rawOffset) }
        if let n = nearest { indicator = Indicator(band: band, offset: n.offset, value: n.value) }
    }

    private func timeString(_ seconds: Int) -> String {
        String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}
