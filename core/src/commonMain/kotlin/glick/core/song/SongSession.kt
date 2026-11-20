package glick.core.song

import glick.core.Metronome
import glick.core.SongMode
import glick.core.SongRef
import glick.core.TimeSignature
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
    /** True while the tempo is being recorded by tapping; [taps] are the beats tapped so far. */
    val recording: Boolean = false,
    val taps: List<Double> = emptyList(),
) {
    val ready: Boolean get() = grid != null && peaks != null
}

/** What the user said about a song when opening it. */
data class SongSetup(val knownBpm: Double?, val signature: TimeSignature)

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
    private var attacks: List<Double>? = null
    private var modeBeforeRecording = SongMode.ClickFollowsSong
    private var bpmBeforeRecording = 120.0
    private val undone = ArrayDeque<SongGrid>()
    private val history = ArrayDeque<SongGrid>()

    /** True when [ref] was opened before and its beat map is on file, so nothing needs asking. */
    fun hasMap(ref: SongRef): Boolean = SongGrid.decode(store.read(projectName(ref))) != null

    /**
     * Opens [ref] for playback at [sampleRate]. A saved map is reused.
     * Otherwise [setup] decides: with a known tempo the song gets a steady
     * grid, to be slid into place; without one it gets a placeholder grid and
     * waits for its tempo to be recorded by tapping.
     */
    fun load(ref: SongRef, sampleRate: Int, setup: SongSetup? = null) {
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
                val duration = audio.frames.toDouble() / audio.sampleRate
                val saved = SongGrid.decode(store.read(projectName(ref)))
                val blank = SongGrid(signatures = listOf(SignatureChange(0, setup?.signature ?: TimeSignature())))
                val grid = saved
                    ?: setup?.knownBpm?.let { blank.steadyAt(it, peaks.firstSoundSeconds(), duration) }
                    ?: blank.placeholder(duration)
                source = audio
                metronome.songAudio = audio
                metronome.setSong(SongTimeline(grid), resetSpeed = true)
                _view.value = SongView(ref = ref, grid = grid, peaks = peaks, durationSeconds = duration)
                if (saved == null) save(ref, grid)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _view.value = SongView(ref = ref, error = e.message ?: "This file could not be read.")
            }
        }
    }

    /** An even grid that only lets the song play; it says nothing about the music. */
    private fun SongGrid.placeholder(duration: Double): SongGrid =
        steadyAt(PLACEHOLDER_BPM, 0.0, duration).copy(knownBpm = null, firstBeat = null, unmapped = true)

    /** Replaces the beats by a steady grid at [bpm] whose bar 1 starts at [firstBeat]. */
    fun setSteady(bpm: Double, firstBeat: Double) {
        val duration = _view.value.durationSeconds
        edit { it.steadyAt(bpm, firstBeat, duration) }
    }

    /**
     * Starts a tempo recording: the song plays from its start at its own
     * speed with the click silent, and every [tap] marks a beat.
     */
    fun startRecording() {
        val view = _view.value
        if (!view.ready || view.recording) return
        metronome.stop()
        val state = metronome.state.value
        modeBeforeRecording = state.songMode
        bpmBeforeRecording = state.bpm
        metronome.clickMuted = true
        metronome.update { it.copy(songMode = SongMode.ClickFollowsSong) }
        metronome.setSong(SongTimeline(SongGrid().placeholder(view.durationSeconds)), resetSpeed = true)
        _view.value = view.copy(recording = true, taps = emptyList())
        metronome.play()
    }

    /**
     * Marks a beat at what is being heard right now. [outputDelaySeconds] is
     * how long rendered sound takes to reach the speaker.
     */
    fun tap(outputDelaySeconds: Double) {
        val view = _view.value
        if (!view.recording) return
        val at = metronome.transport.value.songSeconds - outputDelaySeconds
        val last = view.taps.lastOrNull()
        when {
            at < 0 -> return
            // The song ran past its end and started again: the recording is over.
            last != null && at < last - 1.0 -> stopRecording()
            last == null || at > last + 0.1 -> _view.value = view.copy(taps = view.taps + at)
        }
    }

    /** Ends the recording and turns the taps into the song's beats. Fewer than two taps change nothing. */
    fun stopRecording() {
        val view = _view.value
        if (!view.recording) return
        metronome.stop()
        metronome.clickMuted = false
        metronome.update { it.copy(songMode = modeBeforeRecording, bpm = bpmBeforeRecording) }
        _view.value = view.copy(recording = false, taps = emptyList())
        val grid = view.grid ?: return
        val recorded = grid.withTaps(view.taps, view.durationSeconds)
        if (recorded == grid) metronome.setSong(SongTimeline(grid), resetSpeed = true)
        else edit { recorded }
    }

    /** Pulls the tapped beats onto the nearest audible attacks. */
    fun snapTaps() {
        val audio = source ?: return
        scope.launch {
            val found = attacks ?: withContext(Dispatchers.Default) { BeatDetector.attacks(audio) }.also { attacks = it }
            if (source === audio) edit { it.snappedTo(found) }
        }
    }

    fun smoothTaps() = edit { it.smoothed() }

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
        val changed = transform(current).sanitized().copy(unmapped = false)
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
        metronome.clickMuted = false
        attacks = null
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
        const val PLACEHOLDER_BPM = 120.0

        /** A file name for the song's map, from a hash of its locator. */
        fun projectName(ref: SongRef): String {
            var hash = -0x340d631b7bdddcdbL // FNV-1a, 64 bit
            for (c in ref.id) hash = (hash xor c.code.toLong()) * 0x100000001b3L
            return "songs/" + hash.toULong().toString(16).padStart(16, '0') + ".json"
        }
    }
}
