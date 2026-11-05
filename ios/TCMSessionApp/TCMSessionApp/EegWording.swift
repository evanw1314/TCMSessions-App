import SwiftUI

/// Plain-language wording for the EEG analysis, shared by the session card
/// (SessionDetailView) and the EEG Trend tab (PatientDetailView) so the two never
/// describe the same finding in different words. Direct port of Android's
/// EegWording.kt — the sentences are deliberately identical across platforms.
///
/// Written for a reader without neuroscience training: accurate wording full of
/// "exponent", "0.74 shift in exponent" and "TP9" read to that reader as pure
/// jargon, and a warning its reader can't follow isn't transparent. So the names
/// on screen are plain ("background steepness", "left ear sensor", "the app's model doesn't
/// match"), and the precise terms move to a "Details" line rather than being
/// deleted, so a specialist can still check them.
///
/// Same standing rule as the rest of the card: every sentence describes the
/// recording, never the patient.

/// The aperiodic exponent's on-screen name.
let STEEPNESS = "background steepness"

/// The Muse electrode's plain name, from its 10-20 label (ELECTRODE_NAMES). An
/// unknown label is returned as-is rather than guessed at.
func sensorName(_ channel: String) -> String {
    switch channel {
    case "TP9": return "left ear sensor"
    case "AF7": return "left forehead sensor"
    case "AF8": return "right forehead sensor"
    case "TP10": return "right ear sensor"
    default: return channel
    }
}

/// "left ear sensor", "left ear and right ear sensors",
/// "left ear, left forehead and right ear sensors".
func sensorList(_ channels: [String]) -> String {
    let sides = channels.map { name -> String in
        let plain = sensorName(name)
        return plain.hasSuffix(" sensor") ? String(plain.dropLast(" sensor".count)) : plain
    }
    switch sides.count {
    case 0: return "no sensors"
    case 1: return "\(sides[0]) sensor"
    default:
        return sides.dropLast().joined(separator: ", ") + " and " + sides.last! + " sensors"
    }
}

/// Why the loose-sensor check flagged a sensor (eeg.artifacts.ElectrodeFinding.sign),
/// in plain words. The technical name goes in the Details line. Bare reasons: one
/// sensor can trip two checks, so "a sign of a loose sensor" is said once by
/// `sensorsLeftOut` rather than after each.
func sensorSignWords(_ sign: String) -> String {
    switch sign {
    case "interference_lines": return "electrical noise"
    case "upside_down_spectrum": return "more fast activity than slow"
    case "no_signal": return "no signal at all (flat)"
    case "much_noisier_than_partner": return "much noisier than the matching sensor on the other side"
    default: return sign
    }
}

func sensorSignTechnical(_ sign: String) -> String {
    switch sign {
    case "interference_lines": return "interference lines"
    case "upside_down_spectrum": return "inverted spectrum (more high- than low-frequency power)"
    case "no_signal": return "flat or constant signal"
    case "much_noisier_than_partner": return "much noisier than its partner"
    default: return sign
    }
}

/// One warning, always in the same shape: a headline, what happened, what to do,
/// then the precise terms. `action` and `details` are optional so a warning with
/// nothing useful to add doesn't pad itself out.
struct PlainWarning: Identifiable {
    let title: String
    let body: String
    var action: String?
    var details: String?

    var id: String { title + body }
}

/// Renders a `PlainWarning` in the shape Android's `toText()` produces: bold
/// headline, body, a bold-labelled "What to do", then smaller "Details".
struct PlainWarningView: View {
    let warning: PlainWarning

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(warning.title).font(.footnote.weight(.semibold))
            Text(warning.body).font(.footnote)
            if let action = warning.action {
                Text(boldLabel("What to do: ", action)).font(.footnote)
            }
            if let details = warning.details {
                Text("Details: \(details)").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
    }

    private func boldLabel(_ label: String, _ rest: String) -> AttributedString {
        var head = AttributedString(label)
        head.font = .footnote.weight(.semibold)
        return head + AttributedString(rest)
    }
}

enum EegWarnings {
    /// eeg.interpretation.eye_activity_differs tripped. Says "picked up" because the bump
    /// count isn't eye-specific (a hand on the headband trips it too), and
    /// "noticeably", not "a lot": the trigger sits near the no-change sessions'
    /// own spread (13 reached 9.0 a minute against a line at 10). The figures are
    /// the controls in docs/archive/eeg-lab-notebook.md ("Clean contact
    /// restored"): +0.74 blinking, +0.58 slow sweeps. Against 0.07 that's 8-10
    /// times, but no-change sessions have moved up to 0.25 (29), so "several
    /// times" is the honest reading. Only 31 fit well in BOTH halves (30's steady
    /// half was r² 0.901), hence the Details wording.
    static func eyeActivity() -> PlainWarning {
        PlainWarning(
            title: "Blinking or movement differs between the halves",
            body: "One half picked up noticeably more blinking, eye movement or head movement than "
                + "the other. In our tests, that alone changed the \(STEEPNESS) several times more than it "
                + "usually changes when nothing happens.",
            action: "Don't read the change in \(STEEPNESS) between these halves as meaningful. The "
                + "counts for each half are further down.",
            details: "In controls on this headband, blinking alone shifted the aperiodic exponent by "
                + "0.74 and slow eye sweeps by 0.58, against up to 0.25 with nothing changing (at most "
                + "0.07 when both halves fit well). The sweep session fit well in both halves "
                + "(r² ≥ 0.988), so the fit check didn't catch it."
        )
    }

