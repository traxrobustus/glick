package glick.core

import glick.core.song.SongSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Keeps the saved [Snapshot] up to date: shortly after any setting changes,
 * when the click starts or stops, every few seconds while it plays (for the
 * timers), and on demand when the app closes.
 */
class SnapshotKeeper(
    private val metronome: Metronome,
    private val song: SongSession?,
    private val store: TextStore,
    scope: CoroutineScope,
) {
    /** The desktop window's place, updated by the window itself. */
    @Volatile
    var window: WindowBounds? = null

    init {
        val changes = merge(
            metronome.state.map { },
            metronome.transport.map { it.playing }.distinctUntilChanged().map { },
            song?.view?.map { it.ref }?.distinctUntilChanged()?.map { } ?: metronome.state.map { },
        )
        scope.launch {
            changes.collectLatest {
                delay(SETTLE_MILLIS)
                withContext(Dispatchers.Default) { saveNow() }
            }
        }
        scope.launch {
            while (true) {
                delay(TIMER_SAVE_MILLIS)
                if (metronome.transport.value.playing) withContext(Dispatchers.Default) { saveNow() }
            }
        }
    }

    /** Writes the snapshot immediately. */
    fun saveNow() {
        val snapshot = Snapshot(
            state = metronome.state.value,
            timers = metronome.timerProgress(),
            song = song?.view?.value?.ref,
            window = window,
        )
        store.write(NAME, snapshot.encode())
    }

    companion object {
        const val NAME = "state.json"
        private const val SETTLE_MILLIS = 500L
        private const val TIMER_SAVE_MILLIS = 10_000L

        fun restore(store: TextStore): Snapshot? = Snapshot.decode(store.read(NAME))
    }
}
