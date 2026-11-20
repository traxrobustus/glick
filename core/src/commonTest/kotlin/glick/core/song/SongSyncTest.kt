package glick.core.song

import glick.core.CircleAction
import glick.core.CircleMode
import glick.core.CircleTimer
import glick.core.Metronome
import glick.core.MetronomeState
import glick.core.ParamTarget
import glick.core.SongMode
import glick.core.TimeSignature
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongSyncTest {
    /** A song whose tempo drifts: beat i lasts 0.5 s, growing by 1 ms per beat. */
    private fun driftingGrid(beats: Int = 64, signature: TimeSignature = TimeSignature(4, 4)): SongGrid {
        var t = 1.0
        val markers = List(beats) { i -> BeatMarker(t).also { t += 0.5 + 0.001 * i } }
        return SongGrid(beats = markers, signatures = listOf(SignatureChange(0, signature))).sanitized()
    }

    @Test
    fun timelineMapsBeatsBarsAndTimeBothWays() {
        val timeline = SongTimeline(driftingGrid(beats = 10, signature = TimeSignature(3, 4)))
        assertEquals(3, timeline.barCount) // 10 beats: three whole bars of three, one beat over
        assertEquals(listOf(0, 3, 6, 9), (0..3).map(timeline::barStartBeat))
        assertEquals(1, timeline.barOfBeat(5))
        assertEquals(1.0, timeline.secondsAt(0.0), 1e-12)
        val middle = timeline.secondsAt(4.5)
        assertEquals(4.5, timeline.positionAt(middle), 1e-9)
        assertEquals(60.0 / 0.5, timeline.bpmOfBeat(0), 1e-9)
    }

    @Test
    fun clickFollowsTheSongsDriftingTempoExactly() {
        val grid = driftingGrid()
        val timeline = SongTimeline(grid)
        val m = Metronome(MetronomeState(songMode = SongMode.ClickFollowsSong), nowEpochMillis = { 0L })
        m.setSong(timeline, resetSpeed = true)
        m.play()
        var clock = 0.0
        val block = 256 * 1e9 / 48_000
        // In follow mode at speed 1 the song must run at exactly one second per second.
        val start = timeline.barStartSeconds(0)
        repeat(4000) {
            m.advance(block)
            clock += block / 1e9
            val expected = start + clock
            assertTrue(abs(m.transport.value.songSeconds - expected) < 1e-6, "drifted at $clock s")
        }
    }

    @Test
    fun aStretchedSongStaysOnTheBeatWhenTheTempoChangesMidBeat() {
        val grid = driftingGrid()
        val timeline = SongTimeline(grid)
        val circle = CircleTimer(
            enabled = true, mode = CircleMode.Seconds, seconds = 0.73, sound = false,
            actions = listOf(CircleAction(id = 1, target = ParamTarget.Tempo, amount = 7.0)),
        )
        val m = Metronome(
            MetronomeState(bpm = 90.0, songMode = SongMode.SongFollowsClick, circle = circle),
            nowEpochMillis = { 0L },
        )
        m.setSong(timeline, resetSpeed = true)
        m.play()
        val block = 256 * 1e9 / 48_000
        var checked = 0
        repeat(6000) {
            m.advance(block)
            val t = m.transport.value
            // Whatever the tempo did, the song position is the one of the click's beat position.
            val bar = timeline.barOfBeat(timeline.positionAt(t.songSeconds + 1e-9).toInt())
            val expected = timeline.secondsAt(timeline.barStartBeat(bar) + t.beat + t.beatPhase)
            assertTrue(abs(t.songSeconds - expected) < 1e-6, "song off the beat")
            checked++
        }
        assertTrue(m.state.value.bpm > 150.0, "the circle timer should have raised the tempo many times")
        assertEquals(6000, checked)
    }

    @Test
    fun tempoChangesInFollowModeBecomeASpeedFactor() {
        val timeline = SongTimeline(driftingGrid())
        val m = Metronome(MetronomeState(songMode = SongMode.ClickFollowsSong), nowEpochMillis = { 0L })
        m.setSong(timeline, resetSpeed = true)
        m.play()
        val own = m.state.value.bpm
        m.update { it.copy(bpm = own * 0.5) } // practise at half speed
        val block = 256 * 1e9 / 48_000
        val before = m.transport.value.songSeconds.coerceAtLeast(timeline.barStartSeconds(0))
        repeat(1875) { m.advance(block) } // 10 s of real time
        val advanced = m.transport.value.songSeconds - before
        assertEquals(5.0, advanced, 0.02) // half speed: 5 s of song
        // The click still follows the song's drift, at half its tempo.
        val beat = timeline.positionAt(m.transport.value.songSeconds).toInt()
        assertEquals(timeline.bpmOfBeat(beat) * 0.5, m.state.value.bpm, 0.01)
    }

    @Test
    fun theLoopWrapsOnBarLinesAndTheSignatureFollowsTheSong() {
        val grid = driftingGrid(beats = 40)
            .setSignature(2, TimeSignature(3, 4))
            .setLoop(1, 4)
        val timeline = SongTimeline(grid)
        val m = Metronome(MetronomeState(songMode = SongMode.ClickFollowsSong), nowEpochMillis = { 0L })
        m.setSong(timeline, resetSpeed = true)
        m.play()
        assertEquals(timeline.barStartSeconds(1), m.transport.value.songSeconds, 1e-9)
        assertEquals(TimeSignature(4, 4), m.state.value.signature)
        val loopLength = timeline.barStartSeconds(4) - timeline.barStartSeconds(1)
        val block = 256 * 1e9 / 48_000
        var sawThree = false
        var wraps = 0
        var last = m.transport.value.songSeconds
        var elapsed = 0.0
        while (elapsed < loopLength * 2.5) {
            m.advance(block)
            elapsed += block / 1e9
            val t = m.transport.value
            if (m.state.value.signature == TimeSignature(3, 4)) sawThree = true
            if (t.songSeconds < last) wraps++
            assertTrue(t.songSeconds >= timeline.barStartSeconds(1) - 1e-6)
            assertTrue(t.songSeconds <= timeline.barStartSeconds(4) + 1e-6)
            last = t.songSeconds
        }
        assertTrue(sawThree, "bars 2 and 3 are in 3/4")
        assertEquals(2, wraps)
    }

    @Test
    fun gridEditsBehaveLikeWarpMarkers() {
        val even = SongGrid(beats = List(9) { BeatMarker(it * 1.0) }).sanitized()
        // The first drag slides the whole grid.
        val slid = even.moveBeat(4, 4.2)
        assertEquals(List(9) { it + 0.2 }, slid.beats.map { kotlin.math.round(it.seconds * 1e9) / 1e9 })
        // A second pinned beat stretches only the beats between the two pins.
        val stretched = slid.moveBeat(8, 9.2)
        assertEquals(4.2, stretched.beats[4].seconds, 1e-9)
        assertEquals(6.7, stretched.beats[6].seconds, 1e-9)
        assertEquals(0.2, stretched.beats[0].seconds, 1e-9)

        assertEquals(17, even.doubleTempo().beats.size)
        assertEquals(even.beats.map { it.seconds }, even.doubleTempo().halveTempo().beats.map { it.seconds })
        assertEquals(2, even.setBarStart(6).downbeat)
        assertEquals(even, SongGrid.decode(even.encode()))
    }

    @Test
    fun theSongHasNoPositionWhileTheClickIsStopped() {
        val m = Metronome(MetronomeState(songMode = SongMode.SongFollowsClick), nowEpochMillis = { 0L })
        m.setSong(SongTimeline(driftingGrid()), resetSpeed = true)
        assertTrue(m.transport.value.songSeconds < 0)
        m.play()
        m.advance(500_000_000L)
        assertTrue(m.transport.value.songSeconds > 0)
        m.stop()
        assertTrue(m.transport.value.songSeconds < 0, "a stopped song must not hold its position: the engine would keep sounding it")
        m.advance(500_000_000L)
        assertTrue(m.transport.value.songSeconds < 0)
    }
}
