package glick.core.song
// TEMPORARY local stand-ins until the detector and stretcher branches are merged. Never committed.
data class Detection(val beats: List<Double>, val beatsPerBar: Int, val noteLength: Int, val downbeat: Int, val bpm: Double)
object BeatDetector {
    fun detect(source: AudioSource, progress: (Float) -> Unit = {}): Detection = Detection(List((source.frames / source.sampleRate * 2).toInt()) { it * 0.5 }, 4, 4, 0, 120.0)
    fun steady(beats: List<Double>): List<Double> = beats
}
class TimeStretcher(private val source: AudioSource) {
    fun render(left: FloatArray, right: FloatArray, frames: Int, sourceStart: Double, sourceStep: Double, jump: Boolean) = source.read(sourceStart.toLong(), left, right, 0, frames)
    fun reset() {}
}
class Resampler(val inputRate: Int, val outputRate: Int) {
    fun process(input: FloatArray, count: Int, output: FloatArray): Int { input.copyInto(output, 0, 0, count); return count }
    fun flush(output: FloatArray): Int = 0
}
