import SwiftUI
import UIKit

/// CSV / zip export for TCM sessions. Port of Android's ExportUtil.kt. iOS
/// collapses Android's two destinations (Save-to-device vs Share) into the one
/// system share sheet (`ActivityView`), which itself includes "Save to Files".
enum ExportUtil {
    private static let unsafeFilenameChars = CharacterSet(charactersIn:
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-").inverted

    private static func csvField(_ value: String) -> String {
        if value.contains(",") || value.contains("\"") || value.contains("\n") {
            return "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\""
        }
        return value
    }

    private static func safeFilePart(_ value: String) -> String {
        String(String.UnicodeScalarView(value.unicodeScalars.map {
            unsafeFilenameChars.contains($0) ? "_" : $0
        }))
    }

    /// One session as CSV: a metadata block, then markers, then samples — the same
    /// three things SessionDetailView renders on screen.
    static func sessionCsv(_ session: SessionDetail, patientName: String) -> String {
        var sb = ""
        sb += "Session ID,\(session.id)\n"
        sb += "Patient,\(csvField(patientName))\n"
        sb += "Modality,\(csvField(session.modality))\n"
        sb += "Started At (UTC),\(session.startedAt)\n"
        sb += "Ended At (UTC),\(session.endedAt)\n"
        sb += "Practitioner Note,\(csvField(session.practitionerNote ?? ""))\n"
        sb += "\n"

        sb += "Markers\n"
        sb += "Offset Seconds,Label\n"
        for m in session.markers.sorted(by: { $0.offsetSeconds < $1.offsetSeconds }) {
            sb += "\(m.offsetSeconds),\(csvField(m.label))\n"
        }
        sb += "\n"

        sb += "Samples\n"
        sb += "Offset Seconds,Band,Value\n"
        let sorted = session.samples.sorted {
            if $0.offsetSeconds != $1.offsetSeconds { return $0.offsetSeconds < $1.offsetSeconds }
            return (BAND_ORDER.firstIndex(of: $0.band) ?? 0) < (BAND_ORDER.firstIndex(of: $1.band) ?? 0)
        }
        for s in sorted { sb += "\(s.offsetSeconds),\(s.band),\(s.value)\n" }
        return sb
    }

    static func sessionCsvFilename(_ session: SessionDetail, patientName: String) -> String {
        let datePart = session.startedAt.components(separatedBy: "T").first ?? "session"
        return "\(safeFilePart(patientName))_session\(session.id)_\(datePart).csv"
    }

    static func allSessionsZipFilename(_ patientName: String) -> String {
        "\(safeFilePart(patientName))_all_sessions.zip"
    }

    /// Writes one session's CSV to a temp file and returns its URL (for sharing).
    static func writeSessionCsv(_ session: SessionDetail, patientName: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(sessionCsvFilename(session, patientName: patientName))
        try sessionCsv(session, patientName: patientName).data(using: .utf8)!.write(to: url)
        return url
    }

    /// Bundles one CSV per session into a single (store-only) zip in a temp file.
    static func writeAllSessionsZip(_ sessions: [SessionDetail], patientName: String) throws -> URL {
        var zip = ZipWriter()
        for s in sessions {
            zip.add(name: sessionCsvFilename(s, patientName: patientName),
                    data: sessionCsv(s, patientName: patientName).data(using: .utf8)!)
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(allSessionsZipFilename(patientName))
        try zip.finish().write(to: url)
        return url
    }
}

/// Identifiable wrapper so a temp export URL can drive a `.sheet(item:)`.
struct ExportItem: Identifiable {
    let id = UUID()
    let url: URL
}

/// Bridges UIActivityViewController (the system share sheet, incl. Save to Files)
/// into SwiftUI.
struct ActivityView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// MARK: - Minimal store-only ZIP writer
//
// Foundation ships no zip-archive API (Compression is raw deflate), and the CSVs
// here are tiny, so a store-only (no compression) writer is plenty — it produces
// a standard .zip every unzipper reads.
private struct ZipWriter {
    private struct Entry { let name: String; let crc: UInt32; let size: UInt32; let offset: UInt32 }

    private var data = Data()
    private var entries: [Entry] = []

    mutating func add(name: String, data fileData: Data) {
        let nameBytes = Array(name.utf8)
        let crc = ZipWriter.crc32(fileData)
        let size = UInt32(fileData.count)
        let offset = UInt32(data.count)

        appendLE32(0x0403_4b50)          // local file header signature
        appendLE16(20)                    // version needed
        appendLE16(0)                     // flags
        appendLE16(0)                     // compression = store
        appendLE16(0); appendLE16(0)      // mod time, mod date
        appendLE32(crc)
        appendLE32(size)                  // compressed size (== uncompressed)
        appendLE32(size)
        appendLE16(UInt16(nameBytes.count))
        appendLE16(0)                     // extra length
        data.append(contentsOf: nameBytes)
        data.append(fileData)

        entries.append(Entry(name: name, crc: crc, size: size, offset: offset))
    }

    mutating func finish() -> Data {
        let cdStart = UInt32(data.count)
        for e in entries {
            let nameBytes = Array(e.name.utf8)
            appendLE32(0x0201_4b50)      // central directory header signature
            appendLE16(20)               // version made by
            appendLE16(20)               // version needed
            appendLE16(0)                // flags
            appendLE16(0)                // compression
            appendLE16(0); appendLE16(0) // mod time, mod date
            appendLE32(e.crc)
            appendLE32(e.size)
            appendLE32(e.size)
            appendLE16(UInt16(nameBytes.count))
            appendLE16(0)                // extra
            appendLE16(0)                // comment
            appendLE16(0)                // disk number start
            appendLE16(0)                // internal attrs
            appendLE32(0)                // external attrs
            appendLE32(e.offset)         // local header offset
            data.append(contentsOf: nameBytes)
        }
        let cdSize = UInt32(data.count) - cdStart

        appendLE32(0x0605_4b50)          // end of central directory signature
        appendLE16(0); appendLE16(0)     // disk numbers
        appendLE16(UInt16(entries.count))
        appendLE16(UInt16(entries.count))
        appendLE32(cdSize)
        appendLE32(cdStart)
        appendLE16(0)                    // comment length
        return data
    }

    private mutating func appendLE16(_ value: UInt16) {
        data.append(UInt8(value & 0xff)); data.append(UInt8((value >> 8) & 0xff))
    }
    private mutating func appendLE32(_ value: UInt32) {
        for shift: UInt32 in [0, 8, 16, 24] { data.append(UInt8((value >> shift) & 0xff)) }
    }

    private static let crcTable: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data { crc = crcTable[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8) }
        return crc ^ 0xFFFF_FFFF
    }
}
