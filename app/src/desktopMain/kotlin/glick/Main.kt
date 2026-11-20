package glick

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import glick.core.Metronome
import glick.ui.App

fun main() = application {
    val metronome = remember {
        Metronome(nowEpochMillis = System::currentTimeMillis, logStore = FileActivityLogStore(dataDirectory()))
    }
    Window(
        onCloseRequest = {
            metronome.stop() // closes the running log entry
            exitApplication()
        },
        title = "glick",
        state = rememberWindowState(size = DpSize(1320.dp, 800.dp)),
        onKeyEvent = { event ->
            if (event.key == Key.Spacebar && event.type == KeyEventType.KeyDown) {
                metronome.toggle()
                true
            } else false
        },
    ) {
        App(metronome, midiPorts = ::midiInputPorts)
    }
}
