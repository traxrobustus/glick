package glick.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SECOND = 1_000_000_000L

class MetronomeTest {
    private var now = 1_000_000L
    private fun metronome(state: MetronomeState = MetronomeState()) =
        Metronome(state, nowEpochMillis = { now }, random = Random(1))

    @Test
    fun advancesThroughBeatsAndBars() {
        val m = metronome(MetronomeState(bpm = 120.0, signature = TimeSignature(3, 8)))
        m.play()
        m.advance(SECOND * 3 / 4) // 1.5 beats
        assertEquals(1, m.transport.value.beat)
        assertEquals(0.5, m.transport.value.beatPhase, 1e-9)
        m.advance(SECOND) // 3.5 beats in total
        assertEquals(0, m.transport.value.beat)
        assertEquals(1L, m.transport.value.bar)
    }

    @Test
    fun oneLargeStepEqualsManySmallOnes() {
        val a = metronome(MetronomeState(bpm = 5000.0, signature = TimeSignature(7, 16)))
        val b = metronome(MetronomeState(bpm = 5000.0, signature = TimeSignature(7, 16)))
        a.play(); b.play()
        a.advance(SECOND)
        repeat(1000) { b.advance(SECOND / 1000) }
        assertEquals(a.transport.value.bar, b.transport.value.bar)
        assertEquals(a.transport.value.beat, b.transport.value.beat)
    }

    @Test
    fun circleTimerInBarsRaisesTempo() {
        val m = metronome(MetronomeState(bpm = 60.0, circle = CircleTimer(enabled = true, bars = 2)))
        m.addCircleAction()
        m.play()
        m.advance(SECOND * 7) // 7 beats: not yet two bars
        assertEquals(60.0, m.state.value.bpm)
        m.advance(SECOND * 2)
        assertEquals(65.0, m.state.value.bpm)
        assertEquals(1, m.transport.value.circleCompletions)
    }

    @Test
    fun circleTimerInSecondsLoops() {
        val circle = CircleTimer(enabled = true, mode = CircleMode.Seconds, seconds = 2.0)
        val m = metronome(MetronomeState(circle = circle))
        m.play()
        m.advance(SECOND * 5)
        assertEquals(2, m.transport.value.circleCompletions)
        assertEquals(0.5f, m.transport.value.circleProgress, 1e-4f)
    }

    @Test
    fun actionsCanTargetAnyParameterAndAreClamped() {
        val circle = CircleTimer(enabled = true, mode = CircleMode.Seconds, seconds = 1.0)
        val m = metronome(MetronomeState(circle = circle))
        m.addCircleAction()
        m.update { s ->
            s.copy(circle = s.circle.copy(actions = s.circle.actions.map {
                it.copy(target = ParamTarget.Sub1Division, amount = 20.0)
            }))
        }
        m.play()
        m.advance(SECOND * 3)
        assertEquals(MAX_DIVISION, m.state.value.subdivisions[0].division)
    }

    @Test
    fun sessionTimerPausesOnStopAndStopsTheClick() {
        val m = metronome(MetronomeState(session = SessionTimer(enabled = true, minutes = 1.0)))
        m.play()
        m.advance(SECOND * 20)
        m.stop()
        m.advance(SECOND * 20) // stopped: nothing moves
        assertEquals(20.0, m.transport.value.sessionElapsedSeconds, 1e-6)
        m.play()
        m.advance(SECOND * 50)
        assertFalse(m.transport.value.playing)
        assertTrue(m.transport.value.sessionFinished)
        assertEquals(60.0, m.transport.value.sessionElapsedSeconds, 1e-6)
        m.resetSession()
        assertEquals(0.0, m.transport.value.sessionElapsedSeconds)
    }

    @Test
    fun sessionTimerCanLetTheClickRunOn() {
        val session = SessionTimer(enabled = true, minutes = 0.5, stopOnCompletion = false)
        val m = metronome(MetronomeState(session = session))
        m.play()
        m.advance(SECOND * 40)
        assertTrue(m.transport.value.playing)
        assertTrue(m.transport.value.sessionFinished)
    }

    @Test
    fun sleepModePlaysPlainFortyBpmAndKeepsSettings() {
        val m = metronome(MetronomeState(bpm = 200.0, sleepMode = true, silence = RandomSilence(true, 1f)))
        m.play()
        m.advance(SECOND * 3) // two beats at 40 BPM
        assertEquals(2, m.transport.value.beat)
        assertFalse(m.transport.value.beatSilenced)
        assertEquals(200.0, m.state.value.bpm)
    }

    @Test
    fun fullSilenceProbabilityDropsEveryBeat() {
        val m = metronome(MetronomeState(silence = RandomSilence(true, 1f)))
        m.play()
        assertTrue(m.transport.value.beatSilenced)
        m.advance(SECOND)
        assertTrue(m.transport.value.beatSilenced)
    }

    @Test
    fun everyRunIsLogged() {
        val m = metronome()
        m.play()
        now += 90_000
        m.stop()
        val entry = m.log.value.single()
        assertEquals(90_000L, entry.durationMillis)
        assertEquals("4/4", entry.signature)
        assertEquals(entry, assertNotNull(LogEntry.parse(entry.toLine())))
    }

    @Test
    fun stateIsClampedToLegalRanges() {
        val s = MetronomeState(bpm = 99999.0, signature = TimeSignature(0, 5)).sanitized()
        assertEquals(MAX_BPM, s.bpm)
        assertEquals(TimeSignature(1, 4), s.signature)
        assertEquals(listOf(StepState.Accent), s.beatSteps)
        val wide = MetronomeState(signature = TimeSignature(3, 4)).sanitized()
        assertEquals(listOf(StepState.Accent, StepState.Normal, StepState.Normal), wide.beatSteps)
        assertEquals(6, wide.subdivisions[0].steps.size)
        assertEquals(3, wide.polyVoices[0].steps.size)
    }
}
