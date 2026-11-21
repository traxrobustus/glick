package glick.core.backup

import glick.core.FileActivityLogStore
import glick.core.FilePatternStore
import glick.core.LogEntry
import glick.core.Metronome
import glick.core.Snapshot
import glick.core.SnapshotKeeper
import glick.core.SongRef
import glick.core.TextStore
import glick.core.FileTextStore
import glick.core.song.SongSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Where glick keeps its data, and where audio that arrives in a backup is put. */
class BackupPlaces(val data: File, val importedAudio: File)

/** How a platform reads a song's audio file and names a file as a song. */
interface AudioAccess {
    /** The original audio file of the song with this id, or null when it can no longer be read. */
    fun open(id: String): InputStream?

    /** The id under which [file] can be opened as a song. */
    fun idOf(file: File): String
}

/** What an export or import handled. */
data class BackupReport(
    val songs: Int,
    val audioFiles: Int,
    /** Songs whose audio file could not be read and is not in the archive. */
    val missingAudio: List<String>,
    val patterns: Int,
    val logEntries: Int,
    /** The settings found in an imported archive. */
    val settings: Snapshot? = null,
) {
    fun describe(verb: String): String = buildString {
        append("$verb $songs song${plural(songs)} ($audioFiles with audio), ")
        append("$patterns pattern${plural(patterns)} and $logEntries log entr${if (logEntries == 1) "y" else "ies"}.")
        if (missingAudio.isNotEmpty()) append(" Audio not found for: ${missingAudio.joinToString()}.")
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"
}

@Serializable
private data class ArchivedSong(val id: String, val name: String, val map: String? = null, val audio: String? = null)

@Serializable
private data class Manifest(val format: Int = 1, val songs: List<ArchivedSong> = emptyList())

/**
 * The backup archive: a zip file with the settings, the patterns, the activity
 * log, every song's map and, when wanted, the audio files themselves.
 */
object Backup {
    private const val MANIFEST = "manifest.json"
    private const val PATTERNS = "patterns.tsv"
    private const val LOG = "activity.log"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun export(places: BackupPlaces, audio: AudioAccess, includeAudio: Boolean, output: OutputStream): BackupReport {
        val store = FileTextStore(places.data)
        val songs = SongSession.readLibrary(store)
        val missing = mutableListOf<String>()
        val archived = mutableListOf<ArchivedSong>()
        var audioFiles = 0

        ZipOutputStream(output.buffered()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            for ((index, ref) in songs.withIndex()) {
                val map = store.read(SongSession.projectName(ref))?.let { text ->
                    "songs/$index.json".also { put(it, text) }
                }
                var entry: String? = null
                if (includeAudio) {
                    val stream = runCatching { audio.open(ref.id) }.getOrNull()
                    if (stream == null) missing += ref.name
                    else stream.use {
                        entry = "audio/$index-${safeName(ref.name)}"
                        zip.putNextEntry(ZipEntry(entry))
                        it.copyTo(zip)
                        zip.closeEntry()
                        audioFiles++
                    }
                }
                archived += ArchivedSong(ref.id, ref.name, map, entry)
            }
            for (name in listOf(SnapshotKeeper.NAME, PATTERNS, LOG)) store.read(name)?.let { put(name, it) }
            put(MANIFEST, json.encodeToString(Manifest.serializer(), Manifest(songs = archived)))
        }
        return BackupReport(
            songs = songs.size,
            audioFiles = audioFiles,
            missingAudio = missing,
            patterns = FilePatternStore(places.data).load().size,
            logEntries = FileActivityLogStore(places.data).load().size,
        )
    }

    fun import(places: BackupPlaces, audio: AudioAccess, input: InputStream, replace: Boolean): BackupReport {
        val unpacked = File(places.data, ".import").apply { deleteRecursively(); mkdirs() }
        try {
            unzip(input, unpacked)
            val manifestFile = File(unpacked, MANIFEST)
            require(manifestFile.isFile) { "this file is not a glick backup" }
            val manifest = json.decodeFromString(Manifest.serializer(), manifestFile.readText())
            require(manifest.format == 1) { "this backup was made by a newer version of glick" }

            val store = FileTextStore(places.data)
            if (replace) {
                File(places.data, "songs").deleteRecursively()
                places.importedAudio.deleteRecursively()
                File(places.data, PATTERNS).delete()
                File(places.data, LOG).delete()
            }

            // Songs: audio that came along is stored here and the song then points at that copy,
            // unless this device already has the song under the same location (a backup read
            // back where it was made), which then just gets the archive's map.
            val known = SongSession.readLibrary(store).map { it.id }.toSet()
            val renamed = mutableMapOf<String, SongRef>()
            var audioFiles = 0
            for (song in manifest.songs) {
                val source = song.audio?.let { File(unpacked, it) }?.takeIf { it.isFile }
                val ref = if (source == null || song.id in known) SongRef(song.id, song.name) else {
                    places.importedAudio.mkdirs()
                    val target = File(places.importedAudio, "${sha256(source).take(12)}-${safeName(song.name)}")
                    if (!target.exists()) source.copyTo(target)
                    audioFiles++
                    SongRef(audio.idOf(target), song.name)
                }
                renamed[song.id] = ref
                song.map?.let { File(unpacked, it) }?.takeIf { it.isFile }?.let { store.write(SongSession.projectName(ref), it.readText()) }
            }
            val imported = renamed.values.toList()
            val library = imported + SongSession.readLibrary(store).filter { old -> imported.none { it.id == old.id } }
            store.write(SongSession.LIBRARY, SongSession.encodeLibrary(library))

            val patterns = mergePatterns(places.data, File(unpacked, PATTERNS))
            val logEntries = mergeLog(places.data, File(unpacked, LOG))
            val settings = Snapshot.decode(File(unpacked, SnapshotKeeper.NAME).takeIf { it.isFile }?.readText())
                ?.let { it.copy(song = it.song?.let { ref -> renamed[ref.id] ?: ref }, window = null) }

            return BackupReport(manifest.songs.size, audioFiles, emptyList(), patterns, logEntries, settings)
        } finally {
            unpacked.deleteRecursively()
        }
    }

    /** Brings the running app in line with what [report] imported. */
    fun apply(report: BackupReport, replace: Boolean, metronome: Metronome, song: SongSession) {
        metronome.reloadStores()
        if (replace) {
            song.close()
            report.settings?.let { imported -> metronome.update { imported.state } }
        }
        song.reloadLibrary()
    }

    private fun mergePatterns(data: File, archived: File): Int {
        if (!archived.isFile) return 0
        val incoming = FilePatternStore(archived.parentFile).load()
        val target = FilePatternStore(data)
        target.save(target.load().filter { old -> incoming.none { it.name == old.name } } + incoming)
        return incoming.size
    }

    private fun mergeLog(data: File, archived: File): Int {
        if (!archived.isFile) return 0
        val incoming = archived.readLines().mapNotNull(LogEntry::parse)
        val file = File(data, LOG)
        val existing = if (file.isFile) file.readLines().mapNotNull(LogEntry::parse) else emptyList()
        val all = (existing + incoming).distinct().sortedBy { it.startEpochMillis }
        file.parentFile.mkdirs()
        file.writeText(all.joinToString("") { it.toLine() + "\n" })
        return incoming.size
    }

    private fun unzip(input: InputStream, target: File) {
        val root = target.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val file = File(root, entry.name).canonicalFile
                // An archive must not be able to write outside the folder it is unpacked into.
                require(file.path.startsWith(root.path + File.separator)) { "this file is not a glick backup" }
                if (entry.isDirectory) file.mkdirs()
                else {
                    file.parentFile.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }

    private fun safeName(name: String): String =
        name.map { if (it.isLetterOrDigit() || it in "._- ") it else '_' }.joinToString("").trim().takeLast(80).ifEmpty { "song" }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
