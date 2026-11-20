package glick.core

import glick.core.song.AudioSource
import glick.core.song.TimeStretcher
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns the metronome, and the song loaded into it, into sound. Whoever owns
 * the audio device calls [render] for each block; that call also moves the
 * transport, so the sample counter is the clock and every click lands on its
 * exact sample.
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

    // The loaded song, as last seen by the audio thread.
    private var songSource: AudioSource? = null
    private var stretcher: TimeStretcher? = null
    private var songLeft = FloatArray(0)
    private var songRight = FloatArray(0)
    private var lastSongFrame = -1.0
    private var lastSongStep = 1.0
    private var lastSongJumps = 0

    /** Fills the first [frames] samples of [left] and [right]. */
    fun render(left: FloatArray, right: FloatArray, frames: Int) {
        left.fill(0f, 0, frames)
        right.fill(0f, 0, frames)
        if (testRequested) {
            testRequested = false
            start(Voices.BEAT * 2, 0, 1f)
        }
        metronome.advance(frames * 1e9 / sampleRate, sink)
        renderSong(left, right, frames)

        var kept = 0
        for (slot in 0 until playing) {
            val sound = sounds[playingSound[slot]]
            val position = playingPosition[slot]
            val gain = playingGain[slot]
            val first = if (position < 0) -position else 0
            val last = min(frames, sound.size - position)
            for (i in first until last) {
                val sample = sound[position + i] * gain
                left[i] += sample
                right[i] += sample
            }
            if (position + frames < sound.size) {
                playingSound[kept] = playingSound[slot]
                playingPosition[kept] = position + frames
                playingGain[kept] = gain
                kept++
            }
        }
        playing = kept

        val master = volume
        for (i in 0 until frames) {
            left[i] = (left[i] * master).coerceIn(-1f, 1f)
            right[i] = (right[i] * master).coerceIn(-1f, 1f)
        }
    }

    /**
     * Plays the span of the song that the transport crossed during this block.
     * The transport says where in the file the block ends; the block started
     * where the previous one ended, so the stretcher gets an exact mapping and
     * the song cannot drift from the click.
     */
    private fun renderSong(left: FloatArray, right: FloatArray, frames: Int) {
        val source = metronome.songAudio
        if (source !== songSource) {
            songSource = source
            // A song prepared for another sample rate would play at the wrong pitch, so it stays silent.
            stretcher = source?.takeIf { it.sampleRate == sampleRate }?.let(::TimeStretcher)
            lastSongFrame = -1.0
        }
        val active = stretcher ?: return
        if (songLeft.size < frames) {
            songLeft = FloatArray(frames)
            songRight = FloatArray(frames)
        }

        val transport = metronome.transport.value
        val playingNow = transport.songSeconds >= 0
        if (!playingNow && lastSongFrame < 0) return

        val level = metronome.state.value.songLevel
        if (!playingNow) {
            // The click just stopped: let the song run on for one block while it fades out.
            active.render(songLeft, songRight, frames, lastSongFrame, lastSongStep, false)
            for (i in 0 until frames) {
                val fade = level * (1f - (i + 1f) / frames)
                left[i] += songLeft[i] * fade
                right[i] += songRight[i] * fade
            }
            lastSongFrame = -1.0
            return
        }

        val endFrame = transport.songSeconds * sampleRate
        val jump = lastSongFrame < 0 || transport.songJumps != lastSongJumps
        val step = if (jump) lastSongStep else (endFrame - lastSongFrame) / frames
        active.render(songLeft, songRight, frames, endFrame - step * frames, step, jump)
        for (i in 0 until frames) {
            left[i] += songLeft[i] * level
            right[i] += songRight[i] * level
        }
        lastSongFrame = endFrame
        lastSongStep = step
        lastSongJumps = transport.songJumps
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
    /** Times the device ran out of audio since it was opened; each one is an audible gap. */
    val dropouts: Int = 0,
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
