package ai.meridian.tcmsession

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The before/after band-share calculation behind the session card's two pies
 * and the CSV export's Band Share block. One function for both, so the screen
 * and the file can't disagree.
 *
 * What it measures: the Muse SDK's per-second RELATIVE band power (the five
 * [BAND_ORDER] shares in SessionSample, averaged across the four sensors),
 * which adds up to 100% by definition. That makes a pie an honest picture of
 * it, and every session already has it, so old sessions get pies too. It is
 * NOT corrected for the slow background (the Analyze EEG card is), and it
 * moves with much more than treatment. Measured on the research sessions
 * (one-minute windows, one wearer):
 *  - nothing changing, 2-4 minute recordings: the biggest slice moved a
 *    median 3.4 points, under 6.2 in 19 of 20 comparisons, 7.6 at most;
 *  - blinking or moving the eyes: delta +17 to +42 points, beta -12 to -36;
 *  - eyes closed vs open: alpha 11-20 points bigger with the eyes closed.
 * Whatever shows these numbers has to say so.
 */
object BandShares {
    /** Each pie covers one minute. */
    const val WINDOW_SECONDS = 60

    /** Skipped at the start while the sensors settle. Measured: a
     * 5-second stretch in the first 10 s differed from the same session's
     * 30-90 s average by a median 10-11 points, and one at 10-30 s still by
     * about 7, against about 4 for an ordinary stretch later on. */
    const val SETTLE_SECONDS = 30

    /** Below this many usable seconds a minute's average is too noisy to show. */
    const val MIN_USABLE_SECONDS = 30

    /** The earliest the baseline can be marked: one full minute after the
     * sensors settle, so the "before" pie always has a whole minute to draw
     * on. At
     * exactly 60 s only 30 s would be left after settling, and one missing
     * second would drop the pie. SessionRecorder ignores an earlier tap and
     * the recording screen counts down to it. */
    const val MIN_BASELINE_SECONDS = SETTLE_SECONDS + WINDOW_SECONDS

    /** How far a slice moves with nothing changing: the card's "compare
     * with" figure. Measured on one 25-minute recording with
     * nothing changing (eyes open, one wearer): in 19 of 20 comparisons of
     * two minutes 3 to 25 minutes apart, the biggest slice moved at most
     * 10.2-10.6 points (the largest move was 15), mostly delta and beta. The
     * 2-4 minute eyes-closed tests gave about 6. The larger figure is used:
     * understating it would make ordinary drift look like a change. Not yet
     * measured over 25 minutes with the eyes closed, as in a treatment. */
    const val NO_CHANGE_POINTS = 10

    /** The earliest Treatment done that leaves two separate minutes to
     * compare: a full minute after the "before" minute ends (the baseline
     * mark, or the end of the first minute when there's no baseline). Any
     * earlier and the minutes would overlap, costing the session its pies;
     * on a real treatment that can only be a slip, so SessionRecorder
     * ignores such a tap and the recording screen holds the button until
     * then. */
    fun earliestTreatmentEndSeconds(baselineDurationSeconds: Int?): Int =
        (baselineDurationSeconds ?: (SETTLE_SECONDS + WINDOW_SECONDS)) + WINDOW_SECONDS

    /** Where the "before" minute came from. */
    enum class BeforeSource {
        /** The minute ending at the baseline mark: before treatment. */
        BASELINE,
        /** No baseline marked: the first minute after the sensors settle.
         * Treatment may already have started, so it's not "before treatment". */
        FIRST_MINUTE,
    }

    /** Where the "after" minute came from. */
    enum class AfterSource {
        /** The minute ending at the Treatment done tap. */
        TREATMENT_DONE,
        /** Treatment done wasn't tapped: the recording's last minute, which
         * likely includes the needles coming out, movement and talking. */
        LAST_MINUTE,
    }

    /** One minute, [startSeconds, endSeconds) in session offsets. [percents]
     * follows BAND_ORDER and adds up to 100, or is null when fewer than
     * MIN_USABLE_SECONDS seconds were usable. */
    data class Window(
        val startSeconds: Int,
        val endSeconds: Int,
        val usableSeconds: Int,
        val percents: List<Double>?,
    )

