package glick.core.song

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Result of analysing a song. Times are seconds from the start of the file. */
data class Detection(
    /** Beat times, strictly increasing, covering the whole file. */
    val beats: List<Double>,
    /** Tentative bar length in beats, 2..7. */
    val beatsPerBar: Int,
    /** Tentative denominator of the time signature: 4 or 8. */
    val noteLength: Int,
    /** Index into [beats] of the first beat that starts a bar, in `0 until beatsPerBar`. */
    val downbeat: Int,
    /** Median tempo in beats per minute. */
    val bpm: Double,
)

/**
 * Finds the beats of a song, and guesses its bar length and where bars start.
 *
 * The song is reduced to an "onset envelope" that peaks wherever notes start.
 * How regularly those peaks repeat gives the tempo, followed over time so that
 * a band that speeds up or slows down is tracked. Beats are then the most
 * evenly spaced sequence at that tempo that lands on as many onsets as it can.
 *
 * The beats are reliable on music with a clear pulse. The bar length and the
 * downbeat are an educated guess from where bass, kick drum and chord changes
 * tend to fall, and are meant to be corrected by the user.
 */
object BeatDetector {
    private const val MIN_BPM = 40.0
    private const val MAX_BPM = 240.0

    /** Tempos are weighed by how common they are: most likely here, falling off over about [TEMPO_SPREAD] octaves either side. */
    private const val LIKELY_BPM = 120.0
    private const val TEMPO_SPREAD = 1.0

    /** With a tempo given by the user, how far (in octaves) the song may plausibly stray from it. */
    private const val KNOWN_TEMPO_SPREAD = 0.1

    /** The tempos a user may state. */
    const val MIN_KNOWN_BPM = 20.0
    const val MAX_KNOWN_BPM = 480.0

    /** Seconds of envelope each local tempo estimate looks at, and the seconds between estimates. */
    private const val TEMPO_WINDOW = 8.0
    private const val TEMPO_HOP = 1.0

    /** How strongly the tempo is held steady from one estimate to the next. */
    private const val TEMPO_INERTIA = 20.0

    /** How strongly beats are held to the local tempo rather than pulled onto every onset. */
    private const val TIGHTNESS = 100.0

    /** Seconds either side over which the envelope's local average is taken out. */
    private const val LOCAL_MEAN = 0.5

    /** Frames either side of a tracked beat searched for the onset peak it belongs to. */
    private const val PEAK_REACH = 2

    /** A beat counts as heard when its onset is at least this fraction of the average beat's. */
    private const val HEARD = 0.2

    /**
     * Frames by which an onset's envelope peak comes ahead of the onset
     * itself. The levels are compressed, so they jump as soon as an attack
     * enters the edge of the analysis window, before it reaches the middle;
     * measured on drum-like bursts at several sample rates.
     */
    private const val PEAK_LEAD = 0.6

    /** An onset counts as an attack when it is at least this strong, in units of the song's average onset strength. */
    private const val ATTACK_STRENGTH = 1.0

    /** How many beat intervals next to an end of the music set the interval continued past it. */
    private const val NEARBY = 8

    /** Frames either side of a beat within which a bass note or kick drum has risen to full level. */
    private const val ATTACK = 4

    /** Beats per tempo reading that goes into the median tempo. */
    private const val BPM_SPAN = 4

    /**
     * Beats never measure exactly alike, even when they sound alike. The bass
     * onsets must vary by at least [FLAT_BASS] of their average, and the
     * changes of sound by [FLAT_CHANGE] of the average loudness, to be taken
     * as a pattern rather than as that measuring noise.
     */
    private const val FLAT_BASS = 0.15
    private const val FLAT_CHANGE = 0.03

    /** Fewer beats than this say nothing useful about the bar. */
    private const val MIN_METER_BEATS = 16

    /** A bar length must repeat at least this clearly (correlation) to be believed over plain 4. */
    private const val METER_EVIDENCE = 0.3

    /** How much clearer 2 must be than 4, or 6 than 3, to be chosen over the commoner reading. */
    private const val METER_MARGIN = 0.2

    /** Above this tempo, bars of 5 or 7 are taken to count eighth notes. */
    private const val EIGHTHS_BPM = 160.0

