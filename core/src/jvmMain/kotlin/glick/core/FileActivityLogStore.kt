package glick.core

import java.io.File

/** Keeps the activity log as one tab-separated line per run. */
class FileActivityLogStore(directory: File) : ActivityLogStore {
    private val file = File(directory, "activity.log")

    override fun load(): List<LogEntry> =
        if (file.exists()) file.readLines().mapNotNull(LogEntry::parse) else emptyList()

    override fun append(entry: LogEntry) {
        file.parentFile?.mkdirs()
        file.appendText(entry.toLine() + "\n")
    }

    override fun clear() {
        file.delete()
    }
}
