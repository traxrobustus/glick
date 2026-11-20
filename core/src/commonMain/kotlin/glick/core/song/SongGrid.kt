package glick.core.song

import glick.core.DENOMINATORS
import glick.core.MAX_NUMERATOR
import glick.core.TimeSignature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One beat of the song, in seconds from the start of the file. A pinned beat was placed by hand. */
@Serializable
data class BeatMarker(val seconds: Double, val pinned: Boolean = false)

/** From bar number [bar] on, bars have this time signature. */
@Serializable
data class SignatureChange(val bar: Int, val signature: TimeSignature)

/**
 * The musical map of a song: where its beats are, how they group into bars,
 * and which bars loop. This is what the user edits and what is saved per song.
 */
@Serializable
data class SongGrid(
    val beats: List<BeatMarker> = emptyList(),
    /** Index of the first beat that starts a bar; earlier beats are a pickup. */
    val downbeat: Int = 0,
    val signatures: List<SignatureChange> = listOf(SignatureChange(0, TimeSignature())),
    val loopStartBar: Int = 0,
    /** First bar after the loop; null loops to the last whole bar. */
    val loopEndBar: Int? = null,
    /** The tempo the user stated for this song, which beat detection then starts from. */
    val knownBpm: Double? = null,
) {
    /** Sorted beats, legal signatures and a loop inside the song. */
    fun sanitized(): SongGrid {
        val ordered = beats.sortedBy { it.seconds }.fold(mutableListOf<BeatMarker>()) { kept, beat ->
            // Two markers closer than 20 ms are one beat.
            if (kept.isEmpty() || beat.seconds - kept.last().seconds > 0.02) kept += beat
            kept
        }
        val changes = signatures
            .map { SignatureChange(it.bar.coerceAtLeast(0), it.signature.legal()) }
            .sortedBy { it.bar }
            .distinctBy { it.bar }
            .let { if (it.firstOrNull()?.bar == 0) it else listOf(SignatureChange(0, TimeSignature())) + it }
        val grid = copy(beats = ordered, downbeat = downbeat.coerceIn(0, (ordered.size - 1).coerceAtLeast(0)), signatures = changes)
        val bars = SongTimeline(grid).barCount
        val start = loopStartBar.coerceIn(0, (bars - 1).coerceAtLeast(0))
        val end = loopEndBar?.coerceIn(start + 1, bars.coerceAtLeast(start + 1))?.takeIf { it < bars }
        return grid.copy(loopStartBar = start, loopEndBar = end)
    }

    fun signatureOfBar(bar: Int): TimeSignature = signatures.last { it.bar <= bar }.signature

    fun encode(): String = format.encodeToString(serializer(), this)

    companion object {
        private val format = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(text: String?): SongGrid? =
            if (text.isNullOrBlank()) null
            else runCatching { format.decodeFromString(serializer(), text) }.getOrNull()?.sanitized()

        /** The grid that detection proposes. */
        fun of(detection: Detection): SongGrid = SongGrid(
            beats = detection.beats.map { BeatMarker(it) },
            downbeat = detection.downbeat,
            signatures = listOf(SignatureChange(0, TimeSignature(detection.beatsPerBar, detection.noteLength))),
        ).sanitized()
    }
}

private fun TimeSignature.legal() = TimeSignature(
    numerator.coerceIn(1, MAX_NUMERATOR),
    if (denominator in DENOMINATORS) denominator else 4,
)

/**
 * A [SongGrid] laid out for fast lookup: beat times, the beat each bar starts
 * on, and the loop. Positions are measured in beats from the first marker, so
 * position 2.5 is half-way between the third and fourth beat.
 */
class SongTimeline(val grid: SongGrid) {
    private val times = DoubleArray(grid.beats.size) { grid.beats[it].seconds }
    private val barStarts: IntArray
    private val barSignatures: List<TimeSignature>

    init {
        val starts = mutableListOf<Int>()
        val signatures = mutableListOf<TimeSignature>()
        var beat = grid.downbeat
        var bar = 0
        // A bar counts when all its beats exist; its end may be the first beat past the last marker.
        while (times.size >= 2) {
            val signature = grid.signatureOfBar(bar)
            if (beat + signature.numerator > times.size) break
            starts += beat
            signatures += signature
            beat += signature.numerator
            bar++
        }
        starts += beat
        barStarts = starts.toIntArray()
        barSignatures = signatures
    }

    val beatCount: Int get() = times.size
    val barCount: Int get() = barSignatures.size
    val loopStartBar: Int get() = grid.loopStartBar.coerceIn(0, (barCount - 1).coerceAtLeast(0))
    val loopEndBar: Int get() = (grid.loopEndBar ?: barCount).coerceIn(loopStartBar + 1, barCount.coerceAtLeast(loopStartBar + 1))

    /** True when there is at least one whole bar to play. */
    val playable: Boolean get() = barCount > 0

    fun signatureOfBar(bar: Int): TimeSignature = barSignatures[bar.coerceIn(0, barCount - 1)]

    /** Beat index on which [bar] starts; `barStartBeat(barCount)` is the end of the last bar. */
    fun barStartBeat(bar: Int): Int = barStarts[bar.coerceIn(0, barCount)]

    /** The bar that contains beat index [beat], or -1 in the pickup before the first bar. */
    fun barOfBeat(beat: Int): Int {
        if (beat < barStarts[0]) return -1
        var low = 0
        var high = barCount
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (barStarts[middle] <= beat) low = middle else high = middle
        }
        return low
    }

    /** Time in the file of a position in beats; outside the markers the nearest beat length continues. */
    fun secondsAt(position: Double): Double {
        if (times.size < 2) return times.firstOrNull() ?: 0.0
        val index = position.toInt().coerceIn(0, times.size - 2).let { if (position < 0) 0 else it }
        return times[index] + (position - index) * (times[index + 1] - times[index])
    }

    /** The position in beats of a time in the file: the inverse of [secondsAt]. */
    fun positionAt(seconds: Double): Double {
        if (times.size < 2) return 0.0
        var low = 0
        var high = times.size - 1
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (times[middle] <= seconds) low = middle else high = middle
        }
        return low + (seconds - times[low]) / (times[low + 1] - times[low])
    }

    /** Tempo of the beat that starts at index [beat], from the distance to the next one. */
    fun bpmOfBeat(beat: Int): Double = 60.0 / (secondsAt(beat + 1.0) - secondsAt(beat.toDouble()))

    fun barStartSeconds(bar: Int): Double = secondsAt(barStartBeat(bar).toDouble())
}
