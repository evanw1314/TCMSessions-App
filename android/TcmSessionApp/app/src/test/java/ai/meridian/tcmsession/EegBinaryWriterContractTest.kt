package ai.meridian.tcmsession

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * EegBinaryWriter's output is a contract with the server's parser
 * (server/app/eeg/binary.py). testdata/eeg_blob_v1.bin is checked by both
 * sides: this asserts the writer produces exactly those bytes, and the
 * server's test_eeg_binary_contract.py asserts it reads them back as the same
 * samples. A format change on one side alone fails one of the two.
 */
class EegBinaryWriterContractTest {
    private val testdata = File(System.getProperty("testdata.dir") ?: error("testdata.dir not set (see app/build.gradle.kts)"))
    private val expected = File(testdata, "eeg_blob_v1.bin").readBytes()
    private val fixture = JSONObject(File(testdata, "eeg_blob_v1.json").readText())
    private val channelCount = fixture.getInt("channel_count")

    /** Each sample as (timestamp, values), null values read as NaN. */
    private val samples: List<Pair<Long, DoubleArray>> = fixture.getJSONArray("samples").let { arr ->
        (0 until arr.length()).map { i ->
            val row = arr.getJSONObject(i)
            val values = row.getJSONArray("values")
            row.getLong("timestamp_us") to DoubleArray(values.length()) { c ->
                if (values.isNull(c)) Double.NaN else values.getDouble(c)
            }
        }
    }

    @Test
    fun packWritesTheSharedFixtureExactly() {
        val rows = samples.map { (ts, values) -> doubleArrayOf(ts.toDouble()) + values }
        assertArrayEquals(expected, EegBinaryWriter.pack(rows))
    }

    @Test
    fun packStoredRowsWritesTheSharedFixtureExactly() {
        // SessionJournal's on-disk row: int64 timestamp, then float32 per channel.
        val stored = ByteBuffer.allocate(samples.size * (8 + channelCount * 4)).order(ByteOrder.LITTLE_ENDIAN)
        for ((ts, values) in samples) {
            stored.putLong(ts)
            values.forEach { stored.putFloat(it.toFloat()) }
        }
        assertArrayEquals(expected, EegBinaryWriter.packStoredRows(stored.array(), channelCount))
    }
}
