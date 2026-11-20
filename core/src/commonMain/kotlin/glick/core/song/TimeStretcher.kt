package glick.core.song

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Stretches [source] in time without changing its pitch. One instance per playing song; not thread-safe.
 *
 * The caller dictates, block by block, which span of the source the output must
 * present, and the source is random access, so the stretcher is driven by
 * position rather than by an input queue: it never lags behind the mapping.
 *
 * It is a phase vocoder with a fixed synthesis hop. Every [HOP] output frames a
 * grain is built in four steps, each a private function so that one can be
 * refined without touching the others:
 *
 * 1. **Transient handling** ([findOnset], [lockToOnset]): look in the source
 *    for an attack near the grain. Around one, grains step through the source
 *    at original speed, so the attack lands exactly at its mapped output time
 *    and is not smeared; the stretch happens between attacks.
 * 2. **Analysis** ([analyse]): spectra of the grain centred on the mapped source
 *    position and of a second grain one hop earlier *in the source*. The pair
 *    gives each partial's phase advance per hop, whatever the playback speed.
 * 3. **Phase propagation** ([findPeaks], [propagatePhases]): identity phase
 *    locking. Each spectral peak continues the phase the previous output grain
 *    left behind; the bins around a peak turn with it, which keeps the shape of
 *    every partial. Left and right are turned by the same angle, so the stereo
 *    image is untouched. At an attack, peaks that have just risen keep their
 *    source phase instead, which keeps the attack sharp.
 * 4. **Synthesis** ([synthesise]): back to the time domain and overlap-add.
 *
 * At original speed none of this runs: the source is copied through.
 *
 * The analysis uses a single resolution ([FRAME]) for the whole spectrum; a
 * multi-resolution engine would replace steps 2 to 4 per frequency band.
 */
