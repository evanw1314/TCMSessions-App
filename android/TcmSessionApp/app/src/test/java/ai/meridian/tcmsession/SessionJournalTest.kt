package ai.meridian.tcmsession

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * What the recovery path depends on: a recording written through
 * SessionJournal reads back as exactly what SessionRecorder held in memory,
 * and its raw EEG packs to the same bytes the normal upload sends.
 */
class SessionJournalTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("journal-test").toFile()
        SessionJournal.resetForTests()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private val meta = SessionJournal.Meta(
        patientId = 7,
        patientName = "Test Patient",
        practitionerId = 3,
        headbandDeviceId = null,
        modality = "Acupuncture",
        startedAtIso = "2026-09-11T10:00:00Z",
        clientSessionId = "3f2b8c1e-5d4a-4c7b-9e0f-1a2b3c4d5e6f",
        practitionerNote = "Needles at LI4, patient said 头痛减轻了",
    )

    // Realistic Muse timestamps (microseconds since the epoch, larger than
    // an Int) and values, including NaN for a bad-contact sample.
    private fun rawRows(count: Int, firstUs: Long = 1_757_584_800_000_000L): List<DoubleArray> =
        (0 until count).map { i ->
            doubleArrayOf(
                (firstUs + i * 3906L).toDouble(),
                800.0 + i * 0.25, if (i % 50 == 0) Double.NaN else 812.5 - i,
                -3.125 * i, 1650.123456789,
            )
        }

    private fun SessionJournal.appendRows(rows: List<DoubleArray>) =
        rows.forEach { appendEeg(it[0].toLong(), it[1], it[2], it[3], it[4]) }

    private fun tick(second: Int) = BAND_ORDER.map { SessionSample(second, it, 0.1 * second) } +
        SessionSample(second, "alpha_absolute", NO_SIGNAL_VALUE)

    @Test
    fun packStoredRowsMatchesPack() {
        val rows = rawRows(1000)
        val stored = java.nio.ByteBuffer.allocate(rows.size * 24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        rows.forEach { r ->
            stored.putLong(r[0].toLong())
            (1..4).forEach { stored.putFloat(r[it].toFloat()) }
        }
        assertArrayEquals(EegBinaryWriter.pack(rows), EegBinaryWriter.packStoredRows(stored.array(), 4))
    }

    @Test
    fun recordingReadsBackExactly() {
        val journal = SessionJournal.createIn(root, meta, 1234L)!!
        val rows = rawRows(600)
        journal.appendRows(rows.subList(0, 256))
        journal.appendTick(tick(0))
        journal.appendRows(rows.subList(256, 512))
        journal.appendTick(tick(1))
        val withMarkers = meta.copy(
            baselineDurationSeconds = 1,
            treatmentEndSeconds = 1,
            markers = listOf(SessionMarker(1, "Baseline ended — treatment started"), SessionMarker(1, "needles in, 合谷")),
        )
        journal.writeMeta(withMarkers)
        journal.appendRows(rows.subList(512, 600))
        journal.flushEeg()
        journal.close()
        journal.awaitClosed()

        val found = SessionJournal.orphansIn(root)
        assertEquals(1, found.size)
        val read = found[0]
        assertEquals(withMarkers, read.readMeta())
        assertEquals(tick(0) + tick(1), read.readSamples())
        assertArrayEquals(EegBinaryWriter.pack(rows), read.packRawEeg())
    }

    @Test
    fun partialWritesFromADyingAppAreIgnored() {
        val journal = SessionJournal.createIn(root, meta, 1L)!!
        val rows = rawRows(100)
        journal.appendRows(rows)
        journal.appendTick(tick(0))
        journal.close()
        journal.awaitClosed()
        // The process died mid-write: half a raw row, half a sample line.
        File(journal.dir, "eeg.bin").appendBytes(ByteArray(10) { 1 })
        File(journal.dir, "samples.csv").appendText("1,alp")

        val read = SessionJournal.orphansIn(root).single()
        assertEquals(tick(0), read.readSamples())
        assertArrayEquals(EegBinaryWriter.pack(rows), read.packRawEeg())
    }

    @Test
    fun liveAndDeletedJournalsAreNotOrphans() {
        val live = SessionJournal.createIn(root, meta, 1L)!!
        val done = SessionJournal.createIn(root, meta, 2L)!!
        done.appendTick(tick(0))
        done.delete()
        // Gone from view at once, before its queued writes have even run.
        assertEquals(emptyList<String>(), SessionJournal.orphansIn(root).map { it.dir.name })
        done.awaitClosed()
        assertFalse(done.dir.exists())
        // A live journal can't be deleted by a recovery scan either.
        SessionJournal.orphansIn(root).forEach { it.deleteNow() }
        assertTrue(live.dir.exists())
        live.close()
        live.awaitClosed()
        // Closed (its recorder is done with it): now it's offered.
        assertEquals(listOf(live.dir.name), SessionJournal.orphansIn(root).map { it.dir.name })
    }

    @Test
    fun deleteNowLeavesALiveJournalAlone() {
        val live = SessionJournal.createIn(root, meta, 1L)!!
        live.appendTick(tick(0))
        // A stale handle to the same directory, as a recovery scan taken just
        // before the recording started would hold.
        val stale = SessionJournal.orphansIn(root.also { live.close(); live.awaitClosed() }).single()
        val restarted = SessionJournal.createIn(root, meta, 1L)!!
        stale.deleteNow()
        assertTrue(restarted.dir.exists())
        restarted.close()
        restarted.awaitClosed()
    }

    @Test
    fun unfinishedDeletesAreCleanedUp() {
        val trash = File(root, ".trash-p7_9").apply { mkdirs() }
        File(trash, "samples.csv").writeText("0,alpha,0.5\n")
        assertEquals(emptyList<String>(), SessionJournal.orphansIn(root).map { it.dir.name })
        assertFalse(trash.exists())
    }

    @Test
    fun postponedJournalsWaitForTheNextLaunch() {
        val journal = SessionJournal.createIn(root, meta, 5L)!!
        journal.close()
        journal.awaitClosed()
        SessionJournal.postpone(SessionJournal.orphansIn(root).single())
        assertEquals(emptyList<String>(), SessionJournal.orphansIn(root).map { it.dir.name })
    }

    @Test
    fun sessionIdIsWrittenBeforeReturning() {
        val journal = SessionJournal.createIn(root, meta, 1L)!!
        val written = kotlinx.coroutines.runBlocking { journal.writeMetaNow(meta.copy(sessionId = 42)) }
        assertTrue(written)
        // Readable straight away, with no wait for the io thread.
        val onDisk = SessionJournal.Meta.fromJson(org.json.JSONObject(File(journal.dir, "meta.json").readText()))
        assertEquals(42, onDisk.sessionId)
        journal.close()
        journal.awaitClosed()
    }

    @Test
    fun aJournalFromBeforeTreatmentDoneReadsAsNotTapped() {
        // meta.json as an app version without the field wrote it.
        val older = meta.copy(baselineDurationSeconds = 120).toJson().apply { remove("treatment_end_seconds") }
        assertEquals(meta.copy(baselineDurationSeconds = 120), SessionJournal.Meta.fromJson(older))
    }

    @Test
    fun recoveredEndTimeIsTheLastRecordedSecond() {
        assertEquals("2026-09-11T10:01:00Z", SessionJournal.endTimeFrom("2026-09-11T10:00:00Z", 59))
    }

    @Test
    fun noRawEegMeansNothingToPack() {
        val journal = SessionJournal.createIn(root, meta, 1L)!!
        journal.appendTick(tick(0))
        journal.close()
        journal.awaitClosed()
        val read = SessionJournal.orphansIn(root).single()
        assertFalse(read.hasRawEeg())
        assertEquals(null, read.packRawEeg())
        assertTrue(read.readSamples().isNotEmpty())
        assertNotNull(read.readMeta())
    }
}
