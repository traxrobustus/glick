package glick.core

import java.io.File

/** Keeps saved patterns as one tab-separated line each. */
class FilePatternStore(directory: File) : PatternStore {
    private val file = File(directory, "patterns.tsv")

    override fun load(): List<Pattern> =
        if (file.exists()) file.readLines().mapNotNull(Pattern::parse) else emptyList()

    override fun save(patterns: List<Pattern>) {
        file.parentFile?.mkdirs()
        file.writeText(patterns.joinToString("") { it.toLine() + "\n" })
    }
}
