package glick.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.tan

/**
 * One click voice: a triangle oscillator into a resonant low-pass filter and
 * an amplifier, each shaped by its own fast envelope.
 *
 * The defaults were fitted to a recording of a Boss Dr. Beat DB-33. Its four
 * click sounds turned out to be the same voice at four pitches (about 1850,
 * 1440, 1050 and 850 Hz), so pitch is the only thing that differs per voice.
 * Times are in seconds.
 */
data class ClickVoice(
    val pitchHz: Double,
    /** The pitch starts this fraction sharp and falls back: the "drop". */
    val pitchDrop: Double = 0.045,
    val pitchDecay: Double = 0.0046,
    val attack: Double = 0.00045,
    val decay: Double = 0.0056,
    /** Resting filter cutoff as a multiple of the pitch. */
    val cutoffRatio: Double = 1.18,
    /** How far the filter opens at the onset, as a fraction of the cutoff. */
    val filterEnv: Double = 0.68,
    val filterDecay: Double = 0.003,
    val resonance: Double = 2.1,
) {
    /** Seconds until the amplifier envelope has fallen 80 dB. */
    val duration: Double get() = decay * 9.2 + attack

    /** Renders the whole click at full level, peak-normalised to 1. */
    fun render(sampleRate: Int): FloatArray {
        val n = (duration * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        var phase = 0.25 // triangle starts at its zero crossing, so no DC step
        var ic1 = 0.0
        var ic2 = 0.0
        var peak = 0.0
        val damping = 1.0 / resonance
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            phase += pitchHz * (1 + pitchDrop * exp(-t / pitchDecay)) / sampleRate
            phase -= floor(phase)
            val triangle = 4 * abs(phase - 0.5) - 1

            // Zero-delay state-variable low-pass: stable while the cutoff moves.
            val cutoff = min(pitchHz * cutoffRatio * (1 + filterEnv * exp(-t / filterDecay)), sampleRate * 0.45)
            val g = tan(PI * cutoff / sampleRate)
            val a1 = 1 / (1 + g * (g + damping))
            val a2 = g * a1
            val v3 = triangle - ic2
            val v1 = a1 * ic1 + a2 * v3
            val low = ic2 + a2 * ic1 + g * a2 * v3
            ic1 = 2 * v1 - ic1
            ic2 = 2 * low - ic2

            val sample = low * (1 - exp(-t / attack)) * exp(-t / decay)
            out[i] = sample.toFloat()
            peak = maxOf(peak, abs(sample))
        }
        if (peak > 0) for (i in 0 until n) out[i] = (out[i] / peak).toFloat()
        return out
    }
}

/** Default pitch of every click voice; the first four are the DB-33's own. */
object ClickPitches {
    const val ACCENT = 1850.0
    const val BEAT = 1440.0
    val SUBDIVISIONS = listOf(1050.0, 850.0, 700.0)
    val POLY = listOf(2330.0, 1650.0, 1240.0, 590.0)
}
