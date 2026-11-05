package ai.meridian.tcmsession

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CSV export's before/after Band Share block and the two marks in its
 * header: the same numbers the session card's pies show (BandShares). */
class ExportUtilTest {
    private val calm = listOf(0.40, 0.15, 0.25, 0.14, 0.06)
    private val later = listOf(0.45, 0.17, 0.18, 0.14, 0.06)

    private fun steady(seconds: IntRange, shares: List<Double>) =
        seconds.flatMap { s -> BAND_ORDER.mapIndexed { i, band -> SessionSample(s, band, shares[i]) } }

    private fun session(samples: List<SessionSample>, baseline: Int?, treatmentEnd: Int?) = SessionDetail(
        id = 9,
        modality = "Acupuncture",
        startedAt = "2026-09-14T10:00:00Z",
        endedAt = "2026-09-14T10:30:00Z",
        practitionerNote = null,
        patientDozed = null,
        alertnessRating = null,
        baselineDurationSeconds = baseline,
        treatmentEndSeconds = treatmentEnd,
        markers = listOf(SessionMarker(120, "Baseline ended — treatment started")),
        samples = samples,
    )

    /** The lines under a block's title, up to the blank line that ends it. */
    private fun block(csv: String, title: String): List<String> =
        csv.lines().dropWhile { it != title }.drop(1).takeWhile { it.isNotEmpty() }

    @Test
    fun headerCarriesBothMarks() {
        val csv = ExportUtil.sessionCsv(session(steady(0..1599, calm), 120, 1500), "Test Patient")
        assertTrue(csv.contains("Baseline Ended (s),120\n"))
        assertTrue(csv.contains("Treatment Done (s),1500\n"))
    }

    @Test
    fun unmarkedIsBlankNotZero() {
        val csv = ExportUtil.sessionCsv(session(steady(0..299, calm), null, null), "Test Patient")
        assertTrue(csv.contains("Baseline Ended (s),\n"))
        assertTrue(csv.contains("Treatment Done (s),\n"))
    }

    @Test
    fun bandShareRowsAreThePiesNumbers() {
        val samples = steady(0..899, calm) + steady(900..1599, later)
        val csv = ExportUtil.sessionCsv(session(samples, 120, 1500), "Test Patient")
        assertEquals(
            listOf(
                "Window,Source,Start Seconds,End Seconds,Usable Seconds,Delta %,Theta %,Alpha %,Beta %,Gamma %",
                "Before,End of baseline,60,120,60,40,15,25,14,6",
                "After,Minute before Treatment done,1440,1500,60,45,17,18,14,6",
                "Change (points),,,,,+5,+2,-7,0,0",
            ),
            block(csv, "Band Share"),
        )
    }

    @Test
    fun fallbacksAreExplainedInNoteRows() {
        val csv = ExportUtil.sessionCsv(session(steady(0..299, calm), null, null), "Test Patient")
        val rows = block(csv, "Band Share")
        assertTrue(rows[1].startsWith("Before,First minute after the sensors settled,30,90,60,"))
        assertTrue(rows[2].startsWith("After,Last minute of the recording,240,300,60,"))
        // Quoted: the sentences contain commas.
        assertTrue(rows.any { it.startsWith("Note,\"No baseline was marked") })
        assertTrue(rows.any { it.startsWith("Note,\"The end of treatment wasn't marked") })
        // One wording for every reader, patients included: no button names.
        assertTrue(rows.filter { it.startsWith("Note,") }.none { it.contains("tapped") || it.contains("Treatment done") })
    }

    @Test
    fun aMinuteWithoutEnoughSignalHasBlankPercents() {
        val samples = steady(0..299, calm).map {
            if (it.offsetSeconds in 60..95) it.copy(value = NO_SIGNAL_VALUE) else it
        }
        val rows = block(ExportUtil.sessionCsv(session(samples, 120, null), "Test Patient"), "Band Share")
        assertEquals("Before,End of baseline,60,120,24,,,,,", rows[1])
        assertTrue(rows.none { it.startsWith("Change") })
        assertTrue(rows.any { it.startsWith("Note,\"Before: not enough clean signal (24 of 60 seconds usable") })
    }

    @Test
    fun overlappingMinutesHaveBlankPercentsLikeTheCard() {
        val rows = block(ExportUtil.sessionCsv(session(steady(0..99, calm), null, null), "Test Patient"), "Band Share")
        assertEquals("Before,First minute after the sensors settled,30,90,60,,,,,", rows[1])
        assertEquals("After,Last minute of the recording,40,100,60,,,,,", rows[2])
        assertTrue(rows.none { it.startsWith("Change") })
    }

    @Test
    fun blocksComeInOrder() {
        val csv = ExportUtil.sessionCsv(session(steady(0..299, calm), 120, null), "Test Patient")
        assertTrue(csv.indexOf("\nMarkers\n") < csv.indexOf("\nBand Share\n"))
        assertTrue(csv.indexOf("\nBand Share\n") < csv.indexOf("\nSamples\n"))
    }
}
