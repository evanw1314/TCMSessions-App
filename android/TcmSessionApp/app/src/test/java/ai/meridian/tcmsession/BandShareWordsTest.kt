package ai.meridian.tcmsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The before/after pies' wording (BandShareWords). The patient version must
 * never point at things a patient can't see or use (the practitioner-only EEG
 * analysis card, the recording screen's buttons), and fallback minutes must
 * never be called "Before" or "After".
 */
class BandShareWordsTest {
    private val shares = listOf(40.0, 15.0, 25.0, 14.0, 6.0)
    private val practitioner = BandShareWords(forPatient = false)
    private val patient = BandShareWords(forPatient = true)

    private fun result(
        before: BandShares.BeforeSource,
        after: BandShares.AfterSource,
        beforeWindow: BandShares.Window = BandShares.Window(60, 120, 60, shares),
        afterWindow: BandShares.Window = BandShares.Window(1440, 1500, 55, shares),
    ) = BandShares.Result(beforeWindow, afterWindow, before, after)

    /** Every word the card could show for [r]. */
    private fun allText(words: BandShareWords, r: BandShares.Result, eyeActivity: Boolean): String {
        val warnings = words.warnings(r, eyeActivity) + words.explainer(r)
        return (warnings.flatMap { listOfNotNull(it.title, it.body, it.action, it.details) } +
            listOf(true, false).flatMap { listOf(words.title(it, r), words.caption(it, r)) })
            .joinToString("\n")
    }

    private val everyCase: List<BandShares.Result> =
        BandShares.BeforeSource.values().flatMap { b ->
            BandShares.AfterSource.values().flatMap { a ->
                listOf(
                    result(b, a),
                    result(b, a, beforeWindow = BandShares.Window(30, 50, 20, null)),
                    result(b, a, beforeWindow = BandShares.Window(60, 120, 12, null)),
                    result(b, a, afterWindow = BandShares.Window(1440, 1500, 3, null)),
                    result(b, a, afterWindow = BandShares.Window(40, 100, 60, shares)), // overlapping
                )
            }
        }

    @Test
    fun thePatientVersionNeverPointsAtPractitionerOnlyThings() {
        everyCase.forEach { r ->
            val text = allText(patient, r, eyeActivity = true)
            assertFalse(text, text.contains("EEG analysis"))
            assertFalse(text, text.contains("baseline", ignoreCase = true))
            assertFalse(text, text.contains("Treatment done"))
        }
    }

    @Test
    fun onlyThePractitionerSeesTheEyeActivityWarning() {
        val r = result(BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE)
        assertTrue(practitioner.warnings(r, eyeActivityDiffers = true).any { it.title.startsWith("Blinking") })
        assertTrue(patient.warnings(r, eyeActivityDiffers = true).isEmpty())
        assertTrue(practitioner.warnings(r, eyeActivityDiffers = false).isEmpty())
    }

    @Test
    fun fallbackMinutesAreNamedForWhatTheyAre() {
        val r = result(BandShares.BeforeSource.FIRST_MINUTE, BandShares.AfterSource.LAST_MINUTE)
        listOf(practitioner, patient).forEach { words ->
            assertEquals("First minute", words.title(true, r))
            assertEquals("Last minute", words.title(false, r))
            assertEquals(2, words.warnings(r, eyeActivityDiffers = false).size)
        }
        val real = result(BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE)
        assertEquals("Before", practitioner.title(true, real))
        assertEquals("After", practitioner.title(false, real))
    }

    @Test
    fun captionsSayWhenAndHowMuchWasUsable() {
        val r = result(BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE)
        assertEquals("1:00–2:00, end of baseline\n60 of 60 s usable", practitioner.caption(true, r))
        assertEquals("24:00–25:00, end of treatment\n55 of 60 s usable", patient.caption(false, r))
    }

    @Test
    fun bothVersionsGiveTheNoChangeFigure() {
        val r = result(BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE)
        val figure = "about ${BandShares.NO_CHANGE_POINTS} points"
        assertTrue(practitioner.explainer(r).action!!.contains(figure))
        assertTrue(patient.explainer(r).action!!.contains(figure))
    }

    @Test
    fun aTooShortBaselineIsNamedAsSuch() {
        val r = result(
            BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE,
            beforeWindow = BandShares.Window(30, 50, 20, null),
        )
        assertEquals(listOf("The baseline was too short to use"), practitioner.warnings(r, false).map { it.title })
        assertEquals(listOf("There's no first pie"), patient.warnings(r, false).map { it.title })
    }

    @Test
    fun theHeadingOnlySaysBeforeAndAfterWhenItIs() {
        val real = result(BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE)
        assertEquals("Band share, before and after (uncorrected)", practitioner.heading(real))
        everyCase.filter {
            it.beforeSource != BandShares.BeforeSource.BASELINE || it.afterSource != BandShares.AfterSource.TREATMENT_DONE
        }.forEach { assertFalse(practitioner.heading(it).contains("before and after")) }
        (everyCase.map { patient.heading(it) } + patient.heading(null)).forEach {
            assertFalse(it, it.contains("uncorrected"))
            assertFalse(it, it.contains("before and after"))
        }
    }

    @Test
    fun anOverlapIsExplainedByItsCause() {
        val overlapping = BandShares.Window(100, 160, 60, shares)
        val tooSoon = result(BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE, afterWindow = overlapping)
        assertEquals("Treatment done came too soon", practitioner.warnings(tooSoon, false).first().title)
        assertEquals("The two minutes overlap", patient.warnings(tooSoon, false).first().title)
        val short = result(BandShares.BeforeSource.BASELINE, BandShares.AfterSource.LAST_MINUTE, afterWindow = overlapping)
        val warning = practitioner.warnings(short, false).first()
        assertEquals("The recording is too short to compare", warning.title)
        assertTrue(warning.action!!.contains("past the baseline"))
    }

    @Test
    fun readingCountsAreOnlyGivenWhenBothPiesAreDrawn() {
        val oneMissing = result(
            BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE,
            afterWindow = BandShares.Window(1440, 1500, 3, null),
        )
        assertFalse(practitioner.explainer(oneMissing).details!!.contains("one-second readings"))
        val both = result(BandShares.BeforeSource.BASELINE, BandShares.AfterSource.TREATMENT_DONE)
        assertTrue(practitioner.explainer(both).details!!.contains("mean of 60 and 55 one-second readings"))
    }
}