class TimeStretcher(private val source: AudioSource) {
    private val fft = Fft(FRAME)
    private val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2.0 * PI * it / FRAME)).toFloat() }

    // Source audio around the grain being built: the grain, the grain a hop
    // earlier, and the stretch of source searched for attacks.
    private val rawLeft = FloatArray(RAW_LENGTH)
    private val rawRight = FloatArray(RAW_LENGTH)
    private var rawStart = 0L
    private val blockEnergy = FloatArray(RAW_LENGTH / ONSET_BLOCK + 2)

    // Left and right travel through one complex transform: left in the real
    // part, right in the imaginary part.
    private val currentRe = FloatArray(FRAME)
    private val currentIm = FloatArray(FRAME)
    private val earlierRe = FloatArray(FRAME)
    private val earlierIm = FloatArray(FRAME)

    // Spectra of the grain, bins 0..FRAME/2.
    private val inLeftRe = FloatArray(BINS)
    private val inLeftIm = FloatArray(BINS)
    private val inRightRe = FloatArray(BINS)
    private val inRightIm = FloatArray(BINS)
    private val power = FloatArray(BINS)
    private val peaks = IntArray(BINS)
    private var peakCount = 0

    // Spectra of the previous output grain: the phase memory of the vocoder.
    private val outLeftRe = FloatArray(BINS)
    private val outLeftIm = FloatArray(BINS)
    private val outRightRe = FloatArray(BINS)
    private val outRightIm = FloatArray(BINS)

    // Overlap-add ring, indexed by output time.
    private val mixLeft = FloatArray(FRAME)
    private val mixRight = FloatArray(FRAME)

    /** Output frames produced since the vocoder was last started. */
    private var outTime = 0L

    /** Output time of the centre of the next grain to build. */
    private var nextCentre = 0L

    /** Source position the most recent grain was built for, to notice when the mapping has moved away from it. */
    private var newestGrainPosition = 0.0
    private val resyncDistance = source.sampleRate * RESYNC_MILLIS / 1000.0

    private val directLeft = FloatArray(DIRECT_SCRATCH)
    private val directRight = FloatArray(DIRECT_SCRATCH)

    // Crossfade from whatever was playing before a seek or a change of mode.
    private val fadeLength = min(source.sampleRate * FADE_MILLIS / 1000, HOP).coerceAtLeast(1)
    private val fadeIn = FloatArray(fadeLength) { (0.5 - 0.5 * cos(PI * (it + 0.5) / fadeLength)).toFloat() }
    private val tailLeft = FloatArray(fadeLength)
    private val tailRight = FloatArray(fadeLength)
    private val continuedLeft = FloatArray(fadeLength)
    private val continuedRight = FloatArray(fadeLength)
    private var fadePosition = fadeLength

    private var started = false
    private var direct = false
    private var expectedStart = 0.0
    private var lastStep = 1.0

    /**
     * Fills the first [frames] entries of [left] and [right] (overwriting).
     * Output frame i presents the source at position sourceStart + i * sourceStep
     * (in source frames, fractional). sourceStep 1.0 is original speed, 0.5 half
     * speed, 2.0 double speed; it may change from call to call and the output
     * follows smoothly. [jump] is true when sourceStart is not the continuation
     * of the previous call (seek or loop wrap): the old sound is faded into the
     * new one over a few milliseconds instead of clicking.
     * Called on the audio thread: does not allocate or lock.
     */
    fun render(left: FloatArray, right: FloatArray, frames: Int, sourceStart: Double, sourceStep: Double, jump: Boolean) {
        if (frames <= 0) return
        if (!(abs(sourceStart) < MAX_POSITION) || !(abs(sourceStep) < MAX_STEP)) {
            left.fill(0f, 0, frames)
            right.fill(0f, 0, frames)
            reset()
            return
        }
        val bypassWidth = if (started && direct) BYPASS_LEAVE else BYPASS_ENTER
        val wantDirect = abs(sourceStep - 1.0) <= bypassWidth
        val overtaken = started && !direct && !wantDirect && !jump && mappingHasMoved(sourceStart, sourceStep)
        if (!started || jump || wantDirect != direct || overtaken) {
            keepTail(if (jump) expectedStart else sourceStart)
            direct = wantDirect
            if (!direct) startVocoder()
            started = true
        }
        if (direct) {
            renderDirect(left, right, frames, sourceStart, sourceStep)
        } else {
            renderStretched(left, right, frames, sourceStart, sourceStep)
        }
        fadeFromTail(left, right, frames)
        expectedStart = sourceStart + frames * sourceStep
        lastStep = sourceStep
    }

    /** Forgets all history, as before the first call. */
    fun reset() {
        started = false
        direct = false
        fadePosition = fadeLength
        expectedStart = 0.0
        lastStep = 1.0
    }

    // ---- Original speed ---------------------------------------------------

    /**
     * Copies the source through at (almost) original speed. Whole positions at
     * step 1 are copied exactly; anything else is interpolated with a cubic
     * through the four nearest frames.
     */
    private fun renderDirect(left: FloatArray, right: FloatArray, frames: Int, start: Double, step: Double) {
        var done = 0
        while (done < frames) {
            val count = min(frames - done, DIRECT_CHUNK)
            val first = start + done * step
            val whole = floor(first)
            if (step == 1.0 && first == whole) {
                source.read(whole.toLong(), left, right, done, count)
            } else {
                val last = first + (count - 1) * step
                val base = floor(min(first, last)).toLong() - 1
                source.read(base, directLeft, directRight, 0, DIRECT_SCRATCH)
                for (i in 0 until count) {
                    val at = first + i * step - base
                    val index = at.toInt()
                    val t = (at - index).toFloat()
                    left[done + i] = cubic(directLeft, index, t)
                    right[done + i] = cubic(directRight, index, t)
                }
            }
            done += count
        }
    }

    /** Catmull-Rom interpolation between samples [index] and [index] + 1; exact at t = 0. */
    private fun cubic(samples: FloatArray, index: Int, t: Float): Float {
        val y0 = samples[index - 1]
        val y1 = samples[index]
        val y2 = samples[index + 1]
        val y3 = samples[index + 2]
        return y1 + 0.5f * t * (y2 - y0 + t * (2f * y0 - 5f * y1 + 4f * y2 - y3 + t * (3f * (y1 - y2) + y3 - y0)))
    }

    // ---- Seeks and mode changes -------------------------------------------

    /**
     * Remembers how the sound playing until now would have continued for the
     * next few milliseconds, so the new sound can fade in over it. A fade that
     * is still running is folded in, so repeated seeks never click either.
     */
    private fun keepTail(directContinuation: Double) {
        if (!started) {
            continuedLeft.fill(0f)
            continuedRight.fill(0f)
        } else if (direct) {
            renderDirect(continuedLeft, continuedRight, fadeLength, directContinuation, lastStep)
        } else {
            // Grains already overlap-added past the current time: complete
            // enough over a fade length to stand in for the continuation.
            for (i in 0 until fadeLength) {
                val slot = ((outTime + i) and RING_MASK).toInt()
                continuedLeft[i] = mixLeft[slot] * OUTPUT_GAIN
                continuedRight[i] = mixRight[slot] * OUTPUT_GAIN
            }
        }
        for (i in 0 until fadeLength) {
            val running = fadePosition + i
            if (running < fadeLength) {
                val gain = fadeIn[running]
                tailLeft[i] = continuedLeft[i] * gain + tailLeft[running] * (1f - gain)
                tailRight[i] = continuedRight[i] * gain + tailRight[running] * (1f - gain)
            } else {
                tailLeft[i] = continuedLeft[i]
                tailRight[i] = continuedRight[i]
            }
        }
        fadePosition = 0
    }

    private fun fadeFromTail(left: FloatArray, right: FloatArray, frames: Int) {
        var i = 0
        while (i < frames && fadePosition < fadeLength) {
            val gain = fadeIn[fadePosition]
            left[i] = left[i] * gain + tailLeft[fadePosition] * (1f - gain)
            right[i] = right[i] * gain + tailRight[fadePosition] * (1f - gain)
            fadePosition++
            i++
        }
    }

    // ---- Phase vocoder ----------------------------------------------------

    /**
     * Grains reach half a frame into the future and were placed by
     * extrapolating the step of their time. After an abrupt change of step
     * they sit at the wrong source position, and an attack inside them would
     * come out early or late. True when the newest grain is off by more than
     * [RESYNC_MILLIS]; the vocoder is then restarted on the new mapping, with
     * the usual short fade. Gradual changes stay below the limit and are
     * simply followed by the grains to come.
     */
    private fun mappingHasMoved(start: Double, step: Double): Boolean {
        val newestCentre = nextCentre - HOP
        val position = start + (newestCentre - outTime) * step
        return abs(position - newestGrainPosition) > resyncDistance
    }

    private fun startVocoder() {
        mixLeft.fill(0f)
        mixRight.fill(0f)
        outTime = 0
        // The earliest grain that still reaches output time 0.
        nextCentre = (HOP - HALF).toLong()
    }

    /**
     * Emits overlap-added output, building each grain at the moment the output
     * reaches its first frame. A grain's centre lies half a frame ahead of the
     * output, so its source position is extrapolated with the current step.
     */
    private fun renderStretched(left: FloatArray, right: FloatArray, frames: Int, start: Double, step: Double) {
        var done = 0
        while (done < frames) {
            while (nextCentre - HALF <= outTime) {
                val position = start + (done + (nextCentre - outTime)) * step
                buildGrain(nextCentre, position, step)
                newestGrainPosition = position
                nextCentre += HOP
            }
            val count = min((frames - done).toLong(), nextCentre - HALF - outTime).toInt()
            for (i in 0 until count) {
                val slot = ((outTime + i) and RING_MASK).toInt()
                left[done + i] = mixLeft[slot] * OUTPUT_GAIN
                right[done + i] = mixRight[slot] * OUTPUT_GAIN
                mixLeft[slot] = 0f
                mixRight[slot] = 0f
            }
            outTime += count
            done += count
        }
    }

    /** One grain, centred at output time [centre], presenting the source around [position]. */
    private fun buildGrain(centre: Long, position: Double, step: Double) {
        loadSource(position)
        val onset = findOnset(position, step)
        val attack = onset != NO_ONSET && lockToOnset(position, step, onset)
        analyse()
        findPeaks()
        // Grains that start a stream have no predecessor to continue from.
        propagatePhases(fresh = centre <= 0, attack = attack)
        synthesise(centre)
    }

    /** Reads the source around [position] into the raw buffers. */
    private fun loadSource(position: Double) {
        rawStart = position.roundToLong() - RAW_BEFORE
        source.read(rawStart, rawLeft, rawRight, 0, RAW_LENGTH)
    }

    /**
     * Looks for the start of an attack in the stretch of source that matters to
     * this grain: what its analysis window covers and, when playing faster,
     * what its output span maps to. Returns the source position of the attack
     * nearest to the grain's centre, or [NO_ONSET].
     *
     * An attack is a short block whose high-frequency energy (first difference
     * of the signal) exceeds [ONSET_RATIO] times the loudest block of the
     * preceding [ONSET_HISTORY] blocks. Blocks sit at fixed source positions so
     * consecutive grains agree on where an attack is.
     */
    private fun findOnset(position: Double, step: Double): Double {
        if (step < MIN_LOCK_STEP) return NO_ONSET
        val reach = min(HALF * max(step, 1.0), MAX_ONSET_REACH.toDouble())
        val zoneStart = position - reach
        val zoneEnd = position + reach
        val firstBlock = max(floorBlock(zoneStart), rawStart.floorDiv(ONSET_BLOCK) + 2 + ONSET_HISTORY)
        val lastBlock = min(floorBlock(zoneEnd), (rawStart + RAW_LENGTH).floorDiv(ONSET_BLOCK) - 1)
        if (lastBlock < firstBlock) return NO_ONSET

        val origin = firstBlock - ONSET_HISTORY
        for (block in origin..lastBlock) {
            val base = (block * ONSET_BLOCK - rawStart).toInt()
            var energy = 0f
            for (i in base until base + ONSET_BLOCK) energy += differenceEnergy(i)
            blockEnergy[(block - origin).toInt()] = energy
        }
        var nearest = NO_ONSET
        for (block in firstBlock..lastBlock) {
            val slot = (block - origin).toInt()
            var loudestBefore = 0f
            for (i in slot - ONSET_HISTORY until slot) loudestBefore = max(loudestBefore, blockEnergy[i])
            if (blockEnergy[slot] <= ONSET_RATIO * loudestBefore + ONSET_FLOOR) continue

            // Within the block, the attack starts where the energy first gets
            // near the block's peak.
            val base = (block * ONSET_BLOCK - rawStart).toInt()
            var peak = 0f
            for (i in base until base + ONSET_BLOCK) peak = max(peak, differenceEnergy(i))
            var first = base
            while (differenceEnergy(first) < ONSET_EDGE * peak) first++
            val onset = (rawStart + first).toDouble()
            if (onset < zoneStart || onset >= zoneEnd) continue
            if (abs(onset - position) < abs(nearest - position)) nearest = onset
        }
        return nearest
    }

    private fun floorBlock(position: Double): Long = floor(position / ONSET_BLOCK).toLong()

    private fun differenceEnergy(index: Int): Float {
        val l = rawLeft[index] - rawLeft[index - 1]
        val r = rawRight[index] - rawRight[index - 1]
        return l * l + r * r
    }

    /**
     * Re-centres the grain around the attack at source position [onset], and
     * returns whether the attack falls inside the grain.
     *
     * If it does, the grain is taken so that the attack sits at the same offset
     * from the centre as it must have in the output. Successive grains around
     * an attack therefore step through the source at original speed: the attack
     * lands exactly where the mapping puts it and is presented unstretched.
     *
     * If the attack is due before or after the grain's output span but would
     * still be inside its analysis window (slow playback), the grain is held
     * back with the attack on the window's edge, so that it is not heard early
     * or late as a smeared echo.
     *
     * Either way the stretch is made up in the steadier sound between attacks.
     */
    private fun lockToOnset(position: Double, step: Double, onset: Double): Boolean {
        val outputOffset = (onset - position) / step
        val inside = abs(outputOffset) < HALF
        val sourceOffset = when {
            inside -> outputOffset
            outputOffset > 0 -> max(HALF.toDouble(), onset - position)
            else -> min(-HALF.toDouble(), onset - position)
        }
        loadSource(onset - sourceOffset)
        return inside
    }

    /**
     * Transforms the grain and the grain one hop earlier in the source. Only
     * the current grain is unpacked into per-channel spectra; the earlier one is
     * needed at the peaks alone and is unpacked there.
     */
    private fun analyse() {
        val currentAt = RAW_BEFORE - HALF
        val earlierAt = currentAt - HOP
        for (i in 0 until FRAME) {
            val w = window[i]
            currentRe[i] = rawLeft[currentAt + i] * w
            currentIm[i] = rawRight[currentAt + i] * w
            earlierRe[i] = rawLeft[earlierAt + i] * w
            earlierIm[i] = rawRight[earlierAt + i] * w
        }
        fft.forward(currentRe, currentIm)
        fft.forward(earlierRe, earlierIm)
        for (k in 0 until BINS) {
            val mirror = (FRAME - k) and (FRAME - 1)
            val lr = 0.5f * (currentRe[k] + currentRe[mirror])
            val li = 0.5f * (currentIm[k] - currentIm[mirror])
            val rr = 0.5f * (currentIm[k] + currentIm[mirror])
            val ri = 0.5f * (currentRe[mirror] - currentRe[k])
            inLeftRe[k] = lr
            inLeftIm[k] = li
            inRightRe[k] = rr
            inRightIm[k] = ri
            power[k] = lr * lr + li * li + rr * rr + ri * ri
        }
    }

    /**
     * Lists the bins that are louder (both channels together) than their two
     * neighbours on each side: the partials the phase locking is built around.
     */
    private fun findPeaks() {
        peakCount = 0
        for (k in 2 until BINS - 2) {
            val p = power[k]
            if (p > power[k - 1] && p > power[k - 2] && p > power[k + 1] && p > power[k + 2]) peaks[peakCount++] = k
        }
    }

    /**
     * Turns the grain's spectrum into the output spectrum, region by region
     * (a region is a peak and the bins nearer to it than to any other peak).
     *
     * A region is rotated as a whole, by the angle that makes its peak continue
     * the previous output grain: since the earlier analysis grain lies exactly
     * one synthesis hop back, that is the angle between the earlier grain and
     * the previous output at the peak, measured in whichever channel is louder
     * there. Rotating the whole region keeps the shape of the partial; giving
     * both channels the same rotation keeps the level and phase differences
     * between them.
     *
     * A [fresh] grain is not rotated at all. In an [attack] grain, neither are
     * the regions whose peak has risen by more than [ATTACK_RISE] since the
     * earlier grain: new sound starts with the phases it has in the source.
     */
    private fun propagatePhases(fresh: Boolean, attack: Boolean) {
        if (fresh || peakCount == 0) {
            rotateRegion(0, BINS - 1, 1f, 0f)
            return
        }
        for (n in 0 until peakCount) {
            val k = peaks[n]
            val from = if (n == 0) 0 else (peaks[n - 1] + k) / 2 + 1
            val to = if (n == peakCount - 1) BINS - 1 else (k + peaks[n + 1]) / 2

            val leftPower = inLeftRe[k] * inLeftRe[k] + inLeftIm[k] * inLeftIm[k]
            val rightPower = inRightRe[k] * inRightRe[k] + inRightIm[k] * inRightIm[k]
            val mirror = FRAME - k
            val earlierPeakRe: Float
            val earlierPeakIm: Float
            val previousRe: Float
            val previousIm: Float
            if (leftPower >= rightPower) {
                earlierPeakRe = 0.5f * (earlierRe[k] + earlierRe[mirror])
                earlierPeakIm = 0.5f * (earlierIm[k] - earlierIm[mirror])
                previousRe = outLeftRe[k]
                previousIm = outLeftIm[k]
            } else {
                earlierPeakRe = 0.5f * (earlierIm[k] + earlierIm[mirror])
                earlierPeakIm = 0.5f * (earlierRe[mirror] - earlierRe[k])
                previousRe = outRightRe[k]
                previousIm = outRightIm[k]
            }

            var rotRe = 1f
            var rotIm = 0f
            val earlierPower = earlierPeakRe * earlierPeakRe + earlierPeakIm * earlierPeakIm
            val risen = attack && max(leftPower, rightPower) > ATTACK_RISE * ATTACK_RISE * earlierPower
            if (!risen) {
                val re = previousRe * earlierPeakRe + previousIm * earlierPeakIm
                val im = previousIm * earlierPeakRe - previousRe * earlierPeakIm
                val length = re * re + im * im
                if (length > MIN_ROTATION_POWER) {
                    val scale = 1f / sqrt(length)
                    rotRe = re * scale
                    rotIm = im * scale
                }
            }
            rotateRegion(from, to, rotRe, rotIm)
        }
    }

    private fun rotateRegion(from: Int, to: Int, rotRe: Float, rotIm: Float) {
        for (b in from..to) {
            val lr = inLeftRe[b]
            val li = inLeftIm[b]
            val rr = inRightRe[b]
            val ri = inRightIm[b]
            outLeftRe[b] = lr * rotRe - li * rotIm
            outLeftIm[b] = lr * rotIm + li * rotRe
            outRightRe[b] = rr * rotRe - ri * rotIm
            outRightIm[b] = rr * rotIm + ri * rotRe
        }
    }

    /**
     * Turns the output spectrum back into a windowed grain and adds it to the
     * mix at output time [centre]. Frames already played are left out, which
     * only happens for the grains that start a stream.
     */
    private fun synthesise(centre: Long) {
        currentRe[0] = outLeftRe[0]
        currentIm[0] = outRightRe[0]
        currentRe[HALF] = outLeftRe[HALF]
        currentIm[HALF] = outRightRe[HALF]
        for (k in 1 until HALF) {
            val mirror = FRAME - k
            currentRe[k] = outLeftRe[k] - outRightIm[k]
            currentIm[k] = outLeftIm[k] + outRightRe[k]
            currentRe[mirror] = outLeftRe[k] + outRightIm[k]
            currentIm[mirror] = outRightRe[k] - outLeftIm[k]
        }
        fft.inverse(currentRe, currentIm)
        val begin = centre - HALF
        val skip = max(0L, outTime - begin).toInt()
        for (i in skip until FRAME) {
            val slot = ((begin + i) and RING_MASK).toInt()
            mixLeft[slot] += currentRe[i] * window[i]
            mixRight[slot] += currentIm[i] * window[i]
        }
    }

    private companion object {
        /**
         * Grain length in frames, for sources at 44.1 or 48 kHz. 4096 (85 ms)
         * tells the harmonics of the lowest bass notes apart; with 2048 they
         * share bins and beat against each other. The price of a long grain,
         * smeared attacks, is paid by [lockToOnset] instead.
         */
        const val FRAME = 4096

        /** Synthesis hop, and the distance between the two analysis grains: 75% overlap. */
        const val HOP = FRAME / 4
        const val HALF = FRAME / 2
        const val BINS = HALF + 1
        const val RING_MASK = FRAME - 1L

        /** Undoes the gain of overlap-adding a squared Hann window at 75% overlap. */
        const val OUTPUT_GAIN = 2f / 3f

        /** The copy-through mode is entered within this distance of step 1 and left beyond the wider one. */
        const val BYPASS_ENTER = 0.001
        const val BYPASS_LEAVE = 0.002
        const val FADE_MILLIS = 5

        /** How far, in source time, the mapping may drift from the grains already built before they are rebuilt. */
        const val RESYNC_MILLIS = 1
        const val DIRECT_CHUNK = 512
        const val DIRECT_SCRATCH = 2 * DIRECT_CHUNK + 8

        /** Inputs beyond these are answered with silence rather than arithmetic on nonsense. */
        const val MAX_POSITION = 1e15
        const val MAX_STEP = 1e3

        /** Attack search: block length in source frames, and how many blocks of history a block is compared with. */
        const val ONSET_BLOCK = 32
        const val ONSET_HISTORY = 24
        const val ONSET_RATIO = 4f
        const val ONSET_FLOOR = ONSET_BLOCK * 1e-7f
        const val ONSET_EDGE = 0.25f

        /**
         * How far from a grain's centre attacks are searched, at most, in
         * source frames. It covers the output span of a grain up to 1.5 times
         * original speed; beyond that the outer grains miss the attack and it
         * comes out a little weaker.
         */
        const val MAX_ONSET_REACH = HALF + HOP

        /** Below this step attacks are not handled. */
        const val MIN_LOCK_STEP = 0.01
        const val NO_ONSET = Double.NEGATIVE_INFINITY

        /** A peak this many times stronger than one hop earlier counts as new sound in an attack grain. */
        const val ATTACK_RISE = 1.5f
        const val MIN_ROTATION_POWER = 1e-30f

        /** Source frames kept before and after the grain centre: both analysis grains plus the attack search. */
        const val RAW_BEFORE = MAX_ONSET_REACH + (ONSET_HISTORY + 3) * ONSET_BLOCK
        const val RAW_AFTER = MAX_ONSET_REACH + ONSET_BLOCK * 2
        const val RAW_LENGTH = RAW_BEFORE + RAW_AFTER
    }
}
