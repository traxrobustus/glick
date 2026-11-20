package glick

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import glick.core.FileActivityLogStore
import glick.core.FilePatternStore
import glick.core.Metronome
import glick.ui.App

fun main() = application {
    val metronome = remember {
        Metronome(
            nowEpochMillis = System::currentTimeMillis,
            logStore = FileActivityLogStore(dataDirectory()),
            patternStore = FilePatternStore(dataDirectory()),
        )
    }
    val audio = remember {
        DesktopAudio(metronome, dataDirectory()).also {
            // Lets a soak test start the click without touching the window.
            if (System.getenv("GLICK_AUTOPLAY") == "1") metronome.play()
        }
    }
    Window(
        onCloseRequest = {
            metronome.stop() // closes the running log entry
            audio.close()
            exitApplication()
        },
        title = "glick",
        icon = remember { appIcon() },
        state = rememberWindowState(size = DpSize(1440.dp, 860.dp)),
        onKeyEvent = { event ->
            if (event.key == Key.Spacebar && event.type == KeyEventType.KeyDown) {
                metronome.toggle()
                true
            } else false
        },
    ) {
        App(metronome, midiPorts = ::midiInputPorts, audio = audio)
    }
}

/** The window and taskbar icon, so the platform's default Java icon never shows. */
private fun appIcon(): BitmapPainter {
    val bytes = checkNotNull(object {}.javaClass.getResourceAsStream("/icon.png")) { "icon.png is missing" }.use { it.readBytes() }
    return BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
