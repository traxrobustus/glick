package glick.core.song

/**
 * Random access to decoded audio at one fixed sample rate. Positions are in
 * frames (one frame is one sample per channel).
 */
interface AudioSource {
    val sampleRate: Int

    /** 1 or 2. */
    val channels: Int
    val frames: Long

    /**
     * Copies [count] frames starting at frame [start] into [left] and [right],
     * beginning at index [offset]. Frames before 0 or past the end read as
     * silence; a mono source fills both arrays with the same signal.
     * Implementations must not allocate: this is called on the audio thread.
     */
    fun read(start: Long, left: FloatArray, right: FloatArray, offset: Int, count: Int)
}

/** Audio held in memory; used by tests and for short files. */
class MemoryAudioSource(
    override val sampleRate: Int,
    private val leftSamples: FloatArray,
    private val rightSamples: FloatArray? = null,
) : AudioSource {
    override val channels: Int get() = if (rightSamples == null) 1 else 2
    override val frames: Long get() = leftSamples.size.toLong()

    override fun read(start: Long, left: FloatArray, right: FloatArray, offset: Int, count: Int) {
        val other = rightSamples ?: leftSamples
        for (i in 0 until count) {
            val at = start + i
            val inside = at >= 0 && at < leftSamples.size
            left[offset + i] = if (inside) leftSamples[at.toInt()] else 0f
            right[offset + i] = if (inside) other[at.toInt()] else 0f
        }
    }
}
