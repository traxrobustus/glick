package glick.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import glick.core.FileActivityLogStore
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
        snapshot?.song?.let { song.load(it, audio.status.value.sampleRate.takeIf { rate -> rate > 0 } ?: 48_000) }
    }

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

    private suspend fun pickSong(): SongRef? {
        val result = CompletableDeferred<SongRef?>()
        pendingPick = result
        picker.launch(arrayOf("audio/*"))
        return result.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as GlickApplication
        setContent {
            App(app.metronome, midiPorts = { emptyList() }, audio = app.audio, song = app.song, pickSong = ::pickSong)
        }
    }

    override fun onStop() {
        super.onStop()
        (application as GlickApplication).saveNow()
    }
}
