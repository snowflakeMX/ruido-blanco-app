package ruido.app

import android.content.Context
import ruido.core.NightRecord
import java.io.File

/** A stored night plus what the file itself tells about it. */
data class StoredNight(
    val record: NightRecord,
    /** Set when the night never ended normally: the last time it was saved. */
    val interruptedAtMs: Long? = null,
) {
    val startMs: Long get() = record.startMs
    /** End to show: the real end, or when it was last known alive. */
    val shownEndMs: Long? get() = record.endMs ?: interruptedAtMs
}

/**
 * One CSV file per night in the app's private storage (other apps cannot read it, and
 * it is excluded from backups). Never stores audio.
 */
class NightStorage(context: Context) {
    private val dir = File(context.filesDir, "nights").apply { mkdirs() }

    private fun file(startMs: Long) = File(dir, "$startMs.csv")

    fun save(record: NightRecord) {
        val tmp = File(dir, "${record.startMs}.tmp")
        tmp.writeText(record.toCsv())
        if (!tmp.renameTo(file(record.startMs))) {
            file(record.startMs).writeText(record.toCsv())
            tmp.delete()
        }
    }

    /**
     * Most recent first. [runningStartMs] is the night in progress, if any: every other
     * night without an end was cut off (the app was closed or the phone restarted).
     */
    fun all(runningStartMs: Long? = null): List<StoredNight> =
        dir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
            .mapNotNull { f ->
                val record = runCatching { NightRecord.fromCsv(f.readText()) }.getOrNull() ?: return@mapNotNull null
                val interrupted = record.endMs == null && record.startMs != runningStartMs
                StoredNight(record, if (interrupted) f.lastModified() else null)
            }
            .sortedByDescending { it.startMs }

    fun get(startMs: Long, runningStartMs: Long? = null): StoredNight? =
        all(runningStartMs).firstOrNull { it.startMs == startMs }

    fun setWokeUp(startMs: Long, woke: Boolean?) = edit(startMs) { it.copy(wokeUp = woke) }

    private fun edit(startMs: Long, change: (NightRecord) -> NightRecord) {
        val f = file(startMs)
        if (!f.exists()) return
        val record = runCatching { NightRecord.fromCsv(f.readText()) }.getOrNull() ?: return
        val modified = f.lastModified()
        save(change(record))
        // Keep the "last alive" time of interrupted nights.
        if (record.endMs == null) f.setLastModified(modified)
    }

    fun delete(startMs: Long) {
        file(startMs).delete()
    }
}
