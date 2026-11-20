package glick.core.song

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What beat tracking keeps of a song: a short-time spectrum reduced to a few
 * bands per frame, and how much those bands grew from one frame to the next.
 * This is a few megabytes for a long song, where the audio itself would be
 * hundreds.
 *
 * Frame `i` is centred on time `i / framesPerSecond`.
 */
internal class OnsetFrames(
    val framesPerSecond: Double,
    /** Per frame: the summed rise of the compressed band levels ("spectral flux"). Peaks at note onsets. */
    val flux: FloatArray,
    /** Per frame, [BANDS] log-compressed band levels, lowest band first. */
    val bands: FloatArray,
    /** How many of the lowest bands lie in the bass and kick drum range. */
    val lowBands: Int,
) {
    val count: Int get() = flux.size

    companion object {
        const val BANDS = 24

        /** The mono rate the song is reduced to before analysis; beats need no treble. */
        private const val TARGET_RATE = 11025.0
        private const val WINDOW = 512
        private const val HOP = 128

        /** Decimated samples produced per read of the source. */
        private const val BLOCK = 4096
        private const val LOWEST_HZ = 40.0
        private const val LOW_BAND_HZ = 160.0

        /** Compression strength: levels are `ln(1 + COMPRESSION * amplitude)`, so quiet onsets still count. */
        private const val COMPRESSION = 100f

        /**
         * Streams [source] once, block by block, and returns its frames.
         * [progress] gets 0f..1f.
         */
        fun analyse(source: AudioSource, progress: (Float) -> Unit): OnsetFrames {
            val factor = max(1, (source.sampleRate / TARGET_RATE).roundToInt())
            val rate = source.sampleRate.toDouble() / factor
            val samples = (source.frames + factor - 1) / factor
            val count = (samples / HOP).toInt() + 1

            val kernel = lowPass(factor)
            val reach = kernel.size - 1
            val left = FloatArray(BLOCK * factor + reach)
            val right = FloatArray(left.size)
            // Starts half a window of silence ahead, so frame 0 is centred on time 0.
            val pending = FloatArray(WINDOW + BLOCK)
            var held = WINDOW / 2
            var decimated = 0L

            val fft = Fft(WINDOW)
            val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2.0 * PI * it / WINDOW)).toFloat() }
            val re = FloatArray(WINDOW)
            val im = FloatArray(WINDOW)
            val edges = bandEdges(rate)
            val previous = FloatArray(BANDS)
            val flux = FloatArray(count)
            val bands = FloatArray(count * BANDS)
            // A full-scale sine comes out as amplitude 1.
            val scale = 4f / WINDOW

            var frame = 0
            while (frame < count) {
                source.read(decimated * factor - reach / 2, left, right, 0, left.size)
                for (i in left.indices) left[i] = 0.5f * (left[i] + right[i])
                for (i in 0 until BLOCK) {
                    val at = i * factor
                    var sum = 0f
                    for (k in kernel.indices) sum += left[at + k] * kernel[k]
                    pending[held + i] = sum
                }
                held += BLOCK
                decimated += BLOCK

                var at = 0
                while (held - at >= WINDOW && frame < count) {
                    for (i in 0 until WINDOW) {
                        re[i] = pending[at + i] * hann[i]
                        im[i] = 0f
                    }
                    fft.forward(re, im)
                    var rise = 0f
                    for (band in 0 until BANDS) {
                        var power = 0f
                        for (bin in edges[band] until edges[band + 1]) power += re[bin] * re[bin] + im[bin] * im[bin]
                        val level = ln(1f + COMPRESSION * scale * sqrt(power))
                        rise += max(0f, level - previous[band])
                        previous[band] = level
                        bands[frame * BANDS + band] = level
                    }
                    flux[frame] = rise
                    frame++
                    at += HOP
                }
                pending.copyInto(pending, 0, at, held)
                held -= at
                progress(frame.toFloat() / count)
            }

            val binHz = rate / WINDOW
            val lowBands = (1..BANDS).count { edges[it] * binHz <= LOW_BAND_HZ }.coerceAtLeast(1)
            return OnsetFrames(rate / HOP, flux, bands, lowBands)
        }

        /**
         * Windowed-sinc low-pass for decimating by [factor], centred so it adds
         * no delay. A factor of 1 gives the identity.
         */
        private fun lowPass(factor: Int): FloatArray {
            if (factor == 1) return floatArrayOf(1f)
            val half = 4 * factor
            val cutoff = 0.45 / factor
            val taps = DoubleArray(2 * half + 1) {
                val x = (it - half).toDouble()
                val sinc = if (it == half) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                sinc * (0.5 + 0.5 * cos(PI * x / (half + 1)))
            }
            val sum = taps.sum()
            return FloatArray(taps.size) { (taps[it] / sum).toFloat() }
        }

        /** First FFT bin of each band, plus the end of the last: bands of equal musical width, at least one bin each. */
        private fun bandEdges(rate: Double): IntArray {
            val binHz = rate / WINDOW
            val octaves = (rate / 2) / LOWEST_HZ
            val edges = IntArray(BANDS + 1)
            edges[0] = 1
            for (band in 1..BANDS) {
                val hz = LOWEST_HZ * octaves.pow(band.toDouble() / BANDS)
                edges[band] = max(edges[band - 1] + 1, (hz / binHz).roundToInt())
            }
            edges[BANDS] = min(edges[BANDS], WINDOW / 2)
            return edges
        }
    }
}
