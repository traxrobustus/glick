package glick.core.song

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimeStretcherTest {
    private val rate = 48000
    private val clickSpacing = rate / 2
    private val tenMillis = rate / 100

    private fun sine(frequency: Double, frames: Int, amplitude: Double = 0.5) =
        FloatArray(frames) { (amplitude * sin(2 * PI * frequency * it / rate)).toFloat() }

    /** A sharp decaying burst every half second, the first at 0.5 s. Each burst peaks on its first sample. */
    private fun clickTrain(frames: Int): FloatArray {
        val samples = FloatArray(frames)
        var at = clickSpacing
        while (at + tenMillis < frames) {
            for (i in 0 until tenMillis) samples[at + i] = (0.8 * exp(-i / 72.0) * cos(2 * PI * 2000 * i / rate)).toFloat()
            at += clickSpacing
        }
        return samples
    }

    /** Renders [frames] output frames at a constant [step] from source position 0, in blocks of [block]. */
    private fun stretch(source: AudioSource, frames: Int, block: Int, step: Double): Pair<FloatArray, FloatArray> {
        val stretcher = TimeStretcher(source)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val blockLeft = FloatArray(block)
        val blockRight = FloatArray(block)
        var done = 0
        while (done < frames) {
            val count = min(block, frames - done)
            stretcher.render(blockLeft, blockRight, count, done * step, step, jump = false)
            blockLeft.copyInto(left, done, 0, count)
            blockRight.copyInto(right, done, 0, count)
            done += count
        }
        return left to right
    }

    /** Frequency from the spacing of upward zero crossings. */
    private fun frequency(samples: FloatArray, from: Int, to: Int): Double {
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

    private fun energy(samples: FloatArray, from: Int, count: Int): Double {
        var sum = 0.0
        for (i in from until from + count) sum += samples[i] * samples[i].toDouble()
        return sum
    }

    /** Amplitude of the component at [frequency], by projection under a Hann window. */
    private fun amplitudeAt(samples: FloatArray, frequency: Double, from: Int, to: Int): Double {
        var re = 0.0
        var im = 0.0
        var weight = 0.0
        for (i in from until to) {
            val w = 0.5 - 0.5 * cos(2 * PI * (i - from) / (to - from))
            re += w * samples[i] * cos(2 * PI * frequency * i / rate)
            im += w * samples[i] * sin(2 * PI * frequency * i / rate)
            weight += w
        }
        return 2 * hypot(re, im) / weight
    }

    private fun decibels(ratio: Double) = 20 * log10(ratio)

    private fun peakIndex(samples: FloatArray, from: Int, to: Int): Int {
        var best = from
        for (i in from until to) if (abs(samples[i]) > abs(samples[best])) best = i
        return best
    }

    @Test
    fun aStretchedSineKeepsItsPitchAndLevel() {
        val tone = sine(440.0, 6 * rate)
        val from = rate / 4
        val to = 2 * rate + rate / 4
        for (step in listOf(0.5, 0.8, 1.25, 2.0)) {
            val (left, right) = stretch(MemoryAudioSource(rate, tone), to + rate / 4, 256, step)
            assertEquals(440.0, frequency(left, from, to), 440 * 0.005, "pitch at step $step")
            assertEquals(440.0, frequency(right, from, to), 440 * 0.005, "pitch at step $step, right")
            val level = decibels(rms(left, from, to) / rms(tone, from, to))
            assertTrue(abs(level) < 1.5, "level at step $step changed by $level dB")
        }
    }

    @Test
    fun attacksLandWhereTheMappingPutsThem() {
        val clicks = clickTrain(8 * rate)
        for (step in listOf(0.8, 1.25)) {
            val frames = (7.5 * rate / step).toInt()
            val (out, _) = stretch(MemoryAudioSource(rate, clicks), frames, 256, step)
            var checked = 0
            var sourceAt = clickSpacing
            while (sourceAt / step + tenMillis * 2 < frames) {
                val expected = sourceAt / step
                val peak = peakIndex(out, (expected - 2 * tenMillis).toInt(), (expected + 2 * tenMillis).toInt())
                val errorMillis = (peak - expected) * 1000 / rate
                assertTrue(abs(errorMillis) <= 2.0, "attack of $sourceAt at step $step is off by $errorMillis ms")
                sourceAt += clickSpacing
                checked++
            }
            assertTrue(checked >= 10)
        }
    }

    @Test
    fun attacksFollowAStepThatChangesMidWay() {
        val clicks = clickTrain(8 * rate)
        val stretcher = TimeStretcher(MemoryAudioSource(rate, clicks))
        val block = 256
        val calls = 1400
        val out = FloatArray(calls * block)
        val blockLeft = FloatArray(block)
        val blockRight = FloatArray(block)
        val starts = DoubleArray(calls + 1)
        val steps = DoubleArray(calls) { if (it < calls / 2) 0.8 else 1.1 }
        for (call in 0 until calls) {
            stretcher.render(blockLeft, blockRight, block, starts[call], steps[call], jump = false)
            blockLeft.copyInto(out, call * block)
            starts[call + 1] = starts[call] + block * steps[call]
        }
        var checked = 0
        var sourceAt = clickSpacing
        while (sourceAt + clickSpacing < starts[calls]) {
            var call = 0
            while (starts[call + 1] <= sourceAt) call++
            val expected = call * block + (sourceAt - starts[call]) / steps[call]
            val peak = peakIndex(out, (expected - 2 * tenMillis).toInt(), (expected + 2 * tenMillis).toInt())
            val errorMillis = (peak - expected) * 1000 / rate
            assertTrue(abs(errorMillis) <= 3.0, "attack of $sourceAt is off by $errorMillis ms")
            sourceAt += clickSpacing
            checked++
        }
        assertTrue(checked >= 12)
    }

    @Test
    fun attacksKeepTheirEnergy() {
        val clicks = clickTrain(8 * rate)
        for (step in listOf(0.5, 0.8, 1.25)) {
            val frames = (7.5 * rate / step).toInt()
            val (out, _) = stretch(MemoryAudioSource(rate, clicks), frames, 256, step)
            var sourceAt = clickSpacing
            while (sourceAt / step + tenMillis * 2 < frames) {
                val mapped = (sourceAt / step).roundToInt()
                val kept = energy(out, mapped, tenMillis) / energy(clicks, sourceAt, tenMillis)
                assertTrue(kept >= 0.5, "attack of $sourceAt at step $step keeps only ${kept * 100}% of its energy")
                sourceAt += clickSpacing
            }
        }
    }

    @Test
    fun originalSpeedCopiesTheSource() {
        val music = FloatArray(2 * rate) { (0.3 * sin(it * 0.05) + 0.2 * sin(it * 0.731) + 0.1 * sin(it * it * 1e-6)).toFloat() }
        val (left, right) = stretch(MemoryAudioSource(rate, music), rate, 256, 1.0)
        for (i in tenMillis until rate) {
            assertEquals(music[i], left[i], "frame $i")
            assertEquals(music[i], right[i], "frame $i, right")
        }
    }

    @Test
    fun enteringAndLeavingOriginalSpeedDoesNotClickOrShift() {
        val amplitude = 0.5
        val tone = sine(110.0, 4 * rate, amplitude)
        val normalStep = amplitude * 2 * PI * 110 / rate
        val stretcher = TimeStretcher(MemoryAudioSource(rate, tone))
        val block = 256
        val calls = 150
        val out = FloatArray(calls * block)
        val blockLeft = FloatArray(block)
        val blockRight = FloatArray(block)
        var position = 0.0
        var lastSegmentStart = 0.0
        for (call in 0 until calls) {
            val step = if (call in 50 until 100) 0.875 else 1.0
            if (call == 100) lastSegmentStart = position
            stretcher.render(blockLeft, blockRight, block, position, step, jump = false)
            blockLeft.copyInto(out, call * block)
            position += block * step
        }
        var largest = 0.0
        for (i in tenMillis + 1 until out.size) largest = max(largest, abs(out[i] - out[i - 1]).toDouble())
        assertTrue(largest < 1.5 * normalStep, "largest step $largest, a normal one is $normalStep")

        // Back at original speed the output is the source again, at the mapped position.
        val offset = lastSegmentStart.toInt() - 100 * block
        assertEquals(lastSegmentStart, lastSegmentStart.toInt().toDouble())
        for (i in 100 * block + tenMillis until out.size) assertEquals(tone[i + offset], out[i], "frame $i")
    }

    @Test
    fun theBlockSizeDoesNotChangeTheOutput() {
        val frames = 4 * rate
        val clicks = clickTrain(frames)
        val music = FloatArray(frames) { (0.2 * sin(it * 0.02) + 0.15 * sin(it * 0.3 + 1) + clicks[it] * 0.5).toFloat() }
        val other = FloatArray(frames) { (0.25 * sin(it * 0.047)).toFloat() + music[it] * 0.3f }
        val source = MemoryAudioSource(rate, music, other)
        val length = 3 * rate
        val (left, right) = stretch(source, length, 256, 0.8)
        for (block in listOf(64, 1000)) {
            val (otherLeft, otherRight) = stretch(source, length, block, 0.8)
            for (i in 0 until length) {
                assertEquals(left[i], otherLeft[i], 1e-4f, "block $block, frame $i")
                assertEquals(right[i], otherRight[i], 1e-4f, "block $block, frame $i, right")
            }
        }
    }

    @Test
    fun extremeInputsGiveFiniteOutput() {
        val frames = rate
        val clicks = clickTrain(frames)
        val music = FloatArray(frames) { (0.4 * sin(it * 0.03) + clicks[it]).toFloat() }
        val stretcher = TimeStretcher(MemoryAudioSource(rate, music))
        val left = FloatArray(5000)
        val right = FloatArray(5000)
        for (jumpEveryCall in listOf(false, true)) {
            for (step in listOf(0.1, 8.0, 1.0, 0.999)) {
                for (start in listOf(-50_000.0, -0.5, 100.25, frames - 1000.0, frames + 5000.0)) {
                    for (block in listOf(1, 7, 128, 5000)) {
                        var position = start
                        repeat(6) { call ->
                            stretcher.render(left, right, block, position, step, jump = jumpEveryCall || call == 0)
                            for (i in 0 until block) {
                                assertTrue(left[i].isFinite() && abs(left[i]) < 4f, "step $step start $start block $block")
                                assertTrue(right[i].isFinite() && abs(right[i]) < 4f, "step $step start $start block $block")
                            }
                            position += block * step
                        }
                    }
                }
            }
        }
    }

    @Test
    fun outsideTheFileThereIsSilence() {
        val frames = rate
        val source = MemoryAudioSource(rate, sine(440.0, frames))
        val left = FloatArray(512)
        val right = FloatArray(512)
        for (step in listOf(0.1, 0.8, 1.0, 8.0)) {
            for (start in listOf(-200_000.0, frames + 100_000.0)) {
                val stretcher = TimeStretcher(source)
                var position = start
                repeat(8) { call ->
                    stretcher.render(left, right, 512, position, step, jump = call % 2 == 0)
                    for (i in 0 until 512) {
                        assertEquals(0f, left[i], "step $step start $start")
                        assertEquals(0f, right[i], "step $step start $start")
                    }
                    position += 512 * step
                }
            }
        }
    }

    @Test
    fun resetStartsOver() {
        val source = MemoryAudioSource(rate, sine(440.0, rate))
        val stretcher = TimeStretcher(source)
        val first = FloatArray(2048)
        val again = FloatArray(2048)
        val right = FloatArray(2048)
        stretcher.render(first, right, 2048, 1000.0, 0.8, jump = false)
        stretcher.render(again, right, 2048, 30_000.0, 1.3, jump = true)
        stretcher.reset()
        stretcher.render(again, right, 2048, 1000.0, 0.8, jump = false)
        for (i in first.indices) assertEquals(first[i], again[i], "frame $i")
    }

    @Test
    fun leftAndRightStayApart() {
        val frames = 4 * rate
        val source = MemoryAudioSource(rate, sine(440.0, frames), sine(1000.0, frames))
        val from = rate / 2
        val to = 2 * rate
        for (step in listOf(0.8, 1.25)) {
            val (left, right) = stretch(source, to + rate / 4, 256, step)
            val leftOwn = amplitudeAt(left, 440.0, from, to)
            val rightOwn = amplitudeAt(right, 1000.0, from, to)
            assertEquals(0.5, leftOwn, 0.08)
            assertEquals(0.5, rightOwn, 0.08)
            assertTrue(decibels(amplitudeAt(left, 1000.0, from, to) / rightOwn) < -30.0, "right leaks into left")
            assertTrue(decibels(amplitudeAt(right, 440.0, from, to) / leftOwn) < -30.0, "left leaks into right")
        }
    }

    @Test
    fun aCentredSoundStaysCentred() {
        val frames = 3 * rate
        val clicks = clickTrain(frames)
        val music = FloatArray(frames) { (0.3 * sin(it * 0.04) + 0.2 * sin(it * 0.21) + clicks[it] * 0.5).toFloat() }
        val (left, right) = stretch(MemoryAudioSource(rate, music), 2 * rate, 256, 0.8)
        for (i in left.indices) assertEquals(left[i], right[i], 1e-4f, "frame $i")
    }
}
