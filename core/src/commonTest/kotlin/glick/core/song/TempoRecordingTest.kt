package glick.core.song

import glick.core.TimeSignature
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TempoRecordingTest {
    private val threeFour = SongGrid(signatures = listOf(SignatureChange(0, TimeSignature(3, 4))))

    @Test
    fun aSteadyGridCoversTheFileAndPutsBarOneOnTheFirstBeat() {
        val grid = threeFour.steadyAt(bpm = 120.0, firstBeat = 2.3, duration = 20.0)
        // Beats every 0.5 s from the start of the file to its end.
        assertEquals(0.3, grid.beats.first().seconds, 1e-9)
        assertTrue(grid.beats.last().seconds > 19.5)
        grid.beats.zipWithNext { a, b -> assertEquals(0.5, b.seconds - a.seconds, 1e-9) }
        // A bar line falls exactly on the chosen first beat.
        val timeline = SongTimeline(grid)
        assertTrue((0..timeline.barCount).any { abs(timeline.barStartSeconds(it) - 2.3) < 1e-9 })
        assertEquals(2.3, grid.firstBeat)
        assertEquals(120.0, grid.knownBpm)
        // Sliding it keeps the tempo and moves every beat.
        val slid = grid.steadyAt(120.0, 2.42, 20.0)
        assertEquals(0.42, slid.beats.first().seconds, 1e-9)
        // Moving one beat by hand ends the steady state.
        assertNull(grid.moveBeat(5, 2.85).firstBeat)
    }

    @Test
    fun tapsBecomePinnedBeatsAndTheGridIsExtendedBothWays() {
        // Taps from 10 s at 0.6 s apart, slowing to 0.7 s at the end.
        val taps = listOf(10.0, 10.6, 11.2, 11.8, 12.4, 13.1, 13.8, 14.5, 15.2)
        val grid = threeFour.withTaps(taps, duration = 30.0)
        val times = grid.beats.map { it.seconds }
        assertTrue(times.first() < 0.6 && times.first() >= 0.0)
        assertTrue(times.last() > 29.3)
        // Before the first tap the grid continues at the opening tempo, after the last at the closing one.
        assertEquals(0.6, times[1] - times[0], 1e-9)
        assertEquals(0.7, times.last() - times[times.size - 2], 1e-9)
        assertEquals(taps.size, grid.beats.count { it.pinned })
        // The first tap starts a bar.
        val timeline = SongTimeline(grid)
        assertTrue((0..timeline.barCount).any { abs(timeline.barStartSeconds(it) - 10.0) < 1e-9 })
        assertFalse(grid.unmapped)
        // One tap is not a tempo.
        assertEquals(threeFour, threeFour.withTaps(listOf(4.0), 30.0))
        // Double hits within a tenth of a second count once.
        assertEquals(2, threeFour.withTaps(listOf(1.0, 1.03, 1.5), 3.0).beats.count { it.pinned })
    }

    @Test
    fun snappingMovesTapsOntoNearbyAttacksOnly() {
        val grid = SongGrid().withTaps(listOf(1.03, 1.48, 2.02, 2.5), duration = 4.0)
        val snapped = grid.snappedTo(attacks = listOf(1.0, 1.5, 2.0, 2.2))
        val pinned = snapped.beats.filter { it.pinned }.map { it.seconds }
        assertEquals(listOf(1.0, 1.5, 2.0, 2.5), pinned) // 2.5 has no attack within reach and stays
    }

    @Test
    fun smoothingRemovesScatterButKeepsTheTempo() {
        val scatter = listOf(0.03, -0.02, 0.025, -0.03, 0.02, -0.025, 0.03, -0.02, 0.01, -0.03, 0.02, -0.01)
        val taps = scatter.mapIndexed { i, error -> 5.0 + i * 0.5 + error }
        val smooth = SongGrid().withTaps(taps, duration = 12.0).smoothed()
        val tapped = smooth.beats.filter { it.pinned }.map { it.seconds }
        val worst = tapped.mapIndexed { i, t -> abs(t - (5.0 + i * 0.5)) }.max()
        assertTrue(worst < 0.02, "worst error after smoothing: $worst")
        val intervals = tapped.zipWithNext { a, b -> b - a }
        assertEquals(0.5, intervals.average(), 0.005)
    }

    @Test
    fun attacksAreFoundWhereTheHitsAre() {
        val rate = 22_050
        val audio = FloatArray(rate * 6)
        val hits = listOf(1.0, 1.75, 2.5, 3.6, 4.2)
        for (hit in hits) {
            val start = (hit * rate).toInt()
            for (i in 0 until rate / 20) {
                val t = i.toDouble() / rate
                audio[start + i] += (0.8 * kotlin.math.sin(2 * kotlin.math.PI * 300 * t) * kotlin.math.exp(-t / 0.01)).toFloat()
            }
        }
        val found = BeatDetector.attacks(MemoryAudioSource(rate, audio))
        for (hit in hits) assertTrue(found.any { abs(it - hit) < 0.012 }, "no attack near $hit in $found")
        assertTrue(found.size <= hits.size + 1)
    }
}