    /**
     * Runs the whole analysis. [progress] gets 0f..1f.
     *
     * When the tempo is known, pass it as [knownBpm]: the tracker then looks
     * only near that tempo, which rules out the half- and double-tempo
     * readings it could otherwise fall into, while still following the
     * song's small drifts around it.
     *
     * Reads [source] once in blocks; working memory is a few megabytes however
     * long the song is. A silent file gets a plain 120 BPM grid.
     */
    fun detect(source: AudioSource, knownBpm: Double? = null, progress: (Float) -> Unit = {}): Detection {
        val known = knownBpm?.takeIf { it.isFinite() }?.coerceIn(MIN_KNOWN_BPM, MAX_KNOWN_BPM)
        val duration = source.frames.toDouble() / source.sampleRate
        val frames = OnsetFrames.analyse(source) { progress(0.9f * it) }
        val rate = frames.framesPerSecond
        val envelope = onsetEnvelope(frames.flux, rate)
        val detection = if (envelope == null) {
            val bpm = known ?: LIKELY_BPM
            val beats = generateSequence(0.0) { it + 60 / bpm }.takeWhile { it <= duration }.toList()
            Detection(beats, beatsPerBar = 4, noteLength = 4, downbeat = 0, bpm = bpm)
        } else {
            val periods = trackTempo(envelope, rate, known)
            val placed = placeBeats(trackBeats(envelope, periods), periods, frames.flux, envelope, rate, duration)
            val beats = placed.beats
            // Measured across a few beats at a time, so that the timing error of single beats matters less.
            val span = min(BPM_SPAN, beats.size - 1).coerceAtLeast(1)
            val bpm = 60 / median(beats.windowed(span + 1) { (it.last() - it.first()) / span })
            val meter = guessMeter(beats, placed.music, frames)
            val eighths = meter.beatsPerBar == 6 || (meter.beatsPerBar % 2 == 1 && meter.beatsPerBar > 3 && bpm > EIGHTHS_BPM)
            Detection(beats, meter.beatsPerBar, if (eighths) 8 else 4, meter.downbeat, bpm)
        }
        progress(1f)
        return detection
    }

    /**
     * The times, in seconds, of the clearly audible attacks in [source]: drum
     * hits, plucked and struck notes. Used to pull hand-placed beats onto the
     * sound they were meant for.
     */
    fun attacks(source: AudioSource, progress: (Float) -> Unit = {}): List<Double> {
        val frames = OnsetFrames.analyse(source, progress)
        val rate = frames.framesPerSecond
        val envelope = onsetEnvelope(frames.flux, rate) ?: return emptyList()
        val flux = frames.flux
        return (1 until envelope.size - 1)
            .filter { envelope[it] >= ATTACK_STRENGTH && envelope[it] > envelope[it - 1] && envelope[it] >= envelope[it + 1] }
            .map { (it + peakOffset(flux[it - 1].toDouble(), flux[it].toDouble(), flux[it + 1].toDouble()) + PEAK_LEAD) / rate }
    }

    /**
     * Replaces drifting beats by the best constant-tempo grid through them
     * (least squares on index vs time), same count.
     */
    fun steady(beats: List<Double>): List<Double> {
        val n = beats.size
        if (n < 2) return beats
        val meanIndex = (n - 1) / 2.0
        val meanTime = beats.average()
        var covariance = 0.0
        var variance = 0.0
        for (i in 0 until n) {
            covariance += (i - meanIndex) * (beats[i] - meanTime)
            variance += (i - meanIndex) * (i - meanIndex)
        }
        val interval = covariance / variance
        return List(n) { meanTime + (it - meanIndex) * interval }
    }

    /**
     * The flux with its local average taken out, clipped at zero and scaled
     * to unit strength, so that loud and quiet passages count alike. Null when
     * the song has no onsets at all.
     */
    private fun onsetEnvelope(flux: FloatArray, rate: Double): FloatArray? {
        val n = flux.size
        val reach = (LOCAL_MEAN * rate).roundToInt()
        val running = DoubleArray(n + 1)
        for (i in 0 until n) running[i + 1] = running[i] + flux[i]
        val envelope = FloatArray(n) {
            val from = max(0, it - reach)
            val to = min(n, it + reach + 1)
            max(0.0, flux[it] - (running[to] - running[from]) / (to - from)).toFloat()
        }
        val strength = sqrt(envelope.sumOf { it.toDouble() * it } / n)
        if (strength < 1e-9) return null
        for (i in 0 until n) envelope[i] = (envelope[i] / strength).toFloat()
        return envelope
    }

