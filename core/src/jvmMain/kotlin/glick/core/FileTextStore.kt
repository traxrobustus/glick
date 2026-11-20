package glick.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps each document as a file in [directory]. A document is written to a
 * temporary file first and then moved into place, so a crash mid-write leaves
 * the previous version intact.
 */
class FileTextStore(private val directory: File) : TextStore {
    override fun read(name: String): String? =
        File(directory, name).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    @Synchronized
    override fun write(name: String, text: String) {
        val target = File(directory, name)
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(text)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
