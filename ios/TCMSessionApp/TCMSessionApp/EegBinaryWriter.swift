import Foundation

/// Packs PatientSessionRecorder's raw EEG buffer (rows of `[timestampUs, ch1,
/// ch2, ch3, ch4]` — see its `rawEegSamples` field) into the compact binary wire
/// format POST /sessions/{id}/eeg expects. Direct port of Android's
/// EegBinaryWriter.kt, and this MUST stay byte-for-byte compatible with
/// server/app/eeg/binary.py.
///
/// Layout (little-endian — matches eeg/binary.py's HEADER_STRUCT "<4sBBHIqq"
/// exactly, including its lack of padding):
///
///     offset  size  field
///     0       4     magic = "TEEG" (ASCII)
///     4       1     version (=1)
///     5       1     channel_count
///     6       2     reserved (=0)
///     8       4     sample_count (uint32)
///     12      8     first_timestamp_us (int64) — the Muse SDK's own timestamp
///                    (MICROSECONDS) for the first sample
///     20      8     last_timestamp_us (int64) — same, for the last sample
///     28..          sample_count * channel_count float32 values, SAMPLE-MAJOR
///                    interleaved: [s0c0, s0c1, ..., s0c(C-1), s1c0, ...]. NaN
///                    passes through unchanged (bad electrode contact for that
///                    sample/channel) — see eeg/binary.py's module docstring for
///                    why there's no per-sample timestamp: the server derives
///                    the actual observed sample rate from first/last instead.
// `nonisolated`: pure computation, called from the detached recovery task as
// well as the main actor (this build defaults types to @MainActor).
nonisolated enum EegBinaryWriter {
    private static let magic: [UInt8] = Array("TEEG".utf8)
    private static let version: UInt8 = 1
    private static let headerSize = 4 + 1 + 1 + 2 + 4 + 8 + 8 // 28

    /// One captured raw-EEG sample: the SDK's own microsecond timestamp plus one
    /// value per channel. NaN channel values are meaningful (bad contact) and are
    /// written through unchanged.
    struct Sample {
        let timestampUs: Int64
        let channels: [Double]
    }

    /// Returns nil for an empty buffer (nothing to upload).
    static func pack(_ samples: [Sample]) -> Data? {
        guard let first = samples.first, let last = samples.last else { return nil }
        let channelCount = first.channels.count
        var data = headerData(channelCount: channelCount, sampleCount: samples.count,
                              firstTimestampUs: first.timestampUs, lastTimestampUs: last.timestampUs)
        data.reserveCapacity(headerSize + samples.count * channelCount * 4)
        for sample in samples {
            for channel in 0..<channelCount {
                // A short row would otherwise shift every later sample; pad with
                // NaN, which already means "no reading for this channel".
                let value = channel < sample.channels.count ? sample.channels[channel] : Double.nan
                appendFloat32(&data, Float(value))
            }
        }
        return data
    }

    /// The same wire format, from rows SessionJournal stored on disk as
    /// `[int64 timestampUs, float32 × channelCount]`, little-endian — used to
    /// upload a recording recovered after the app closed. Produces exactly what
    /// pack() would for the same samples (pack() also narrows each channel value
    /// to a float). A trailing partial row, from the app dying mid-write, is ignored.
    static func packStoredRows(_ rows: Data, channelCount: Int) -> Data? {
        let rowSize = 8 + channelCount * 4
        let sampleCount = rows.count / rowSize
        guard sampleCount > 0 else { return nil }
        // Index off a contiguous copy: `rows` may be a slice whose indices don't
        // start at zero, which would make every offset below wrong.
        let bytes = [UInt8](rows)

        let firstTimestampUs = readInt64(bytes, at: 0)
        let lastTimestampUs = readInt64(bytes, at: (sampleCount - 1) * rowSize)

        var data = headerData(channelCount: channelCount, sampleCount: sampleCount,
                              firstTimestampUs: firstTimestampUs, lastTimestampUs: lastTimestampUs)
        data.reserveCapacity(headerSize + sampleCount * channelCount * 4)
        for sample in 0..<sampleCount {
            let rowStart = sample * rowSize + 8
            // The channel values are already little-endian float32 on disk, in
            // exactly the order the wire format wants — copy them straight across.
            data.append(contentsOf: bytes[rowStart..<(rowStart + channelCount * 4)])
        }
        return data
    }

    private static func headerData(channelCount: Int, sampleCount: Int,
                                   firstTimestampUs: Int64, lastTimestampUs: Int64) -> Data {
        var data = Data()
        data.append(contentsOf: magic)                      // 4 bytes
        data.append(version)                                // 1 byte
        data.append(UInt8(truncatingIfNeeded: channelCount)) // 1 byte
        appendLE16(&data, 0)                                // reserved, 2 bytes
        appendLE32(&data, UInt32(truncatingIfNeeded: sampleCount)) // 4 bytes
        appendLE64(&data, UInt64(bitPattern: firstTimestampUs))    // 8 bytes
        appendLE64(&data, UInt64(bitPattern: lastTimestampUs))     // 8 bytes
        return data
    }

    private static func readInt64(_ bytes: [UInt8], at offset: Int) -> Int64 {
        var value: UInt64 = 0
        for i in (0..<8).reversed() {
            value = (value << 8) | UInt64(bytes[offset + i])
        }
        return Int64(bitPattern: value)
    }

    private static func appendFloat32(_ data: inout Data, _ value: Float) {
        appendLE32(&data, value.bitPattern)
    }

    private static func appendLE16(_ data: inout Data, _ value: UInt16) {
        data.append(UInt8(value & 0xFF))
        data.append(UInt8((value >> 8) & 0xFF))
    }

    private static func appendLE32(_ data: inout Data, _ value: UInt32) {
        for shift in stride(from: 0, to: 32, by: 8) {
            data.append(UInt8((value >> UInt32(shift)) & 0xFF))
        }
    }

    private static func appendLE64(_ data: inout Data, _ value: UInt64) {
        for shift in stride(from: 0, to: 64, by: 8) {
            data.append(UInt8((value >> UInt64(shift)) & 0xFF))
        }
    }
}
