package ai.meridian.tcmsession

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * CSV/zip export for TCM sessions, offered two ways: handed to Android's
 * system share sheet (save to Drive, email, etc.), or written straight to a
 * location the user picks via the Storage Access Framework (ACTION_CREATE_DOCUMENT,
 * driven by an activity's CreateDocument launcher) — the latter is a real
 * on-device save (e.g. into Downloads) with no WRITE_EXTERNAL_STORAGE / MediaStore
 * plumbing needed, and it works the same on every OS version back to minSdk 26.
 */
object ExportUtil {
    private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")

    private fun csvField(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private fun safeFilePart(value: String): String = value.replace(UNSAFE_FILENAME_CHARS, "_")

    /** One session as CSV: a metadata block, then markers, the before/after
     * band shares, then samples — the same things SessionDetailActivity
     * renders on screen. */
    fun sessionCsv(session: SessionDetail, patientName: String): String {
        val sb = StringBuilder()
        sb.append("Session ID,${session.id}\n")
        sb.append("Patient,${csvField(patientName)}\n")
        sb.append("Modality,${csvField(session.modality)}\n")
        sb.append("Started At (UTC),${session.startedAt}\n")
        sb.append("Ended At (UTC),${session.endedAt}\n")
        sb.append("Practitioner Note,${csvField(session.practitionerNote ?: "")}\n")
        // Blank when not marked.
        sb.append("Baseline Ended (s),${session.baselineDurationSeconds ?: ""}\n")
        sb.append("Treatment Done (s),${session.treatmentEndSeconds ?: ""}\n")
        sb.append("\n")

        sb.append("Markers\n")
        sb.append("Offset Seconds,Label\n")
        session.markers.sortedBy { it.offsetSeconds }.forEach {
            sb.append("${it.offsetSeconds},${csvField(it.label)}\n")
        }
        sb.append("\n")

        appendBandShare(sb, session)
        sb.append("\n")

        sb.append("Samples\n")
        sb.append("Offset Seconds,Band,Value\n")
        session.samples
            .sortedWith(compareBy({ it.offsetSeconds }, { BAND_ORDER.indexOf(it.band) }))
            .forEach { sb.append("${it.offsetSeconds},${it.band},${it.value}\n") }

        return sb.toString()
    }

    /** The session card's before/after pies as rows (BandShares, the same
     * calculation the card draws): whole percents that add up to 100, and
     * the change worked out from those same numbers, so the file matches the
     * screen. The Samples block below keeps every raw reading for anyone who
     * wants more precision. A minute without enough clean signal leaves its
     * percent cells blank, and each fallback or gap gets a Note row saying
     * what it means. */
    private fun appendBandShare(sb: StringBuilder, session: SessionDetail) {
        sb.append("Band Share\n")
        val result = BandShares.compute(session.samples, session.baselineDurationSeconds, session.treatmentEndSeconds)
        if (result == null) {
            sb.append("Note,No band readings were recorded\n")
            return
        }
        sb.append("Window,Source,Start Seconds,End Seconds,Usable Seconds,")
        sb.append(BAND_ORDER.joinToString(",") { it.replaceFirstChar { c -> c.uppercase() } + " %" } + "\n")

        fun row(name: String, source: String, w: BandShares.Window) {
            // Blank when the minutes overlap, like the card, which draws no
            // pies then: two shares of the same seconds aren't a comparison.
            val cells = w.percents?.takeUnless { result.overlapping }?.let { BandShares.wholePercents(it) }
                ?.joinToString(",") ?: List(BAND_ORDER.size) { "" }.joinToString(",")
            sb.append("$name,${csvField(source)},${w.startSeconds},${w.endSeconds},${w.usableSeconds},$cells\n")
        }
        row(
            "Before",
            when (result.beforeSource) {
                BandShares.BeforeSource.BASELINE -> "End of baseline"
                BandShares.BeforeSource.FIRST_MINUTE -> "First minute after the sensors settled"
            },
            result.before,
        )
        row(
            "After",
            when (result.afterSource) {
                BandShares.AfterSource.TREATMENT_DONE -> "Minute before Treatment done"
                BandShares.AfterSource.LAST_MINUTE -> "Last minute of the recording"
            },
            result.after,
        )
        result.pointChanges()?.let { changes ->
            sb.append("Change (points),,,,," + changes.joinToString(",") { if (it > 0) "+$it" else "$it" } + "\n")
        }
        bandShareNotes(result).forEach { sb.append("Note,${csvField(it)}\n") }
    }

    /** One wording for every reader: a patient can export their own
     * sessions too, so the notes say what happened to the data without
     * naming the recording screen's buttons (unlike the card, which words
     * each reader's version separately - see BandShareWords). */
    private fun bandShareNotes(r: BandShares.Result): List<String> {
        val notes = mutableListOf<String>()
        if (r.beforeSource == BandShares.BeforeSource.FIRST_MINUTE) {
            notes += "No baseline was marked, so Before is the first minute after the sensors settled. " +
                "Treatment may already have started, so it isn't a before-treatment minute."
        }
        if (r.afterSource == BandShares.AfterSource.LAST_MINUTE) {
            notes += "The end of treatment wasn't marked, so After is the last minute of the recording, which " +
                "may include the needles coming out, movement and talking."
        }
        if (r.overlapping) {
            notes += "The two minutes would overlap, so Before and After aren't compared."
        }
        fun tooLittle(name: String, w: BandShares.Window) {
            if (w.percents != null) return
            val length = w.endSeconds - w.startSeconds
            notes += if (name == "Before" && r.beforeSource == BandShares.BeforeSource.BASELINE &&
                length < BandShares.MIN_USABLE_SECONDS
            ) {
                "Before: the baseline was too short. After skipping its first ${BandShares.SETTLE_SECONDS} " +
                    "seconds while the sensors settle, less than ${BandShares.MIN_USABLE_SECONDS} seconds of it is left."
            } else {
                "$name: not enough clean signal (${w.usableSeconds} of $length seconds usable, at least " +
                    "${BandShares.MIN_USABLE_SECONDS} needed), so no percentages."
            }
        }
        tooLittle("Before", r.before)
        tooLittle("After", r.after)
        return notes
    }

    fun sessionCsvFilename(session: SessionDetail, patientName: String): String {
        val datePart = session.startedAt.substringBefore("T")
        return "${safeFilePart(patientName)}_session${session.id}_$datePart.csv"
    }

    fun allSessionsZipFilename(patientName: String): String =
        "${safeFilePart(patientName)}_all_sessions.zip"

    private fun writeZipEntries(zos: ZipOutputStream, sessions: List<SessionDetail>, patientName: String) {
        sessions.forEach { session ->
            zos.putNextEntry(ZipEntry(sessionCsvFilename(session, patientName)))
            zos.write(sessionCsv(session, patientName).toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }
    }

    /** Writes one session's CSV straight to a destination the user picked via
     * ACTION_CREATE_DOCUMENT (a real on-device save, e.g. into Downloads). */
    fun writeSessionCsvTo(context: Context, uri: android.net.Uri, session: SessionDetail, patientName: String) {
        val out = context.contentResolver.openOutputStream(uri)
            ?: error("Unable to open destination for writing")
        out.use { it.write(sessionCsv(session, patientName).toByteArray(StandardCharsets.UTF_8)) }
    }

    /** Same as [writeSessionCsvTo] but for the all-sessions zip. */
    fun writeAllSessionsZipTo(context: Context, uri: android.net.Uri, sessions: List<SessionDetail>, patientName: String) {
        val out = context.contentResolver.openOutputStream(uri)
            ?: error("Unable to open destination for writing")
        ZipOutputStream(BufferedOutputStream(out)).use { zos -> writeZipEntries(zos, sessions, patientName) }
    }

    /** Writes into cacheDir/exports (clearing anything left from a previous
     * export first, since this dir is otherwise never cleaned up) and hands
     * back a content:// URI via FileProvider — a raw file:// URI can't be
     * opened by the target app once shared cross-process on API 24+. */
    private fun writeToExportsDir(context: Context, filename: String, write: (File) -> Unit): android.net.Uri {
        val dir = File(context.cacheDir, "exports")
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, filename)
        write(file)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareSessionCsv(context: Context, session: SessionDetail, patientName: String) {
        val filename = sessionCsvFilename(session, patientName)
        val uri = writeToExportsDir(context, filename) { file ->
            file.writeText(sessionCsv(session, patientName), StandardCharsets.UTF_8)
        }
        share(context, uri, "text/csv")
    }

    /** One CSV per session bundled into a single zip, named after the patient. */
    fun shareAllSessionsZip(context: Context, sessions: List<SessionDetail>, patientName: String) {
        val uri = writeToExportsDir(context, allSessionsZipFilename(patientName)) { file ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zos ->
                writeZipEntries(zos, sessions, patientName)
            }
        }
        share(context, uri, "application/zip")
    }

    private fun share(context: Context, uri: android.net.Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export session data"))
    }
}
