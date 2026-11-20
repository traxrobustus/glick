package glick.core

const val MIN_BPM = 5.0
const val MAX_BPM = 5000.0
const val SLEEP_BPM = 40.0

const val MAX_NUMERATOR = 128
const val MAX_DIVISION = 32
const val MAX_POLY_PULSES = 64

const val SUBDIVISION_LINES = 3
const val POLY_VOICES = 4

/** Note lengths allowed as a time-signature denominator. */
val DENOMINATORS = listOf(1, 2, 4, 8, 16, 32, 64, 128)

/** The tempo counts [denominator] notes per minute; a bar holds [numerator] of them. */
data class TimeSignature(val numerator: Int = 4, val denominator: Int = 4) {
    override fun toString() = "$numerator/$denominator"
}

/** How one step of a lane sounds. Tapping a step cycles through these. */
enum class StepState {
    Normal, Accent, Mute;

    fun next(): StepState = entries[(ordinal + 1) % entries.size]
}

/** Pads with [StepState.Normal] or truncates, so a lane always has one state per step. */
internal fun List<StepState>.fitted(size: Int): List<StepState> =
    if (this.size == size) this else List(size) { getOrElse(it) { StepState.Normal } }

/**
 * One subdivision lane: [division] evenly spaced clicks inside every beat.
 * [steps] holds one state per click of the whole bar, beat after beat.
 */
data class SubdivisionLine(
    val enabled: Boolean = false,
    val division: Int = 2,
    val level: Float = 0.6f,
    val steps: List<StepState> = emptyList(),
)

/** One polyrhythm voice: [pulses] evenly spaced clicks across the whole bar, one of [steps] each. */
data class PolyVoice(
    val enabled: Boolean = false,
    val pulses: Int = 3,
    val level: Float = 0.7f,
    val steps: List<StepState> = emptyList(),
)

data class RandomSilence(val enabled: Boolean = false, val probability: Float = 0.25f)

enum class CircleMode { Bars, Seconds }

/** On each circle-timer completion, add [amount] to [target]. */
data class CircleAction(
    val id: Long,
    val enabled: Boolean = true,
    val target: ParamTarget = ParamTarget.Tempo,
    val amount: Double = ParamTarget.Tempo.defaultAmount,
)

data class CircleTimer(
    val enabled: Boolean = false,
    val mode: CircleMode = CircleMode.Bars,
    val bars: Int = 4,
    val seconds: Double = 30.0,
    val sound: Boolean = true,
    val actions: List<CircleAction> = emptyList(),
)

data class SessionTimer(
    val enabled: Boolean = false,
    val minutes: Double = 10.0,
    val stopOnCompletion: Boolean = true,
)

data class MidiSync(val enabled: Boolean = false, val deviceId: String? = null)

data class MetronomeState(
    val bpm: Double = 120.0,
    val signature: TimeSignature = TimeSignature(),
    /** One state per beat of the bar: normal click, accent (higher pitch) or mute. */
    val beatSteps: List<StepState> = listOf(StepState.Accent),
    val beatLevel: Float = 1f,
    val subdivisions: List<SubdivisionLine> = listOf(
        SubdivisionLine(division = 2),
        SubdivisionLine(division = 3),
        SubdivisionLine(division = 4),
    ),
    val polyVoices: List<PolyVoice> = listOf(
        PolyVoice(pulses = 3),
        PolyVoice(pulses = 5),
        PolyVoice(pulses = 7),
        PolyVoice(pulses = 2),
    ),
    val silence: RandomSilence = RandomSilence(),
    val circle: CircleTimer = CircleTimer(),
    val session: SessionTimer = SessionTimer(),
    val midi: MidiSync = MidiSync(),
    /** Overrides the pattern with a plain [SLEEP_BPM] click; settings are kept. */
    val sleepMode: Boolean = false,
) {
    /** Brings every field back inside its legal range. */
    fun sanitized(): MetronomeState {
        val numerator = signature.numerator.coerceIn(1, MAX_NUMERATOR)
        return copy(
            bpm = if (bpm.isNaN()) 120.0 else bpm.coerceIn(MIN_BPM, MAX_BPM),
            signature = TimeSignature(
                numerator,
                if (signature.denominator in DENOMINATORS) signature.denominator else 4,
            ),
            beatSteps = beatSteps.fitted(numerator),
            beatLevel = beatLevel.coerceIn(0f, 1f),
            subdivisions = List(SUBDIVISION_LINES) { i ->
                val line = subdivisions.getOrElse(i) { SubdivisionLine() }
                val division = line.division.coerceIn(1, MAX_DIVISION)
                line.copy(
                    division = division,
                    level = line.level.coerceIn(0f, 1f),
                    steps = line.steps.fitted(numerator * division),
                )
            },
            polyVoices = List(POLY_VOICES) { i ->
                val voice = polyVoices.getOrElse(i) { PolyVoice() }
                val pulses = voice.pulses.coerceIn(1, MAX_POLY_PULSES)
                voice.copy(
                    pulses = pulses,
                    level = voice.level.coerceIn(0f, 1f),
                    steps = voice.steps.fitted(pulses),
                )
            },
            silence = silence.copy(probability = silence.probability.coerceIn(0f, 1f)),
            circle = circle.copy(
                bars = circle.bars.coerceIn(1, 9999),
                seconds = circle.seconds.coerceIn(0.1, 86_400.0),
            ),
            session = session.copy(minutes = session.minutes.coerceIn(0.1, 6000.0)),
        )
    }

    /** What actually plays: the full pattern, or the sleep-mode click. */
    fun effective(): MetronomeState =
        if (!sleepMode) this
        else copy(
            bpm = SLEEP_BPM,
            beatSteps = beatSteps.map { StepState.Normal },
            subdivisions = subdivisions.map { it.copy(enabled = false) },
            polyVoices = polyVoices.map { it.copy(enabled = false) },
            silence = silence.copy(enabled = false),
            circle = circle.copy(enabled = false),
        )
}

