package glick.core.song

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming windowed-sinc sample-rate converter for one channel.
 *
 * Each output sample is a weighted sum of the input samples around its exact
 * position in the input. The weights are a Kaiser-windowed sinc, low-passed
 * just under the lower of the two Nyquist frequencies, read from a table of
 * [PHASES] fractional positions with linear interpolation in between.
 * Positions are tracked in whole numbers, so the output neither drifts nor
 * depends on how the input is cut into calls, and it is not delayed: output
 * sample 0 is input sample 0.
 */
class Resampler(val inputRate: Int, val outputRate: Int) {
    private val inputStep: Long
    private val outputStep: Long

    /** Input samples used on each side of an output position. */
    private val reach: Int
    private val taps: Int
    private val table: FloatArray

    private val buffer: FloatArray

    /** Number of valid samples in [buffer]. */
    private var filled = 0

    /** Index in [buffer] of the input sample at or just before the next output position. */
    private var at = 0

    /** Fraction of the next output position past that sample, in units of 1 / [outputStep]. */
    private var fraction = 0L

    init {
        require(inputRate > 0 && outputRate > 0) { "Sample rates must be positive" }
        val divisor = gcd(inputRate, outputRate)
        inputStep = (inputRate / divisor).toLong()
        outputStep = (outputRate / divisor).toLong()
        // When converting down, the filter is stretched to cut at the output's Nyquist frequency.
        val scale = min(1.0, outputRate.toDouble() / inputRate)
        reach = ceil(ZERO_CROSSINGS / scale).toInt()
        taps = 2 * reach
        table = FloatArray((PHASES + 1) * taps)
        val cutoff = scale * CUTOFF
        for (phase in 0..PHASES) {
            val row = phase * taps
            var sum = 0.0
            for (t in 0 until taps) {
                val x = t - reach + 1 - phase.toDouble() / PHASES
                val weight = cutoff * sinc(cutoff * x) * kaiser(x / reach)
                table[row + t] = weight.toFloat()
                sum += weight
            }
            // Unity gain at every fractional position, so a steady level stays steady.
            for (t in 0 until taps) table[row + t] = (table[row + t] / sum).toFloat()
        }
        buffer = FloatArray(taps + CHUNK)
        clear()
    }

    /** Converts [count] input samples; returns how many samples were written to [output], which must hold at least count * outputRate / inputRate + 64. */
    fun process(input: FloatArray, count: Int, output: FloatArray): Int {
        var written = 0
        var taken = 0
        while (taken < count) {
            val portion = min(count - taken, buffer.size - filled)
            input.copyInto(buffer, filled, taken, taken + portion)
            filled += portion
            taken += portion
            written = convert(output, written)
        }
        return written
    }

    /**
     * Writes the tail still inside the filter; returns the number of samples.
     * [output] must hold at least 64 * max(1, outputRate / inputRate) samples.
     * Afterwards the converter is ready for a new stream.
     */
    fun flush(output: FloatArray): Int {
        var written = 0
        var remaining = reach
        while (remaining > 0) {
            val portion = min(remaining, buffer.size - filled)
            buffer.fill(0f, filled, filled + portion)
            filled += portion
            remaining -= portion
            written = convert(output, written)
        }
        clear()
        return written
    }

    /** Produces every output sample the buffered input allows, then drops the input no longer needed. */
    private fun convert(output: FloatArray, offset: Int): Int {
        var written = offset
        while (at + reach < filled) {
            val phase = fraction.toDouble() / outputStep * PHASES
            val row = phase.toInt()
            val blend = (phase - row).toFloat()
            val near = row * taps
            val far = near + taps
            val first = at - reach + 1
            var nearSum = 0f
            var farSum = 0f
            for (t in 0 until taps) {
                val sample = buffer[first + t]
                nearSum += sample * table[near + t]
                farSum += sample * table[far + t]
            }
            output[written++] = nearSum + (farSum - nearSum) * blend
            fraction += inputStep
            at += (fraction / outputStep).toInt()
            fraction %= outputStep
        }
        val drop = min(at - reach + 1, filled)
        buffer.copyInto(buffer, 0, drop, filled)
        filled -= drop
        at -= drop
        return written
    }

    /** Starts a stream: silence before the first sample, the first output on the first input. */
    private fun clear() {
        buffer.fill(0f, 0, reach - 1)
        filled = reach - 1
        at = reach - 1
        fraction = 0
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** Kaiser window over -1..1, zero outside. */
    private fun kaiser(x: Double): Double =
        if (x <= -1.0 || x >= 1.0) 0.0 else besselI0(KAISER_BETA * sqrt(1.0 - x * x)) / besselI0(KAISER_BETA)

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var n = 1
        while (term > sum * 1e-12) {
            term *= (x / (2.0 * n)) * (x / (2.0 * n))
            sum += term
            n++
        }
        return sum
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private companion object {
        /** Zero crossings of the sinc on each side, at the lower of the two rates. */
        const val ZERO_CROSSINGS = 32

        /**
         * Filter cutoff as a fraction of the lower Nyquist frequency. The
         * transition band straddles Nyquist: flat to about 19 kHz at 44.1 kHz,
         * and what aliases lands above 20 kHz.
         */
        const val CUTOFF = 0.955

        /** Kaiser shape for about 90 dB of stopband rejection. */
        const val KAISER_BETA = 9.0

        /** Fractional positions in the filter table. */
        const val PHASES = 512

        /** Input samples buffered per conversion pass. */
        const val CHUNK = 1024
    }
}
