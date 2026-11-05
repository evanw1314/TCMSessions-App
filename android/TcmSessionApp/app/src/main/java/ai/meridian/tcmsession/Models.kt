package ai.meridian.tcmsession

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The server always emits started_at/ended_at as UTC ISO-8601 with a
 * trailing 'Z' (schemas.serialize_utc),
 * e.g. "2026-07-27T22:06:11Z". That's correct but not viewer-friendly: it's
 * UTC, not the phone's local time zone, and the raw ISO format reads as a
 * wall of digits. This reformats both timestamps into the device's local
 * time zone, e.g. "Jul 27, 2026 · 3:06 – 3:16 PM" (collapsing the date once
 * when start/end fall on the same local day). Falls back to the raw strings
 * if either fails to parse rather than crashing the screen over a display nicety.
 */
fun formatSessionRange(startedAtIso: String, endedAtIso: String): String {
    val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val start = runCatching { isoParser.parse(startedAtIso) }.getOrNull()
    val end = runCatching { isoParser.parse(endedAtIso) }.getOrNull()
    if (start == null || end == null) return "$startedAtIso – $endedAtIso"

    // No explicit timeZone set below, so these use the device's local time zone.
    val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())

    return if (dateFmt.format(start) == dateFmt.format(end)) {
        "${dateFmt.format(start)} · ${timeFmt.format(start)} – ${timeFmt.format(end)}"
    } else {
        "${dateFmt.format(start)} ${timeFmt.format(start)} – ${dateFmt.format(end)} ${timeFmt.format(end)}"
    }
}

/** Same UTC-ISO-with-Z timestamps as [formatSessionRange], but for a single instant
 * (e.g. PatientSummary.last_session_at) rather than a start/end pair. */
fun formatTimestamp(iso: String): String {
    val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val date = runCatching { isoParser.parse(iso) }.getOrNull() ?: return iso

    val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    return "${dateFmt.format(date)} · ${timeFmt.format(date)}"
}

/** Compact "Jul 27" form of [formatTimestamp] — for EegMetricChartView's x-axis
 * labels, where a patient's full multi-session trend needs to fit several dates
 * across one chart width without wrapping or overlapping. */
fun formatShortDate(iso: String): String {
    val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val date = runCatching { isoParser.parse(iso) }.getOrNull() ?: return iso
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
}

/** Canonical band display order (low frequency to high), matching schemas.BAND_TYPES.
 * Shared by BandChartView (line/legend order) and SessionDetailActivity (stats table rows). */
val BAND_ORDER = listOf("delta", "theta", "alpha", "beta", "gamma")

/** Per-band color shared by the chart lines/legend and the stats table's band labels,
 * so a color in one place means the same band everywhere on screen. Low frequency =
 * cool colors, high = warm — a common EEG visualization convention. */
private val BAND_COLOR_MAP = mapOf(
    "delta" to android.graphics.Color.parseColor("#3B7DD8"),
    "theta" to android.graphics.Color.parseColor("#8A5FD1"),
    "alpha" to android.graphics.Color.parseColor("#3FA66B"),
    "beta" to android.graphics.Color.parseColor("#E08A2C"),
    "gamma" to android.graphics.Color.parseColor("#D8455A"),
)
fun bandColor(band: String): Int = BAND_COLOR_MAP.getValue(band)

/** Sentinel for "no valid band-power reading this tick" (bad electrode contact
 * or an SDK-reported NaN). Relative band power is always >= 0, so -1.0 is
 * unambiguous, and it's finite — passes the server's math.isfinite() check
 * and org.json's ban on NaN with no schema change. Shared by SessionActivity
 * (writes it), SessionDetailActivity and BandChartView (both exclude it). */
const val NO_SIGNAL_VALUE = -1.0

