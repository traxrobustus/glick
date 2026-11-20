package glick.core.song

import glick.core.SongRef

/** Receives decoded audio, interleaved, 16 bits per sample. */
fun interface PcmSink {
    fun write(sampleRate: Int, channels: Int, samples: ShortArray, frames: Int)
}

/** Reads audio files; each platform has its own. */
interface AudioFileDecoder {
    /** A text that changes when the file's content does (its size and date), so stale caches are noticed. */
    fun fingerprint(id: String): String

    /** Decodes the whole file into [sink]. [progress] gets 0 to 1 when the length is known. */
    fun decode(id: String, sink: PcmSink, progress: (Float) -> Unit)
}

/** Turns a chosen file into playable audio. */
interface SongLibrary {
    /**
     * Returns the song as audio at [sampleRate], decoding and converting it if
     * that has not been done before. Blocking; call it off the UI thread.
     */
    fun open(ref: SongRef, sampleRate: Int, progress: (Float) -> Unit): AudioSource
}

/** The outline of a waveform: the lowest and highest sample of every [blockFrames] frames. */
class WaveformPeaks(val blockFrames: Int, val sampleRate: Int, val low: FloatArray, val high: FloatArray) {
    val blocks: Int get() = low.size

    companion object {
        const val BLOCK_FRAMES = 512

        fun of(source: AudioSource, progress: (Float) -> Unit = {}): WaveformPeaks {
            val count = ((source.frames + BLOCK_FRAMES - 1) / BLOCK_FRAMES).toInt()
            val low = FloatArray(count)
            val high = FloatArray(count)
            val left = FloatArray(BLOCK_FRAMES)
            val right = FloatArray(BLOCK_FRAMES)
            for (block in 0 until count) {
                source.read(block.toLong() * BLOCK_FRAMES, left, right, 0, BLOCK_FRAMES)
                var minimum = 0f
                var maximum = 0f
                for (i in 0 until BLOCK_FRAMES) {
                    val sample = (left[i] + right[i]) / 2
                    if (sample < minimum) minimum = sample
                    if (sample > maximum) maximum = sample
                }
                low[block] = minimum
                high[block] = maximum
                if (block % 2048 == 0) progress(block.toFloat() / count)
            }
            return WaveformPeaks(BLOCK_FRAMES, source.sampleRate, low, high)
        }
    }
}
