package glick.core

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/** The audio thread calls [Metronome.advance]; nothing the UI does may hold it up. */
class AudioThreadTest {
    private class SlowPatternStore : PatternStore {
        override fun load() = emptyList<Pattern>()
        override fun save(patterns: List<Pattern>) = Thread.sleep(300)
    }

    private class SlowLogStore : ActivityLogStore {
        override fun load() = emptyList<LogEntry>()
        override fun append(entry: LogEntry) = Thread.sleep(300)
        override fun clear() = Thread.sleep(300)
    }

    /** Longest time, in milliseconds, one audio block had to wait while [uiAction] ran. */
    private fun worstBlockDuring(metronome: Metronome, uiAction: () -> Unit): Long {
        var worst = 0L
        var running = true
        val audio = thread {
            while (running) {
                val before = System.nanoTime()
                metronome.advance(5_000_000L)
                worst = maxOf(worst, (System.nanoTime() - before) / 1_000_000)
                Thread.sleep(1)
            }
        }
        Thread.sleep(50)
        uiAction()
        running = false
        audio.join()
        return worst
    }

    @Test
    fun savingAPatternDoesNotBlockTheAudioThread() {
        val m = Metronome(nowEpochMillis = { 0L }, patternStore = SlowPatternStore())
        m.play()
        val worst = worstBlockDuring(m) {
            m.savePattern("a")
            m.deletePattern("a")
        }
        assertTrue(worst < 50, "audio block waited $worst ms")
    }

    @Test
    fun writingTheActivityLogDoesNotBlockTheAudioThread() {
        val m = Metronome(nowEpochMillis = { 0L }, logStore = SlowLogStore())
        val worst = worstBlockDuring(m) {
            m.play()
            m.stop()
            m.play()
            m.clearLog()
        }
        assertTrue(worst < 50, "audio block waited $worst ms")
    }
}