    /// The trend list's shorter form: one line per session, no room for the
    /// controls, and the session card is a tap away.
    static func eyeActivityShort() -> PlainWarning {
        PlainWarning(
            title: "Blinking or movement differs between the halves",
            body: "So the change in \(STEEPNESS) between this session's halves may come from that.",
            action: "Don't read that change as meaningful."
        )
    }

    static func allSensorsFlagged() -> PlainWarning {
        PlainWarning(
            title: "Every sensor shows signs of a loose fit",
            body: "None could be left out, so the app can't tell which readings to trust. Rhythms and "
                + "the blink and eye-movement counts couldn't be checked, and every number below is unreliable.",
            action: "Re-seat the headband before the next recording: ear sensors on bare skin, hair "
                + "out of the way.",
            details: "every measurable channel (of TP9, AF7, AF8, TP10) had a sign the electrode "
                + "check would exclude it for."
        )
    }

    static func allSensorsFlaggedShort() -> PlainWarning {
        PlainWarning(
            title: "Every sensor shows signs of a loose fit",
            body: "So this session's numbers are unreliable."
        )
    }

    /// Some sensors left out of both halves. `findings` are the excluding ones;
    /// `keptChannels` what the numbers now come from.
    static func sensorsLeftOut(findings: [ElectrodeFinding], keptChannels: [String]) -> PlainWarning {
        let names = findings.map(\.channelName).uniqued()
        let reasons = findings.map { sensorSignWords($0.sign) }.uniqued()
        // A flat sensor alone isn't evidence of a loose fit (it can be a dead
        // channel), so the loose-sensor reading is only added when some other
        // sign is present.
        let looseNote: String
        if findings.allSatisfy({ $0.sign == "no_signal" }) {
            looseNote = ""
        } else if reasons.count == 1 {
            looseNote = ", a sign of a loose sensor"
        } else {
            looseNote = " - signs of a loose sensor"
        }
        let one = names.count == 1
        let detail = findings
            .uniqued(by: { "\($0.channelName)|\($0.sign)" })
            .map { "\($0.channelName) (\(sensorSignTechnical($0.sign)))" }
            .joined(separator: "; ")
        return PlainWarning(
            title: one ? "The \(sensorName(names[0])) was left out" : "Some sensors were left out",
            body: "The \(sensorList(names)) showed \(reasons.joined(separator: " and "))\(looseNote). "
                + "\(one ? "It was" : "They were") left out of both halves, so the numbers below "
                + "come from the \(sensorList(keptChannels)) only.",
            action: "Re-seat the headband before the next recording: ear sensors on bare skin, hair "
                + "out of the way.",
            details: "excluded " + detail + "."
        )
    }

    /// A sensor much noisier than its partner: a warning only, never an
    /// exclusion, because one-sided muscle or eye activity does it too.
    static func noisierThanPartner(_ finding: ElectrodeFinding) -> PlainWarning {
        let partner = finding.partnerName.map { sensorName($0) } ?? "the matching sensor"
        return PlainWarning(
            title: "One sensor was much noisier than its partner",
            body: "In the \(finding.half) half, the \(sensorName(finding.channelName)) was much "
                + "noisier than the \(partner). That can mean a loose fit, but muscle tension or eye "
                + "movement on one side does it too, so it was kept in.",
            action: "Check that sensor's fit before the next recording.",
            details: "\(finding.channelName) vs \(finding.partnerName ?? "partner"), noisier-than-partner "
                + "check (warning only, not excluded)."
        )
    }

    /// A half whose specparam fit is below eeg.interpretation.LOW_FIT_R_SQUARED. About THE
    /// MODEL, not the patient: a poor match means these numbers don't describe
    /// this recording, which says nothing about how the session went.
    /// `withRhythms` names the peaks too, since r² covers the whole model; the
    /// trend list shows none, so it leaves them out.
    static func lowFit(rSquared: Double, threshold: Double?, withRhythms: Bool) -> PlainWarning {
        PlainWarning(
            title: "The app's model doesn't match this half well",
            body: "So this half's \(STEEPNESS)\(withRhythms ? " and rhythms" : "") may not "
                + "describe the recording accurately.",
            action: "Treat this half's numbers as rough.",
            details: String(format: "specparam fit r² = %.3f", rSquared)
                + (threshold.map { String(format: ", below the %.2f threshold.", $0) } ?? ".")
        )
    }
}

private extension Array where Element == String {
    /// Order-preserving distinct — the wording depends on the order findings
    /// arrive in, so Set would scramble it.
    func uniqued() -> [String] {
        var seen = Set<String>()
        return filter { seen.insert($0).inserted }
    }
}

private extension Array where Element == ElectrodeFinding {
    func uniqued(by key: (Element) -> String) -> [Element] {
        var seen = Set<String>()
        return filter { seen.insert(key($0)).inserted }
    }
}
