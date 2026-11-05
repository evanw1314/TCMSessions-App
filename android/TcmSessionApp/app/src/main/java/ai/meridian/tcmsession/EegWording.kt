package ai.meridian.tcmsession

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Plain-language wording for the EEG analysis, shared by the session card
 * (SessionDetailActivity) and the EEG Trend tab (PatientDetailActivity) so the
 * two never describe the same finding in different words.
 *
 * Written for a reader without neuroscience training: accurate wording full
 * of "exponent", "0.74 shift in exponent" and "TP9" read to that reader as
 * pure jargon, and a warning its reader can't follow isn't transparent. So the
 * names on screen are plain ("background steepness", "left ear sensor", "the
 * app's model doesn't match"), and the precise terms move to a "Details" line
 * rather than being deleted, so a specialist can still check them.
 *
 * Same standing rule as the rest of the card: every sentence describes the
 * recording, never the patient.
 */

/** The aperiodic exponent's on-screen name. */
const val STEEPNESS = "background steepness"

/** The Muse electrode's plain name, from its 10-20 label (ELECTRODE_NAMES).
 * An unknown label is returned as-is rather than guessed at. */
fun sensorName(channel: String): String = when (channel) {
    "TP9" -> "left ear sensor"
    "AF7" -> "left forehead sensor"
    "AF8" -> "right forehead sensor"
    "TP10" -> "right ear sensor"
    else -> channel
}

/** "left ear sensor", "left ear and right ear sensors",
 * "left ear, left forehead and right ear sensors". */
fun sensorList(channels: List<String>): String {
    val sides = channels.map { sensorName(it).removeSuffix(" sensor") }
    return when (sides.size) {
        0 -> "no sensors"
        1 -> "${sides[0]} sensor"
        else -> sides.dropLast(1).joinToString(", ") + " and " + sides.last() + " sensors"
    }
}

/** Why the loose-sensor check flagged a sensor (eeg.artifacts.ElectrodeFinding
 * .sign), in plain words. The technical name goes in the Details line. Bare
 * reasons: one sensor can trip two checks, so "a sign of a loose sensor" is
 * said once by sensorsLeftOut rather than after each. */
fun sensorSignWords(sign: String): String = when (sign) {
    "interference_lines" -> "electrical noise"
    "upside_down_spectrum" -> "more fast activity than slow"
    "no_signal" -> "no signal at all (flat)"
    "much_noisier_than_partner" -> "much noisier than the matching sensor on the other side"
    else -> sign
}

fun sensorSignTechnical(sign: String): String = when (sign) {
    "interference_lines" -> "interference lines"
    "upside_down_spectrum" -> "inverted spectrum (more high- than low-frequency power)"
    "no_signal" -> "flat or constant signal"
    "much_noisier_than_partner" -> "much noisier than its partner"
    else -> sign
}

/**
 * One warning, always in the same shape: a headline, what happened, what to
 * do, then the precise terms. [action] and [details] are optional so a
 * warning with nothing useful to add doesn't pad itself out.
 */
class PlainWarning(
    val title: String,
    val body: String,
    val action: String? = null,
    val details: String? = null,
) {
    fun toText(): CharSequence {
        val out = SpannableStringBuilder()
        appendStyled(out, title, StyleSpan(Typeface.BOLD))
        out.append("\n").append(body)
        action?.let {
            out.append("\n\n")
            appendStyled(out, "What to do: ", StyleSpan(Typeface.BOLD))
            out.append(it)
        }
        details?.let {
            out.append("\n\n")
            appendStyled(out, "Details: $it", RelativeSizeSpan(0.9f))
        }
        return out
    }

    private fun appendStyled(out: SpannableStringBuilder, text: String, span: Any) {
        val start = out.length
        out.append(text)
        out.setSpan(span, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

object EegWarnings {
    /** eeg.interpretation.eye_activity_differs tripped. Says "picked up" because the bump
     * count isn't eye-specific (a hand on the headband trips it too), and
     * "noticeably", not "a lot": the trigger sits near the no-change sessions'
     * own spread (13 reached 9.0 a minute against a line at 10). The figures
     * are the controls in docs/archive/eeg-lab-notebook.md ("Clean contact
     * restored"): +0.74 blinking, +0.58 slow sweeps. Against 0.07 that's 8-10
     * times, but no-change sessions have moved up to 0.25 (29), so "several
     * times" is the honest reading. Only 31 fit well in BOTH halves (30's
     * steady half was r² 0.901), hence the Details wording. */
    fun eyeActivity(): PlainWarning = PlainWarning(
        title = "Blinking or movement differs between the halves",
        body = "One half picked up noticeably more blinking, eye movement or head movement than " +
            "the other. In our tests, that alone changed the $STEEPNESS several times more than it " +
            "usually changes when nothing happens.",
        action = "Don't read the change in $STEEPNESS between these halves as meaningful. The " +
            "counts for each half are further down.",
        details = "In controls on this headband, blinking alone shifted the aperiodic exponent by " +
            "0.74 and slow eye sweeps by 0.58, against up to 0.25 with nothing changing (at most " +
            "0.07 when both halves fit well). The sweep session fit well in both halves " +
            "(r² ≥ 0.988), so the fit check didn't catch it.",
    )

    /** The trend list's shorter form: one line per session, no room for the
     * controls, and the session card is a tap away. */
    fun eyeActivityShort(): PlainWarning = PlainWarning(
        title = "Blinking or movement differs between the halves",
        body = "So the change in $STEEPNESS between this session's halves may come from that.",
        action = "Don't read that change as meaningful.",
    )

    fun allSensorsFlagged(): PlainWarning = PlainWarning(
        title = "Every sensor shows signs of a loose fit",
        body = "None could be left out, so the app can't tell which readings to trust. Rhythms and " +
            "the blink and eye-movement counts couldn't be checked, and every number below is unreliable.",
        action = "Re-seat the headband before the next recording: ear sensors on bare skin, hair " +
            "out of the way.",
        details = "every measurable channel (of TP9, AF7, AF8, TP10) had a sign the electrode " +
            "check would exclude it for.",
    )

    fun allSensorsFlaggedShort(): PlainWarning = PlainWarning(
        title = "Every sensor shows signs of a loose fit",
        body = "So this session's numbers are unreliable.",
    )

    /** Some sensors left out of both halves. [findings] are the excluding
     * ones; [keptChannels] what the numbers now come from. */
    fun sensorsLeftOut(findings: List<ElectrodeFinding>, keptChannels: List<String>): PlainWarning {
        val names = findings.map { it.channelName }.distinct()
        val reasons = findings.map { sensorSignWords(it.sign) }.distinct()
        // A flat sensor alone isn't evidence of a loose fit (it can be a
        // dead channel), so the loose-sensor reading is only added when some
        // other sign is present.
        val looseNote = when {
            findings.all { it.sign == "no_signal" } -> ""
            reasons.size == 1 -> ", a sign of a loose sensor"
            else -> " - signs of a loose sensor"
        }
        val one = names.size == 1
        return PlainWarning(
            title = if (one) "The ${sensorName(names[0])} was left out" else "Some sensors were left out",
            body = "The ${sensorList(names)} showed ${reasons.joinToString(" and ")}$looseNote. " +
                "${if (one) "It was" else "They were"} left out of both halves, so the numbers below " +
                "come from the ${sensorList(keptChannels)} only.",
            action = "Re-seat the headband before the next recording: ear sensors on bare skin, hair " +
                "out of the way.",
            details = "excluded " + findings.distinctBy { it.channelName to it.sign }
                .joinToString("; ") { "${it.channelName} (${sensorSignTechnical(it.sign)})" } + ".",
        )
    }

    /** A sensor much noisier than its partner: a warning only, never an
     * exclusion, because one-sided muscle or eye activity does it too. */
    fun noisierThanPartner(finding: ElectrodeFinding): PlainWarning {
        val partner = finding.partnerName?.let { sensorName(it) } ?: "the matching sensor"
        return PlainWarning(
            title = "One sensor was much noisier than its partner",
            body = "In the ${finding.half} half, the ${sensorName(finding.channelName)} was much " +
                "noisier than the $partner. That can mean a loose fit, but muscle tension or eye " +
                "movement on one side does it too, so it was kept in.",
            action = "Check that sensor's fit before the next recording.",
            details = "${finding.channelName} vs ${finding.partnerName ?: "partner"}, noisier-than-partner " +
                "check (warning only, not excluded).",
        )
    }

    /** A half whose specparam fit is below eeg.interpretation.LOW_FIT_R_SQUARED. About
     * THE MODEL, not the patient: a poor match means these numbers don't
     * describe this recording, which says nothing about how the session went.
     * [withRhythms] names the peaks too, since r² covers the whole model;
     * the trend list shows none, so it leaves them out. */
    fun lowFit(rSquared: Double, threshold: Double?, withRhythms: Boolean): PlainWarning = PlainWarning(
        title = "The app's model doesn't match this half well",
        body = "So this half's $STEEPNESS${if (withRhythms) " and rhythms" else ""} may not " +
            "describe the recording accurately.",
        action = "Treat this half's numbers as rough.",
        details = "specparam fit r² = %.3f".format(rSquared) +
            (threshold?.let { ", below the %.2f threshold.".format(it) } ?: "."),
    )
}

/**
 * Wording for the session card's before/after band-share pies (BandShares),
 * for the practitioner or, with [forPatient], for a patient reading about
 * their own session (patients see the card too, with the caveats in plain
 * words). Both versions describe and never judge:
 * nothing says whether the treatment worked. The patient version leaves out
 * the terms of art and the EEG analysis card, which patients can't open. The
 * figures are the measurements quoted on BandShares.
 */
class BandShareWords(private val forPatient: Boolean) {
    private val settle = BandShares.SETTLE_SECONDS
    private val minUsable = BandShares.MIN_USABLE_SECONDS
    private val noChange = BandShares.NO_CHANGE_POINTS

    /** "Before" and "After" only when the minute really is one; a fallback
     * minute is named for what it is. */
    fun title(isBefore: Boolean, r: BandShares.Result): String = when {
        isBefore && r.beforeSource == BandShares.BeforeSource.BASELINE -> "Before"
        isBefore -> "First minute"
        r.afterSource == BandShares.AfterSource.TREATMENT_DONE -> "After"
        else -> "Last minute"
    }

    /** Under each pie: when, which minute, and how much of it was usable. */
    fun caption(isBefore: Boolean, r: BandShares.Result): String {
        val w = if (isBefore) r.before else r.after
        val which = when {
            isBefore && r.beforeSource == BandShares.BeforeSource.BASELINE ->
                if (forPatient) "resting, before treatment" else "end of baseline"
            isBefore -> "after the sensors settled"
            r.afterSource == BandShares.AfterSource.TREATMENT_DONE ->
                if (forPatient) "end of treatment" else "before Treatment done"
            else -> "end of the recording"
        }
        return "${clock(w.startSeconds)}–${clock(w.endSeconds)}, $which\n" +
            "${w.usableSeconds} of ${w.endSeconds - w.startSeconds} s usable"
    }

    /** What the pies can't be trusted for, most important first. The
     * eye-activity one needs the EEG analysis, which only a practitioner
     * sees, so it's never shown to a patient. */
    fun warnings(r: BandShares.Result, eyeActivityDiffers: Boolean): List<PlainWarning> {
        val out = mutableListOf<PlainWarning>()
        if (r.overlapping) out += overlap(r)
        if (r.beforeSource == BandShares.BeforeSource.FIRST_MINUTE) out += noBaseline()
        if (r.afterSource == BandShares.AfterSource.LAST_MINUTE) out += noTreatmentDone()
        if (!r.overlapping) {
            if (r.before.percents == null) out += missing(isBefore = true, r)
            if (r.after.percents == null) out += missing(isBefore = false, r)
        }
        if (eyeActivityDiffers && !forPatient) out += eyeActivity()
        return out
    }

    /** The card's heading. "Before and after" only when both minutes really
     * are; "(uncorrected)" only for the practitioner, like the other
     * band-power cards - a patient has no corrected numbers to set it against. */
    fun heading(r: BandShares.Result?): String = when {
        forPatient -> "Your headband readings, split by rhythm"
        r == null -> "Band share (uncorrected)"
        r.beforeSource == BandShares.BeforeSource.BASELINE && r.afterSource == BandShares.AfterSource.TREATMENT_DONE ->
            "Band share, before and after (uncorrected)"
        else -> "Band share in two minutes (uncorrected)"
    }

    /** How to read the pies, said once under them. The no-change figure is a
     * 95th percentile (the largest move was 15), so both versions say that
     * bigger moves happen with nothing changing too. */
    fun explainer(r: BandShares.Result): PlainWarning = if (forPatient) {
        val beforeAndAfter = r.beforeSource == BandShares.BeforeSource.BASELINE &&
            r.afterSource == BandShares.AfterSource.TREATMENT_DONE
        PlainWarning(
            title = "These pies show how the headband's readings split into five rhythm types" +
                if (beforeAndAfter) ": one minute before your treatment, and one at its end."
                else ", in one minute near the start of your session and one near the end.",
            body = "They're a rough picture, not a measure of how well the treatment worked. The " +
                "slices always add up to 100%, so when one grows, the others shrink. Relaxing, closing " +
                "your eyes, getting sleepy, talking or tensing your jaw can all change the slices on " +
                "their own.",
            action = "Even with nothing changing, slices can move by about $noChange points, and now " +
                "and then more. If you'd like to talk through what you see, ask your practitioner.",
            details = "Muse SDK relative band power, averaged across the four sensors.",
        )
    } else {
        PlainWarning(
            title = "Each pie is one minute of the recording, split by rhythm band.",
            body = "Delta is almost always the biggest slice: slow activity is naturally the strongest, " +
                "whether or not anything is happening. The slices always add up to 100%, so when one " +
                "grows the others shrink. Opening or closing the eyes, dozing off, talking and jaw " +
                "tension all change the slices too.",
            action = "Compare the change column with about $noChange points: in a 25-minute test with " +
                "nothing changing, slices moved less than that in 19 of 20 comparisons, and 15 at most. " +
                "Even a bigger change doesn't show the treatment caused it.",
            details = "Muse SDK relative band power, averaged across the four sensors" +
                (if (r.comparable) "; mean of ${r.before.usableSeconds} and ${r.after.usableSeconds} " +
                    "one-second readings" else "") +
                ". The first $settle s are skipped while the sensors settle. Not corrected for the slow " +
                "background (see EEG analysis below). $noChange points is the 95th percentile of " +
                "one-minute comparisons in one 25-minute eyes-open recording with nothing changing " +
                "(largest 15); 2-4 minute eyes-closed tests gave about 6.",
        )
    }

    /** Why the two minutes overlap, by its actual cause: a Treatment done
     * tapped too soon (which the recording screen now prevents), or a
     * recording too short to hold both. */
    private fun overlap(r: BandShares.Result): PlainWarning = when {
        r.afterSource == BandShares.AfterSource.TREATMENT_DONE && forPatient -> PlainWarning(
            title = "The two minutes overlap",
            body = "They'd share the same seconds, so they aren't compared and no pies are drawn.",
        )
        r.afterSource == BandShares.AfterSource.TREATMENT_DONE -> PlainWarning(
            title = "Treatment done came too soon",
            body = "It was tapped less than a minute after the first pie's minute ends, so the two " +
                "minutes would overlap. They aren't compared, and no pies are drawn.",
        )
        else -> PlainWarning(
            title = "The recording is too short to compare",
            body = "Its two minutes would overlap, so they aren't two separate moments, and " +
                "no pies are drawn.",
            action = when {
                forPatient -> null
                r.beforeSource == BandShares.BeforeSource.BASELINE ->
                    "Pies need the recording to run at least a minute past the baseline."
                else -> "Pies need a recording of at least 2½ minutes."
            },
        )
    }

    private fun noBaseline() = if (forPatient) {
        PlainWarning(
            title = "This isn't a before-and-after comparison",
            body = "The first pie is from near the start of your session, when the treatment may " +
                "already have begun.",
        )
    } else {
        PlainWarning(
            title = "No baseline was marked",
            body = "So the first pie is the first minute after the sensors settled. Treatment may " +
                "already have started by then, so the two pies aren't a before-and-after comparison.",
            action = "Next time, tap \"Baseline done — start treatment\" before starting treatment.",
            details = "first pie: $settle-${settle + BandShares.WINDOW_SECONDS} s; the first $settle s " +
                "are skipped while the sensors settle.",
        )
    }

    private fun noTreatmentDone() = if (forPatient) {
        PlainWarning(
            title = "The second pie may include the end of your visit",
            body = "It's the last minute of the recording, which may include wrapping up: needles " +
                "coming out, moving and talking. Those change the slices on their own.",
        )
    } else {
        PlainWarning(
            title = "Treatment done wasn't tapped",
            body = "So the second pie is the last minute of the recording, which likely includes " +
                "wrapping up: needles coming out, movement and talking. In our tests, blinking and eye " +
                "movement alone moved the delta slice by 17 to 42 points.",
            action = "Next time, tap \"Treatment done\" before taking the needles out.",
        )
    }

    private fun missing(isBefore: Boolean, r: BandShares.Result): PlainWarning {
        val w = if (isBefore) r.before else r.after
        val length = w.endSeconds - w.startSeconds
        val which = if (isBefore) "first" else "second"
        val baselineTooShort = isBefore && r.beforeSource == BandShares.BeforeSource.BASELINE &&
            length < minUsable
        return when {
            forPatient && baselineTooShort -> PlainWarning(
                title = "There's no first pie",
                body = "The resting period before treatment was too short to measure.",
            )
            forPatient -> PlainWarning(
                title = "There's no $which pie",
                body = "There weren't enough clean readings from the headband in that minute to draw it.",
            )
            baselineTooShort -> PlainWarning(
                title = "The baseline was too short to use",
                body = "After skipping its first $settle seconds while the sensors settle, less than " +
                    "$minUsable seconds of it is left, so there's no first pie.",
                action = "Aim for a baseline of 2 to 3 minutes.",
                details = "baseline marked at ${clock(r.before.endSeconds)}.",
            )
            else -> PlainWarning(
                title = "Not enough clean signal for the $which pie",
                body = "Only ${w.usableSeconds} of $length seconds had usable readings (at least " +
                    "$minUsable are needed), so this pie is left out. The headband may have been off, " +
                    "loose, or disconnected.",
                action = "Check the headband's fit before the next recording.",
            )
        }
    }

    /** eeg.interpretation.eye_activity_differs from the EEG analysis. Its halves split at
     * the baseline mark (the midpoint without one), so each pie sits inside
     * one of them. */
    private fun eyeActivity() = PlainWarning(
        title = "Blinking or movement differs between the halves",
        body = "The EEG analysis below found noticeably more blinking, eye or head movement in one " +
            "half of this session. In our tests, blinking and eye movement alone moved the delta slice " +
            "by 17 to 42 points.",
        action = "Don't read a change in the delta or beta slice here as meaningful.",
        details = "the analysis's eye-activity check over its two halves, which split at the baseline " +
            "mark (or the midpoint without one).",
    )

    private fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
