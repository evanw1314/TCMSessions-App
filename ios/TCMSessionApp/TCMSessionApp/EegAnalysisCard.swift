import SwiftUI

/// The EEG analysis card on a session's detail screen — port of
/// SessionDetailActivity's renderEegAnalysis and everything under it.
///
/// Two states: not-yet-analyzed (an "Analyze EEG" button and a short explainer)
/// or analyzed (the button stays live, relabeled "Re-analyze", with a per-half
/// breakdown below). The button is deliberately NOT hidden after a successful
/// run: POST /sessions/{id}/eeg/analyze keeps the raw blob and recomputes on
/// every call, so a session can be re-run as the correction pipeline improves —
/// and any field added to the analysis output later would otherwise be invisible
/// on exactly the sessions worth re-examining.
///
/// Standing rule for every sentence here, same as ai_summary's prompts: describe
/// the RECORDING, never the patient.
struct EegAnalysisCard: View {
    let sessionId: Int
    /// The session's own baseline marker, used to word the half-split line when
    /// the pipeline fell back to the midpoint — see qualityLines.
    let baselineDurationSeconds: Int?

    @State private var result: EegAnalysisResult?
    @State private var statusText = ""
    @State private var analysisInFlight = false
    @State private var lastAnalyzedAtText: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("EEG analysis").font(.subheadline.weight(.semibold))

