package glick.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import glick.core.BackupActions
import glick.core.FileActivityLogStore
import glick.core.backup.AudioAccess
import glick.core.backup.Backup
import glick.core.backup.BackupPlaces
import glick.core.FilePatternStore
import glick.core.FileTextStore
import glick.core.Metronome
import glick.core.MetronomeState
import glick.core.SnapshotKeeper
import glick.core.SongRef
import glick.core.TimerProgress
import glick.core.song.CachingSongLibrary
import glick.core.song.SongSession
import glick.ui.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the metronome, its audio and the loaded song for the whole process, so
 * rotation cannot interrupt the click. Everything is restored from the last run.
 */
class GlickApplication : Application() {
    lateinit var metronome: Metronome
        private set
    lateinit var audio: AndroidAudio
        private set
    lateinit var song: SongSession
        private set
    private lateinit var keeper: SnapshotKeeper

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val documents = FileTextStore(filesDir)
        val snapshot = SnapshotKeeper.restore(documents)
        metronome = Metronome(
            initial = snapshot?.state ?: MetronomeState(),
            nowEpochMillis = System::currentTimeMillis,
            logStore = FileActivityLogStore(filesDir),
            patternStore = FilePatternStore(filesDir),
            timers = snapshot?.timers ?: TimerProgress(),
        )
        audio = AndroidAudio(this, metronome)
        song = SongSession(metronome, CachingSongLibrary(AndroidAudioDecoder(this), File(cacheDir, "songs")), documents, scope)
        keeper = SnapshotKeeper(metronome, song, documents, scope)
        // While the click plays, a foreground service keeps the app alive behind a locked screen.
        scope.launch {
            var serviceUp = false
            metronome.transport.map { it.playing }.distinctUntilChanged().collect { playing ->
                if (playing || serviceUp) PlaybackService.follow(this@GlickApplication, playing)
                serviceUp = playing
            }
        }
        snapshot?.song?.let { song.load(it, audio.status.value.sampleRate.takeIf { rate -> rate > 0 } ?: 48_000) }
    }

    val backupPlaces: BackupPlaces get() = BackupPlaces(filesDir, File(filesDir, "audio"))

    /** Called when the app leaves the screen: Android may end the process at any time after that. */
    fun saveNow() {
        keeper.saveNow()
        song.flush()
    }
}

class MainActivity : ComponentActivity() {
    private var pendingPick: CompletableDeferred<SongRef?>? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val ref = uri?.let {
            // Keep access across restarts, so the song can be reopened with the app.
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            SongRef(id = it.toString(), name = describe(it).first)
        }
        pendingPick?.complete(ref)
        pendingPick = null
    }

    private var pendingDocument: CompletableDeferred<Uri?>? = null
    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        pendingDocument?.complete(uri)
        pendingDocument = null
    }
    private val opener = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pendingDocument?.complete(uri)
        pendingDocument = null
    }

    /** Backups go through Android's own file chooser, so they can be saved to Drive or any folder. */
    private val backup = object : BackupActions {
        private val audioFiles = object : AudioAccess {
            override fun open(id: String) = runCatching { contentResolver.openInputStream(Uri.parse(id)) }.getOrNull()
            override fun idOf(file: File): String = Uri.fromFile(file).toString()
        }

        override suspend fun export(includeAudio: Boolean): String? {
            val app = application as GlickApplication
            val asked = CompletableDeferred<Uri?>().also { pendingDocument = it }
            saver.launch("glick-backup-${java.time.LocalDate.now()}.zip")
            val target = asked.await() ?: return null
            return withContext(Dispatchers.IO) {
                app.saveNow()
                val out = contentResolver.openOutputStream(target) ?: error("the file could not be written")
                out.use { Backup.export(app.backupPlaces, audioFiles, includeAudio, it) }.describe("Exported")
            }
        }

        override suspend fun import(replace: Boolean): String? {
            val app = application as GlickApplication
            val asked = CompletableDeferred<Uri?>().also { pendingDocument = it }
            opener.launch(arrayOf("application/zip", "application/octet-stream"))
            val source = asked.await() ?: return null
            val report = withContext(Dispatchers.IO) {
                val input = contentResolver.openInputStream(source) ?: error("the file could not be read")
                input.use { Backup.import(app.backupPlaces, audioFiles, it, replace) }
            }
            Backup.apply(report, replace, app.metronome, app.song)
            return report.describe("Imported")
        }
    }

    private suspend fun pickSong(): SongRef? {
        val result = CompletableDeferred<SongRef?>()
        pendingPick = result
        picker.launch(arrayOf("audio/*"))
        return result.await()
    }

    // Android 13 and later only show the "glick is playing" notification if the user agrees.
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as GlickApplication
        setContent {
            App(app.metronome, midiPorts = { emptyList() }, audio = app.audio, song = app.song, pickSong = ::pickSong, backup = backup)
        }
    }

    override fun onStop() {
        super.onStop()
        (application as GlickApplication).saveNow()
    }
}
