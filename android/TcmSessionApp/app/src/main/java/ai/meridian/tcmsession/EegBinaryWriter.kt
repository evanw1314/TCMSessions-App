package ai.meridian.tcmsession

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs SessionRecorder's raw EEG buffer (a list of `[timestampUs, ch1, ch2,
 * ch3, ch4]` DoubleArrays — see its `rawEegSamples` field) into the compact
 * binary wire format POST /sessions/{id}/eeg expects. MUST stay
 * byte-for-byte compatible with server/app/eeg/binary.py (the shared fixture
 * in testdata/ pins both sides).
 *
 * Layout (little-endian — matches eeg/binary.py's HEADER_STRUCT "<4sBBHIqq"
 * exactly, including its lack of padding):
 *
 *     offset  size  field
 *     0       4     magic = "TEEG" (ASCII)
 *     4       1     version (=1)
 *     5       1     channel_count
 *     6       2     reserved (=0)
 *     8       4     sample_count (uint32 — written as a signed Int; a
 *                    session would need >2^31 samples, ~intractable, to
 *                    overflow that)
 *     12      8     first_timestamp_us (int64) — the Muse SDK's own
 *                    packet timestamp (MICROSECONDS, confirmed on hardware)
 *                    for the first sample
 *     20      8     last_timestamp_us (int64) — same, for the last sample
 *     28..          sample_count * channel_count float32 values,
 *                    SAMPLE-MAJOR interleaved: [s0c0, s0c1, ..., s0c(C-1),
 *                    s1c0, ...]. NaN passes through unchanged (bad electrode
 *                    contact for that sample/channel) — see
 *                    eeg/binary.py's module docstring for why there's no
 *                    per-sample timestamp: the server derives the actual
 *                    observed sample rate from first/last instead.
 */
object EegBinaryWriter {
    private const val MAGIC = "TEEG"
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 4 + 1 + 1 + 2 + 4 + 8 + 8 // 28

    /** `samples`: each row is `[timestampUs, ch1, ch2, ch3, ch4, ...]` — the
     * exact shape SessionRecorder.kt's `rawEegSamples` already accumulates.
     * Returns null for an empty buffer (nothing to upload). */
    fun pack(samples: List<DoubleArray>): ByteArray? {
        if (samples.isEmpty()) return null
        val channelCount = samples[0].size - 1
        val sampleCount = samples.size
        val firstTimestampUs = samples.first()[0].toLong()
        val lastTimestampUs = samples.last()[0].toLong()

        val buffer = headerBuffer(channelCount, sampleCount, firstTimestampUs, lastTimestampUs)
        for (row in samples) {
            for (c in 1..channelCount) {
                buffer.putFloat(row[c].toFloat())
            }
        }
        return buffer.array()
    }

    /** The same wire format, from rows SessionJournal stored on disk as
     * `[int64 timestampUs, float32 × channelCount]`, little-endian — used to
     * upload a recording recovered after the app closed. Produces exactly
     * what pack() would for the same samples (pack() also narrows the
     * timestamp to a Long and each channel value to a float). A trailing
     * partial row, from the app dying mid-write, is ignored. */
    fun packStoredRows(rows: ByteArray, channelCount: Int): ByteArray? {
        val rowSize = 8 + channelCount * 4
        val sampleCount = rows.size / rowSize
        if (sampleCount == 0) return null
        val src = ByteBuffer.wrap(rows).order(ByteOrder.LITTLE_ENDIAN)
        val firstTimestampUs = src.getLong(0)
        val lastTimestampUs = src.getLong((sampleCount - 1) * rowSize)

        val buffer = headerBuffer(channelCount, sampleCount, firstTimestampUs, lastTimestampUs)
        for (s in 0 until sampleCount) {
            val rowStart = s * rowSize + 8
            for (c in 0 until channelCount) {
                buffer.putFloat(src.getFloat(rowStart + c * 4))
            }
        }
        return buffer.array()
    }

    private fun headerBuffer(channelCount: Int, sampleCount: Int, firstTimestampUs: Long, lastTimestampUs: Long): ByteBuffer {
        val totalSize = HEADER_SIZE + sampleCount * channelCount * 4
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC.toByteArray(Charsets.US_ASCII)) // 4 bytes
        buffer.put(VERSION) // 1 byte
        buffer.put(channelCount.toByte()) // 1 byte
        buffer.putShort(0) // reserved, 2 bytes
        buffer.putInt(sampleCount) // 4 bytes
        buffer.putLong(firstTimestampUs) // 8 bytes
        buffer.putLong(lastTimestampUs) // 8 bytes
        return buffer
    }
}
