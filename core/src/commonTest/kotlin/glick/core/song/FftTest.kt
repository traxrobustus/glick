package glick.core.song

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FftTest {
    @Test
    fun aSineLandsInItsBinAndTheInverseRestoresTheSignal() {
        val n = 1024
        val signal = FloatArray(n) { sin(2 * PI * 37 * it / n).toFloat() + 0.25f * sin(2 * PI * 200 * it / n).toFloat() }
        val re = signal.copyOf()
        val im = FloatArray(n)
        val fft = Fft(n)
        fft.forward(re, im)
        assertEquals(n / 2f, hypot(re[37], im[37]), 0.05f)
        assertEquals(n / 8f, hypot(re[200], im[200]), 0.05f)
        assertTrue(hypot(re[38], im[38]) < 0.05f)
        fft.inverse(re, im)
        for (i in 0 until n) assertTrue(abs(re[i] - signal[i]) < 1e-4f)
    }
}
