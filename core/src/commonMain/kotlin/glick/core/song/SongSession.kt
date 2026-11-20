package glick.core.song

import glick.core.Metronome
import glick.core.SongRef
import glick.core.TextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the song view shows. */
data class SongView(
    val ref: SongRef? = null,
    /** Set while a song is being prepared: what is happening and how far it is, 0 to 1. */
    val busy: String? = null,
    val progress: Float = 0f,
    /** Why the last load failed, if it did. */
    val error: String? = null,
    val grid: SongGrid? = null,
    val peaks: WaveformPeaks? = null,
    val durationSeconds: Double = 0.0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val ready: Boolean get() = grid != null && peaks != null
}

/**
 * The song being worked on: loads it, finds its beats, applies the user's
 * edits with undo, and saves the result per song after every change, so the
 * same file opens with its map already in place.
 */
class SongSession(
    private val metronome: Metronome,
    private val library: SongLibrary,
    private val store: TextStore,
    private val scope: CoroutineScope,
) {
    private val _view = MutableStateFlow(SongView())
    val view: StateFlow<SongView> = _view.asStateFlow()

    private var loading: Job? = null
    private var saving: Job? = null
    private var source: AudioSource? = null
    private val undone = ArrayDeque<SongGrid>()
    private val history = ArrayDeque<SongGrid>()

    /** True when [ref] was opened before and its beat map is on file, so nothing needs detecting. */
    fun hasMap(ref: SongRef): Boolean = SongGrid.decode(store.read(projectName(ref))) != null

    /**
     * Opens [ref] for playback at [sampleRate]. A saved map is reused;
     * otherwise beats are detected, near [knownBpm] when the tempo is known.
     */
    fun load(ref: SongRef, sampleRate: Int, knownBpm: Double? = null) {
        loading?.cancel()
        detach()
        history.clear()
        undone.clear()
        _view.value = SongView(ref = ref, busy = "Reading the audio")
        loading = scope.launch {
            try {
                val audio = withContext(Dispatchers.Default) {
                    library.open(ref, sampleRate) { p -> report("Reading the audio", p) }
                }
                val peaks = withContext(Dispatchers.Default) { WaveformPeaks.of(audio) }
                val saved = SongGrid.decode(store.read(projectName(ref)))
                val grid = saved ?: withContext(Dispatchers.Default) {
                    SongGrid.of(BeatDetector.detect(audio, knownBpm) { p -> report("Finding the beats", p) }).copy(knownBpm = knownBpm)
                }
                source = audio
                metronome.songAudio = audio
                metronome.setSong(SongTimeline(grid), resetSpeed = true)
                _view.value = SongView(
                    ref = ref, grid = grid, peaks = peaks,
                    durationSeconds = audio.frames.toDouble() / audio.sampleRate,
                )
                if (saved == null) save(ref, grid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _view.value = SongView(ref = ref, error = e.message ?: "This file could not be read.")
            }
        }
    }

    /** The sample rate the loaded song was prepared for, or 0 when none is loaded. */
    val preparedSampleRate: Int get() = source?.sampleRate ?: 0

    /** Removes the song; the click carries on alone. */
    fun close() {
        loading?.cancel()
        detach()
        _view.value = SongView()
    }

    /** Applies one edit to the grid. */
    fun edit(transform: (SongGrid) -> SongGrid) {
        val current = _view.value.grid ?: return
        val changed = transform(current).sanitized()
        if (changed == current) return
        history.addLast(current)
        if (history.size > MAX_UNDO) history.removeFirst()
        undone.clear()
        apply(changed)
    }

    fun undo() {
        val current = _view.value.grid ?: return
        val previous = history.removeLastOrNull() ?: return
        undone.addLast(current)
        apply(previous)
    }

    fun redo() {
        val current = _view.value.grid ?: return
        val next = undone.removeLastOrNull() ?: return
        history.addLast(current)
        apply(next)
    }

    /**
     * Throws the map away and detects the beats again. With [knownBpm] the
     * search stays near that tempo; the value is remembered with the song.
     */
    fun redetect(knownBpm: Double? = null) {
        val audio = source ?: return
        val ref = _view.value.ref ?: return
        loading?.cancel()
        loading = scope.launch {
            _view.value = _view.value.copy(busy = "Finding the beats", progress = 0f)
            val detected = withContext(Dispatchers.Default) {
                SongGrid.of(BeatDetector.detect(audio, knownBpm) { p -> report("Finding the beats", p) }).copy(knownBpm = knownBpm)
            }
            _view.value = _view.value.copy(busy = null)
            edit { detected }
            save(ref, detected)
        }
    }

    private fun apply(grid: SongGrid) {
        val ref = _view.value.ref ?: return
        metronome.setSong(SongTimeline(grid))
        _view.value = _view.value.copy(grid = grid, canUndo = history.isNotEmpty(), canRedo = undone.isNotEmpty())
        save(ref, grid)
    }

    /** Writes the map shortly after the last change, so a drag does not write on every pixel. */
    private fun save(ref: SongRef, grid: SongGrid) {
        saving?.cancel()
        saving = scope.launch {
            delay(SAVE_DELAY_MILLIS)
            withContext(Dispatchers.Default) { store.write(projectName(ref), grid.encode()) }
        }
    }

    /** Writes any pending change now; call before the app closes. */
    fun flush() {
        val view = _view.value
        val ref = view.ref ?: return
        val grid = view.grid ?: return
        saving?.cancel()
        store.write(projectName(ref), grid.encode())
    }

    private fun detach() {
        metronome.setSong(null)
        metronome.songAudio = null
        source = null
    }

    private fun report(stage: String, progress: Float) {
        val view = _view.value
        if (view.busy != null) _view.value = view.copy(busy = stage, progress = progress)
    }

    private companion object {
        const val MAX_UNDO = 100
        const val SAVE_DELAY_MILLIS = 400L

        /** A file name for the song's map, from a hash of its locator. */
        fun projectName(ref: SongRef): String {
            var hash = -0x340d631b7bdddcdbL // FNV-1a, 64 bit
            for (c in ref.id) hash = (hash xor c.code.toLong()) * 0x100000001b3L
            return "songs/" + hash.toULong().toString(16).padStart(16, '0') + ".json"
        }
    }
}
