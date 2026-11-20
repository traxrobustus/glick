package glick.core

/**
 * A saved rhythm: everything the pattern grid shows, together with the time
 * signature it was written for. Tempo is deliberately left out, so a pattern
 * can be recalled at whatever speed is being practised.
 */
data class Pattern(
    val name: String,
    val signature: TimeSignature,
    val beatLevel: Float,
    val beatSteps: List<StepState>,
    val subdivisions: List<SubdivisionLine>,
    val polyVoices: List<PolyVoice>,
) {
    fun applyTo(state: MetronomeState): MetronomeState = state.copy(
        signature = signature,
        beatLevel = beatLevel,
        beatSteps = beatSteps,
        subdivisions = subdivisions,
        polyVoices = polyVoices,
    )

    /** Tab-separated form used by file-backed stores. */
    fun toLine(): String = buildList {
        add(name)
        add(signature.numerator)
        add(signature.denominator)
        add(beatLevel)
        add(encode(beatSteps))
        subdivisions.forEach { add("${if (it.enabled) 1 else 0},${it.division},${it.level},${encode(it.steps)}") }
        polyVoices.forEach { add("${if (it.enabled) 1 else 0},${it.pulses},${it.level},${encode(it.steps)}") }
    }.joinToString("\t")

    companion object {
        fun of(name: String, state: MetronomeState) = Pattern(
            name = name.replace('\t', ' ').replace('\n', ' ').trim().take(40),
            signature = state.signature,
            beatLevel = state.beatLevel,
            beatSteps = state.beatSteps,
            subdivisions = state.subdivisions,
            polyVoices = state.polyVoices,
        )

        fun parse(line: String): Pattern? {
            val p = line.trimEnd('\r', '\n').split('\t')
            if (p.size != 5 + SUBDIVISION_LINES + POLY_VOICES) return null
            fun <T> lane(text: String, make: (Boolean, Int, Float, List<StepState>) -> T): T? {
                val f = text.split(',')
                if (f.size != 4) return null
                return make(f[0] == "1", f[1].toIntOrNull() ?: return null, f[2].toFloatOrNull() ?: return null, decode(f[3]))
            }
            return Pattern(
                name = p[0],
                signature = TimeSignature(p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null),
                beatLevel = p[3].toFloatOrNull() ?: return null,
                beatSteps = decode(p[4]),
                subdivisions = List(SUBDIVISION_LINES) { lane(p[5 + it], ::SubdivisionLine) ?: return null },
                polyVoices = List(POLY_VOICES) { lane(p[5 + SUBDIVISION_LINES + it], ::PolyVoice) ?: return null },
            )
        }

        // One letter per step: n normal, a accent, m mute.
        private fun encode(steps: List<StepState>) = steps.joinToString("") { it.name.take(1).lowercase() }
        private fun decode(text: String) = text.map { c ->
            when (c) {
                'a' -> StepState.Accent
                'm' -> StepState.Mute
                else -> StepState.Normal
            }
        }
    }
}

/** Where saved patterns are kept between runs. */
interface PatternStore {
    fun load(): List<Pattern>
    fun save(patterns: List<Pattern>)
}

class InMemoryPatternStore : PatternStore {
    private var patterns = emptyList<Pattern>()
    override fun load() = patterns
    override fun save(patterns: List<Pattern>) { this.patterns = patterns }
}
