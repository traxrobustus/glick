package glick.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import glick.core.song.AudioSource
import glick.core.song.SongTimeline
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.random.Random

/** Identifies which click voice an event is for. */
object Voices {
    const val BEAT = 0
    /** Subdivision line n is `SUB + n`. */
    const val SUB = 1
    /** Polyrhythm voice n is `POLY + n`. */
    const val POLY = SUB + SUBDIVISION_LINES
    const val CHIME = POLY + POLY_VOICES
    const val COUNT = CHIME + 1
}

/** Receives every click as the transport passes it. */
fun interface ClickSink {
    /** [offsetNanos] is measured from the start of the current [Metronome.advance] call. */
    fun click(offsetNanos: Double, voice: Int, level: Float, accent: Boolean)
}

/** Where the click is right now. Published after every [Metronome.advance]. */
data class Transport(
    val playing: Boolean = false,
    /** Zero-based beat inside the bar. */
    val beat: Int = 0,
    /** Progress through the current beat, 0 until 1. */
    val beatPhase: Double = 0.0,
    val bar: Long = 0,
    /** True when random silence dropped the current beat. */
    val beatSilenced: Boolean = false,
    val circleProgress: Float = 0f,
    /** Counts circle-timer completions; a change is the cue for the completion sound. */
    val circleCompletions: Int = 0,
    val sessionElapsedSeconds: Double = 0.0,
    val sessionFinished: Boolean = false,
    /** Where the loaded song is, in seconds from the start of its file; negative when no song is playing. */
    val songSeconds: Double = -1.0,
    /** Changes whenever the song position jumps (start, loop wrap, edit) rather than flows. */
    val songJumps: Int = 0,
    /** In a song workout: which pass is playing (from 0) and at what tempo; -1 and 0 otherwise. */
    val workoutPass: Int = -1,
    val workoutBpm: Double = 0.0,
)

/**
 * The metronome's state and transport.
 *
 * Time is fed in from outside through [advance], so whatever owns the clock
 * decides the precision: the audio callback when sound is running, a UI frame
 * loop otherwise. Every method may be called from any thread.
 */
