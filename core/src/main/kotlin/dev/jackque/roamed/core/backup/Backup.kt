package dev.jackque.roamed.core.backup

import dev.jackque.roamed.core.geo.RevealZoom
import dev.jackque.roamed.core.model.CellRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-disk backup format.
 *
 * Cells are stored as bare arrays rather than objects - `[x, y, firstSeen, lastSeen, visits]` -
 * because a heavy user has hundreds of thousands of them and field names would triple the file.
 */
@Serializable
data class BackupDocument(
    // format and version are deliberately required: with defaults, any JSON object at all would
    // decode into a valid-looking empty backup and silently import as "no cells".
    val format: String,
    val version: Int,
    val revealZoom: Int = RevealZoom.Z,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val cellCount: Int = 0,
    val cells: List<List<Long>> = emptyList(),
)

object BackupFormat {
    const val NAME = "roamed-backup"
    const val VERSION = 1
}

class BackupException(message: String) : Exception(message)

/**
 * Writes a backup incrementally.
 *
 * The sink shape exists so callers can page cells out of a database between [add] calls without
 * ever holding the whole export in memory, and without having to bridge a suspending query into a
 * non-suspending Sequence.
 */
class BackupSink(private val out: Appendable) {

    private var started = false
    private var firstCell = true

    fun begin(exportedAt: Long, appVersion: String, cellCount: Int, revealZoom: Int = RevealZoom.Z) {
        check(!started) { "begin() called twice" }
        started = true
        out.append("{\"format\":\"").append(BackupFormat.NAME).append("\"")
        out.append(",\"version\":").append(BackupFormat.VERSION.toString())
        out.append(",\"revealZoom\":").append(revealZoom.toString())
        out.append(",\"exportedAt\":").append(exportedAt.toString())
        out.append(",\"appVersion\":\"").append(escape(appVersion)).append("\"")
        out.append(",\"cellCount\":").append(cellCount.toString())
        out.append(",\"cells\":[")
    }

    fun add(cell: CellRecord) {
        check(started) { "add() before begin()" }
        if (!firstCell) out.append(',')
        firstCell = false
        out.append('[')
            .append(cell.x.toString()).append(',')
            .append(cell.y.toString()).append(',')
            .append(cell.firstSeen.toString()).append(',')
            .append(cell.lastSeen.toString()).append(',')
            .append(cell.visits.toString())
            .append(']')
    }

    fun end() {
        check(started) { "end() before begin()" }
        out.append("]}")
    }

    companion object {
        internal fun escape(value: String): String {
            val sb = StringBuilder(value.length + 8)
            for (c in value) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
            return sb.toString()
        }
    }
}

object BackupWriter {

    /** Convenience wrapper for callers that already have every cell to hand. */
    fun write(
        out: Appendable,
        cells: Sequence<CellRecord>,
        exportedAt: Long,
        appVersion: String,
        cellCount: Int,
        revealZoom: Int = RevealZoom.Z,
    ) {
        val sink = BackupSink(out)
        sink.begin(exportedAt, appVersion, cellCount, revealZoom)
        cells.forEach(sink::add)
        sink.end()
    }
}

object BackupReader {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses a backup and hands back its cells.
     *
     * A backup written at a different [RevealZoom] is rejected rather than silently mis-imported:
     * the coordinates would land somewhere else entirely on the map.
     */
    fun read(text: String): List<CellRecord> {
        val document = try {
            json.decodeFromString(BackupDocument.serializer(), text)
        } catch (e: Exception) {
            throw BackupException("This file is not a Roamed backup (${e.message ?: "unreadable"}).")
        }
        if (document.format != BackupFormat.NAME) {
            throw BackupException("Unexpected file format: ${document.format}")
        }
        if (document.version > BackupFormat.VERSION) {
            throw BackupException("This backup was written by a newer version of the app.")
        }
        if (document.revealZoom != RevealZoom.Z) {
            throw BackupException(
                "This backup uses grid zoom ${document.revealZoom}, but this build stores cells at zoom ${RevealZoom.Z}."
            )
        }
        return document.cells.mapNotNull { row ->
            if (row.size < 2) return@mapNotNull null
            CellRecord(
                x = row[0].toInt(),
                y = row[1].toInt(),
                firstSeen = row.getOrElse(2) { 0L },
                lastSeen = row.getOrElse(3) { row.getOrElse(2) { 0L } },
                visits = row.getOrElse(4) { 1L }.toInt().coerceAtLeast(1),
            )
        }
    }
}
