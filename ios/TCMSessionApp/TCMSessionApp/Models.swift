import SwiftUI
import Foundation

// Data models + display helpers ported 1:1 from Android's Models.kt. Field
// names on the wire are snake_case and must match server/app/schemas.py exactly
// (the server does NOT camelCase-convert); the Swift property names below are
// camelCase and the snake_case mapping lives in APIClient's parsing helpers.

// MARK: - Display helpers

/// The server always emits started_at/ended_at as UTC ISO-8601 with a trailing
/// 'Z' (schemas.serialize_utc), e.g. "2026-07-27T22:06:11Z". This reformats a
/// start/end pair into the device's local time zone, collapsing the date once
/// when both fall on the same local day. Falls back to the raw strings if either
/// fails to parse rather than crashing the screen over a display nicety.
func formatSessionRange(_ startedAtIso: String, _ endedAtIso: String) -> String {
    guard let start = parseServerDate(startedAtIso), let end = parseServerDate(endedAtIso) else {
        return "\(startedAtIso) – \(endedAtIso)"
    }
    let dateFmt = localDateFormatter
    let timeFmt = localTimeFormatter
    if dateFmt.string(from: start) == dateFmt.string(from: end) {
        return "\(dateFmt.string(from: start)) · \(timeFmt.string(from: start)) – \(timeFmt.string(from: end))"
    } else {
        return "\(dateFmt.string(from: start)) \(timeFmt.string(from: start)) – \(dateFmt.string(from: end)) \(timeFmt.string(from: end))"
    }
}

/// Same UTC-ISO-with-Z timestamps as `formatSessionRange`, but for a single
/// instant (e.g. Patient.lastSessionAt, PatientNote.createdAt).
func formatTimestamp(_ iso: String) -> String {
    guard let date = parseServerDate(iso) else { return iso }
    return "\(localDateFormatter.string(from: date)) · \(localTimeFormatter.string(from: date))"
}

/// Compact "Jul 27" form of `formatTimestamp` — for EegMetricChartView's x-axis
/// labels, where a patient's full multi-session trend needs to fit several dates
/// across one chart width without wrapping or overlapping.
func formatShortDate(_ iso: String) -> String {
    guard let date = parseServerDate(iso) else { return iso }
    return shortDateFormatter.string(from: date)
}

/// Shared by the main actor and SessionJournal's off-main recovery path;
/// DateFormatter is thread-safe for parsing/formatting on iOS 7+.
nonisolated private let serverDateParser: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone(identifier: "UTC")
    f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    return f
}()

private let localDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MMM d, yyyy"
    return f
}()

private let localTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "h:mm a"
    return f
}()

private let shortDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MMM d"
    return f
}()

private func parseServerDate(_ iso: String) -> Date? { serverDateParser.date(from: iso) }

/// Produces the same "yyyy-MM-dd'T'HH:mm:ss'Z'" UTC string the server expects
/// for started_at/ended_at (mirrors SessionRecorder.isoNow()).
func isoNowUTC() -> String { formatServerIso(Date()) }

/// Parses one of the server's UTC-ISO-with-Z timestamps. Shared with
/// SessionJournal, which reconstructs a recovered recording's end time.
nonisolated func parseServerIso(_ iso: String) -> Date? { serverDateParser.date(from: iso) }

/// The inverse of `parseServerIso` — the exact format the server expects back.
nonisolated func formatServerIso(_ date: Date) -> String { serverDateParser.string(from: date) }

// MARK: - Band display constants

/// Canonical band display order (low frequency to high), matching schemas.BAND_TYPES.
let BAND_ORDER = ["delta", "theta", "alpha", "beta", "gamma"]

