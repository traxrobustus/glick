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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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

    private val _songs = MutableStateFlow(readLibrary(store))
    /** Every song opened so far, most recent first. */
    val songs: StateFlow<List<SongRef>> = _songs.asStateFlow()

    init {
        // The variation in use follows the practice settings as they are changed (never mid-run: a
        // workout or the song itself moves the tempo then).
        scope.launch {
            metronome.state.collect { state ->
                val view = _view.value
                val grid = view.grid ?: return@collect
                if (!view.ready || view.recording || applyingVariation || metronome.transport.value.playing) return@collect
                val captured = grid.capturing(state)
                if (captured != grid) {
                    _view.value = _view.value.copy(grid = captured)
                    view.ref?.let { save(it, captured) }
                }
            }
        }
    }

    /** Reads the song library again, after a backup was imported into the store. */
    fun reloadLibrary() {
        _songs.value = readLibrary(store)
    }

    private fun remember(ref: SongRef) {
        val all = listOf(ref) + _songs.value.filter { it.id != ref.id }
        _songs.value = all
        scope.launch(Dispatchers.Default) { store.write(LIBRARY, encodeLibrary(all)) }
    }

    /** Makes variation [index] of the song the one in use: its loop and its practice settings. */
    fun selectVariation(index: Int) {
        if (_view.value.grid?.variations?.getOrNull(index) == null) return
        switching { it.withVariation(index) }
    }

    fun addVariation() = edit { it.capturing(metronome.state.value).addVariation() }

    fun deleteVariation() = switching { it.deleteVariation() }

    /**
     * Changes which variation is in use. The settings in force belong to the one being
     * left, so they must not be written into the one arriving before its own are applied.
     */
    private fun switching(transform: (SongGrid) -> SongGrid) {
        // What is in force is first written into the one being left, in case it was only just set.
        val leaving = metronome.state.value.takeIf { !metronome.transport.value.playing }
        applyingVariation = true
        try {
            edit { transform(if (leaving != null) it.capturing(leaving) else it) }
        } finally {
            applyingVariation = false
        }
        applyVariationSettings()
    }

    fun renameVariation(name: String) = edit { it.renameVariation(name) }

    /** Puts the practice settings of the variation in use in force. */
    private fun applyVariationSettings() {
        val chosen = _view.value.grid?.current ?: return
        applyingVariation = true
        try {
            metronome.update {
                // Each mode has its own tempo setting; the other one is left alone, or the click
                // would be given a tempo that the song then has to be bent to.
                if (chosen.songMode == SongMode.SongFollowsClick) {
                    it.copy(songMode = chosen.songMode, bpm = chosen.bpm, workout = chosen.workout)
                } else {
                    it.copy(songMode = chosen.songMode, workout = chosen.workout)
                }
            }
            // Entering follow mode starts at the song's own speed; the variation's comes on top.
            if (chosen.songMode != SongMode.SongFollowsClick) metronome.update { it.copy(songSpeed = chosen.songSpeed) }
        } finally {
            applyingVariation = false
        }
    }

    private var loading: Job? = null
    private var saving: Job? = null
    private var source: AudioSource? = null
    private var attacks: List<Double>? = null
    private var applyingVariation = false
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
                metronome.songName = ref.name
                metronome.setSong(SongTimeline(grid), resetSpeed = true)
                _view.value = SongView(ref = ref, grid = grid, peaks = peaks, durationSeconds = duration)
                // A song opened before comes back as it was last practised.
                if (saved != null) applyVariationSettings()
                remember(ref)
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
        val changed = transform(current).sanitized().copy(unmapped = false).let {
            // The variation in use always holds the loop as it now is.
            if (applyingVariation || metronome.transport.value.playing) it.capturingLoop() else it.capturing(metronome.state.value)
        }
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
        metronome.songName = null
        metronome.setSong(null)
        metronome.songAudio = null
        source = null
    }

    private fun report(stage: String, progress: Float) {
        val view = _view.value
        if (view.busy != null) _view.value = view.copy(busy = stage, progress = progress)
    }

    /** While playing only the loop is taken over; the tempo may be a workout's or the song's at that moment. */
    private fun SongGrid.capturingLoop(): SongGrid {
        if (variations.isEmpty()) return this
        val now = current.copy(loopStartBar = loopStartBar, loopEndBar = loopEndBar)
        return if (now == current) this else copy(variations = variations.mapIndexed { i, v -> if (i == variation) now else v })
    }

    companion object {
        private const val MAX_UNDO = 100
        private const val SAVE_DELAY_MILLIS = 400L
        private const val PLACEHOLDER_BPM = 120.0

        /** The document that lists every song opened so far. */
        const val LIBRARY = "songs/index.json"

        private val libraryFormat = Json { ignoreUnknownKeys = true }
        private val librarySerializer = ListSerializer(SongRef.serializer())

        fun readLibrary(store: TextStore): List<SongRef> =
            store.read(LIBRARY)?.let { runCatching { libraryFormat.decodeFromString(librarySerializer, it) }.getOrNull() }.orEmpty()

        fun encodeLibrary(songs: List<SongRef>): String = libraryFormat.encodeToString(librarySerializer, songs)

        /** A file name for the song's map, from a hash of its locator. */
        fun projectName(ref: SongRef): String {
            var hash = -0x340d631b7bdddcdbL // FNV-1a, 64 bit
            for (c in ref.id) hash = (hash xor c.code.toLong()) * 0x100000001b3L
            return "songs/" + hash.toULong().toString(16).padStart(16, '0') + ".json"
        }
    }
}
