package ai.meridian.tcmsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The windows and arithmetic behind the before/after band pies (BandShares),
 * which the session card and the CSV export both show.
 */
class BandSharesTest {
    private val calm = listOf(0.40, 0.15, 0.25, 0.14, 0.06)

    // One recorded second as the recorder saves it: the five relative bands
    // plus an absolute one, which the pies must ignore.
    private fun tick(second: Int, shares: List<Double>) =
        BAND_ORDER.mapIndexed { i, band -> SessionSample(second, band, shares[i]) } +
            SessionSample(second, "alpha_absolute", 0.9)

    private fun steady(seconds: IntRange, shares: List<Double> = calm) = seconds.flatMap { tick(it, shares) }

    private fun assertPercents(expected: List<Double>, actual: List<Double>?) {
        assertEquals(expected.size, actual!!.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 1e-9) }
    }

    @Test
    fun baselineAndTreatmentDoneSetBothMinutes() {
        val r = BandShares.compute(steady(0..1599), baselineDurationSeconds = 120, treatmentEndSeconds = 1500)!!
        assertEquals(BandShares.Window(60, 120, 60, calm.map { it * 100 }).copy(percents = null), r.before.copy(percents = null))
        assertEquals(1440, r.after.startSeconds)
        assertEquals(1500, r.after.endSeconds)
        assertEquals(BandShares.BeforeSource.BASELINE, r.beforeSource)
        assertEquals(BandShares.AfterSource.TREATMENT_DONE, r.afterSource)
        assertTrue(r.comparable)
        assertPercents(calm.map { it * 100 }, r.before.percents)
        assertEquals(listOf(0, 0, 0, 0, 0), r.pointChanges())
    }

    @Test
    fun withoutABaselineTheFirstMinuteAfterSettlingIsUsed() {
        val r = BandShares.compute(steady(0..599), baselineDurationSeconds = null, treatmentEndSeconds = 500)!!
        assertEquals(BandShares.BeforeSource.FIRST_MINUTE, r.beforeSource)
        assertEquals(BandShares.SETTLE_SECONDS, r.before.startSeconds)
        assertEquals(BandShares.SETTLE_SECONDS + 60, r.before.endSeconds)
    }

    @Test
    fun withoutTreatmentDoneTheLastMinuteIsUsed() {
        val r = BandShares.compute(steady(0..299), baselineDurationSeconds = 120, treatmentEndSeconds = null)!!
        assertEquals(BandShares.AfterSource.LAST_MINUTE, r.afterSource)
        assertEquals(240, r.after.startSeconds)
        assertEquals(300, r.after.endSeconds)
        assertEquals(60, r.after.usableSeconds)
    }

    @Test
    fun aBaselineInsideTheSettlePeriodIsTooShortNotReplaced() {
        val r = BandShares.compute(steady(0..599), baselineDurationSeconds = 50, treatmentEndSeconds = 500)!!
        assertEquals(BandShares.BeforeSource.BASELINE, r.beforeSource)
        assertEquals(30, r.before.startSeconds)
        assertEquals(50, r.before.endSeconds)
        assertEquals(20, r.before.usableSeconds)
        assertNull(r.before.percents)
        assertFalse(r.comparable)
        assertNull(r.pointChanges())
    }

    @Test
    fun aBaselineInsideTheSettlePeriodGivesAnEmptyWindow() {
        val r = BandShares.compute(steady(0..599), baselineDurationSeconds = 10, treatmentEndSeconds = 500)!!
        assertEquals(10, r.before.startSeconds)
        assertEquals(10, r.before.endSeconds)
        assertEquals(0, r.before.usableSeconds)
    }

    @Test
    fun secondsMissingABandDontCount() {
        val samples = steady(0..299).map {
            if (it.band == "alpha" && it.offsetSeconds in 60..79) it.copy(value = NO_SIGNAL_VALUE) else it
        } + tick(90, listOf(0.4, 0.15, Double.NaN, 0.14, 0.06))
        val r = BandShares.compute(samples, baselineDurationSeconds = 120, treatmentEndSeconds = null)!!
        // 60 seconds minus 20 no-signal ones; second 90's NaN tick is averaged
        // with its good twin, so that second still counts.
        assertEquals(40, r.before.usableSeconds)
    }

    @Test
    fun tooFewUsableSecondsLeaveThePieOut() {
        val samples = steady(0..299).map {
            if (it.offsetSeconds in 60..95) it.copy(value = NO_SIGNAL_VALUE) else it
        }
        val r = BandShares.compute(samples, baselineDurationSeconds = 120, treatmentEndSeconds = null)!!
        assertEquals(24, r.before.usableSeconds)
        assertNull(r.before.percents)
        assertFalse(r.comparable)
    }

    @Test
    fun eachSecondIsRescaledThenTheSecondsAveraged() {
        val low = listOf(0.2, 0.2, 0.2, 0.2, 0.1) // adds up to 0.9
        val high = listOf(0.5, 0.2, 0.1, 0.1, 0.1)
        val samples = steady(0..59, calm) + steady(60..89, low) + steady(90..119, high) + steady(120..299, calm)
        val r = BandShares.compute(samples, baselineDurationSeconds = 120, treatmentEndSeconds = null)!!
        val expected = low.indices.map { 100 * (low[it] / 0.9 + high[it]) / 2 }
        assertPercents(expected, r.before.percents)
        assertEquals(100.0, r.before.percents!!.sum(), 1e-9)
    }

    @Test
    fun twoTicksInOneSecondCountOnce() {
        val samples = steady(0..299) + tick(70, listOf(0.2, 0.15, 0.45, 0.14, 0.06))
        val r = BandShares.compute(samples, baselineDurationSeconds = 120, treatmentEndSeconds = null)!!
        assertEquals(60, r.before.usableSeconds)
        // Second 70 is the average of its two ticks: alpha (0.25 + 0.45) / 2.
        val alpha = (59 * 0.25 + 0.35) / 60 * 100
        assertEquals(alpha, r.before.percents!![BAND_ORDER.indexOf("alpha")], 1e-9)
    }

    @Test
    fun aSessionTooShortForTwoSeparateMinutesIsNotCompared() {
        val r = BandShares.compute(steady(0..99), baselineDurationSeconds = null, treatmentEndSeconds = null)!!
        assertTrue(r.overlapping)
        assertFalse(r.comparable)
        assertNull(r.pointChanges())
    }

    @Test
    fun onlyAbsoluteBandsMeansNothingToShow() {
        val samples = (0..299).map { SessionSample(it, "alpha_absolute", 0.9) }
        assertNull(BandShares.compute(samples, 120, null))
        assertNull(BandShares.compute(emptyList(), 120, null))
    }

    @Test
    fun theEarliestBaselineLeavesAFullBeforeMinute() {
        assertEquals(90, BandShares.MIN_BASELINE_SECONDS)
        val r = BandShares.compute(steady(0..599), BandShares.MIN_BASELINE_SECONDS, 500)!!
        assertEquals(BandShares.SETTLE_SECONDS, r.before.startSeconds)
        assertEquals(BandShares.WINDOW_SECONDS, r.before.endSeconds - r.before.startSeconds)
        assertEquals(60, r.before.usableSeconds)
        // Treatment done's earliest follows from it: 2:30 either way.
        assertEquals(150, BandShares.earliestTreatmentEndSeconds(BandShares.MIN_BASELINE_SECONDS))
        assertEquals(150, BandShares.earliestTreatmentEndSeconds(null))
    }

    @Test
    fun treatmentDoneAtTheEarliestLeavesTwoSeparateMinutes() {
        val samples = steady(0..1599)
        val withBaseline = BandShares.earliestTreatmentEndSeconds(120)
        assertEquals(180, withBaseline)
        assertFalse(BandShares.compute(samples, 120, withBaseline)!!.overlapping)
        assertTrue(BandShares.compute(samples, 120, withBaseline - 1)!!.overlapping)
        val noBaseline = BandShares.earliestTreatmentEndSeconds(null)
        assertEquals(150, noBaseline)
        assertFalse(BandShares.compute(samples, null, noBaseline)!!.overlapping)
        assertTrue(BandShares.compute(samples, null, noBaseline - 1)!!.overlapping)
    }

    @Test
    fun wholePercentsAlwaysAddUpTo100() {
        assertEquals(listOf(33, 33, 34, 0, 0), BandShares.wholePercents(listOf(33.3, 33.3, 33.4, 0.0, 0.0)))
        assertEquals(listOf(20, 20, 20, 20, 20), BandShares.wholePercents(listOf(20.0, 20.0, 20.0, 20.0, 20.0)))
        val uneven = listOf(19.5, 19.5, 20.5, 20.5, 20.0)
        assertEquals(100, BandShares.wholePercents(uneven).sum())
    }

    @Test
    fun pointChangesUseTheRoundedNumbers() {
        val r = BandShares.Result(
            before = BandShares.Window(60, 120, 60, listOf(24.4, 30.6, 15.0, 20.0, 10.0)),
            after = BandShares.Window(1440, 1500, 60, listOf(17.6, 30.4, 22.0, 20.0, 10.0)),
            beforeSource = BandShares.BeforeSource.BASELINE,
            afterSource = BandShares.AfterSource.TREATMENT_DONE,
        )
        // Shown as 24/31/15/20/10 and 18/30/22/20/10.
        assertEquals(listOf(-6, -1, 7, 0, 0), r.pointChanges())
    }
}
