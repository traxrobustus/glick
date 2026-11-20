package glick.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import glick.core.AudioDevice
import glick.core.AudioOutput
import glick.core.AudioStatus
import glick.core.ClickEngine
import glick.core.Metronome
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Plays the metronome through a low-latency AudioTrack at the device's native
 * sample rate. As on desktop, click positions are fixed in samples.
 */
class AndroidAudio(context: Context, private val metronome: Metronome) : AudioOutput {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val preferences = context.getSharedPreferences("audio", Context.MODE_PRIVATE)

    override val devices = MutableStateFlow(listDevices())
    override val status = MutableStateFlow(AudioStatus())
    override val volume = MutableStateFlow(preferences.getFloat("volume", 0.8f))

    private var worker: Worker? = null

    init {
        start(null)
    }

    override fun refreshDevices() {
        devices.value = listDevices()
    }

    override fun selectDevice(deviceId: String?) = start(deviceId)

    override fun setVolume(volume: Float) {
        this.volume.value = volume.coerceIn(0f, 1f)
        worker?.engine?.volume = this.volume.value
        preferences.edit().putFloat("volume", this.volume.value).apply()
    }

    override fun playTestSound() {
        worker?.engine?.playTestSound()
    }

    private fun outputs(): List<AudioDeviceInfo> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }

    private fun listDevices(): List<AudioDevice> = outputs().map { info ->
        AudioDevice(id = info.id.toString(), name = "${typeName(info.type)} · ${info.productName}")
    }

    @Synchronized
    private fun start(deviceId: String?) {
        worker?.shutdown()
        worker = null
        try {
            val device = deviceId?.let { id ->
                outputs().firstOrNull { it.id.toString() == id } ?: error("This device is no longer available.")
            }
            val rate = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minBytes > 0) { "This phone does not offer a float audio output." }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "The audio output could not be opened." }
            if (device != null) check(track.setPreferredDevice(device)) { "This device cannot be used for playback." }
            val engine = ClickEngine(metronome, rate).also { it.volume = volume.value }
            worker = Worker(track, engine, deviceId).also { it.start() }
            status.value = AudioStatus(
                running = true,
                deviceId = deviceId,
                sampleRate = rate,
                latencyMillis = track.bufferSizeInFrames * 1000 / rate,
            )
        } catch (e: Exception) {
            status.value = AudioStatus(deviceId = deviceId, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private inner class Worker(
        private val track: AudioTrack,
        val engine: ClickEngine,
        private val deviceId: String?,
    ) : Thread("glick-audio") {
        @Volatile
        private var alive = true

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val block = FloatArray(BLOCK_FRAMES)
            try {
                track.play()
                while (alive) {
                    engine.render(block, BLOCK_FRAMES)
                    val written = track.write(block, 0, BLOCK_FRAMES, AudioTrack.WRITE_BLOCKING)
                    check(written >= 0) { "Playback stopped (error $written)." }
                }
            } catch (e: Exception) {
                if (alive) status.value = AudioStatus(deviceId = deviceId, error = e.message ?: "Playback stopped.")
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }

        fun shutdown() {
            alive = false
            join(1000)
        }
    }

    private companion object {
        const val BLOCK_FRAMES = 128

        fun typeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Output"
        }
    }
}
