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
    return copy(beats = moved, firstBeat = null).sanitized()
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
        firstBeat = null,
    ).sanitized()
}

fun SongGrid.deleteBeat(index: Int): SongGrid {
    if (index !in beats.indices || beats.size <= 2) return this
    return copy(
        beats = beats.filterIndexed { i, _ -> i != index },
        downbeat = if (index < downbeat) downbeat - 1 else downbeat,
        firstBeat = null,
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
    return copy(beats = doubled, downbeat = downbeat * 2, firstBeat = null).sanitized()
}

/** Keeps every other beat, starting with the bar start: the same music counted half as fast. */
fun SongGrid.halveTempo(): SongGrid {
    val parity = downbeat % 2
    val kept = beats.filterIndexed { i, _ -> i % 2 == parity }
    if (kept.size < 2) return this
    return copy(beats = kept, downbeat = downbeat / 2, firstBeat = null).sanitized()
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
    copy(beats = BeatDetector.steady(beats.map { it.seconds }).map { BeatMarker(it) }, firstBeat = null).sanitized()

/**
 * A perfectly even grid at [bpm] over a file [duration] seconds long, with the
 * first beat of a bar at [firstBeat]. Beats run from the start of the file to
 * its end, so [firstBeat] may be anywhere, also in the middle of the song.
 */
fun SongGrid.steadyAt(bpm: Double, firstBeat: Double, duration: Double): SongGrid {
    val period = 60.0 / bpm.coerceIn(BeatDetector.MIN_KNOWN_BPM, BeatDetector.MAX_KNOWN_BPM)
    val first = kotlin.math.ceil(-firstBeat / period - 1e-9).toInt()
    val last = kotlin.math.floor((duration - firstBeat) / period + 1e-9).toInt()
    val perBar = signatureOfBar(0).numerator
    return copy(
        beats = (first..last).map { BeatMarker((firstBeat + it * period).coerceAtLeast(0.0)) },
        downbeat = ((-first) % perBar + perBar) % perBar,
        knownBpm = bpm,
        firstBeat = firstBeat,
        unmapped = false,
    ).sanitized()
}

/**
 * The grid made from a tempo recording. [taps] become pinned beats, the first
 * one starting a bar; before the first and after the last tap the grid carries
 * on at the tempo of the nearest taps, to both ends of the file.
 */
fun SongGrid.withTaps(taps: List<Double>, duration: Double): SongGrid {
    val tapped = taps.sorted().fold(mutableListOf<Double>()) { kept, tap ->
        if (kept.isEmpty() || tap - kept.last() > MIN_TAP_GAP) kept += tap
        kept
    }
    if (tapped.size < 2) return this
    fun typical(intervals: List<Double>) = intervals.sorted()[intervals.size / 2]
    val intervals = tapped.zipWithNext { a, b -> b - a }
    val lead = typical(intervals.take(NEARBY_TAPS))
    val trail = typical(intervals.takeLast(NEARBY_TAPS))
    val before = generateSequence(tapped.first() - lead) { it - lead }.takeWhile { it >= 0 }.toList().asReversed()
    val after = generateSequence(tapped.last() + trail) { it + trail }.takeWhile { it <= duration }.toList()
    val perBar = signatureOfBar(0).numerator
    return copy(
        beats = before.map { BeatMarker(it) } + tapped.map { BeatMarker(it, pinned = true) } + after.map { BeatMarker(it) },
        downbeat = before.size % perBar,
        loopStartBar = 0,
        loopEndBar = null,
        firstBeat = null,
        unmapped = false,
    ).sanitized()
}

/** Taps closer together than this are one tap. */
private const val MIN_TAP_GAP = 0.1

/** How many tap intervals next to an end of a recording set the tempo continued past it. */
private const val NEARBY_TAPS = 4

/**
 * Moves every pinned beat onto the nearest of [attacks] (sorted times of
 * audible onsets) when one lies within [window] seconds; the others stay.
 */
fun SongGrid.snappedTo(attacks: List<Double>, window: Double = 0.06): SongGrid {
    if (attacks.isEmpty()) return this
    val moved = beats.map { beat ->
        if (!beat.pinned) return@map beat
        val after = attacks.binarySearch(beat.seconds).let { if (it < 0) -it - 1 else it }
        val nearest = listOfNotNull(attacks.getOrNull(after - 1), attacks.getOrNull(after))
            .minBy { kotlin.math.abs(it - beat.seconds) }
        if (kotlin.math.abs(nearest - beat.seconds) <= window) beat.copy(seconds = nearest) else beat
    }
    return copy(beats = moved, firstBeat = null).sanitized()
}

/**
 * Evens out the scatter of tapped beats: each one moves onto the straight
 * line through itself and its [reach] neighbours on each side, so the tempo
 * still follows the taps over a few beats but single early or late taps
 * vanish. The grid before and after the taps is laid out again from the
 * smoothed tempo. A grid with no tapped beats is smoothed as a whole.
 */
fun SongGrid.smoothed(reach: Int = 4): SongGrid {
    if (beats.size < 3) return this
    val firstTap = beats.indexOfFirst { it.pinned }.takeIf { it >= 0 } ?: 0
    val lastTap = beats.indexOfLast { it.pinned }.takeIf { it >= 0 } ?: beats.lastIndex
    if (lastTap - firstTap < 2) return this
    val times = DoubleArray(beats.size) { beats[it].seconds }
    for (i in firstTap..lastTap) {
        val from = maxOf(firstTap, i - reach)
        val to = minOf(lastTap, i + reach)
        val meanIndex = (from + to) / 2.0
        val meanTime = (from..to).sumOf { beats[it].seconds } / (to - from + 1)
        var covariance = 0.0
        var variance = 0.0
        for (j in from..to) {
            covariance += (j - meanIndex) * (beats[j].seconds - meanTime)
            variance += (j - meanIndex) * (j - meanIndex)
        }
        times[i] = meanTime + (i - meanIndex) * covariance / variance
    }
    val span = minOf(NEARBY_TAPS, lastTap - firstTap)
    val lead = (times[firstTap + span] - times[firstTap]) / span
    val trail = (times[lastTap] - times[lastTap - span]) / span
    for (i in 0 until firstTap) times[i] = times[firstTap] - (firstTap - i) * lead
    for (i in lastTap + 1..beats.lastIndex) times[i] = times[lastTap] + (i - lastTap) * trail
    val moved = beats.mapIndexed { i, beat -> beat.copy(seconds = times[i]) }.filter { it.seconds >= 0 }
    val perBar = signatureOfBar(0).numerator
    val shifted = ((downbeat - (beats.size - moved.size)) % perBar + perBar) % perBar
    return copy(beats = moved, downbeat = shifted, firstBeat = null).sanitized()
}

/** Removes the signature change at [bar]; that bar then continues in the signature before it. Bar 0 always has one. */
fun SongGrid.removeSignature(bar: Int): SongGrid =
    if (bar <= 0) this else copy(signatures = signatures.filter { it.bar != bar }).sanitized()

/** A grid whose variation in use holds the grid's loop and the practice settings of [state]. */
fun SongGrid.capturing(state: glick.core.MetronomeState): SongGrid {
    if (variations.isEmpty()) return this
    val before = current
    // While a workout is on, the tempo belongs to its ladder and is not the variation's own.
    val own = !state.workout.enabled
    val now = before.copy(
        loopStartBar = loopStartBar,
        loopEndBar = loopEndBar,
        songMode = state.songMode,
        bpm = if (own && state.songMode == glick.core.SongMode.SongFollowsClick) state.bpm else before.bpm,
        songSpeed = if (own && state.songMode == glick.core.SongMode.ClickFollowsSong) state.songSpeed else before.songSpeed,
        workout = state.workout,
    )
    return if (now == before) this else copy(variations = variations.mapIndexed { i, v -> if (i == variation) now else v })
}

/** Makes variation [index] the one in use and takes over its loop. */
fun SongGrid.withVariation(index: Int): SongGrid {
    val chosen = variations.getOrNull(index) ?: return this
    return copy(variation = index, loopStartBar = chosen.loopStartBar, loopEndBar = chosen.loopEndBar).sanitized()
}

/** Stores the current loop and settings once more, as a new variation that is then in use. */
fun SongGrid.addVariation(): SongGrid {
    val names = variations.map { it.name }.toSet()
    val name = generateSequence(variations.size + 1) { it + 1 }.map { "Variation $it" }.first { it !in names }
    return copy(variations = variations + current.copy(name = name), variation = variations.size)
}

/** Deletes the variation in use; the one before it takes over. The last variation cannot be deleted. */
fun SongGrid.deleteVariation(): SongGrid {
    if (variations.size <= 1) return this
    val left = variations.filterIndexed { i, _ -> i != variation }
    return copy(variations = left).withVariation((variation - 1).coerceAtLeast(0))
}

fun SongGrid.renameVariation(name: String): SongGrid {
    val clean = name.trim().take(40)
    if (clean.isEmpty()) return this
    return copy(variations = variations.mapIndexed { i, v -> if (i == variation) v.copy(name = clean) else v })
}
