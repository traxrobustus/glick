package glick.core

import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.test.Test

/** Writes build/click-preview.wav: a bar of each voice, for listening checks. */
class ClickPreview {
    @Test
    fun writePreview() {
        val rate = 44_100
        val beat = rate * 60 / 100
        val pitches = listOf(ClickPitches.ACCENT, ClickPitches.BEAT) + ClickPitches.SUBDIVISIONS + ClickPitches.POLY
        val pcm = ByteArray(pitches.size * 4 * beat * 2)
        pitches.forEachIndexed { voice, pitch ->
            val click = ClickVoice(pitch).render(rate)
            repeat(4) { n ->
                val start = (voice * 4 + n) * beat
                click.forEachIndexed { i, sample ->
                    val v = (sample * 0.8f * Short.MAX_VALUE).toInt()
                    pcm[(start + i) * 2] = v.toByte()
                    pcm[(start + i) * 2 + 1] = (v shr 8).toByte()
                }
            }
        }
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        val file = File("build/click-preview.wav").apply { parentFile.mkdirs() }
        AudioSystem.write(AudioInputStream(pcm.inputStream(), format, (pcm.size / 2).toLong()), AudioFileFormat.Type.WAVE, file)
    }
}