    /**
     * The beat period, in frames, at every frame of the song.
     *
     * Each second, the envelope around it is compared with itself shifted by
     * every plausible period (autocorrelation). The path through those
     * estimates that scores best without jumping about is taken, which keeps
     * the tempo from flipping to its half or double for a moment and carries
     * it across passages with no onsets.
     */
    private fun trackTempo(envelope: FloatArray, rate: Double, known: Double?): DoubleArray {
        val n = envelope.size
        // Onset peaks are a frame or two wide and periods are rarely whole frames:
        // widened a little, the peaks still meet when shifted by the nearest whole period.
        val smooth = FloatArray(n) {
            var sum = 0f
            for (k in -2..2) if (it + k in 0 until n) sum += (3 - abs(k)) * envelope[it + k]
            sum / 9
        }
        // A stated tempo may lie outside the usual range; the search then reaches a little past it.
        val fastest = max(MAX_BPM, (known ?: 0.0) * 1.25)
        val slowest = min(MIN_BPM, (known ?: MIN_BPM) / 1.25)
        val minLag = max(2, floor(60 * rate / fastest).toInt())
        val maxLag = ceil(60 * rate / slowest).toInt()
        val lags = maxLag - minLag + 1
        val centre = known ?: LIKELY_BPM
        val spread = if (known != null) KNOWN_TEMPO_SPREAD else TEMPO_SPREAD
        val prior = DoubleArray(lags) {
            val octaves = ln(60 * rate / (minLag + it) / centre) / ln(2.0) / spread
            exp(-0.5 * octaves * octaves)
        }
        val length = min(n, (TEMPO_WINDOW * rate).roundToInt())
        val hop = (TEMPO_HOP * rate).roundToInt()
        val windows = max(1, n / hop)

        // Score of every period in every window.
        val scores = Array(windows) { DoubleArray(lags) }
        val similarity = DoubleArray(2 * maxLag + 1)
        val centred = FloatArray(length)
        for (window in 0 until windows) {
            val start = (window * hop + hop / 2 - length / 2).coerceIn(0, n - length)
            var mean = 0f
            for (i in 0 until length) mean += smooth[start + i]
            mean /= length
            var power = 0.0
            for (i in 0 until length) {
                centred[i] = smooth[start + i] - mean
                power += centred[i] * centred[i]
            }
            if (power < 1e-9 * length) continue
            for (lag in minLag..2 * maxLag) {
                val overlap = length - lag
                var sum = 0f
                for (i in 0 until overlap) sum += centred[i] * centred[i + lag]
                // Too little overlap says nothing reliable.
                similarity[lag] = if (overlap >= length / 4) sum / overlap / (power / length) else 0.0
            }
            // The envelope also repeats after two true periods, which tells the period from its half.
            for (i in 0 until lags) {
                val lag = minLag + i
                scores[window][i] = prior[i] * max(0.0, similarity[lag] + 0.5 * similarity[2 * lag])
            }
        }

        // Best path through the windows.
        val octave = DoubleArray(lags) { ln((minLag + it).toDouble()) / ln(2.0) }
        var best = scores[0].copyOf()
        val from = Array(windows) { IntArray(lags) }
        for (window in 1 until windows) {
            val next = DoubleArray(lags)
            for (i in 0 until lags) {
                var top = Double.NEGATIVE_INFINITY
                var topAt = 0
                for (j in 0 until lags) {
                    val jump = octave[i] - octave[j]
                    val value = best[j] - TEMPO_INERTIA * jump * jump
                    if (value > top) {
                        top = value
                        topAt = j
                    }
                }
                next[i] = top + scores[window][i]
                from[window][i] = topAt
            }
            best = next
        }
        val chosen = DoubleArray(windows)
        // With nothing to go by (too short, or no repetition at all) the likeliest tempo is as good as any.
        val likely = ((60 * rate / LIKELY_BPM).roundToInt() - minLag).coerceIn(0, lags - 1)
        var at = if (best.max() > 0) best.indices.maxBy { best[it] } else likely
        for (window in windows - 1 downTo 0) {
            val score = scores[window]
            val shift = if (at in 1 until lags - 1) peakOffset(score[at - 1], score[at], score[at + 1]) else 0.0
            chosen[window] = minLag + at + shift
            at = from[window][at]
        }

        return DoubleArray(n) {
            val position = ((it - hop / 2).toDouble() / hop).coerceIn(0.0, windows - 1.0)
            val low = position.toInt().coerceAtMost(windows - 1)
            val high = min(low + 1, windows - 1)
            chosen[low] + (chosen[high] - chosen[low]) * (position - low)
        }
    }