/** Result of POST /auth/login or /auth/verify — clinicName is null only
 * for an account with no clinic, which shouldn't happen for a practitioner.
 * `role` is "practitioner" or "patient" (mirrors models.Role) — LoginActivity
 * checks it against the tab the user signed in on before trusting the token. */
data class LoginResult(
    val token: String, val userId: Int, val userName: String,
    val clinicName: String?, val role: String,
)

/** Result of POST /auth/register — the account isn't real yet; a code was
 * emailed to `email` and expires at `expiresAt` (server's raw ISO string,
 * only ever shown as-is, not parsed). */
data class PendingRegistration(val email: String, val codeSentTo: String, val expiresAt: String)

/** Mirrors server/app/schemas.py's PatientSummary. */
data class Patient(
    val id: Int,
    val name: String,
    // Null for a patient added by name only, before any account has been
    // assigned to receive their data (see ApiClient.addPatient/assignPatientEmail).
    val email: String?,
    val headbandDeviceId: String?,
    val lastSessionAt: String?,
)

/** One dated note in PatientDetailActivity's Notes tab — mirrors
 * PatientNoteOut. A practitioner's own list (GET /patients/{id}/notes) is
 * scoped to notes THEY wrote (see PatientNote's docstring: never shared
 * across practitioners); the patient's self-view (GET /me/notes) instead
 * spans every practitioner who's treated them, which is what
 * practitionerName is for — telling those apart when there's more than one. */
data class PatientNote(val id: Int, val body: String, val createdAt: String, val practitionerName: String)

/** One buffered marker recorded during a live session. */
data class SessionMarker(val offsetSeconds: Int, val label: String)

/** One buffered band-power sample recorded during a live session.
 * `band` is one of alpha|beta|delta|theta|gamma — see schemas.BAND_TYPES. */
data class SessionSample(val offsetSeconds: Int, val band: String, val value: Double)

/** Row in a patient's session list — mirrors TcmSessionSummary. */
data class SessionSummary(
    val id: Int,
    val modality: String,
    val startedAt: String,
    val endedAt: String,
)

/** Full session detail — mirrors TcmSessionOut. */
data class SessionDetail(
    val id: Int,
    val modality: String,
    val startedAt: String,
    val endedAt: String,
    val practitionerNote: String?,
    // Optional post-session context (both null = not answered). Why it
    // exists: a rise in the low bands during a resting session is the classic drowsiness/sleep-
    // onset signature, not necessarily a treatment effect, and these let a
    // reviewer tell those apart later instead of guessing.
    val patientDozed: Boolean?,
    val alertnessRating: Int?,
    // Elapsed seconds from session start to "baseline done, treatment
    // started" (SessionRecorder.markBaselineEnd) — null means no baseline
    // phase was recorded. See models.TcmSession.baseline_duration_seconds
    // server-side for why this exists.
    val baselineDurationSeconds: Int?,
    // Elapsed seconds at "Treatment done" (SessionRecorder.markTreatmentEnd):
    // the end of the treatment itself, before the needles come out. Null
    // means it wasn't tapped.
    // See models.TcmSession.treatment_end_seconds server-side.
    val treatmentEndSeconds: Int? = null,
    val markers: List<SessionMarker>,
    val samples: List<SessionSample>,
)

/** One real oscillatory peak found above the aperiodic background in a given
 * band — mirrors OscillationPeakOut. Absence of a band from a half's `peaks`
 * list is itself informative (no real oscillation found there), not a gap to
 * fill in — see docs/eeg-aperiodic-correction.md. */
data class OscillationPeak(
    val band: String,
    val centerFrequency: Double,
    val power: Double,
    val bandwidth: Double,
)

/** Aperiodic-corrected metrics for one session-half — mirrors EegHalfMetricOut.
 * `aperiodicExponent` is the arousal-tracking number (steepens with drowsiness);
 * `peaks` are the real oscillations left after subtracting that background. */