/// Per-band color shared by the chart lines/legend and the stats table's band
/// labels, so a color in one place means the same band everywhere. Low frequency
/// = cool colors, high = warm — a common EEG visualization convention. Hex values
/// match Android's BAND_COLOR_MAP exactly.
func bandColor(_ band: String) -> Color {
    switch band {
    case "delta": return Color(red: 0x3B / 255, green: 0x7D / 255, blue: 0xD8 / 255)
    case "theta": return Color(red: 0x8A / 255, green: 0x5F / 255, blue: 0xD1 / 255)
    case "alpha": return Color(red: 0x3F / 255, green: 0xA6 / 255, blue: 0x6B / 255)
    case "beta":  return Color(red: 0xE0 / 255, green: 0x8A / 255, blue: 0x2C / 255)
    case "gamma": return Color(red: 0xD8 / 255, green: 0x45 / 255, blue: 0x5A / 255)
    default:      return .gray
    }
}

/// Sentinel for "no valid band-power reading this tick" (bad electrode contact
/// or an SDK-reported NaN). Relative band power is always >= 0, so -1.0 is
/// unambiguous and finite (passes the server's math.isfinite() check). Shared by
/// MuseSessionService (writes it), SessionDetailView and BandChartView (exclude it).
nonisolated let NO_SIGNAL_VALUE = -1.0

// MARK: - Models

/// Result of POST /auth/login, /auth/verify or /auth/google. `clinicName` is nil
/// only for an account with no clinic (shouldn't happen for a practitioner).
/// `role` is "practitioner" or "patient" (mirrors models.Role).
struct LoginResult {
    let token: String
    let userId: Int
    let userName: String
    let clinicName: String?
    let role: String
}

/// Result of POST /auth/register — the account isn't real yet; a code was emailed
/// to `email` and expires at `expiresAt` (server's raw ISO string, shown as-is).
struct PendingRegistration {
    let email: String
    let codeSentTo: String
    let expiresAt: String
}

/// Mirrors server/app/schemas.py's PatientSummary.
struct Patient: Identifiable, Equatable, Hashable {
    let id: Int
    let name: String
    // Nil for a patient added by name only, before any account has been assigned
    // to receive their data (see APIClient.addPatient/assignPatientEmail).
    let email: String?
    let headbandDeviceId: String?
    let lastSessionAt: String?
}

/// One dated note in PatientDetailView's Notes tab — mirrors PatientNoteOut.
struct PatientNote: Identifiable, Equatable {
    let id: Int
    let body: String
    let createdAt: String
    let practitionerName: String
}

/// One buffered marker recorded during a live session. Carries a stable id so
/// SwiftUI lists can track it; only offsetSeconds/label go over the wire.
struct SessionMarker: Identifiable, Equatable {
    let id = UUID()
    var offsetSeconds: Int
    var label: String
}

/// One buffered band-power sample. `band` is one of alpha|beta|delta|theta|gamma.
struct SessionSample: Equatable {
    let offsetSeconds: Int
    let band: String
    let value: Double
}

/// Row in a patient's session list — mirrors TcmSessionSummary.
struct SessionSummary: Identifiable, Equatable {
    let id: Int
    let modality: String
    let startedAt: String
    let endedAt: String
}

/// Full session detail — mirrors TcmSessionOut.
struct SessionDetail: Identifiable, Equatable {
    let id: Int
    let modality: String
    let startedAt: String
    let endedAt: String
    let practitionerNote: String?
    // Optional post-session context (both nil = not answered): a rise in the
    // low bands during a resting session is the classic drowsiness/sleep-onset
    // signature, not necessarily a treatment effect, and these let a reviewer
    // tell those apart later instead of guessing.
    let patientDozed: Bool?
    let alertnessRating: Int?
    // Elapsed seconds from session start to "baseline done, treatment started"
    // (PatientSessionRecorder.markBaselineEnd) — nil means no baseline phase
    // was recorded. See models.TcmSession.baseline_duration_seconds server-side.
    let baselineDurationSeconds: Int?
    let markers: [SessionMarker]
    let samples: [SessionSample]
}

// MARK: - EEG analysis (aperiodic-corrected metrics)