    /**
     * The frames of the beats, from the first period of the song to the last.
     *
     * Every frame is scored as "the best sequence of beats ending here": its
     * own onset strength plus the best earlier beat, marked down the further
     * the gap between them is from the local period (after Ellis, "Beat
     * Tracking by Dynamic Programming"). Through silence the sequence simply
     * carries on at the local period.
     */
    private fun trackBeats(envelope: FloatArray, periods: DoubleArray): IntArray {
        val n = envelope.size
        val score = DoubleArray(n)
        val previous = IntArray(n)
        for (frame in 0 until n) {
            val period = periods[frame]
            var best = Double.NEGATIVE_INFINITY
            var bestAt = -1
            for (earlier in max(0, frame - (2 * period).roundToInt())..frame - (period / 2).roundToInt()) {
                val stretch = ln((frame - earlier) / period)
                val value = score[earlier] - TIGHTNESS * stretch * stretch
                if (value > best) {
                    best = value
                    bestAt = earlier
                }
            }
            score[frame] = envelope[frame] + if (bestAt >= 0) best else 0.0
            previous[frame] = bestAt
        }
        val tail = max(0, n - periods[n - 1].roundToInt())
        var frame = (tail until n).maxBy { score[it] }
        val beats = ArrayList<Int>()
        while (frame >= 0) {
            beats += frame
            frame = previous[frame]
        }
        beats.reverse()
        return beats.toIntArray()
    }

    /**
     * Turns tracked beat frames into beat times that cover the whole file.
     *
     * A beat with an onset under it moves onto that onset's exact time, found
     * between frames. A beat with none (a held note, a break, silence) is
     * spaced evenly between its neighbours that have one. Before the first and
     * after the last such beat the nearby beat interval is continued to the
     * ends of the file.
     */
    private fun placeBeats(
        beatFrames: IntArray,
        periods: DoubleArray,
        flux: FloatArray,
        envelope: FloatArray,
        rate: Double,
        duration: Double,
    ): Placed {
        val last = envelope.size - 1
        val times = DoubleArray(beatFrames.size)
        val strengths = FloatArray(beatFrames.size)
        for (i in beatFrames.indices) {
            val frame = beatFrames[i]
            val peak = (max(0, frame - PEAK_REACH)..min(last, frame + PEAK_REACH)).maxBy { envelope[it] }
            val shift = if (peak in 1 until last) peakOffset(flux[peak - 1].toDouble(), flux[peak].toDouble(), flux[peak + 1].toDouble()) else 0.0
            times[i] = (peak + shift + PEAK_LEAD) / rate
            strengths[i] = envelope[peak]
        }
        val heard = HEARD * strengths.average()
        val anchors = times.indices.filter { strengths[it] >= heard }
        for ((a, b) in anchors.zipWithNext()) {
            for (i in a + 1 until b) times[i] = times[a] + (times[b] - times[a]) * (i - a) / (b - a)
        }
        val kept = times.slice(anchors.first()..anchors.last())
        val fallback = periods[beatFrames[anchors.first()]] / rate
        val lead = if (kept.size > 1) median(kept.take(NEARBY + 1).zipWithNext { a, b -> b - a }) else fallback
        val trail = if (kept.size > 1) median(kept.takeLast(NEARBY + 1).zipWithNext { a, b -> b - a }) else fallback
        val before = generateSequence(kept.first() - lead) { it - lead }.takeWhile { it >= 0 }.toList().asReversed()
        val after = generateSequence(kept.last() + trail) { it + trail }.takeWhile { it <= duration }.toList()
        return Placed(before + kept + after, music = before.size until before.size + kept.size)
    }

    /** Beat times for the whole file, and which of them lie where there is [music] rather than silence around it. */
    private class Placed(val beats: List<Double>, val music: IntRange)

    private class Meter(val beatsPerBar: Int, val downbeat: Int)