            if statusText.isEmpty {
                Text("Aperiodic-corrected metrics for each half of the recording, computed from the "
                     + "raw EEG on the server. Tap Analyze to run it.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                Text(statusText).font(.footnote).foregroundStyle(.secondary)
            }

            Button {
                runAnalysis()
            } label: {
                Label(analysisInFlight ? "Analyzing…" : (isAnalyzed ? "Re-analyze" : "Analyze EEG"),
                      systemImage: "waveform.path.ecg.rectangle")
            }
            .buttonStyle(.bordered)
            .disabled(analysisInFlight)

            if let result, isAnalyzed {
                analysisBody(result)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .task { await loadMetrics() }
    }

    private var isAnalyzed: Bool {
        guard let result else { return false }
        return result.analyzedAt != nil && !result.halves.isEmpty
    }

    // MARK: - Loading

    /// Rereads whatever the last analyze computed, without re-running it.
    private func loadMetrics() async {
        do {
            let fetched = try await APIClient.getEegMetrics(sessionId: sessionId)
            // A tap can beat this reread home. Applying it then would overwrite
            // "Analyzing…" with the PREVIOUS run's timestamp.
            guard !analysisInFlight else { return }
            apply(fetched)
        } catch {
            // Leave the card at its default "tap Analyze" state.
        }
    }

    /// On-demand only, same "practitioner-tapped button" precedent as the AI
    /// summary — this IS the compute step server-side (mne filtering + specparam
    /// fit), not just a fetch.
    ///
    /// A 404 here is usually not a bug, so its message is shown as-is instead of
    /// a generic "failed": it means either that no raw EEG was ever uploaded for
    /// this session (best-effort, non-fatal upload — see
    /// PatientSessionRecorder.uploadRawEegBestEffort) or that the blob is gone
    /// from disk, which the server words differently.
    private func runAnalysis() {
        analysisInFlight = true
        statusText = "Analyzing — this takes a few seconds..."
        Task {
            defer { analysisInFlight = false }
            do {
                apply(try await APIClient.analyzeEeg(sessionId: sessionId))
            } catch {
                // Keep the previous run's context: on a re-analysis the half
                // breakdown from the earlier run is still rendered below, and
                // replacing the status outright would read as though the error
                // described THOSE numbers.
                if let previous = lastAnalyzedAtText {
                    statusText = "\(previous) — re-analysis failed: \(error.localizedDescription)"
                } else {
                    statusText = error.localizedDescription
                }
            }
        }
    }

    private func apply(_ fetched: EegAnalysisResult) {
        guard let analyzedAt = fetched.analyzedAt, !fetched.halves.isEmpty else { return }
        result = fetched
        lastAnalyzedAtText = "Analyzed " + formatTimestamp(analyzedAt)
        statusText = lastAnalyzedAtText ?? ""
    }

    // MARK: - Rendered analysis

    @ViewBuilder
    private func analysisBody(_ result: EegAnalysisResult) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(qualityLines(result).joined(separator: "\n"))
                .font(.caption).foregroundStyle(.secondary)

            ForEach(electrodeWarnings(result)) { PlainWarningView(warning: $0) }
            if result.eyeActivityDiffers == true {
                PlainWarningView(warning: EegWarnings.eyeActivity())
            }

            // One range for both halves' charts, so they read against each other.
            let powerRange = EegSpectrumChartView.powerRange(of: result.halves.compactMap(\.spectrum))
            if powerRange != nil { spectrumLegend }

            ForEach(Array(result.halves.enumerated()), id: \.element.id) { index, half in
                if index > 0 { Divider() }
                halfView(half, powerRange: powerRange, result: result)
            }
        }
    }

    /// Recording-wide context for the numbers below it: how the halves were
    /// split, what sample rate the headband actually achieved, how much of the
    /// signal arrived empty, and whether any channels were dropped.
    ///
    /// The split line comes first because it is the one most able to mislead:
    /// "First/Second half" and "Baseline/Treatment" render almost identically but
    /// mean very different things, and only the latter is a before/after comparison.
    private func qualityLines(_ result: EegAnalysisResult) -> [String] {
        var lines: [String] = []

        // The half labels are the only carrier of which split ran — see
        // eeg.analysis.analyze_session_eeg's baseline-cutoff fallback.
        if result.halves.contains(where: { $0.half == "baseline" }) {
            lines.append("Split at the marked baseline end, so the halves are before and after that point.")
        } else if baselineDurationSeconds != nil {
            // The pipeline also falls back to the midpoint when a baseline WAS
            // marked but lands outside the uploaded raw EEG (a late headband
            // connect, or a truncated best-effort upload). Saying "no baseline
            // was marked" here would tell the practitioner they didn't do
            // something they did.
            lines.append("A baseline was marked, but it falls outside the uploaded raw EEG and couldn't "
                + "be used. The halves are split at the recording's midpoint instead, so they are not "
                + "a before/after comparison.")
        } else {
            lines.append("The halves are split at the recording's midpoint rather than at a marked "
                + "baseline, so they are two arbitrary stretches of one session, not a before/after "
                + "comparison.")
        }

        if let rate = result.observedSampleRateHz {
            lines.append(String(format: "Recorded at %.1f readings per second (Hz).", rate))
        }
        if let fraction = result.interpolatedSampleFraction {
            // Fraction of (sample, channel) READINGS, not of samples — with four
            // channels, one dead electrode alone is 25%. Worded as "empty", with
            // the limit spelled out beside it: an electrode lifted off the skin
            // keeps sending numbers, so it never shows up here (session 24 held
            // one side off the forehead for two minutes and this read 0.0%, which
            // on its own reads as "contact was fine").
            lines.append(String(format: "%.1f%% of sensor readings arrived empty. ", fraction * 100)
                + "That only counts gaps the headband reports: a sensor lifted off the skin still "
                + "sends readings, so it isn't counted here. A lifted sensor may show up as the model "
                + "not matching, or as many windows dropped for movement, but open eyes or muscle "
                + "tension cause those too.")
        }

        let excluded = result.excludedChannelIndices
        let findings = result.electrodeFindings
        if excluded == nil {
            lines.append("Which sensors were used: not recorded for this analysis.")
        } else if findings == nil, !(excluded ?? []).isEmpty {
            // Analyzed before the electrode check existed: the only thing that
            // excluded channels then was the eye correction, now off, so a
            // re-analysis will change these numbers.
            lines.append("The forehead sensors were left out by the old eye-movement correction, "
                + "which has since been switched off. Tap Re-analyze to update.")
        } else if findings == nil {
            lines.append("All four sensors used. The loose-sensor check didn't exist when this "
                + "was analyzed - tap Re-analyze to run it.")
        } else if findings!.isEmpty {
            // Exclusions and warnings get their own block below; this line only
            // speaks when there's nothing to warn about.
            lines.append("All four sensors used. The loose-sensor check found no signs of a "
                + "loose sensor, though it can't catch every one.")
        }
        return lines
    }

    /// The electrode check's result as warnings, empty when there's nothing to
    /// warn about (the quality lines cover the quiet cases). Wording in
    /// EegWarnings, shared with the trend tab. Three cases, which can combine:
    /// some electrodes excluded; every electrode flagged so none could be; an
    /// electrode much noisier than its partner (named, and NOT excluded).
    private func electrodeWarnings(_ result: EegAnalysisResult) -> [PlainWarning] {
        guard let findings = result.electrodeFindings else { return [] }
        let excluded = result.excludedChannelIndices ?? []
        var warnings: [PlainWarning] = []
        if result.allElectrodesFlagged == true {
            warnings.append(EegWarnings.allSensorsFlagged())
        } else if !excluded.isEmpty {
            let kept = ELECTRODE_NAMES.enumerated()
                .filter { !excluded.contains($0.offset) }
                .map(\.element)
            warnings.append(EegWarnings.sensorsLeftOut(
                findings: findings.filter { $0.sign != "much_noisier_than_partner" }, keptChannels: kept))
        }
        for finding in findings where finding.sign == "much_noisier_than_partner" && !finding.excluded {
            warnings.append(EegWarnings.noisierThanPartner(finding))
        }
        return warnings
    }

    /// How to read the spectrum charts, said once above both halves rather than
    /// repeated under each. Describes the MODEL and the recording, never the
    /// patient. Says the peaks are what the table measures rather than that every
    /// bump is one: specparam decides what counts, and a bump it rejected is
    /// noise by its own test.
    private var spectrumLegend: some View {
        Text("How to read each half's chart: the solid line is the recording, from slow "
             + "activity on the left to fast on the right. The dashed line is the steady background, "
             + "and how steeply it falls is the \(STEEPNESS). Some bumps of the solid line above the "
             + "dashed one are rhythms; the table lists the ones the app counts as real, the strongest "
             + "in each band. An alpha rhythm (8-13 Hz) usually appears with the eyes closed and fades "
             + "when they open; how strong it is here isn't a reliable measure of anything. The thin "
             + "line is the app's model; where it comes apart from the solid line, the model doesn't "
             + "match the recording well.\n\n"
             + "The \(STEEPNESS) is most reliable compared with the other half of the same session; it "
             + "varies more from one session to the next.\n\n"
             + "Details: solid = power spectrum, dashed = aperiodic fit (its slope is the exponent), "
             + "thin = full specparam model (r² measures its match).")
            .font(.caption).foregroundStyle(.secondary)
    }

    // MARK: - One half

    /// One half's block: steepness/eye-movement/window text, the spectrum chart,
    /// a low-fit warning if the model didn't match, then a small peaks table.
    @ViewBuilder
    private func halfView(_ half: EegHalfMetric, powerRange: ClosedRange<Double>?,
                          result: EegAnalysisResult) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(half.half.capitalized) half").font(.footnote.weight(.semibold))
            Text(String(format: "\(STEEPNESS.prefix(1).uppercased() + STEEPNESS.dropFirst()): %.2f",
                        half.aperiodicExponent))
                .font(.footnote).foregroundStyle(.secondary)
            Text(eogText(half)).font(.footnote).foregroundStyle(.secondary)
            Text(deflectionText(half, result: result)).font(.footnote).foregroundStyle(.secondary)
            Text(windowTallyText(half)).font(.footnote).foregroundStyle(.secondary)
            // The precise terms, kept for a specialist rather than deleted — see
            // EegWording. Muted and smaller so the plain lines above lead.
            Text(String(format: "Details: aperiodic exponent %.2f, offset %.2f, fit r² %.3f, %d epochs",
                        half.aperiodicExponent, half.aperiodicOffset, half.rSquared, half.epochCount))
                .font(.caption2).foregroundStyle(.tertiary)

            spectrumView(half, powerRange: powerRange)

            if isLowFit(half.rSquared, result.lowFitRSquared) {
                // Names the steepness AND the rhythms because r² measures the
                // whole decomposition, not just the aperiodic part, so presenting
                // the peaks as unaffected would be an overclaim.
                PlainWarningView(warning: EegWarnings.lowFit(
                    rSquared: half.rSquared, threshold: result.lowFitRSquared, withRhythms: true))
            }

            peaksTable(half.peaks, peaksEvaluable: result.allElectrodesFlagged != true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Counted from the signal, independent of patient_dozed/alertness_rating
    /// self-report. Not labelled an arousal marker: deliberate eye movement
    /// produces these just as readily as drowsiness. Nil means "not enough clean
    /// signal," not a rate of zero, so it's worded as its own sentence.
    private func eogText(_ half: EegHalfMetric) -> String {
        guard let rate = half.eogSemRatePerMin else {
            return "Slow eye movements: not counted (not enough clean signal)"
        }
        return String(format: "Slow eye movements: %.1f per minute", rate)
    }

    /// Not called a blink count alone: on this headband blinks, eye movements and
    /// a hand on the headband all produce these (see eeg.artifacts.DEFLECTION_*).
    /// An analysis from before the field existed is told apart by its missing
    /// electrode check, which arrived in the same change.
    private func deflectionText(_ half: EegHalfMetric, result: EegAnalysisResult) -> String {
        if let rate = half.deflectionRatePerMin {
            return String(format: "Blinks and eye or head movements: %.1f per minute", rate)
        }
        if result.electrodeFindings == nil {
            return "Blinks and eye or head movements: not recorded for this analysis"
        }
        if result.allElectrodesFlagged == true {
            return "Blinks and eye or head movements: not counted (every sensor flagged)"
        }
        return "Blinks and eye or head movements: not counted (too little usable signal)"
    }

    /// One half's window tally. Deliberately three numbers rather than one
    /// "rejected" total: empty readings means the headband sent NaN (not the same
    /// as "off the skin" — a lifted electrode still sends numbers), movement
    /// means large amplitude swings, and "kept with fewer sensors" isn't a
    /// rejection at all — those windows ARE in the spectrum, just measured by
    /// less of the headband. Summing them would hide which actually happened.
    private func windowTallyText(_ half: EegHalfMetric) -> String {
        guard let badContact = half.epochsRejectedBadContact else {
            return "Windows: \(half.epochCount) kept - drop detail not recorded for this analysis"
        }
        return "Windows: \(half.epochCount) kept, \(badContact) dropped (empty readings), "
            + "\(half.epochsRejectedAmplitude ?? 0) dropped (movement), "
            + "\(half.epochsPartialContact ?? 0) kept with fewer sensors"
    }

    /// This half's spectrum with the fit drawn over it, or one line saying it
    /// isn't stored. Nil means the analysis predates the field, not that there is
    /// nothing to show — naming the fix since the button is right there.
    @ViewBuilder
    private func spectrumView(_ half: EegHalfMetric, powerRange: ClosedRange<Double>?) -> some View {
        if let spectrum = half.spectrum, let powerRange {
            EegSpectrumChartView(spectrum: spectrum, powerRange: powerRange)
        } else {
            Text("Chart: not recorded for this analysis - tap Re-analyze to add it.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    /// Header + one row per detected peak; an empty list renders a single line
    /// instead of a header over nothing. Band names color-matched to bandColor,
    /// same "a color means the same band everywhere" convention as the stats table.
    @ViewBuilder
    private func peaksTable(_ peaks: [OscillationPeak], peaksEvaluable: Bool) -> some View {
        if peaks.isEmpty {
            // With every electrode flagged the pipeline reports no peaks at all
            // (the interference lines crowd real ones out), so an empty list
            // there is "not evaluable", not a finding.
            Text(peaksEvaluable
                 ? "Rhythms: none found"
                 : "Rhythms: can't be checked - every sensor shows signs of a loose fit")
                .font(.footnote).foregroundStyle(.secondary)
        } else {
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 4) {
                GridRow {
                    Text("Rhythm").fontWeight(.semibold)
                    Text("Hz").fontWeight(.semibold)
                    Text("Strength").fontWeight(.semibold)
                    Text("Width").fontWeight(.semibold)
                }
                .font(.caption2).foregroundStyle(.secondary)
                ForEach(peaks, id: \.band) { peak in
                    GridRow {
                        Text(peak.band.capitalized)
                            .foregroundStyle(bandColor(peak.band)).fontWeight(.semibold)
                        Text(String(format: "%.1f", peak.centerFrequency))
                        Text(String(format: "%.2f", peak.power))
                        Text(String(format: "%.1f", peak.bandwidth))
                    }
                    .font(.caption2)
                }
            }
            .padding(.top, 2)
        }
    }
}