/// Muse electrode order, by channel index — mirrors eeg.params.CHANNEL_NAMES.
let ELECTRODE_NAMES = ["TP9", "AF7", "AF8", "TP10"]

/// One real oscillatory peak found above the aperiodic background in a given
/// band — mirrors OscillationPeakOut. Absence of a band from a half's `peaks`
/// list is itself informative (no real oscillation found there), not a gap to
/// fill in — see docs/eeg-aperiodic-correction.md.
struct OscillationPeak: Equatable {
    let band: String
    let centerFrequency: Double
    let power: Double
    let bandwidth: Double
}

/// One half's power spectrum and the model fit to it, index-aligned — mirrors
/// EegSpectrumOut. Power is log10 (the space specparam fits in), which
/// EegSpectrumChartView plots as-is. Stored server-side at analyze time, so it
/// always comes from the same fit as the numbers beside it.
struct EegSpectrum: Equatable {
    let freqsHz: [Double]
    let logPower: [Double]
    let logModelFit: [Double]
    let logAperiodicFit: [Double]
}

/// Aperiodic-corrected metrics for one session-half — mirrors EegHalfMetricOut.
/// `aperiodicExponent` is the arousal-tracking number (steepens with drowsiness);
/// `peaks` are the real oscillations left after subtracting that background.
struct EegHalfMetric: Equatable, Identifiable {
    let half: String
    let aperiodicOffset: Double
    let aperiodicExponent: Double
    let rSquared: Double
    let epochCount: Int
    let peaks: [OscillationPeak]
    // Slow eye movements counted from the signal (deliberate eye movement
    // produces them as readily as drowsiness), independent of self-report. Nil
    // means "not enough clean signal this half to score," not a rate of zero.
    let eogSemRatePerMin: Double?
    let eogWindowsAnalyzed: Int
    // What this half LOST, split by cause — kept apart rather than summed
    // because they mean different things: bad contact is the headband sending
    // empty (NaN) readings — NOT every case of an electrode off the skin, since
    // a lifted electrode still sends numbers — amplitude is movement, a jaw
    // clench, or a loose electrode being handled, and partialContact counts
    // epochs that were KEPT but measured by fewer electrodes than the headband
    // has. Nil (not 0) when the analysis predates these fields.
    let epochsRejectedBadContact: Int?
    let epochsRejectedAmplitude: Int?
    let epochsPartialContact: Int?
    // Nil when the analysis predates the field — see EegSpectrum.
    let spectrum: EegSpectrum?
    // Large slow deflections per minute (blinks, eye or head movement). Nil is
    // "not measured", never zero.
    let deflectionRatePerMin: Double?

    var id: String { half }
}

/// One electrode-check finding — mirrors ElectrodeFindingOut. `sign` is
/// interference_lines, upside_down_spectrum, no_signal or
/// much_noisier_than_partner; the first three exclude the electrode from both
/// halves, the last only warns. `value` is log10.
struct ElectrodeFinding: Equatable {
    let channelName: String
    let half: String
    let sign: String
    let value: Double
    let excluded: Bool
    let partnerName: String?
}

