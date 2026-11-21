package glick.core

/**
 * Saving everything glick keeps into one archive file, and reading such a
 * file back. Each platform supplies its own way of choosing the file.
 */
interface BackupActions {
    /** Lets the user choose where to save, then writes the archive. Returns what was done, or null when cancelled. */
    suspend fun export(includeAudio: Boolean): String?

    /**
     * Lets the user choose an archive, then reads it. With [replace] everything
     * already here is removed first; otherwise the archive is added to it and
     * wins where both have the same song or pattern.
     */
    suspend fun import(replace: Boolean): String?
}