data class EegHalfMetric(
    val half: String,
    val aperiodicOffset: Double,
    val aperiodicExponent: Double,
    val rSquared: Double,
    val epochCount: Int,
    val peaks: List<OscillationPeak>,
    // Slow eye movements counted from the signal (deliberate eye movement
    // produces them as readily as drowsiness), independent of self-report — mirrors
    // SessionEegMetric.eog_sem_rate_per_min. Null
    // means "not enough clean signal this half to score," not a rate of zero.
    val eogSemRatePerMin: Double?,
    val eogWindowsAnalyzed: Int,
    // What this half LOST, split by cause — mirrors SessionEegMetric's
    // three columns. Kept apart rather than summed because they mean
    // different things: bad contact is the headband sending empty (NaN)
    // readings - NOT every case of an electrode off the skin, since a lifted
    // electrode still sends numbers (session 24) - amplitude is
    // movement, a jaw clench, or a loose electrode being handled, and
    // partialContact counts epochs that were KEPT but measured by fewer
    // electrodes than the headband has.
    // Null (not 0) when the analysis predates these fields.
    val epochsRejectedBadContact: Int?,
    val epochsRejectedAmplitude: Int?,
    val epochsPartialContact: Int?,
    // Null when the analysis predates the field — see EegSpectrum.
    val spectrum: EegSpectrum? = null,
    // Large slow deflections per minute (blinks, eye or head movement) —
    // mirrors SessionEegMetric.deflection_rate_per_min. Null is "not
    // measured", never zero.
    val deflectionRatePerMin: Double? = null,
)

/** Muse electrode order, by channel index — mirrors eeg.params.CHANNEL_NAMES
 * (see EOG_FRONTAL_CHANNEL_INDICES there for the montage assumption). */
val ELECTRODE_NAMES = listOf("TP9", "AF7", "AF8", "TP10")

/** One electrode-check finding — mirrors ElectrodeFindingOut (see
 * eeg.artifacts.ElectrodeFinding). `sign` is interference_lines,
 * upside_down_spectrum, no_signal or much_noisier_than_partner; the first
 * three exclude the electrode from both halves, the last only warns. `value`
 * is log10. */
data class ElectrodeFinding(
    val channelName: String,
    val half: String,
    val sign: String,
    val value: Double,
    val excluded: Boolean,
    val partnerName: String?,
)

/** One half's power spectrum and the model fit to it, index-aligned — mirrors
 * EegSpectrumOut. Power is log10 (the space specparam fits in), which
 * EegSpectrumChartView plots as-is. Stored server-side at analyze time, so it
 * always comes from the same fit as the numbers beside it. */
data class EegSpectrum(
    val freqsHz: List<Double>,
    val logPower: List<Double>,
    val logModelFit: List<Double>,
    val logAperiodicFit: List<Double>,
)

/** Mirrors EegAnalysisOut — the shared response shape for both running the
 * pipeline (POST .../eeg/analyze) and rereading its last result (GET
 * .../eeg-metrics). Empty `halves` + null `analyzedAt` both mean "never
 * analyzed" (no raw EEG captured, or Analyze hasn't been tapped yet). */
data class EegAnalysisResult(
    val halves: List<EegHalfMetric>,
    val analyzedAt: String?,
    // Session-wide recording quality, not per-half. observedSampleRateHz is
    // what the headband ACTUALLY achieved (derived from the raw blob's
    // timestamps), not the nominal 256Hz. excludedChannelIndices is empty
    // when the analysis ran and excluded nothing, and null when it predates
    // the field — a distinction the card words differently.
    val observedSampleRateHz: Double? = null,
    val interpolatedSampleFraction: Double? = null,
    val excludedChannelIndices: List<Int>? = null,
    // The server's own "don't trust this fit" line
    // (eeg.interpretation.LOW_FIT_R_SQUARED), shipped so the card flags exactly the
    // halves ai_summary hedges in prose instead of keeping a second copy
    // that can drift. Null only on a response predating the field.
    val lowFitRSquared: Double? = null,
    // The electrode check behind excludedChannelIndices. Null means the
    // analysis predates the check, so a non-empty exclusion came from the
    // old, switched-off eye correction; empty means it ran and found nothing.
    val electrodeFindings: List<ElectrodeFinding>? = null,
    // Every electrode flagged, so none could be excluded: the halves' empty
    // peak lists mean "not evaluable", never "none detected". Null when the
    // analysis predates the electrode check.
    val allElectrodesFlagged: Boolean? = null,
    // The server's eeg.interpretation.eye_activity_differs over the two halves — the
    // same rule ai_summary applies. Null when it couldn't be checked.
    val eyeActivityDiffers: Boolean? = null,
)

