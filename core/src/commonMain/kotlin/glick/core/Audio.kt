package glick.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the metronome into sound. Whoever owns the audio device calls [render]
 * for each block; that call also moves the transport, so the sample counter is
 * the clock and every click lands on its exact sample.
 */
class ClickEngine(private val metronome: Metronome, val sampleRate: Int) {
    // Two sounds per voice: the plain one, then its accent (same voice, higher pitch).
    private val sounds: Array<FloatArray> = Array(Voices.COUNT * 2) { index ->
        val voice = index / 2
        val accent = index % 2 == 1
        val pitch = when {
            voice == Voices.BEAT -> ClickPitches.BEAT
            voice == Voices.CHIME -> ClickPitches.CHIME
            voice >= Voices.POLY -> ClickPitches.POLY[voice - Voices.POLY]
            else -> ClickPitches.SUBDIVISIONS[voice - Voices.SUB]
        } * if (accent) ClickPitches.ACCENT_RATIO else 1.0
        ClickVoice(pitch, decay = if (voice == Voices.CHIME) 0.03 else ClickVoice(pitch).decay).render(sampleRate)
    }

    // Fixed pool of playing clicks, so the audio thread never allocates.
    private val playingSound = IntArray(MAX_PLAYING)
    private val playingPosition = IntArray(MAX_PLAYING)
    private val playingGain = FloatArray(MAX_PLAYING)
    private var playing = 0

    /** Master volume, 0 to 1. */
    @Volatile
    var volume: Float = 0.8f

    @Volatile
    private var testRequested = false

    private val sink = ClickSink { offsetNanos, voice, level, accent ->
        start(voice * 2 + if (accent) 1 else 0, (offsetNanos * sampleRate / 1e9).roundToInt(), level)
    }

    /** Plays one beat click regardless of the transport, to check the output. */
    fun playTestSound() {
        testRequested = true
    }

    /** Fills the first [frames] samples of [out] with mono audio. */
    fun render(out: FloatArray, frames: Int) {
        out.fill(0f, 0, frames)
        if (testRequested) {
            testRequested = false
            start(Voices.BEAT * 2, 0, 1f)
        }
        metronome.advance(frames * 1e9 / sampleRate, sink)

        var kept = 0
        for (slot in 0 until playing) {
            val sound = sounds[playingSound[slot]]
            val position = playingPosition[slot]
            val gain = playingGain[slot]
            val first = if (position < 0) -position else 0
            val last = min(frames, sound.size - position)
            for (i in first until last) out[i] += sound[position + i] * gain
            if (position + frames < sound.size) {
                playingSound[kept] = playingSound[slot]
                playingPosition[kept] = position + frames
                playingGain[kept] = gain
                kept++
            }
        }
        playing = kept

        val master = volume
        for (i in 0 until frames) out[i] = (out[i] * master).coerceIn(-1f, 1f)
    }

    private fun start(sound: Int, offsetFrames: Int, level: Float) {
        if (playing == MAX_PLAYING) return
        playingSound[playing] = sound
        playingPosition[playing] = -offsetFrames
        playingGain[playing] = level * HEADROOM
        playing++
    }

    private companion object {
        const val MAX_PLAYING = 96
        const val HEADROOM = 0.6f
    }
}

data class AudioDevice(val id: String, val name: String)

data class AudioStatus(
    val running: Boolean = false,
    /** The device in use or asked for; null means the system default. */
    val deviceId: String? = null,
    val sampleRate: Int = 0,
    val latencyMillis: Int = 0,
    /** Why there is no sound, when [running] is false. */
    val error: String = "",
)

/** A platform's audio playback, as shown in the audio settings panel. */
interface AudioOutput {
    val devices: StateFlow<List<AudioDevice>>
    val status: StateFlow<AudioStatus>
    val volume: StateFlow<Float>
    fun refreshDevices()
    /** Switches playback to [deviceId]; null means the system default. */
    fun selectDevice(deviceId: String?)
    fun setVolume(volume: Float)
    fun playTestSound()
}
