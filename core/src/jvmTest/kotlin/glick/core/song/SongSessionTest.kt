package glick.core.song

import glick.core.InMemoryTextStore
import glick.core.Metronome
import glick.core.SongRef
import glick.core.TimeSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongSessionTest {
    private val rate = 8000
    private val ref = SongRef("song", "song.wav")

    /** A 30 s song with a hit at 2.0 s and every half second after it. */
    private val library = object : SongLibrary {
        override fun open(ref: SongRef, sampleRate: Int, progress: (Float) -> Unit): AudioSource {
            val audio = FloatArray(rate * 30)
            var at = 2.0
            while (at < 29) {
                for (i in 0 until 200) audio[(at * rate).toInt() + i] = if (i % 20 < 10) 0.8f else -0.8f
                at += 0.5
            }
            return MemoryAudioSource(sampleRate, audio)
        }
    }

    private fun session(store: InMemoryTextStore = InMemoryTextStore(), test: (SongSession, Metronome) -> Unit) {
        val scope = CoroutineScope(Dispatchers.Default)
        val metronome = Metronome(nowEpochMillis = { 0L })
        try {
            test(SongSession(metronome, library, store, scope), metronome)
        } finally {
            scope.cancel()
        }
    }

    private fun SongSession.awaitReady() {
        repeat(500) {
            if (view.value.ready) return
            Thread.sleep(10)
        }
        error("the song did not load: ${view.value}")
    }

    @Test
    fun aKnownTempoGivesASteadyGridStartingAtTheFirstSound() = session { song, _ ->
        song.load(ref, rate, SongSetup(knownBpm = 120.0, signature = TimeSignature(3, 4)))
        song.awaitReady()
        val grid = assertNotNull(song.view.value.grid)
        assertEquals(2.0, assertNotNull(grid.firstBeat), 0.07)
        assertEquals(TimeSignature(3, 4), grid.signatureOfBar(0))
        assertFalse(grid.unmapped)
    }

    @Test
    fun anUnknownTempoWaitsForARecordingAndUndoBringsTheOldGridBackWhole() = session { song, metronome ->
        song.load(ref, rate, SongSetup(knownBpm = 120.0, signature = TimeSignature(4, 4)))
        song.awaitReady()
        val steady = assertNotNull(song.view.value.grid)

        song.startRecording()
        assertTrue(song.view.value.recording)
        assertTrue(metronome.clickMuted)
        // Tap at 0.5 s of song time apart, as the transport passes.
        repeat(8) {
            metronome.advance(500_000_000L)
            song.tap(outputDelaySeconds = 0.0)
        }
        assertEquals(8, song.view.value.taps.size)
        song.stopRecording()
        assertFalse(metronome.clickMuted)
        val recorded = assertNotNull(song.view.value.grid)
        assertEquals(8, recorded.beats.count { it.pinned })
        assertNull(recorded.firstBeat)

        song.undo()
        assertEquals(steady, song.view.value.grid)
        assertNotNull(song.view.value.grid?.firstBeat)
        song.redo()
        assertEquals(recorded, song.view.value.grid)
    }

    @Test
    fun aSongOpenedAgainKeepsItsMap() {
        val store = InMemoryTextStore()
        session(store) { song, _ ->
            song.load(ref, rate, SongSetup(knownBpm = 100.0, signature = TimeSignature(4, 4)))
            song.awaitReady()
            song.setSteady(100.0, 2.25)
            song.flush()
        }
        session(store) { song, _ ->
            assertTrue(song.hasMap(ref))
            song.load(ref, rate)
            song.awaitReady()
            assertEquals(2.25, song.view.value.grid?.firstBeat)
        }
    }

    @Test
    fun withoutATempoTheSongIsUnmappedUntilSomethingGivesItBeats() = session { song, _ ->
        song.load(ref, rate, SongSetup(knownBpm = null, signature = TimeSignature(4, 4)))
        song.awaitReady()
        assertTrue(assertNotNull(song.view.value.grid).unmapped)
        song.setSteady(120.0, 2.0)
        assertFalse(assertNotNull(song.view.value.grid).unmapped)
    }
}
