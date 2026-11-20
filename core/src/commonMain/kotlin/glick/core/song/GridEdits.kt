package glick.core.song

import glick.core.TimeSignature

/** The edits the song view offers. Each returns a new, sanitized grid. */

/**
 * Moves beat [index] to [seconds] and pins it. Beats between it and the nearest
 * pinned beat on each side keep their relative spacing; a side with no pinned
 * beat moves along rigidly, so the first drag slides the whole grid into place.
 */
fun SongGrid.moveBeat(index: Int, seconds: Double): SongGrid {
    if (index !in beats.indices) return this
    val before = (index - 1 downTo 0).firstOrNull { beats[it].pinned }
    val after = (index + 1..beats.lastIndex).firstOrNull { beats[it].pinned }
    val low = before?.let { beats[it].seconds + 0.03 * (index - it) } ?: Double.NEGATIVE_INFINITY
    val high = after?.let { beats[it].seconds - 0.03 * (it - index) } ?: Double.POSITIVE_INFINITY
    if (low > high) return this
    val target = seconds.coerceIn(low, high)
    val old = beats[index].seconds
    val moved = beats.mapIndexed { i, beat ->
        when {
            i == index -> BeatMarker(target, pinned = true)
            i < index && before == null -> beat.copy(seconds = beat.seconds + target - old)
            i > index && after == null -> beat.copy(seconds = beat.seconds + target - old)
            i < index && i > before!! -> beat.copy(seconds = rescale(beat.seconds, beats[before].seconds, old, target))
            i > index && i < after!! -> beat.copy(seconds = rescale(beat.seconds, beats[after].seconds, old, target))
            else -> beat
        }
    }
    return copy(beats = moved).sanitized()
}

/** Maps [value] from the span anchor..oldEnd onto anchor..newEnd. */
private fun rescale(value: Double, anchor: Double, oldEnd: Double, newEnd: Double): Double =
    anchor + (value - anchor) * (newEnd - anchor) / (oldEnd - anchor)

/** Adds a pinned beat at [seconds]. */
fun SongGrid.addBeat(seconds: Double): SongGrid {
    val index = beats.indexOfFirst { it.seconds > seconds }.let { if (it < 0) beats.size else it }
    return copy(
        beats = beats.take(index) + BeatMarker(seconds, pinned = true) + beats.drop(index),
        downbeat = if (index <= downbeat && beats.isNotEmpty()) downbeat + 1 else downbeat,
    ).sanitized()
}

fun SongGrid.deleteBeat(index: Int): SongGrid {
    if (index !in beats.indices || beats.size <= 2) return this
    return copy(
        beats = beats.filterIndexed { i, _ -> i != index },
        downbeat = if (index < downbeat) downbeat - 1 else downbeat,
    ).sanitized()
}

/** Removes the pin from beat [index], leaving it where it is. */
fun SongGrid.unpinBeat(index: Int): SongGrid =
    copy(beats = beats.mapIndexed { i, beat -> if (i == index) beat.copy(pinned = false) else beat })

/** Makes beat [index] the first beat of a bar, shifting every bar line with it. */
fun SongGrid.setBarStart(index: Int): SongGrid {
    if (index !in beats.indices) return this
    val first = signatureOfBar(0).numerator
    return copy(downbeat = index % first).sanitized()
}

/** Puts a beat half-way between every pair of beats: the same music counted twice as fast. */
fun SongGrid.doubleTempo(): SongGrid {
    val doubled = buildList {
        beats.forEachIndexed { i, beat ->
            add(beat)
            if (i < beats.lastIndex) add(BeatMarker((beat.seconds + beats[i + 1].seconds) / 2))
        }
    }
    return copy(beats = doubled, downbeat = downbeat * 2).sanitized()
}

/** Keeps every other beat, starting with the bar start: the same music counted half as fast. */
fun SongGrid.halveTempo(): SongGrid {
    val parity = downbeat % 2
    val kept = beats.filterIndexed { i, _ -> i % 2 == parity }
    if (kept.size < 2) return this
    return copy(beats = kept, downbeat = downbeat / 2).sanitized()
}

/** Gives [bar] and every bar after it, up to the next change, the signature [signature]. */
fun SongGrid.setSignature(bar: Int, signature: TimeSignature): SongGrid =
    copy(signatures = signatures.filter { it.bar != bar } + SignatureChange(bar, signature))
        .dropRedundantSignatures()
        .sanitized()

private fun SongGrid.dropRedundantSignatures(): SongGrid {
    val ordered = signatures.sortedBy { it.bar }
    return copy(signatures = ordered.filterIndexed { i, change -> i == 0 || change.signature != ordered[i - 1].signature })
}

/** Loops from the start of [startBar] to the start of [endBar]. */
fun SongGrid.setLoop(startBar: Int, endBar: Int): SongGrid {
    val bars = SongTimeline(this).barCount
    val start = startBar.coerceIn(0, (bars - 1).coerceAtLeast(0))
    val end = endBar.coerceIn(start + 1, bars.coerceAtLeast(start + 1))
    return copy(loopStartBar = start, loopEndBar = end.takeIf { it < bars }).sanitized()
}

/** Replaces the beats by the constant-tempo grid that fits them best; nothing stays pinned. */
fun SongGrid.steadied(): SongGrid =
    copy(beats = BeatDetector.steady(beats.map { it.seconds }).map { BeatMarker(it) }).sanitized()
