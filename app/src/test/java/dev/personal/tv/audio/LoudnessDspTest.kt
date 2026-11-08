package dev.personal.tv.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessDspTest {
    private val sr = 48000

    private fun tone(sec: Double, amp: Double, hz: Double = 440.0): FloatArray {
        val n = (sec * sr).toInt()
        return FloatArray(n * 2) { i -> (amp * sin(2 * PI * hz * (i / 2) / sr)).toFloat() }
    }

    private fun rmsDb(b: FloatArray, from: Int = 0, to: Int = b.size): Double {
        var s = 0.0; for (i in from until to) s += b[i] * b[i]
        return 20 * log10(sqrt(s / (to - from)) + 1e-12)
    }

    private fun run(dsp: LoudnessDsp, b: FloatArray): FloatArray { val c = b.copyOf(); dsp.process(c, c.size / 2); return c }

    @Test fun nightShrinksLoudQuietGap() {
        val quiet = tone(4.0, 0.03)   // 对白 ≈ -33 dBFS
        val loud = tone(1.0, 0.9)     // 爆炸 ≈ -4 dBFS
        val dsp = LoudnessDsp(sr, 2).apply { level = false; night = true }
        val q = run(dsp, quiet); val l = run(dsp, loud)
        val before = rmsDb(loud) - rmsDb(quiet)
        val after = rmsDb(l, l.size / 2) - rmsDb(q, q.size / 2)
        println("night: gap before %.1f dB, after %.1f dB".format(before, after))
        assertTrue(after < before - 10)
    }

    @Test fun levelingBringsSourcesTogether() {
        val dspA = LoudnessDsp(sr, 2).apply { night = false }
        val dspB = LoudnessDsp(sr, 2).apply { night = false }
        val a = run(dspA, tone(10.0, 0.05))  // 片源 A 偏小声 ≈ -29 dBFS
        val b = run(dspB, tone(10.0, 0.4))   // 片源 B 偏大声 ≈ -11 dBFS
        val tail = a.size * 8 / 10
        println("level: A %.1f → %.1f, B %.1f → %.1f".format(-29.0, rmsDb(a, tail), -11.0, rmsDb(b, tail)))
        assertTrue(kotlin.math.abs(rmsDb(a, tail) - rmsDb(b, tail)) < 4)
    }

    @Test fun silenceIsNotBoosted() {
        val dsp = LoudnessDsp(sr, 2)
        val out = run(dsp, tone(5.0, 0.0005)) // -69 dBFS 底噪
        println("silence: %.1f dB".format(rmsDb(out, out.size / 2)))
        assertTrue(rmsDb(out, out.size / 2) < -55)
    }

    @Test fun neverClips() {
        val dsp = LoudnessDsp(sr, 2).apply { dialog = true }
        val out = run(dsp, tone(3.0, 0.99, 2500.0))
        assertTrue(out.all { kotlin.math.abs(it) <= 0.9f })
    }
}
