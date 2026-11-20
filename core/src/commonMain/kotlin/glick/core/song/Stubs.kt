package glick.core.song
// TEMPORARY stand-ins until the stretcher branch is merged.
class TimeStretcher(private val source: AudioSource) {
    fun render(left: FloatArray, right: FloatArray, frames: Int, sourceStart: Double, sourceStep: Double, jump: Boolean) = source.read(sourceStart.toLong(), left, right, 0, frames)
    fun reset() {}
}
class Resampler(val inputRate: Int, val outputRate: Int) {
    fun process(input: FloatArray, count: Int, output: FloatArray): Int { input.copyInto(output, 0, 0, count); return count }
    fun flush(output: FloatArray): Int = 0
}