/** One session-half's corrected EEG metrics for the cross-session trend view
 * — mirrors EegTrendPointOut (GET /patients/{id}/eeg-trend). `period` is 0
 * for the earlier half (first/baseline) and 1 for the later half (second/
 * treatment) regardless of which raw `half` label this session used — lets
 * PatientDetailActivity group points consistently across sessions that mix
 * baseline/treatment and first/second labeling. Server-ordered oldest-first
 * (see that endpoint's docstring — unlike every other list in this app).
 * ONE PATIENT's OWN history only — never meaningful compared against another
 * patient's numbers, only against this same patient's own earlier sessions. */
data class EegTrendPoint(
    val sessionId: Int,
    val sessionStartedAt: String,
    val half: String,
    val period: Int,
    val aperiodicOffset: Double,
    val aperiodicExponent: Double,
    val rSquared: Double,
    val epochCount: Int,
    val eogSemRatePerMin: Double?,
    val eogWindowsAnalyzed: Int,
    // eeg.interpretation.LOW_FIT_R_SQUARED as shipped on this point — see isLowFit. Null
    // from a server predating the field.
    val lowFitRSquared: Double? = null,
    val deflectionRatePerMin: Double? = null,
    // Session-level, repeated on both of a session's points: the card's
    // eye-activity rule, and the electrodes the analysis left out (null when
    // the analysis predates the field).
    val eyeActivityDiffers: Boolean? = null,
    val excludedChannelNames: List<String>? = null,
    val allElectrodesFlagged: Boolean? = null,
)

/** The ONE client-side rule for "don't trust this fit", shared by the session
 * card and the trend tab so they can't flag different halves. [threshold] is
 * the server's eeg.interpretation.LOW_FIT_R_SQUARED, shipped with every EEG response. Null
 * (a server predating the field) flags nothing, rather than guessing a value
 * the server's own AI summary might not use. NaN always flags: it means
 * specparam returned no goodness-of-fit at all, and a bare `<` is false for
 * NaN, which would let the least trustworthy value through as if it passed.
 *
 * Wherever a flag can appear, show r² to 3 decimals, not 2: with the
 * threshold at 0.97, a flagged 0.966 printed as "0.97" reads as the cutoff
 * itself, as if it had passed. */
fun isLowFit(rSquared: Double, threshold: Double?): Boolean =
    rSquared.isNaN() || (threshold != null && rSquared < threshold)

/** period (0/1) -> a stable label/color, shared by EegMetricChartView wherever it
 * plots period-grouped series (the patient EEG trend chart today) so "period 0"
 * reads the same way — same word, same color — everywhere in the app, regardless
 * of whether the underlying session actually used baseline/treatment or
 * first/second labeling (see EegTrendPoint's own docstring on why period exists).
 * Blue-vs-amber is this app's only "earlier vs later" convention, so it's kept to
 * just these two entries rather than a general palette. */
val EEG_TREND_PERIOD_LABELS = mapOf(0 to "Earlier half", 1 to "Later half")
val EEG_TREND_PERIOD_COLORS = mapOf(
    0 to android.graphics.Color.parseColor("#4C86E8"),
    1 to android.graphics.Color.parseColor("#E0863B"),
)
