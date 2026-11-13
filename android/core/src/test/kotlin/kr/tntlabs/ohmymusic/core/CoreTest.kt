package kr.tntlabs.ohmymusic.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun fftMatchesNaiveDft() {
        val n = 64
        val x = FloatArray(n) { (sin(it * 0.3) + 0.5 * cos(it * 1.7)).toFloat() }
        val got = Fft(n).magnitudes(x, 0, null)
        for (k in 0..n / 2) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                re += x[t] * cos(2 * PI * k * t / n)
                im -= x[t] * sin(2 * PI * k * t / n)
            }
            assertEquals(sqrt(re * re + im * im), got[k].toDouble(), 1e-4)
        }
    }

    @Test
    fun camelotMatchesMacApp() {
        assertEquals("8B", Key(0, false, 1f).camelot)   // C major
        assertEquals("8A", Key(9, true, 1f).camelot)    // A minor
        assertEquals("1B", Key(11, false, 1f).camelot)  // B major
        assertEquals("1A", Key(8, true, 1f).camelot)    // A♭ minor
        assertEquals("A♭m", Key(8, true, 1f).short)
    }

    @Test
    fun sineLandsInItsSemitoneBand() {
        // A4(440Hz) 사인파 → MIDI 69 의 가운데 칸이 가장 크다
        val f = KeyFeatures()
        val x = FloatArray(KeyFeatures.SAMPLE_RATE * 3) { sin(2 * PI * 440 * it / KeyFeatures.SAMPLE_RATE).toFloat() }
        f.append(x)
        val frame = f.snapshot().last()
        val peak = frame.indices.maxBy { frame[it] }
        assertEquals((69 - KeyFeatures.MIDI_LO) * KeyFeatures.BINS_PER_SEMITONE + 1, peak)
        assertTrue(f.frameCount > 0 && abs(f.seconds - f.frameCount / 5.0) < 1e-9)
    }
}
