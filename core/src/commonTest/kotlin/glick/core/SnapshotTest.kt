package glick.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnapshotTest {
    private val busy = MetronomeState(
        bpm = 173.5,
        signature = TimeSignature(7, 8),
        beatSteps = listOf(StepState.Accent, StepState.Mute, StepState.Normal),
        subdivisions = listOf(SubdivisionLine(true, 5, 0.4f), SubdivisionLine(), SubdivisionLine()),
        circle = CircleTimer(
            enabled = true, mode = CircleMode.Seconds, seconds = 12.5,
            actions = listOf(CircleAction(id = 4, target = ParamTarget.Sub2Division, amount = -1.0)),
        ),
        session = SessionTimer(enabled = true, minutes = 25.0),
        sleepMode = true,
        songMode = SongMode.SongFollowsClick,
    ).sanitized()

    @Test
    fun everythingComesBack() {
        val snapshot = Snapshot(
            state = busy,
            timers = TimerProgress(sessionElapsedSeconds = 321.0, circleElapsedSeconds = 3.5, circleCompletions = 9),
            song = SongRef("/music/song.flac", "song.flac"),
            window = WindowBounds(10, 20, 1200, 800),
        )
        assertEquals(snapshot, Snapshot.decode(snapshot.encode()))
    }

    @Test
    fun restoredTimersContinueWhereTheyWere() {
        val m = Metronome(busy.copy(sleepMode = false), nowEpochMillis = { 0L }, timers = TimerProgress(sessionElapsedSeconds = 100.0, circleCompletions = 3))
        assertEquals(100.0, m.transport.value.sessionElapsedSeconds, 1e-9)
        assertEquals(3, m.transport.value.circleCompletions)
        m.play()
        m.advance(2_000_000_000L)
        assertEquals(102.0, m.timerProgress().sessionElapsedSeconds, 1e-6)
        // A restored action keeps its id; new ones get a fresh one.
        m.addCircleAction()
        assertEquals(listOf(4L, 5L), m.state.value.circle.actions.map { it.id })
    }

    @Test
    fun damagedOrOlderFilesFallBackGracefully() {
        assertNull(Snapshot.decode(null))
        assertNull(Snapshot.decode("{ not json"))
        // A file from a version that knew fewer fields, plus one this version does not know.
        val old = """{"version":0,"state":{"bpm":99.0,"fromTheFuture":true}}"""
        val restored = Snapshot.decode(old)!!
        assertEquals(99.0, restored.state.bpm)
        assertEquals(MetronomeState().signature, restored.state.signature)
        // Out-of-range values are brought back into range.
        assertEquals(MAX_BPM, Snapshot.decode("""{"state":{"bpm":1e9}}""")!!.state.bpm)
    }
}