private fun sub(i: Int, f: (SubdivisionLine, Double) -> SubdivisionLine): (MetronomeState, Double) -> MetronomeState =
    { s, v -> s.copy(subdivisions = s.subdivisions.mapIndexed { n, l -> if (n == i) f(l, v) else l }) }

private fun poly(i: Int, f: (PolyVoice, Double) -> PolyVoice): (MetronomeState, Double) -> MetronomeState =
    { s, v -> s.copy(polyVoices = s.polyVoices.mapIndexed { n, p -> if (n == i) f(p, v) else p }) }

private fun pct(level: Float) = level * 100.0
private fun unpct(v: Double) = (v / 100.0).toFloat()
private fun whole(v: Double) = kotlin.math.round(v).toInt()

/**
 * A metronome parameter that a circle-timer action can move. Values are read and
 * written as plain numbers (levels and probabilities in percent); out-of-range
 * results are clamped by [MetronomeState.sanitized].
 */
enum class ParamTarget(
    val label: String,
    val unit: String,
    val defaultAmount: Double,
    val read: (MetronomeState) -> Double,
    val write: (MetronomeState, Double) -> MetronomeState,
) {
    Tempo("Tempo", "BPM", 5.0, { it.bpm }, { s, v -> s.copy(bpm = v) }),
    Numerator(
        "Beats per bar", "", 1.0,
        { it.signature.numerator.toDouble() },
        { s, v -> s.copy(signature = s.signature.copy(numerator = whole(v))) },
    ),
    /** Moves the beat accent: every accent is cleared and one is set on the target beat. */
    AccentBeat(
        "Accent beat", "", 1.0,
        { it.beatSteps.indexOf(StepState.Accent).coerceAtLeast(0) + 1.0 },
        { s, v ->
            val target = (whole(v) - 1).coerceIn(0, s.beatSteps.lastIndex.coerceAtLeast(0))
            s.copy(
                beatSteps = s.beatSteps.mapIndexed { i, step ->
                    when {
                        i == target -> StepState.Accent
                        step == StepState.Accent -> StepState.Normal
                        else -> step
                    }
                },
            )
        },
    ),
    BeatLevel("Beat level", "%", -5.0, { pct(it.beatLevel) }, { s, v -> s.copy(beatLevel = unpct(v)) }),
    Sub1Division("Sub 1 division", "", 1.0, { it.subdivisions[0].division.toDouble() }, sub(0) { l, v -> l.copy(division = whole(v)) }),
    Sub2Division("Sub 2 division", "", 1.0, { it.subdivisions[1].division.toDouble() }, sub(1) { l, v -> l.copy(division = whole(v)) }),
    Sub3Division("Sub 3 division", "", 1.0, { it.subdivisions[2].division.toDouble() }, sub(2) { l, v -> l.copy(division = whole(v)) }),
    Sub1Level("Sub 1 level", "%", 5.0, { pct(it.subdivisions[0].level) }, sub(0) { l, v -> l.copy(level = unpct(v)) }),
    Sub2Level("Sub 2 level", "%", 5.0, { pct(it.subdivisions[1].level) }, sub(1) { l, v -> l.copy(level = unpct(v)) }),
    Sub3Level("Sub 3 level", "%", 5.0, { pct(it.subdivisions[2].level) }, sub(2) { l, v -> l.copy(level = unpct(v)) }),
    Poly1Pulses("Poly 1 pulses", "", 1.0, { it.polyVoices[0].pulses.toDouble() }, poly(0) { p, v -> p.copy(pulses = whole(v)) }),
    Poly2Pulses("Poly 2 pulses", "", 1.0, { it.polyVoices[1].pulses.toDouble() }, poly(1) { p, v -> p.copy(pulses = whole(v)) }),
    Poly3Pulses("Poly 3 pulses", "", 1.0, { it.polyVoices[2].pulses.toDouble() }, poly(2) { p, v -> p.copy(pulses = whole(v)) }),
    Poly4Pulses("Poly 4 pulses", "", 1.0, { it.polyVoices[3].pulses.toDouble() }, poly(3) { p, v -> p.copy(pulses = whole(v)) }),
    Poly1Level("Poly 1 level", "%", 5.0, { pct(it.polyVoices[0].level) }, poly(0) { p, v -> p.copy(level = unpct(v)) }),
    Poly2Level("Poly 2 level", "%", 5.0, { pct(it.polyVoices[1].level) }, poly(1) { p, v -> p.copy(level = unpct(v)) }),
    Poly3Level("Poly 3 level", "%", 5.0, { pct(it.polyVoices[2].level) }, poly(2) { p, v -> p.copy(level = unpct(v)) }),
    Poly4Level("Poly 4 level", "%", 5.0, { pct(it.polyVoices[3].level) }, poly(3) { p, v -> p.copy(level = unpct(v)) }),
    SilenceProbability(
        "Silence probability", "%", 5.0,
        { pct(it.silence.probability) },
        { s, v -> s.copy(silence = s.silence.copy(probability = unpct(v))) },
    ),
    CircleLength(
        "Circle length", "", 1.0,
        { if (it.circle.mode == CircleMode.Bars) it.circle.bars.toDouble() else it.circle.seconds },
        { s, v ->
            s.copy(
                circle = if (s.circle.mode == CircleMode.Bars) s.circle.copy(bars = whole(v))
                else s.circle.copy(seconds = v),
            )
        },
    ),
}

/** A MIDI input the tempo can be slaved to. */
data class MidiPort(val id: String, val name: String)
