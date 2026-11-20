package glick.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

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
)

/**
 * The metronome's state and transport.
 *
 * Time is fed in from outside through [advance], so whatever owns the clock
 * decides the precision: a UI frame loop for now, the audio callback once the
 * sound engine exists. All calls must come from one thread.
 */
class Metronome(
    initial: MetronomeState = MetronomeState(),
    private val nowEpochMillis: () -> Long,
    private val random: Random = Random.Default,
    private val logStore: ActivityLogStore = InMemoryActivityLogStore(),
) {
    private val _state = MutableStateFlow(initial.sanitized())
    val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private val _transport = MutableStateFlow(Transport())
    val transport: StateFlow<Transport> = _transport.asStateFlow()

    private val _log = MutableStateFlow(logStore.load())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private var circleElapsedNanos = 0.0
    private var circleBars = 0
    private var sessionElapsedNanos = 0.0
    private var runStartMillis = 0L
    private var runStartBpm = 0.0
    private var nextActionId = 1L

    fun update(transform: (MetronomeState) -> MetronomeState) {
        val old = _state.value
        val new = transform(old).sanitized()
        if (new == old) return
        _state.value = new
        if (new.session.enabled && !old.session.enabled) resetSession()
        if (new.circle.enabled != old.circle.enabled || new.circle.mode != old.circle.mode) resetCircle()
        val numerator = new.effective().signature.numerator
        val t = _transport.value
        if (t.beat >= numerator) _transport.value = t.copy(beat = 0, beatPhase = 0.0)
        publishTimers()
    }

    fun addCircleAction() = update {
        it.copy(circle = it.circle.copy(actions = it.circle.actions + CircleAction(id = nextActionId++)))
    }

    fun play() {
        val t = _transport.value
        if (t.playing) return
        if (t.sessionFinished) sessionElapsedNanos = 0.0
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
        val t = _transport.value
        if (!t.playing) return
        _transport.value = t.copy(playing = false, beat = 0, beatPhase = 0.0, beatSilenced = false)
        val s = _state.value
        val entry = LogEntry(
            startEpochMillis = runStartMillis,
            endEpochMillis = nowEpochMillis(),
            startBpm = runStartBpm,
            endBpm = s.effective().bpm,
            signature = s.signature.toString(),
            sleepMode = s.sleepMode,
        )
        logStore.append(entry)
        _log.value = _log.value + entry
    }

    fun toggle() = if (_transport.value.playing) stop() else play()

    fun resetSession() {
        sessionElapsedNanos = 0.0
        _transport.value = _transport.value.copy(sessionFinished = false)
        publishTimers()
    }

    fun resetCircle() {
        circleElapsedNanos = 0.0
        circleBars = 0
        publishTimers()
    }

    fun clearLog() {
        logStore.clear()
        _log.value = emptyList()
    }

    /** Moves the transport forward by [dtNanos] of real time. */
    fun advance(dtNanos: Long) {
        val start = _transport.value
        if (!start.playing || dtNanos <= 0) return

        var remaining = dtNanos.toDouble()
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

            remaining -= step
            phase += step / beatNanos
            if (circleSeconds) circleElapsedNanos += step
            if (sessionRuns) sessionElapsedNanos += step

            if (sessionRuns && step >= toSession) {
                sessionFinished = true
                if (s.session.stopOnCompletion) stopRequested = true
            }
            if (circleSeconds && step >= toCircle) {
                circleElapsedNanos = 0.0
                completions++
                runCircleActions()
            }
            if (step >= toBeat) {
                phase = 0.0
                beat++
                if (beat >= s.signature.numerator) {
                    beat = 0
                    bar++
                    if (s.circle.enabled && s.circle.mode == CircleMode.Bars) {
                        circleBars++
                        if (circleBars >= s.circle.bars) {
                            circleBars = 0
                            completions++
                            runCircleActions()
                        }
                    }
                }
                // A tempo or signature action may have shortened the bar.
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
        if (stopRequested) stop()
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
        _transport.value = t.copy(
            circleProgress = circleProgress.coerceIn(0.0, 1.0).toFloat(),
            sessionElapsedSeconds = sessionElapsedNanos / 1e9,
        )
    }
}
