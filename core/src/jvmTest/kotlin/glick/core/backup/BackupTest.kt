package glick.core.backup

import glick.core.FileActivityLogStore
import glick.core.FilePatternStore
import glick.core.FileTextStore
import glick.core.LogEntry
import glick.core.MetronomeState
import glick.core.Pattern
import glick.core.Snapshot
import glick.core.SnapshotKeeper
import glick.core.SongRef
import glick.core.song.SongGrid
import glick.core.song.SongSession
import glick.core.song.steadyAt
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupTest {
    private fun folder(): File = Files.createTempDirectory("glick-backup-test").toFile().apply { deleteOnExit() }

    /** Songs are plain files here, and a file's id is its path. */
    private val files = object : AudioAccess {
        override fun open(id: String) = File(id).takeIf { it.isFile }?.inputStream()
        override fun idOf(file: File): String = file.absolutePath
    }

    private fun places(root: File) = BackupPlaces(File(root, "data"), File(root, "data/audio"))

    /** A device with two songs (one whose audio file has gone), a pattern, two runs and its settings. */
    private fun device(root: File): BackupPlaces {
        val places = places(root)
        val store = FileTextStore(places.data)
        val audio = File(root, "music/groove.wav").apply { parentFile.mkdirs(); writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        val here = SongRef(audio.absolutePath, "groove.wav")
        val gone = SongRef(File(root, "music/gone.mp3").absolutePath, "gone.mp3")
        store.write(SongSession.LIBRARY, SongSession.encodeLibrary(listOf(here, gone)))
        store.write(SongSession.projectName(here), SongGrid().steadyAt(100.0, 0.5, 30.0).encode())
        store.write(SongSession.projectName(gone), SongGrid().steadyAt(140.0, 0.0, 30.0).encode())
        FilePatternStore(places.data).save(listOf(Pattern.of("Seven", MetronomeState()), Pattern.of("Shared", MetronomeState(bpm = 99.0))))
        FileActivityLogStore(places.data).apply {
            append(LogEntry(1000, 61_000, 100.0, 120.0, "4/4", false, "groove.wav"))
            append(LogEntry(100_000, 130_000, 60.0, 60.0, "7/8", false))
        }
        store.write(SnapshotKeeper.NAME, Snapshot(state = MetronomeState(bpm = 77.0), song = here).encode())
        return places
    }

    private fun archive(places: BackupPlaces, includeAudio: Boolean = true): Pair<ByteArray, BackupReport> {
        val out = ByteArrayOutputStream()
        val report = Backup.export(places, files, includeAudio, out)
        return out.toByteArray() to report
    }

    @Test
    fun anExportCarriesEverythingAndSaysWhatIsMissing() {
        val (bytes, report) = archive(device(folder()))
        assertEquals(2, report.songs)
        assertEquals(1, report.audioFiles)
        assertEquals(listOf("gone.mp3"), report.missingAudio)
        assertEquals(2, report.patterns)
        assertEquals(2, report.logEntries)
        assertTrue(bytes.size > 100)
        assertTrue("gone.mp3" in report.describe("Exported"))
    }

    @Test
    fun replacingMakesAnotherDeviceLikeTheArchive() {
        val (bytes, _) = archive(device(folder()))
        val targetRoot = folder()
        val target = places(targetRoot)
        FilePatternStore(target.data).save(listOf(Pattern.of("Old", MetronomeState())))

        val report = Backup.import(target, files, ByteArrayInputStream(bytes), replace = true)
        val store = FileTextStore(target.data)
        val library = SongSession.readLibrary(store)
        assertEquals(listOf("groove.wav", "gone.mp3"), library.map { it.name })

        // The audio came along: the song now points at this device's own copy, and its map moved with it.
        val groove = library[0]
        assertTrue(File(groove.id).isFile && File(groove.id).startsWith(target.importedAudio))
        assertEquals(5000, File(groove.id).length())
        assertEquals(100.0, assertNotNull(SongGrid.decode(store.read(SongSession.projectName(groove)))).knownBpm)
        // The song without audio keeps its old location, and its map.
        assertEquals(140.0, assertNotNull(SongGrid.decode(store.read(SongSession.projectName(library[1])))).knownBpm)

        assertEquals(listOf("Seven", "Shared"), FilePatternStore(target.data).load().map { it.name })
        assertEquals(2, FileActivityLogStore(target.data).load().size)
        assertEquals(77.0, report.settings?.state?.bpm)
        assertEquals(groove.id, report.settings?.song?.id)
    }

    @Test
    fun mergingAddsToWhatIsThereAndTheArchiveWinsConflicts() {
        val (bytes, _) = archive(device(folder()))
        val target = places(folder())
        FilePatternStore(target.data).save(listOf(Pattern.of("Mine", MetronomeState()), Pattern.of("Shared", MetronomeState(bpm = 50.0))))
        FileActivityLogStore(target.data).apply {
            append(LogEntry(50_000, 55_000, 90.0, 90.0, "4/4", false))
            append(LogEntry(100_000, 130_000, 60.0, 60.0, "7/8", false)) // also in the archive
        }
        val mine = SongRef("/somewhere/mine.flac", "mine.flac")
        FileTextStore(target.data).write(SongSession.LIBRARY, SongSession.encodeLibrary(listOf(mine)))

        Backup.import(target, files, ByteArrayInputStream(bytes), replace = false)
        Backup.import(target, files, ByteArrayInputStream(bytes), replace = false) // importing twice adds nothing more

        val patterns = FilePatternStore(target.data).load()
        assertEquals(setOf("Mine", "Seven", "Shared"), patterns.map { it.name }.toSet())
        val log = FileActivityLogStore(target.data).load()
        assertEquals(listOf(1000L, 50_000L, 100_000L), log.map { it.startEpochMillis })
        val library = SongSession.readLibrary(FileTextStore(target.data))
        assertEquals(setOf("groove.wav", "gone.mp3", "mine.flac"), library.map { it.name }.toSet())
        assertEquals(3, library.size)
        assertEquals(1, target.importedAudio.listFiles()!!.size)
    }

    @Test
    fun withoutAudioTheArchiveIsSmallAndSongsKeepTheirLocation() {
        val source = device(folder())
        val (bytes, report) = archive(source, includeAudio = false)
        assertEquals(0, report.audioFiles)
        assertTrue(bytes.size < 4000)
        val target = places(folder())
        Backup.import(target, files, ByteArrayInputStream(bytes), replace = true)
        assertFalse(target.importedAudio.exists())
        assertEquals(SongSession.readLibrary(FileTextStore(source.data)), SongSession.readLibrary(FileTextStore(target.data)))
    }

    @Test
    fun otherZipFilesAndHostileArchivesAreRefused() {
        fun zipOf(vararg entries: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, text) in entries) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
        val root = folder()
        val target = places(root)
        assertFailsWith<IllegalArgumentException> {
            Backup.import(target, files, ByteArrayInputStream(zipOf("readme.txt" to "hello")), replace = false)
        }
        assertFailsWith<IllegalArgumentException> {
            Backup.import(target, files, ByteArrayInputStream(zipOf("../../escaped.txt" to "x", "manifest.json" to "{}")), replace = false)
        }
        assertFalse(File(root, "escaped.txt").exists())
        assertFalse(File(target.data, ".import").exists())
    }
}
