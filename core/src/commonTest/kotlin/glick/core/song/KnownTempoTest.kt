package glick.core.song

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnownTempoTest {
    private val rate = 22_050

    /** Short tone bursts at [bpm], the first of every four lower and louder. */
    private fun song(bpm: Double, seconds: Int = 30): Pair<MemoryAudioSource, List<Double>> {
        val audio = FloatArray(rate * seconds)
        val beats = generateSequence(0.5) { it + 60 / bpm }.takeWhile { it < seconds - 0.5 }.toList()
        beats.forEachIndexed { index, at ->
            val accent = index % 4 == 0
            val start = (at * rate).toInt()
            for (i in 0 until rate / 20) {
                val t = i.toDouble() / rate
                audio[start + i] += ((if (accent) 0.9 else 0.5) * sin(2 * PI * (if (accent) 110 else 440) * t) * exp(-t / 0.01)).toFloat()
            }
        }
        return MemoryAudioSource(rate, audio) to beats
    }

    private fun matched(found: List<Double>, truth: List<Double>): Double =
        truth.count { t -> found.any { abs(it - t) < 0.035 } }.toDouble() / truth.size

    @Test
    fun aStatedTempoStopsAFastSongBeingReadAtHalfSpeed() {
        val (audio, truth) = song(200.0)
        val unaided = BeatDetector.detect(audio)
        val aided = BeatDetector.detect(audio, knownBpm = 200.0)
        assertEquals(100.0, unaided.bpm, 2.0) // the weakness the hint is for
        assertEquals(200.0, aided.bpm, 2.0)
        assertTrue(matched(aided.beats, truth) > 0.95)
    }

    @Test
    fun aRoughlyRightTempoIsEnoughAndASlowOneWorksToo() {
        val (fast, fastTruth) = song(180.0)
        val nearly = BeatDetector.detect(fast, knownBpm = 176.0)
        assertEquals(180.0, nearly.bpm, 1.8)
        assertTrue(matched(nearly.beats, fastTruth) > 0.95)

        val (slow, slowTruth) = song(50.0, seconds = 40)
        val found = BeatDetector.detect(slow, knownBpm = 50.0)
        assertEquals(50.0, found.bpm, 0.5)
        assertTrue(matched(found.beats, slowTruth) > 0.95)
    }

    @Test
    fun aSilentFileGetsAGridAtTheStatedTempo() {
        val silence = MemoryAudioSource(rate, FloatArray(rate * 10))
        assertEquals(90.0, BeatDetector.detect(silence, knownBpm = 90.0).bpm)
    }
}
