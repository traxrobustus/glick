package glick.core.song

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeatDetectorTest {
    @Test
    fun aSteadyFourFourSongIsTrackedOnTheBeatWithItsBarsFound() {
        val truth = steadyBeats(bpm = 120.0, first = 0.3, seconds = 30.0)
        val found = BeatDetector.detect(drums(22050, 30.0, truth, beatsPerBar = 4))

        assertTrue(matched(truth, found.beats, tolerance = 0.035) >= 0.95)
        assertOnePerBeat(truth, found.beats)
        assertEquals(120.0, found.bpm, 1.2)
        assertEquals(4, found.beatsPerBar)
        assertEquals(4, found.noteLength)
        assertTrue(found.downbeat in 0 until 4)
        assertStartsABar(found.beats[found.downbeat], firstBar = 0.3, barSeconds = 2.0)
    }

    @Test
    fun aWaltzIsHeardInThree() {
        val truth = steadyBeats(bpm = 90.0, first = 0.3, seconds = 30.0)
        val found = BeatDetector.detect(drums(22050, 30.0, truth, beatsPerBar = 3))

        assertTrue(matched(truth, found.beats, tolerance = 0.035) >= 0.95)
        assertEquals(90.0, found.bpm, 0.9)
        assertEquals(3, found.beatsPerBar)
        assertStartsABar(found.beats[found.downbeat], firstBar = 0.3, barSeconds = 2.0)
    }

    @Test
    fun aSongThatSpeedsUpIsFollowed() {
        val truth = ArrayList<Double>()
        var time = 0.3
        while (time < 39.5) {
            truth += time
            time += 60 / (100 + 30 * time / 40)
        }
        val found = BeatDetector.detect(drums(22050, 40.0, truth, beatsPerBar = 4))

        assertTrue(matched(truth, found.beats, tolerance = 0.040) >= 0.90)
        assertOnePerBeat(truth, found.beats)
    }

    @Test
    fun beatsRunThroughLeadingSilenceAndStayAligned() {
        val truth = steadyBeats(bpm = 120.0, first = 2.0, seconds = 30.0)
        val found = BeatDetector.detect(drums(22050, 30.0, truth, beatsPerBar = 4))

        assertTrue(matched(truth, found.beats, tolerance = 0.035) >= 0.95)
        // The grid continues back to the start of the file, in step with the music.
        assertTrue(found.beats.first() < 0.5)
        assertTrue(found.beats.count { it < 1.98 } >= 3)
        for (beat in found.beats.filter { it < 1.98 }) assertStartsABar(beat, firstBar = 0.0, barSeconds = 0.5)
        assertStartsABar(found.beats[found.downbeat], firstBar = 0.0, barSeconds = 2.0)
    }

    @Test
    fun stereoAtFortyFourOneIsTimedLikeAnyOtherRate() {
        val truth = steadyBeats(bpm = 104.0, first = 0.4, seconds = 20.0)
        val left = drums(44100, 20.0, truth, beatsPerBar = 4, seed = 1)
        val right = drums(44100, 20.0, truth, beatsPerBar = 4, seed = 2)
        val found = BeatDetector.detect(Stereo(left, right))

        assertTrue(matched(truth, found.beats, tolerance = 0.035) >= 0.95)
        assertEquals(104.0, found.bpm, 1.04)
        assertEquals(4, found.beatsPerBar)
    }

    @Test
    fun beatsCoverTheFileAndProgressRunsToTheEnd() {
        val truth = steadyBeats(bpm = 120.0, first = 0.3, seconds = 12.0)
        var last = 0f
        val found = BeatDetector.detect(drums(22050, 20.0, truth, beatsPerBar = 4)) {
            assertTrue(it >= last && it <= 1f)
            last = it
        }

        assertEquals(1f, last)
        assertTrue(found.beats.zipWithNext().all { (a, b) -> b > a })
        assertTrue(found.beats.first() < 0.5)
        // The last eight seconds are silent, yet still carry beats in step with the music.
        assertTrue(found.beats.last() > 19.5)
        for (beat in found.beats.filter { it > 12.5 }) assertStartsABar(beat, firstBar = 0.3, barSeconds = 0.5)
    }

    @Test
    fun aSilentFileGetsAPlainGrid() {
        val found = BeatDetector.detect(MemoryAudioSource(22050, FloatArray(22050 * 3)))

        assertEquals(listOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0), found.beats)
        assertEquals(120.0, found.bpm)
        assertEquals(4, found.beatsPerBar)
        assertEquals(0, found.downbeat)
    }

    @Test
    fun filesTooShortToHaveATempoStillGetAnAnswer() {
        val empty = BeatDetector.detect(MemoryAudioSource(22050, FloatArray(0)))
        assertEquals(listOf(0.0), empty.beats)

        val oneHit = BeatDetector.detect(drums(22050, 0.4, listOf(0.1), beatsPerBar = 4))
        assertEquals(1, oneHit.beats.size)
        assertEquals(0.1, oneHit.beats.single(), 0.035)
        assertEquals(4, oneHit.beatsPerBar)
        assertEquals(0, oneHit.downbeat)
    }

    @Test
    fun steadyFitsAnEvenGridThroughJitteredBeats() {
        val random = Random(3)
        val interval = 60 / 97.0
        val jittered = List(80) { 1.5 + it * interval + (random.nextDouble() - 0.5) * 0.03 }
        val grid = BeatDetector.steady(jittered)

        assertEquals(jittered.size, grid.size)
        val step = grid[1] - grid[0]
        for ((a, b) in grid.zipWithNext()) assertEquals(step, b - a, 1e-9)
        assertEquals(interval, step, interval * 0.005)
        assertEquals(1.5, grid.first(), 0.01)
    }

    private fun steadyBeats(bpm: Double, first: Double, seconds: Double): List<Double> =
        generateSequence(first) { it + 60 / bpm }.takeWhile { it < seconds - 0.5 }.toList()

    /** Share of the true beats that have a found beat within [tolerance] seconds. */
    private fun matched(truth: List<Double>, found: List<Double>, tolerance: Double): Double =
        truth.count { beat -> found.any { abs(it - beat) <= tolerance } }.toDouble() / truth.size

    /** Fails when the tracker runs at double tempo, which would still match every true beat. */
    private fun assertOnePerBeat(truth: List<Double>, found: List<Double>) {
        val within = found.count { it > truth.first() - 0.1 && it < truth.last() + 0.1 }
        assertTrue(abs(within - truth.size) <= truth.size / 20, "found $within beats where ${truth.size} were played")
    }

    private fun assertStartsABar(time: Double, firstBar: Double, barSeconds: Double) {
        val bars = (time - firstBar) / barSeconds
        assertTrue(abs(bars - bars.roundToInt()) * barSeconds <= 0.035, "$time s is not a bar start")
    }

    /**
     * A drum track with a hit on every beat: a low kick on bar starts and a
     * higher tick on the other beats.
     */
    private fun drums(sampleRate: Int, seconds: Double, beats: List<Double>, beatsPerBar: Int, seed: Int = 7): MemoryAudioSource {
        val samples = FloatArray((seconds * sampleRate).toInt())
        val random = Random(seed)
        for ((index, beat) in beats.withIndex()) {
            val start = (beat * sampleRate).roundToInt()
            val kick = index % beatsPerBar == 0
            for (i in 0 until (0.3 * sampleRate).toInt()) {
                if (start + i >= samples.size) break
                val t = i.toDouble() / sampleRate
                val noise = random.nextDouble() * 2 - 1
                samples[start + i] += if (kick) {
                    (0.8 * sin(2 * PI * 60 * t) * exp(-t / 0.09) + 0.3 * noise * exp(-t / 0.004)).toFloat()
                } else {
                    (0.4 * sin(2 * PI * 1200 * t) * exp(-t / 0.03) + 0.15 * noise * exp(-t / 0.01)).toFloat()
                }
            }
        }
        return MemoryAudioSource(sampleRate, samples)
    }

    /** Two mono sources played as the left and right channel. */
    private class Stereo(private val left: AudioSource, private val right: AudioSource) : AudioSource {
        private val unused = FloatArray(1 shl 20)
        override val sampleRate: Int get() = left.sampleRate
        override val channels: Int get() = 2
        override val frames: Long get() = left.frames

        override fun read(start: Long, left: FloatArray, right: FloatArray, offset: Int, count: Int) {
            this.left.read(start, left, unused, offset, count)
            this.right.read(start, unused, right, offset, count)
        }
    }
}