    data class Result(
        val before: Window,
        val after: Window,
        val beforeSource: BeforeSource,
        val afterSource: AfterSource,
    ) {
        /** The two minutes share seconds (a session too short to hold both),
         * so they aren't two separate moments and mustn't be compared. */
        val overlapping: Boolean get() = after.startSeconds < before.endSeconds

        /** Both pies can be drawn and compared. */
        val comparable: Boolean get() = !overlapping && before.percents != null && after.percents != null

        /** After minus before, in whole points, worked out from the same
         * rounded numbers the pies show, so the table adds up on screen
         * (24% → 18% is -6, never -7 from unrounded values). Null unless
         * [comparable]. */
        fun pointChanges(): List<Int>? {
            if (!comparable) return null
            val b = wholePercents(before.percents!!)
            val a = wholePercents(after.percents!!)
            return a.indices.map { a[it] - b[it] }
        }
    }

    /** Null when the session has no relative band samples at all. */
    fun compute(samples: List<SessionSample>, baselineDurationSeconds: Int?, treatmentEndSeconds: Int?): Result? {
        val relative = samples.filter { it.band in BAND_ORDER }
        if (relative.isEmpty()) return null
        val perSecond = usableSeconds(relative)

        val beforeSource: BeforeSource
        val beforeStart: Int
        val beforeEnd: Int
        if (baselineDurationSeconds != null) {
            // A baseline that barely outlasts the settle period leaves a short
            // (or empty) window. That reads as too few usable seconds, rather
            // than being quietly swapped for a minute of treatment.
            beforeSource = BeforeSource.BASELINE
            beforeEnd = baselineDurationSeconds
            beforeStart = min(max(SETTLE_SECONDS, beforeEnd - WINDOW_SECONDS), beforeEnd)
        } else {
            beforeSource = BeforeSource.FIRST_MINUTE
            beforeStart = SETTLE_SECONDS
            beforeEnd = SETTLE_SECONDS + WINDOW_SECONDS
        }

        val afterSource = if (treatmentEndSeconds != null) AfterSource.TREATMENT_DONE else AfterSource.LAST_MINUTE
        val afterEnd = treatmentEndSeconds ?: (relative.maxOf { it.offsetSeconds } + 1)
        val afterStart = max(0, afterEnd - WINDOW_SECONDS)

        return Result(
            before = window(perSecond, beforeStart, beforeEnd),
            after = window(perSecond, afterStart, afterEnd),
            beforeSource = beforeSource,
            afterSource = afterSource,
        )
    }

    /** [percents] as whole numbers that still add up to exactly 100 (largest
     * remainder), so a pie never reads 99% or 101% in total. */
    fun wholePercents(percents: List<Double>): List<Int> {
        val result = percents.map { floor(it).toInt() }.toMutableList()
        var left = (100 - result.sum()).coerceAtLeast(0)
        for (i in percents.indices.sortedByDescending { percents[it] - floor(percents[it]) }) {
            if (left == 0) break
            result[i]++
            left--
        }
        return result
    }

    /** Offset -> that second's five shares, rescaled to add up to 1, for every
     * second in which all five bands have a real reading. A second can hold
     * two ticks (the once-a-second timer drifts, so two can land on the same
     * whole second); their readings are averaged band by band, and the second
     * still counts once. */
    private fun usableSeconds(relative: List<SessionSample>): Map<Int, DoubleArray> =
        relative.groupBy { it.offsetSeconds }.mapNotNull { (offset, inSecond) ->
            val means = BAND_ORDER.map { band ->
                inSecond.filter { it.band == band && isReading(it.value) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { readings -> readings.sumOf { it.value } / readings.size }
            }
            if (means.any { it == null }) return@mapNotNull null
            val total = means.sumOf { it!! }
            if (total <= 0.0) null else offset to DoubleArray(BAND_ORDER.size) { means[it]!! / total }
        }.toMap()

    /** A real reading: not the no-signal marker, not NaN or infinite. */
    private fun isReading(value: Double): Boolean = value != NO_SIGNAL_VALUE && value.isFinite() && value >= 0.0

    /** Every usable second in the window counts equally. Each already adds up
     * to 1, so their average does too and needs no second rescale. */
    private fun window(perSecond: Map<Int, DoubleArray>, start: Int, end: Int): Window {
        val picked = (start until end).mapNotNull { perSecond[it] }
        if (picked.size < MIN_USABLE_SECONDS) return Window(start, end, picked.size, null)
        return Window(start, end, picked.size, BAND_ORDER.indices.map { i -> 100.0 * picked.sumOf { it[i] } / picked.size })
    }
}
