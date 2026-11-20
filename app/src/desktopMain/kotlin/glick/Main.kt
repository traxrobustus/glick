package glick

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import glick.core.FileActivityLogStore
import glick.core.FilePatternStore
import glick.core.FileTextStore
import glick.core.Metronome
import glick.core.MetronomeState
import glick.core.SnapshotKeeper
import glick.core.TimerProgress
import glick.core.WindowBounds
import glick.core.song.CachingSongLibrary
import glick.core.song.SongSession
import kotlinx.coroutines.CoroutineScope
import java.io.File
import glick.ui.App

fun main() = application {
    val scope = rememberCoroutineScope()
    val app = remember { DesktopApp(scope) }
    val saved = app.snapshot?.window
    val windowState = rememberWindowState(
        size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1440.dp, 860.dp),
        position = saved?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
    )
    Window(
        onCloseRequest = {
            app.close(windowState.bounds())
            exitApplication()
        },
        title = "glick",
        icon = remember { appIcon() },
        state = windowState,
        onKeyEvent = { event ->
            if (event.key == Key.Spacebar && event.type == KeyEventType.KeyDown) {
                // While a tempo is being recorded the space bar taps the beat.
                if (app.song.view.value.recording) app.song.tap(app.audio.status.value.latencyMillis / 1000.0)
                else app.metronome.toggle()
                true
            } else false
        },
    ) {
        App(app.metronome, midiPorts = ::midiInputPorts, audio = app.audio, song = app.song, pickSong = { pickSongFile() })
    }
}

private fun WindowState.bounds(): WindowBounds? {
    if (!position.isSpecified || size == DpSize.Unspecified) return null
    return WindowBounds(position.x.value.toInt(), position.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt())
}

/** Everything the desktop app owns, restored from the last run. */
private class DesktopApp(scope: CoroutineScope) {
    private val directory = dataDirectory()
    private val documents = FileTextStore(directory)
    val snapshot = SnapshotKeeper.restore(documents)

    val metronome = Metronome(
        initial = snapshot?.state ?: MetronomeState(),
        nowEpochMillis = System::currentTimeMillis,
        logStore = FileActivityLogStore(directory),
        patternStore = FilePatternStore(directory),
        timers = snapshot?.timers ?: TimerProgress(),
    )
    val audio = DesktopAudio(metronome, directory)
    val song = SongSession(metronome, CachingSongLibrary(DesktopAudioDecoder(), File(directory, "cache")), documents, scope)
    private val keeper = SnapshotKeeper(metronome, song, documents, scope).also { it.window = snapshot?.window }

    init {
        snapshot?.song?.let { song.load(it, audio.status.value.sampleRate.takeIf { rate -> rate > 0 } ?: 48_000) }
        // Lets a soak test start the click without touching the window.
        if (System.getenv("GLICK_AUTOPLAY") == "1") metronome.play()
    }

    fun close(window: WindowBounds?) {
        metronome.stop() // closes the running log entry
        keeper.window = window ?: keeper.window
        keeper.saveNow()
        song.flush()
        audio.close()
    }
}

/** The window and taskbar icon, so the platform's default Java icon never shows. */
private fun appIcon(): BitmapPainter {
    val bytes = checkNotNull(object {}.javaClass.getResourceAsStream("/icon.png")) { "icon.png is missing" }.use { it.readBytes() }
    return BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
