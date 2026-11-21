package glick.core

/** One stretch of time during which the metronome was running. */
data class LogEntry(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val startBpm: Double,
    val endBpm: Double,
    val signature: String,
    val sleepMode: Boolean,
    /** What was practised: the song's name, when one was playing. */
    val label: String = "",
) {
    val durationMillis: Long get() = (endEpochMillis - startEpochMillis).coerceAtLeast(0)

    /** Tab-separated form used by file-backed stores. */
    fun toLine(): String =
        listOf(startEpochMillis, endEpochMillis, startBpm, endBpm, signature, if (sleepMode) 1 else 0, label)
            .joinToString("\t")

    companion object {
        fun parse(line: String): LogEntry? {
            val p = line.trimEnd('\r', '\n').split('\t')
            if (p.size < 6) return null
            return LogEntry(
                startEpochMillis = p[0].toLongOrNull() ?: return null,
                endEpochMillis = p[1].toLongOrNull() ?: return null,
                startBpm = p[2].toDoubleOrNull() ?: return null,
                endBpm = p[3].toDoubleOrNull() ?: return null,
                signature = p[4],
                sleepMode = p[5] == "1",
                // Entries written before labels existed have six fields.
                label = p.getOrElse(6) { "" },
            )
        }
    }
}

/** Where the activity log is kept between runs. */
interface ActivityLogStore {
    fun load(): List<LogEntry>
    fun append(entry: LogEntry)
    fun clear()
}

class InMemoryActivityLogStore : ActivityLogStore {
    private val entries = mutableListOf<LogEntry>()
    override fun load(): List<LogEntry> = entries.toList()
    override fun append(entry: LogEntry) { entries += entry }
    override fun clear() = entries.clear()
}
