package glick.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClickEngineTest {
    /** Added to a voice id in the collected clicks when the step was accented. */
    private val ACCENTED = 100

    private fun metronome(state: MetronomeState) = Metronome(state, nowEpochMillis = { 0L })

    /** Collects (time in beats, voice) for everything clicked before [beats] beats have passed. */
    private fun clicks(state: MetronomeState, beats: Double, stepNanos: Double): List<Pair<Double, Int>> {
        val m = metronome(state)
        val beatNanos = 60e9 / state.bpm
        val found = mutableListOf<Pair<Double, Int>>()
        var base = 0.0
        m.play()
        while (base < beats * beatNanos - 1e-3) {
            m.advance(stepNanos) { offset, voice, _, accent ->
                found += (base + offset) / beatNanos to (if (accent) ACCENTED + voice else voice)
            }
            base += stepNanos
        }
        return found.filter { it.first < beats - 1e-9 }
    }

    @Test
    fun everyLaneClicksAtItsExactPosition() {
        val state = MetronomeState(
            bpm = 90.0,
            signature = TimeSignature(4, 4),
            subdivisions = listOf(SubdivisionLine(true, 3), SubdivisionLine(), SubdivisionLine()),
            polyVoices = listOf(PolyVoice(true, 3), PolyVoice(), PolyVoice(), PolyVoice()),
        )
        // An awkward block length, so clicks fall mid-block and on block edges.
        val found = clicks(state, beats = 4.0, stepNanos = 60e9 / 90.0 / 7.3)
        fun times(voice: Int) = found.filter { it.second == voice }.map { it.first }

        assertEquals(listOf(0.0), times(ACCENTED + Voices.BEAT))
        assertEquals(listOf(1.0, 2.0, 3.0), times(Voices.BEAT).map { kotlin.math.round(it * 1e6) / 1e6 })
        val triplets = times(Voices.SUB)
        assertEquals(12, triplets.size)
        triplets.forEachIndexed { i, t -> assertEquals(i / 3.0, t, 1e-9) }
        val poly = times(Voices.POLY)
        assertEquals(3, poly.size)
        poly.forEachIndexed { i, t -> assertEquals(i * 4.0 / 3.0, t, 1e-9) }
    }

    @Test
    fun blockSizeDoesNotChangeWhatIsPlayed() {
        val state = MetronomeState(
            bpm = 4999.0,
            signature = TimeSignature(7, 16),
            subdivisions = listOf(SubdivisionLine(true, 5), SubdivisionLine(true, 2), SubdivisionLine()),
            polyVoices = listOf(PolyVoice(true, 11), PolyVoice(), PolyVoice(), PolyVoice()),
        )
        val coarse = clicks(state, beats = 70.0, stepNanos = 9.7e6)
        val fine = clicks(state, beats = 70.0, stepNanos = 0.31e6)
        assertTrue(coarse.size > 500)
        assertEquals(coarse.map { it.second }.sorted(), fine.map { it.second }.sorted())
    }

    @Test
    fun beatsLandOnExactSamples() {
        val rate = 48_000
        val engine = ClickEngine(metronome(MetronomeState(bpm = 120.0, beatSteps = emptyList())).also { it.play() }, rate)
        val block = FloatArray(256)
        val audio = FloatArray(rate * 3)
        var written = 0
        while (written + block.size <= audio.size) {
            engine.render(block, block.size)
            block.copyInto(audio, written)
            written += block.size
        }
        // Each click is the same waveform, so its first audible sample marks its start.
        val onsets = mutableListOf<Int>()
        var quietFor = rate
        for (i in 0 until written) {
            if (abs(audio[i]) > 1e-4f) {
                if (quietFor > rate / 10) onsets += i
                quietFor = 0
            } else quietFor++
        }
        assertEquals(6, onsets.size)
        for (i in 1 until onsets.size) assertEquals(24_000, onsets[i] - onsets[i - 1])
    }

    @Test
    fun randomSilenceAlsoSilencesTheSound() {
        val engine = ClickEngine(metronome(MetronomeState(silence = RandomSilence(true, 1f))).also { it.play() }, 48_000)
        val block = FloatArray(4800)
        repeat(20) {
            engine.render(block, block.size)
            assertTrue(block.all { it == 0f })
        }
    }

    @Test
    fun circleCompletionPlaysItsChime() {
        val circle = CircleTimer(enabled = true, mode = CircleMode.Seconds, seconds = 1.0)
        val found = clicks(MetronomeState(bpm = 60.0, circle = circle), beats = 3.5, stepNanos = 7e6)
        assertEquals(3, found.count { it.second == Voices.CHIME })
        val silent = clicks(MetronomeState(bpm = 60.0, circle = circle.copy(sound = false)), 3.5, 7e6)
        assertEquals(0, silent.count { it.second == Voices.CHIME })
    }

    @Test
    fun stepsCanBeAccentedOrMutedInEveryLane() {
        val n = StepState.Normal; val a = StepState.Accent; val m = StepState.Mute
        val state = MetronomeState(
            bpm = 100.0,
            signature = TimeSignature(3, 4),
            beatSteps = listOf(a, m, n),
            subdivisions = listOf(
                SubdivisionLine(true, 2, steps = listOf(m, n, m, a, m, m)),
                SubdivisionLine(), SubdivisionLine(),
            ),
            polyVoices = listOf(PolyVoice(true, 2, steps = listOf(m, a)), PolyVoice(), PolyVoice(), PolyVoice()),
        )
        val found = clicks(state, beats = 3.0, stepNanos = 11e6)
        fun times(voice: Int) = found.filter { it.second == voice }.map { kotlin.math.round(it.first * 1e6) / 1e6 }

        assertEquals(listOf(0.0), times(ACCENTED + Voices.BEAT))
        assertEquals(listOf(2.0), times(Voices.BEAT)) // beat 2 is muted
        assertEquals(listOf(0.5), times(Voices.SUB))
        assertEquals(listOf(1.5), times(ACCENTED + Voices.SUB))
        assertEquals(emptyList(), times(Voices.POLY))
        assertEquals(listOf(1.5), times(ACCENTED + Voices.POLY))
    }

    @Test
    fun patternsAreSavedRecalledAndSurviveTextStorage() {
        val m = metronome(MetronomeState(signature = TimeSignature(7, 8)))
        m.update { s ->
            s.copy(
                beatSteps = s.beatSteps.mapIndexed { i, step -> if (i == 3) StepState.Mute else step },
                subdivisions = s.subdivisions.mapIndexed { i, l -> if (i == 1) l.copy(enabled = true, division = 5) else l },
            )
        }
        val saved = m.state.value
        m.savePattern("Seven\tand five")
        m.update { MetronomeState(bpm = 77.0, signature = TimeSignature(4, 4)) }
        m.loadPattern(m.patterns.value.single().name)

        val now = m.state.value
        assertEquals(TimeSignature(7, 8), now.signature)
        assertEquals(saved.beatSteps, now.beatSteps)
        assertEquals(saved.subdivisions, now.subdivisions)
        assertEquals(77.0, now.bpm) // tempo is not part of a pattern

        val pattern = m.patterns.value.single()
        assertEquals(pattern, Pattern.parse(pattern.toLine()))
        m.deletePattern(pattern.name)
        assertTrue(m.patterns.value.isEmpty())
    }
}