    /**
     * Guesses the bar length and the first bar start.
     *
     * Each beat gets an accent: how much bass or kick drum starts on it, plus
     * how much the sound changes from the beat before (chords tend to change
     * on bar starts). The bar length is the number of beats after which the
     * accents repeat best, leaning toward 4 and then 3, and reading 2 as 4 and
     * 6 as 3 unless the longer or shorter bar is clearly the better fit. The
     * downbeat is the position in the bar with the strongest accents.
     */
    private fun guessMeter(beats: List<Double>, music: IntRange, frames: OnsetFrames): Meter {
        val plain = Meter(beatsPerBar = 4, downbeat = 0)
        if (music.count() < MIN_METER_BEATS) return plain
        val accents = beatAccents(beats.slice(music), frames)
        val n = accents.size
        val power = accents.sumOf { it * it } / n
        if (power < 1e-12) return plain
        val repeat = DoubleArray(8)
        for (bar in 2..7) {
            var sum = 0.0
            for (i in 0 until n - bar) sum += accents[i] * accents[i + bar]
            repeat[bar] = sum / (n - bar) / power
        }
        val leaning = doubleArrayOf(0.0, 0.0, 0.0, 0.05, 0.1, 0.0, 0.0, 0.0)
        var bar = (2..7).maxBy { repeat[it] + leaning[it] }
        if (repeat[bar] < METER_EVIDENCE) return plain
        if (bar == 2 && repeat[2] - repeat[4] < METER_MARGIN) bar = 4
        if (bar == 6 && repeat[6] - repeat[3] < METER_MARGIN) bar = 3
        val accented = (0 until bar).maxBy { phase ->
            var sum = 0.0
            var count = 0
            for (i in phase until n step bar) {
                sum += accents[i]
                count++
            }
            sum / count
        }
        return Meter(bar, downbeat = (music.first + accented) % bar)
    }

    /** One accent per beat: the bass onset on it plus the change of sound since the beat before, each standardised. */
    private fun beatAccents(beats: List<Double>, frames: OnsetFrames): DoubleArray {
        val n = beats.size
        val bandCount = OnsetFrames.BANDS
        val bands = frames.bands
        val at = IntArray(n + 1) {
            val time = if (it < n) beats[it] else 2 * beats[n - 1] - beats[n - 2]
            (time * frames.framesPerSecond).roundToInt().coerceIn(0, frames.count - 1)
        }
        val bass = DoubleArray(n)
        val change = DoubleArray(n)
        var sound = DoubleArray(bandCount)
        var loudness = 0.0
        val lowLevel = { frame: Int ->
            var sum = 0.0
            for (band in 0 until frames.lowBands) sum += bands[frame * bandCount + band]
            sum
        }
        for (beat in 0 until n) {
            // How far the bass rises across the beat: its loudest just after, over its quietest just before.
            val before = (max(0, at[beat] - ATTACK)..at[beat]).minOf(lowLevel)
            val after = (at[beat]..min(frames.count - 1, at[beat] + ATTACK)).maxOf(lowLevel)
            bass[beat] = max(0.0, after - before)
            // Averaged from just ahead of the beat, so that its attack always counts as its own.
            val start = max(0, at[beat] - PEAK_REACH)
            val end = max(start + 1, at[beat + 1] - PEAK_REACH)
            val average = DoubleArray(bandCount)
            for (frame in start until end) {
                for (band in 0 until bandCount) average[band] += bands[frame * bandCount + band] / (end - start)
            }
            if (beat > 0) for (band in 0 until bandCount) change[beat] += abs(average[band] - sound[band])
            sound = average
            loudness += average.sum() / n
        }
        val bassScore = standardised(bass, noise = FLAT_BASS * bass.average())
        val changeScore = standardised(change, noise = FLAT_CHANGE * loudness)
        return DoubleArray(n) { bassScore[it] + changeScore[it] }
    }

    /**
     * [values] shifted to zero mean and scaled to unit spread. All zero when
     * their spread is no more than [noise]: scaling that up would invent a
     * pattern.
     */
    private fun standardised(values: DoubleArray, noise: Double): DoubleArray {
        val mean = values.average()
        val spread = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return DoubleArray(values.size) { if (spread > noise) (values[it] - mean) / spread else 0.0 }
    }

    /**
     * Where, between -0.5 and 0.5 samples from the middle one, the parabola
     * through three samples peaks. Zero when the middle is not a peak.
     */
    private fun peakOffset(before: Double, middle: Double, after: Double): Double {
        val curve = before - 2 * middle + after
        if (curve >= 0 || middle < before || middle < after) return 0.0
        return (0.5 * (before - after) / curve).coerceIn(-0.5, 0.5)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 60 / LIKELY_BPM
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
