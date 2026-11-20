package glick.core.song

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResamplerTest {
    private fun sine(frequency: Double, rate: Int, count: Int, amplitude: Double = 0.5) =
        FloatArray(count) { (amplitude * sin(2 * PI * frequency * it / rate)).toFloat() }

    /** Converts [input] in uneven portions, as a decoder would deliver it, and flushes. */
    private fun convert(input: FloatArray, from: Int, to: Int): FloatArray {
        val resampler = Resampler(from, to)
        val portionOut = FloatArray((1000L * to / from).toInt() + 64)
        val result = FloatArray((input.size.toLong() * to / from).toInt() + 256)
        var length = 0
        var taken = 0
        var portion = 1
        while (taken < input.size) {
            val count = min(portion, input.size - taken)
            val written = resampler.process(input.copyOfRange(taken, taken + count), count, portionOut)
            portionOut.copyInto(result, length, 0, written)
            length += written
            taken += count
            portion = portion * 7 % 1000 + 1
        }
        val tail = resampler.flush(portionOut)
        portionOut.copyInto(result, length, 0, tail)
        return result.copyOf(length + tail)
    }

    private fun frequency(samples: FloatArray, rate: Int, from: Int, to: Int): Double {
        var first = 0.0
        var last = 0.0
        var crossings = 0
        for (i in from + 1 until to) {
            if (samples[i - 1] < 0 && samples[i] >= 0) {
                last = i - 1 + samples[i - 1] / (samples[i - 1] - samples[i]).toDouble()
                if (crossings == 0) first = last
                crossings++
            }
        }
        return (crossings - 1) / (last - first) * rate
    }

    private fun rms(samples: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i].toDouble()
        return sqrt(sum / (to - from))
    }

    private fun decibels(ratio: Double) = 20 * log10(ratio)

    @Test
    fun aSineKeepsItsFrequencyLevelAndLength() {
        for ((from, to) in listOf(44100 to 48000, 48000 to 44100)) {
            val input = sine(1000.0, from, 2 * from)
            val output = convert(input, from, to)
            assertTrue(abs(output.size - 2 * to) <= 2, "length ${output.size} for $from to $to")
            val start = to / 10
            val end = output.size - to / 10
            assertEquals(1000.0, frequency(output, to, start, end), 1.0)
            val level = decibels(rms(output, start, end) / rms(input, from / 10, input.size - from / 10))
            assertTrue(abs(level) < 0.2, "level changed by $level dB for $from to $to")
        }
    }

    @Test
    fun theOutputIsNotDelayed() {
        val input = FloatArray(4410)
        input[2205] = 1f
        val output = convert(input, 44100, 48000)
        var peak = 0
        for (i in output.indices) if (abs(output[i]) > abs(output[peak])) peak = i
        assertEquals(2400, peak)
    }

    @Test
    fun theSameRateCopies() {
        val input = sine(1000.0, 48000, 5000)
        val output = convert(input, 48000, 48000)
        assertEquals(input.size, output.size)
        for (i in 500 until input.size - 500) assertEquals(input[i], output[i], 1e-4f)
    }

    @Test
    fun soundAboveTheNewNyquistIsRemovedNotFoldedBack() {
        // 23 kHz does not fit into 44.1 kHz; unfiltered it would come back as 21.1 kHz.
        val input = sine(23000.0, 48000, 48000)
        val output = convert(input, 48000, 44100)
        val level = decibels(rms(output, 4410, output.size - 4410) / rms(input, 4800, input.size - 4800))
        assertTrue(level < -60.0, "alias at $level dB")
    }

    @Test
    fun aHighToneComesThroughCleanly() {
        // 18 kHz at 48 kHz, converted down: everything that is not 18 kHz is garbage.
        val size = 16384
        val input = sine(18000.0, 48000, 48000)
        val output = convert(input, 48000, 44100)
        val re = FloatArray(size) { (output[4410 + it] * (0.5 - 0.5 * cos(2 * PI * it / size))).toFloat() }
        val im = FloatArray(size)
        Fft(size).forward(re, im)
        val toneBin = 18000.0 * size / 44100
        var tone = 0.0
        var garbage = 0.0
        for (k in 0..size / 2) {
            val power = hypot(re[k], im[k]).toDouble().let { it * it }
            if (abs(k - toneBin) <= 8) tone += power else garbage += power
        }
        val level = 10 * log10(garbage / tone)
        assertTrue(level < -60.0, "garbage at $level dB")
        val passed = decibels(rms(output, 4410, output.size - 4410) / rms(input, 4800, input.size - 4800))
        assertTrue(abs(passed) < 0.5, "18 kHz changed by $passed dB")
    }
}
