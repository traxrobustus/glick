package glick.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClickSynthTest {
    private val rate = 48_000

    @Test
    fun clickIsShortNormalisedAndFadesOut() {
        val click = ClickVoice(ClickPitches.BEAT).render(rate)
        assertTrue(click.size < rate / 10, "longer than 100 ms")
        assertEquals(1f, click.maxOf { abs(it) }, 1e-5f)
        assertTrue(abs(click.first()) < 0.01f, "starts with a step")
        assertTrue(click.takeLast(20).all { abs(it) < 0.001f }, "does not fade to silence")
    }

    @Test
    fun peakComesWithinTwoMilliseconds() {
        val click = ClickVoice(ClickPitches.ACCENT).render(rate)
        val peakAt = click.indices.maxBy { abs(click[it]) }
        assertTrue(peakAt < rate * 2 / 1000, "peak at sample $peakAt")
    }

    @Test
    fun pitchFollowsTheVoice() {
        for (pitch in listOf(ClickPitches.ACCENT, ClickPitches.BEAT) + ClickPitches.SUBDIVISIONS) {
            val click = ClickVoice(pitch).render(rate)
            // Count rising zero crossings between 4 and 14 ms, after the drop has settled.
            val from = rate * 4 / 1000
            val to = rate * 14 / 1000
            var crossings = 0
            for (i in from until to) if (click[i - 1] < 0 && click[i] >= 0) crossings++
            val measured = crossings / ((to - from).toDouble() / rate)
            assertTrue(abs(measured - pitch) < pitch * 0.12, "pitch $pitch measured $measured")
        }
    }
}
