package ruido.app

import android.content.Context
import ruido.core.NightRecord
import java.io.File

/** One CSV file per night in the app's private storage. Never stores audio. */
class NightStorage(context: Context) {
    private val dir = File(context.filesDir, "nights").apply { mkdirs() }

    private fun file(startMs: Long) = File(dir, "$startMs.csv")

    fun save(record: NightRecord) {
        val tmp = File(dir, "${record.startMs}.tmp")
        tmp.writeText(record.toCsv())
        tmp.renameTo(file(record.startMs))
    }

    /** Most recent first. */
    fun all(): List<NightRecord> =
        dir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
            .mapNotNull { runCatching { NightRecord.fromCsv(it.readText()) }.getOrNull() }
            .sortedByDescending { it.startMs }

    fun latest(): NightRecord? = all().firstOrNull()

    fun setWokeUp(startMs: Long, woke: Boolean) {
        val f = file(startMs)
        val record = runCatching { NightRecord.fromCsv(f.readText()) }.getOrNull() ?: return
        save(record.copy(wokeUp = woke))
    }

    fun csv(startMs: Long): String? = file(startMs).takeIf { it.exists() }?.readText()
}