class Metronome(
    initial: MetronomeState = MetronomeState(),
    private val nowEpochMillis: () -> Long,
    private val random: Random = Random.Default,
    private val logStore: ActivityLogStore = InMemoryActivityLogStore(),
    private val patternStore: PatternStore = InMemoryPatternStore(),
    timers: TimerProgress = TimerProgress(),
) {
    private val _state = MutableStateFlow(initial.sanitized())
    val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private val _transport = MutableStateFlow(
        Transport(circleCompletions = timers.circleCompletions, sessionFinished = timers.sessionFinished),
    )
    val transport: StateFlow<Transport> = _transport.asStateFlow()

    private val _log = MutableStateFlow(logStore.load())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _patterns = MutableStateFlow(patternStore.load())
    val patterns: StateFlow<List<Pattern>> = _patterns.asStateFlow()

    private var circleElapsedNanos = timers.circleElapsedSeconds * 1e9
    private var circleBars = timers.circleBars
    private var sessionElapsedNanos = timers.sessionElapsedSeconds * 1e9
    private var runStartMillis = 0L
    private var runStartBpm = 0.0
    private var nextActionId = (initial.circle.actions.maxOfOrNull { it.id } ?: 0L) + 1
    private val lock = SynchronizedObject()

    // The loaded song, and where the transport is in it. The song is slaved to
    // the beat position, never to the clock, so any tempo change keeps it in sync.
    private var song: SongTimeline? = null
    private var songBar = 0
    private var songBeat = 0
    private var songJumps = 0

    private var workoutPass = -1
    private var workoutBpm = 0.0
    private var bpmBeforeWorkout = 120.0
    private var speedBeforeWorkout = 1.0

    /** Shown in the activity log next to a run that had this song playing. */
    @Volatile
    var songName: String? = null

    /** While true no click sounds; the transport runs on. Used while a song's tempo is being tapped. */
    @Volatile
    var clickMuted: Boolean = false

    /** The decoded audio of the loaded song, for whoever renders sound. */
    @Volatile
    var songAudio: AudioSource? = null

    init {
        publishTimers()
    }

    /** The timers' progress, to be saved with the settings. */
    fun timerProgress(): TimerProgress = synchronized(lock) {
        val t = _transport.value
        TimerProgress(
            sessionElapsedSeconds = sessionElapsedNanos / 1e9,
            sessionFinished = t.sessionFinished,
            circleElapsedSeconds = circleElapsedNanos / 1e9,
            circleBars = circleBars,
            circleCompletions = t.circleCompletions,
        )
    }

    /**
     * Loads, replaces or (with null) removes the song map. Called again after
     * every edit of the grid; a playing song then stays in the bar it was in.
     */
    fun setSong(timeline: SongTimeline?, resetSpeed: Boolean = false): Unit = synchronized(lock) {
        song = timeline?.takeIf { it.playable }
        if (resetSpeed) _state.value = _state.value.copy(songSpeed = 1.0)
        val active = activeSong() ?: return
        val t = _transport.value
        songBar = if (t.playing) songBar.coerceIn(active.loopStartBar, active.loopEndBar - 1) else active.loopStartBar
        val beatInBar = if (t.playing) t.beat.coerceAtMost(active.signatureOfBar(songBar).numerator - 1) else 0
        songBeat = active.barStartBeat(songBar) + beatInBar
        songJumps++
        applySong(active)
        _transport.value = _transport.value.copy(beat = beatInBar)
        publishTimers()
    }

    private fun activeSong(): SongTimeline? = song.takeIf { !_state.value.sleepMode }

    /** Makes the click's signature, and in follow mode its tempo, those of the current song beat. */
    private fun applySong(active: SongTimeline) {
        val old = _state.value
        var s = old
        val signature = active.signatureOfBar(songBar)
        if (s.signature != signature) s = s.copy(signature = signature)
        if (s.songMode == SongMode.ClickFollowsSong) {
            val bpm = (active.bpmOfBeat(songBeat) * s.songSpeed).coerceIn(MIN_BPM, MAX_BPM)
            if (s.bpm != bpm) s = s.copy(bpm = bpm)
        }
        if (s !== old) _state.value = s.sanitized()
    }

    /**
     * In follow mode the tempo belongs to the song, so a tempo change from the
     * jog wheel or a circle action is kept as a speed factor on top of it.
     */
    private fun noteTempoChange(before: Double) {
        val active = activeSong() ?: return
        val s = _state.value
        if (s.songMode != SongMode.ClickFollowsSong || s.bpm == before) return
        _state.value = s.copy(songSpeed = s.bpm / active.bpmOfBeat(songBeat)).sanitized()
    }

    // Disk writes are queued under [lock] and run after it is released, so the
    // audio thread never waits for the file system.
    private val ioLock = SynchronizedObject()
    private val pendingIo = mutableListOf<() -> Unit>()

    private fun flushIo() = synchronized(ioLock) {
        val jobs = synchronized(lock) { pendingIo.toList().also { pendingIo.clear() } }
        jobs.forEach { it() }
    }

    fun update(transform: (MetronomeState) -> MetronomeState): Unit = synchronized(lock) {
        updateLocked(transform)
    }

    private fun updateLocked(transform: (MetronomeState) -> MetronomeState) {
        val old = _state.value
        var new = transform(old).sanitized()
        val active = song.takeIf { !new.sleepMode }
        if (active != null) {
            // Bars come from the song. In follow mode so does the tempo: entering that
            // mode starts at the song's own speed unless a speed is given with it, and a
            // new speed sets the tempo.
            new = new.copy(signature = active.signatureOfBar(songBar)).sanitized()
            if (new.songMode == SongMode.ClickFollowsSong) {
                val entering = old.songMode != SongMode.ClickFollowsSong
                if (entering && new.songSpeed == old.songSpeed) new = new.copy(songSpeed = 1.0)
                if (entering || new.songSpeed != old.songSpeed) {
                    new = new.copy(bpm = (active.bpmOfBeat(songBeat) * new.songSpeed).coerceIn(MIN_BPM, MAX_BPM))
                }
            }
        }
        if (new == old) return
        _state.value = new
        noteTempoChange(old.bpm)
        if (active != null && old.sleepMode) setSongPositionToLoopStart(active)
        if (new.session.enabled && !old.session.enabled) resetSessionLocked()
        if (new.circle.enabled != old.circle.enabled || new.circle.mode != old.circle.mode) resetCircleLocked()
        val numerator = new.effective().signature.numerator
        val t = _transport.value
        // A shorter bar wraps the beat counter but keeps the phase, so the pulse never jumps.
        if (t.beat >= numerator) _transport.value = t.copy(beat = t.beat % numerator)
        publishTimers()
    }

    /** Puts the tempo of workout pass [pass] in force. */
    private fun setWorkoutPass(active: SongTimeline, pass: Int) {
        val s = _state.value
        val tempo = s.workout.tempoOfPass(pass, s.workout.maxBpm ?: active.loopBpm)
        workoutPass = pass
        workoutBpm = tempo
        _state.value = (
            if (s.songMode == SongMode.ClickFollowsSong) s.copy(songSpeed = tempo / active.loopBpm)
            else s.copy(bpm = tempo)
            ).sanitized()
    }

    private fun setSongPositionToLoopStart(active: SongTimeline) {
        songBar = active.loopStartBar
        songBeat = active.barStartBeat(songBar)
        songJumps++
        applySong(active)
    }

    fun addCircleAction() = update {
        it.copy(circle = it.circle.copy(actions = it.circle.actions + CircleAction(id = nextActionId++)))
    }

    /** Saves the current pattern under [name], replacing any pattern of that name. */
    fun savePattern(name: String) {
        synchronized(lock) {
            val pattern = Pattern.of(name, _state.value)
            if (pattern.name.isEmpty()) return
            val all = _patterns.value.filter { it.name != pattern.name } + pattern
            _patterns.value = all
            pendingIo += { patternStore.save(all) }
        }
        flushIo()
    }

    fun loadPattern(name: String): Unit = synchronized(lock) {
        val pattern = _patterns.value.firstOrNull { it.name == name } ?: return
        updateLocked(pattern::applyTo)
    }

    fun deletePattern(name: String) {
        synchronized(lock) {
            val all = _patterns.value.filter { it.name != name }
            _patterns.value = all
            pendingIo += { patternStore.save(all) }
        }
        flushIo()
    }

    fun play(): Unit = synchronized(lock) { playLocked() }

    private fun playLocked() {
        val t = _transport.value
        if (t.playing) return
        if (t.sessionFinished) sessionElapsedNanos = 0.0
        workoutPass = -1
        workoutBpm = 0.0
        activeSong()?.let { active ->
            if (_state.value.workout.enabled) {
                bpmBeforeWorkout = _state.value.bpm
                speedBeforeWorkout = _state.value.songSpeed
                setWorkoutPass(active, 0)
            }
            setSongPositionToLoopStart(active)
        }
        runStartMillis = nowEpochMillis()
        runStartBpm = _state.value.effective().bpm
        _transport.value = t.copy(
            playing = true,
            beat = 0,
            beatPhase = 0.0,
            bar = 0,
            beatSilenced = rollSilence(),
            sessionFinished = false,
        )
        publishTimers()
    }

    /** Stops the click. Both timers keep their progress until reset. */
    fun stop() {
        synchronized(lock) { stopLocked() }
        flushIo()
    }

    private fun stopLocked() {
        val t = _transport.value
        if (!t.playing) return
        // No song position while stopped: the song falls silent with the click.
        _transport.value = t.copy(
            playing = false, beat = 0, beatPhase = 0.0, beatSilenced = false, songSeconds = -1.0,
            workoutPass = -1, workoutBpm = 0.0,
        )
        // A workout borrowed the tempo for its ladder; it goes back to what it was.
        if (workoutPass >= 0) {
            workoutPass = -1
            workoutBpm = 0.0
            _state.value = _state.value.copy(bpm = bpmBeforeWorkout, songSpeed = speedBeforeWorkout).sanitized()
            activeSong()?.let(::applySong)
        }
        val s = _state.value
        val entry = LogEntry(
            startEpochMillis = runStartMillis,
            endEpochMillis = nowEpochMillis(),
            startBpm = runStartBpm,
            endBpm = s.effective().bpm,
            signature = s.signature.toString(),
            sleepMode = s.sleepMode,
            label = if (activeSong() != null) songName.orEmpty() else "",
        )
        pendingIo += { logStore.append(entry) }
        _log.value = _log.value + entry
    }

    fun toggle() {
        synchronized(lock) { if (_transport.value.playing) stopLocked() else playLocked() }
        flushIo()
    }

    fun resetSession(): Unit = synchronized(lock) { resetSessionLocked() }

    private fun resetSessionLocked() {
        sessionElapsedNanos = 0.0
        _transport.value = _transport.value.copy(sessionFinished = false)
        publishTimers()
    }

    fun resetCircle(): Unit = synchronized(lock) { resetCircleLocked() }

    private fun resetCircleLocked() {
        circleElapsedNanos = 0.0
        circleBars = 0
        _transport.value = _transport.value.copy(circleCompletions = 0)
        publishTimers()
    }

    /** Reads the log and the patterns again from their stores, after a backup was imported into them. */
    fun reloadStores(): Unit = synchronized(lock) {
        _log.value = logStore.load()
        _patterns.value = patternStore.load()
    }

    fun clearLog() {
        synchronized(lock) {
            pendingIo += { logStore.clear() }
            _log.value = emptyList()
        }
        flushIo()
    }

    fun advance(dtNanos: Long, sink: ClickSink? = null) = advance(dtNanos.toDouble(), sink)

    /**
     * Moves the transport forward by [dtNanos] of real time and reports every
     * click that falls inside that span to [sink], with its exact offset.
     */
    fun advance(dtNanos: Double, sink: ClickSink? = null) {
        // Only a session timer running out stops the click from here; its log
        // entry is then written once the lock is free.
        if (advanceLocked(dtNanos, sink)) flushIo()
    }

    /** Returns true when the click was stopped during this span. */
    private fun advanceLocked(dtNanos: Double, clicks: ClickSink?): Boolean = synchronized(lock) {
        val sink = clicks.takeUnless { clickMuted }
        val start = _transport.value
        if (!start.playing || dtNanos <= 0) return false

        var remaining = dtNanos
        var elapsed = 0.0
        var beat = start.beat
        var phase = start.beatPhase
        var bar = start.bar
        var silenced = start.beatSilenced
        var completions = start.circleCompletions
        var sessionFinished = start.sessionFinished
        var stopRequested = false

        while (remaining > 0.0 && !stopRequested) {
            // Re-read each step: a circle action may just have changed the tempo.
            val s = _state.value.effective()
            val beatNanos = 60e9 / s.bpm
            val circleSeconds = s.circle.enabled && s.circle.mode == CircleMode.Seconds
            val sessionRuns = s.session.enabled && !sessionFinished

            val toBeat = (1.0 - phase) * beatNanos
            val toCircle = if (circleSeconds) s.circle.seconds * 1e9 - circleElapsedNanos else Double.MAX_VALUE
            val toSession = if (sessionRuns) s.session.minutes * 60e9 - sessionElapsedNanos else Double.MAX_VALUE
            val step = minOf(remaining, toBeat, minOf(toCircle, toSession)).coerceAtLeast(0.0)
            val reachesBeat = step >= toBeat
            val nextPhase = if (reachesBeat) 1.0 else phase + step / beatNanos

            if (sink != null && step > 0.0) emitClicks(s, beat, phase, nextPhase, elapsed, beatNanos, silenced, sink)

            remaining -= step
            elapsed += step
            phase = nextPhase
            if (circleSeconds) circleElapsedNanos += step
            if (sessionRuns) sessionElapsedNanos += step

            var circleDone = false
            if (sessionRuns && step >= toSession) {
                sessionFinished = true
                if (s.session.stopOnCompletion) stopRequested = true
            }
            if (circleSeconds && step >= toCircle) {
                circleElapsedNanos = 0.0
                circleDone = true
            }
            if (reachesBeat) {
                phase = 0.0
                beat++
                val newBar = beat >= s.signature.numerator
                val active = activeSong()
                if (active != null) {
                    songBeat++
                    if (newBar) {
                        songBar++
                        if (songBar >= active.loopEndBar) {
                            songBar = active.loopStartBar
                            songJumps++
                            if (workoutPass >= 0) {
                                // One pass of the ladder is done: the next is a step faster, and the one at the top is the last.
                                val top = _state.value.workout.let { it.maxBpm ?: active.loopBpm }
                                if (workoutBpm >= maxOf(top, _state.value.workout.startBpm) - 1e-9) stopRequested = true
                                else setWorkoutPass(active, workoutPass + 1)
                            }
                        }
                        songBeat = active.barStartBeat(songBar)
                    }
                    applySong(active)
                }
                if (newBar) {
                    beat = 0
                    bar++
                    if (s.circle.enabled && s.circle.mode == CircleMode.Bars) {
                        circleBars++
                        if (circleBars >= s.circle.bars) {
                            circleBars = 0
                            circleDone = true
                        }
                    }
                }
            }
            if (circleDone) {
                completions++
                if (s.circle.sound) sink?.click(elapsed, Voices.CHIME, 1f, false)
                val tempoBefore = _state.value.bpm
                runCircleActions()
                noteTempoChange(tempoBefore)
            }
            if (reachesBeat) {
                // A signature action may have shortened the bar.
                beat = beat.coerceAtMost(_state.value.effective().signature.numerator - 1)
                silenced = rollSilence()
            }
        }

        _transport.value = start.copy(
            beat = beat,
            beatPhase = phase.coerceIn(0.0, 1.0),
            bar = bar,
            beatSilenced = silenced,
            circleCompletions = completions,
            sessionFinished = sessionFinished,
        )
        publishTimers()
        if (stopRequested) stopLocked()
        stopRequested
    }

    /**
     * Reports the clicks whose position lies in the half-open span from [from]
     * until [to] of beat number [beat] (both in beats, 0 until 1). Successive
     * spans share their end points exactly, so no click is lost or doubled.
     */
    private fun emitClicks(
        s: MetronomeState,
        beat: Int,
        from: Double,
        to: Double,
        baseNanos: Double,
        beatNanos: Double,
        silenced: Boolean,
        sink: ClickSink,
    ) {
        if (!silenced) {
            val beatStep = s.beatSteps[beat]
            if (from == 0.0 && s.beatLevel > 0f && beatStep != StepState.Mute) {
                sink.click(baseNanos, Voices.BEAT, s.beatLevel, beatStep == StepState.Accent)
            }
            s.subdivisions.forEachIndexed { index, line ->
                if (!line.enabled || line.level <= 0f) return@forEachIndexed
                val division = line.division.toDouble()
                var k = ceil(from * division).toInt()
                while (k < line.division && k / division < to) {
                    val at = k / division
                    val step = line.steps[beat * line.division + k]
                    if (at >= from && step != StepState.Mute) {
                        sink.click(
                            baseNanos + (at - from) * beatNanos, Voices.SUB + index, line.level,
                            step == StepState.Accent,
                        )
                    }
                    k++
                }
            }
        }
        val beats = s.signature.numerator.toDouble()
        val barFrom = (beat + from) / beats
        val barTo = (beat + to) / beats
        s.polyVoices.forEachIndexed { index, voice ->
            if (!voice.enabled || voice.level <= 0f) return@forEachIndexed
            val pulses = voice.pulses.toDouble()
            var j = ceil(barFrom * pulses).toInt()
            while (j < voice.pulses && j / pulses < barTo) {
                val at = j / pulses
                val step = voice.steps[j]
                if (at >= barFrom && step != StepState.Mute) {
                    val offset = ((at - barFrom) * beats * beatNanos).coerceAtLeast(0.0)
                    sink.click(baseNanos + offset, Voices.POLY + index, voice.level, step == StepState.Accent)
                }
                j++
            }
        }
    }

    private fun rollSilence(): Boolean {
        val silence = _state.value.effective().silence
        return silence.enabled && random.nextFloat() < silence.probability
    }

    private fun runCircleActions() {
        var s = _state.value
        for (action in s.circle.actions) {
            if (!action.enabled) continue
            s = action.target.write(s, action.target.read(s) + action.amount).sanitized()
        }
        _state.value = s
    }

    private fun publishTimers() {
        val s = _state.value.effective()
        val t = _transport.value
        val circleProgress = when {
            !s.circle.enabled -> 0.0
            s.circle.mode == CircleMode.Seconds -> circleElapsedNanos / (s.circle.seconds * 1e9)
            else -> (circleBars + (t.beat + t.beatPhase) / s.signature.numerator) / s.circle.bars
        }
        val active = activeSong()
        _transport.value = t.copy(
            circleProgress = circleProgress.coerceIn(0.0, 1.0).toFloat(),
            sessionElapsedSeconds = sessionElapsedNanos / 1e9,
            songSeconds = if (active != null && t.playing) active.secondsAt(songBeat + t.beatPhase) else -1.0,
            songJumps = songJumps,
            workoutPass = if (t.playing) workoutPass else -1,
            workoutBpm = if (t.playing) workoutBpm else 0.0,
        )
    }
}
