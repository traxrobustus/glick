package glick.android

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import glick.core.FileActivityLogStore
import glick.core.Metronome
import glick.ui.App

/** Owns the metronome and its audio for the whole process, so rotation cannot interrupt the click. */
class GlickApplication : Application() {
    lateinit var metronome: Metronome
        private set
    lateinit var audio: AndroidAudio
        private set

    override fun onCreate() {
        super.onCreate()
        metronome = Metronome(nowEpochMillis = System::currentTimeMillis, logStore = FileActivityLogStore(filesDir))
        audio = AndroidAudio(this, metronome)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as GlickApplication
        setContent {
            App(app.metronome, midiPorts = { emptyList() }, audio = app.audio)
        }
    }
}
