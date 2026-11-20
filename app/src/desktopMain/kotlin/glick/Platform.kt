package glick

import glick.core.MidiPort
import java.io.File
import javax.sound.midi.MidiSystem

/** Per-user data folder: %APPDATA%\glick on Windows, XDG data home elsewhere. */
fun dataDirectory(): File {
    val home = System.getProperty("user.home")
    val base = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
    } else {
        System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
    }
    return File(base, "glick")
}

/** MIDI devices that can send to us (hardware inputs and virtual ports). */
fun midiInputPorts(): List<MidiPort> =
    MidiSystem.getMidiDeviceInfo().mapNotNull { info ->
        val device = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: return@mapNotNull null
        if (device.maxTransmitters == 0) return@mapNotNull null
        MidiPort(id = "${info.name}|${info.vendor}", name = info.name)
    }
