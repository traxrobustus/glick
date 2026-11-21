package glick.core.song

import glick.core.LogEntry
import glick.core.Metronome
import glick.core.MetronomeState
import glick.core.SongMode
import glick.core.SongWorkout
import glick.core.TimeSignature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkoutAndVariationsTest {
    /** A steady 120 BPM song: 4 bars of 4/4 loop in 8 seconds. */
    private fun steadySong(): SongTimeline =
        SongTimeline(SongGrid().steadyAt(120.0, 0.0, 40.0).setLoop(0, 4))

    /** Plays until the click stops; returns the tempo of every pass and the total seconds played. */
    private fun runWorkout(mode: SongMode, workout: SongWorkout): Pair<List<Double>, Double> {
        val m = Metronome(MetronomeState(songMode = mode, workout = workout), nowEpochMillis = { 0L })
        m.setSong(steadySong(), resetSpeed = true)
        if (mode == SongMode.SongFollowsClick) m.update { it.copy(bpm = 133.0) }
        val startTempo = m.state.value.bpm
        m.play()
        val tempos = mutableListOf<Double>()
        var seconds = 0.0
        val block = 256 * 1e9 / 48_000
        while (m.transport.value.playing && seconds < 600) {
            val t = m.transport.value
            if (tempos.size == t.workoutPass) tempos += t.workoutBpm
            // Within a pass the click's tempo is the pass's tempo.
            assertEquals(t.workoutBpm, m.state.value.bpm, 0.01)
            m.advance(block)
            seconds += block / 1e9
        }
        assertFalse(m.transport.value.playing, "the workout must stop by itself")
        // Afterwards nothing of the ladder is left: no pass shown, and the tempo is the one from before.
        assertEquals(-1, m.transport.value.workoutPass)
        assertEquals(startTempo, m.state.value.bpm, 0.01)
        return tempos to seconds
    }

    @Test
    fun theLadderClimbsToTheSongsOwnTempoAndStops() {
        for (mode in SongMode.entries) {
            val (tempos, seconds) = runWorkout(mode, SongWorkout(enabled = true, startBpm = 80.0, stepBpm = 15.0))
            // 80, 95, 110, then the last pass exactly at the song's 120.
            assertEquals(listOf(80.0, 95.0, 110.0, 120.0), tempos, "mode $mode")
            // Each pass is 16 beats at its tempo.
            val expected = tempos.sumOf { 16 * 60.0 / it }
            assertEquals(expected, seconds, 0.05, "mode $mode")
        }
    }

    @Test
    fun theMaximumCanBeFasterThanTheSong() {
        val workout = SongWorkout(enabled = true, startBpm = 100.0, stepBpm = 25.0, maxBpm = 150.0)
        assertEquals(listOf(100.0, 125.0, 150.0), runWorkout(SongMode.ClickFollowsSong, workout).first)
        assertEquals(3, workout.passes(150.0))
        assertEquals(4, SongWorkout(startBpm = 80.0, stepBpm = 15.0).passes(120.0))
    }

    @Test
    fun aPlainSongRunHasNoWorkoutAndIsLabelledInTheLog() {
        val m = Metronome(nowEpochMillis = { 0L })
        m.songName = "song.mp3"
        m.setSong(steadySong(), resetSpeed = true)
        m.play()
        m.advance(1_000_000_000L)
        assertEquals(-1, m.transport.value.workoutPass)
        m.stop()
        val entry = m.log.value.single()
        assertEquals("song.mp3", entry.label)
        assertEquals(entry, LogEntry.parse(entry.toLine()))
        // An entry written before labels existed still reads.
        assertEquals("", LogEntry.parse("1\t2\t120.0\t120.0\t4/4\t0")!!.label)
    }

    @Test
    fun variationsKeepTheirOwnLoopAndSettingsOverOneSharedBeatMap() {
        val base = SongGrid().steadyAt(120.0, 0.0, 40.0)
        assertEquals(listOf(DEFAULT_VARIATION), base.variations.map { it.name })

        val slow = MetronomeState(songMode = SongMode.SongFollowsClick, bpm = 90.0)
        val chorus = base.setLoop(4, 8).capturing(slow).addVariation().renameVariation("Chorus")
        assertEquals(listOf(DEFAULT_VARIATION, "Chorus"), chorus.variations.map { it.name })
        assertEquals(1, chorus.variation)

        // Changing the loop of "Chorus" leaves "Whole song" alone.
        val edited = chorus.setLoop(5, 7).capturing(slow)
        assertEquals(4, edited.variations[0].loopStartBar)
        assertEquals(5, edited.variations[1].loopStartBar)
        assertEquals(90.0, edited.variations[1].bpm)

        val back = edited.withVariation(0)
        assertEquals(4 to 8, back.loopStartBar to back.loopEndBar)
        // Both see a beat that is corrected afterwards.
        val moved = back.moveBeat(10, back.beats[10].seconds + 0.02)
        assertEquals(moved.beats, moved.withVariation(1).beats)

        val deleted = edited.deleteVariation()
        assertEquals(listOf(DEFAULT_VARIATION), deleted.variations.map { it.name })
        assertEquals(deleted, deleted.deleteVariation()) // the last one stays
        assertEquals(edited, SongGrid.decode(edited.encode()))
    }

    @Test
    fun signatureChangesCanBeAddedAndRemovedAnywhere() {
        val grid = SongGrid().steadyAt(120.0, 0.0, 60.0)
            .setSignature(2, TimeSignature(3, 4))
            .setSignature(5, TimeSignature(7, 8))
        assertEquals(listOf(0, 2, 5), grid.signatures.map { it.bar })
        val timeline = SongTimeline(grid)
        assertEquals(listOf(0, 4, 8, 11, 14, 17, 24), (0..6).map(timeline::barStartBeat))
        val removed = grid.removeSignature(2)
        assertEquals(listOf(0, 5), removed.signatures.map { it.bar })
        assertEquals(grid, grid.removeSignature(0)) // bar 1 always has a signature
        assertTrue(SongTimeline(removed).barStartBeat(5) == 20)
    }
}
