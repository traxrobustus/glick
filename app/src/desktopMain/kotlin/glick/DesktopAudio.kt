package glick

import glick.core.AudioDevice
import glick.core.AudioOutput
import glick.core.AudioStatus
import glick.core.ClickEngine
import glick.core.Metronome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Properties
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/**
 * Plays the metronome through Java Sound. A dedicated thread renders small
 * blocks and writes them to the device; the click positions are fixed in
 * samples, so the thread's own scheduling cannot shift the rhythm.
 */
class DesktopAudio(private val metronome: Metronome, directory: File) : AudioOutput {
    private val settingsFile = File(directory, "audio.properties")
    private val settings = Properties().apply {
        if (settingsFile.exists()) runCatching { settingsFile.inputStream().use(::load) }
    }

    override val devices = MutableStateFlow(listDevices())
    override val status = MutableStateFlow(AudioStatus())
    override val volume = MutableStateFlow(settings.getProperty("volume")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.8f)

    private var worker: Worker? = null

    init {
        val saved = settings.getProperty("device")?.takeIf { it.isNotEmpty() }
        start(saved)
        // A saved device may be unplugged; the system default is the better fallback than silence.
        if (saved != null && !status.value.running) start(null)
    }

    override fun refreshDevices() {
        devices.value = listDevices()
    }

    @Synchronized
    override fun selectDevice(deviceId: String?) {
        start(deviceId)
        if (status.value.running) {
            settings.setProperty("device", deviceId ?: "")
            save()
        }
    }

    override fun setVolume(volume: Float) {
        this.volume.value = volume.coerceIn(0f, 1f)
        worker?.engine?.volume = this.volume.value
        settings.setProperty("volume", this.volume.value.toString())
        save()
    }

    override fun playTestSound() {
        worker?.engine?.playTestSound()
    }

    @Synchronized
    fun close() {
        worker?.shutdown()
        worker = null
    }

    @Synchronized
    private fun start(deviceId: String?) {
        worker?.shutdown()
        worker = null
        try {
            val mixer = deviceId?.let { id ->
                playbackMixers().firstOrNull { it.name == id } ?: error("This device is no longer available.")
            }
            val line = openLine(mixer)
            val engine = ClickEngine(metronome, line.format.sampleRate.toInt()).also { it.volume = volume.value }
            worker = Worker(line, engine, deviceId).also { it.start() }
            status.value = AudioStatus(
                running = true,
                deviceId = deviceId,
                sampleRate = engine.sampleRate,
                latencyMillis = line.bufferSize / line.format.frameSize * 1000 / engine.sampleRate,
            )
        } catch (e: Exception) {
            status.value = AudioStatus(deviceId = deviceId, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun openLine(mixer: Mixer.Info?): SourceDataLine {
        var failure: Exception? = null
        for (rate in listOf(48_000, 44_100)) for (channels in listOf(2, 1)) {
            val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
            try {
                val line = if (mixer == null) AudioSystem.getSourceDataLine(format)
                else AudioSystem.getSourceDataLine(format, mixer)
                line.open(format, BUFFER_FRAMES * format.frameSize)
                return line
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("No playback device found.")
    }

    private fun save() {
        runCatching {
            settingsFile.parentFile.mkdirs()
            settingsFile.outputStream().use { settings.store(it, "glick audio settings") }
        }
    }

    private inner class Worker(
        private val line: SourceDataLine,
        val engine: ClickEngine,
        private val deviceId: String?,
    ) : Thread("glick-audio") {
        @Volatile
        private var alive = true

        init {
            isDaemon = true
            priority = MAX_PRIORITY
        }

        override fun run() {
            val channels = line.format.channels
            val mono = FloatArray(BLOCK_FRAMES)
            val bytes = ByteArray(BLOCK_FRAMES * channels * 2)
            try {
                line.start()
                while (alive) {
                    engine.render(mono, BLOCK_FRAMES)
                    var at = 0
                    for (sample in mono) {
                        val value = (sample * Short.MAX_VALUE).toInt()
                        repeat(channels) {
                            bytes[at++] = value.toByte()
                            bytes[at++] = (value shr 8).toByte()
                        }
                    }
                    line.write(bytes, 0, bytes.size)
                }
            } catch (e: Exception) {
                if (alive) status.value = AudioStatus(deviceId = deviceId, error = e.message ?: "Playback stopped.")
            } finally {
                runCatching { line.stop() }
                runCatching { line.close() }
            }
        }

        fun shutdown() {
            alive = false
            join(1000)
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 256
        const val BUFFER_FRAMES = 2048

        fun playbackMixers(): List<Mixer.Info> = AudioSystem.getMixerInfo().filter { info ->
            val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: return@filter false
            mixer.sourceLineInfo.any { it is DataLine.Info && SourceDataLine::class.java.isAssignableFrom(it.lineClass) }
        }

        fun listDevices(): List<AudioDevice> = playbackMixers().map { AudioDevice(id = it.name, name = it.name) }
    }
}