/// Mirrors EegAnalysisOut — the shared response shape for both running the
/// pipeline (POST .../eeg/analyze) and rereading its last result (GET
/// .../eeg-metrics). Empty `halves` + nil `analyzedAt` both mean "never
/// analyzed" (no raw EEG captured, or Analyze hasn't been tapped yet).
struct EegAnalysisResult: Equatable {
    let halves: [EegHalfMetric]
    let analyzedAt: String?
    // Session-wide recording quality, not per-half. observedSampleRateHz is
    // what the headband ACTUALLY achieved (derived from the raw blob's
    // timestamps), not the nominal 256Hz. excludedChannelIndices is empty when
    // the analysis ran and excluded nothing, and nil when it predates the
    // field — a distinction the card words differently.
    let observedSampleRateHz: Double?
    let interpolatedSampleFraction: Double?
    let excludedChannelIndices: [Int]?
    // The server's own "don't trust this fit" line (eeg.interpretation.LOW_FIT_R_SQUARED),
    // shipped so the card flags exactly the halves ai_summary hedges in prose
    // instead of keeping a second copy that can drift.
    let lowFitRSquared: Double?
    // The electrode check behind excludedChannelIndices. Nil means the analysis
    // predates the check; empty means it ran and found nothing.
    let electrodeFindings: [ElectrodeFinding]?
    // Every electrode flagged, so none could be excluded: the halves' empty
    // peak lists mean "not evaluable", never "none detected".
    let allElectrodesFlagged: Bool?
    // The server's eeg.interpretation.eye_activity_differs over the two halves — the same
    // rule ai_summary applies. Nil when it couldn't be checked.
    let eyeActivityDiffers: Bool?

    /// Nothing has ever been analyzed for this session.
    var isEmpty: Bool { halves.isEmpty && analyzedAt == nil }
}

/// One session-half's corrected EEG metrics for the cross-session trend view —
/// mirrors EegTrendPointOut (GET /patients/{id}/eeg-trend). `period` is 0 for
/// the earlier half (first/baseline) and 1 for the later half
/// (second/treatment) regardless of which raw `half` label this session used —
/// lets the trend tab group points consistently across sessions that mix
/// baseline/treatment and first/second labeling. Server-ordered oldest-first,
/// unlike every other list in this app. ONE PATIENT's OWN history only — never
/// meaningful compared against another patient's numbers.
struct EegTrendPoint: Equatable {
    let sessionId: Int
    let sessionStartedAt: String
    let half: String
    let period: Int
    let aperiodicOffset: Double
    let aperiodicExponent: Double
    let rSquared: Double
    let epochCount: Int
    let eogSemRatePerMin: Double?
    let eogWindowsAnalyzed: Int
    // eeg.interpretation.LOW_FIT_R_SQUARED as shipped on this point — see isLowFit.
    let lowFitRSquared: Double?
    let deflectionRatePerMin: Double?
    // Session-level, repeated on both of a session's points.
    let eyeActivityDiffers: Bool?
    let excludedChannelNames: [String]?
    let allElectrodesFlagged: Bool?
}

/// The ONE client-side rule for "don't trust this fit", shared by the session
/// card and the trend tab so they can't flag different halves. `threshold` is
/// the server's eeg.interpretation.LOW_FIT_R_SQUARED, shipped with every EEG response. Nil
/// (a server predating the field) flags nothing, rather than guessing a value
/// the server's own AI summary might not use. NaN always flags: it means
/// specparam returned no goodness-of-fit at all, and a bare `<` is false for
/// NaN, which would let the least trustworthy value through as if it passed.
///
/// Wherever a flag can appear, show r² to 3 decimals, not 2: with the threshold
/// at 0.97, a flagged 0.966 printed as "0.97" reads as the cutoff itself, as if
/// it had passed.
func isLowFit(_ rSquared: Double, _ threshold: Double?) -> Bool {
    rSquared.isNaN || (threshold != nil && rSquared < threshold!)
}

/// period (0/1) -> a stable label/color, shared wherever period-grouped series
/// are plotted so "period 0" reads the same way — same word, same color —
/// everywhere in the app, regardless of whether the underlying session actually
/// used baseline/treatment or first/second labeling. Blue-vs-amber is this
/// app's only "earlier vs later" convention, so it's kept to just these two.
let EEG_TREND_PERIOD_LABELS = [0: "Earlier half", 1: "Later half"]
let EEG_TREND_PERIOD_COLORS: [Int: Color] = [
    0: Color(red: 0x4C / 255, green: 0x86 / 255, blue: 0xE8 / 255),
    1: Color(red: 0xE0 / 255, green: 0x86 / 255, blue: 0x3B / 255),
]
