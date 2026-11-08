package dev.personal.tv.audio

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 夜间音量 / 音量拉平 / 对白增强 的信号处理，纯 Kotlin，方便单元测试。
 * 输入输出是交错的 float PCM（-1..1），原地处理。
 *
 * 处理顺序：对白 EQ → 慢速拉平（AGC）→ 快速压缩（夜间）→ 限幅（-1 dBFS，防止加了增益后爆音）
 */
class LoudnessDsp(val sampleRate: Int, val channels: Int) {

    @Volatile var level = true      // 音量拉平：不同片源 / 不同集响度一致
    @Volatile var night = true      // 夜间音量：压缩大动态，爆炸声不炸、对白不小
    @Volatile var dialog = false    // 对白增强：2.5 kHz 附近 +5 dB

    // ---- 参数
    private val targetDb = -20.0          // 拉平目标（RMS）
    private val agcMinDb = -10.0
    private val agcMaxDb = 12.0
    private val agcSlewDbPerSec = 3.0     // 拉平增益每秒最多变 3 dB，听不出来在调
    private val gateDb = -50.0            // 低于这个算静音，不跟着拉，免得把底噪放大
    private val compThreshDb = -26.0
    private val compRatio = 3.0
    private val compMakeupDb = 5.0
    private val ceiling = 0.89            // -1 dBFS

    private fun coef(ms: Double) = exp(-1.0 / (ms / 1000.0 * sampleRate))
    private val atk = coef(8.0)
    private val rel = coef(250.0)
    private val slow = coef(3000.0)
    private val limRel = coef(80.0)
    private val agcStep = agcSlewDbPerSec / sampleRate

    private var env = 1e-6
    private var slowEnv = 10.0.pow(-20.0 / 10) // 从目标响度起步：没听到有声内容前不加增益
    private var agcDb = 0.0
    private var limGain = 1.0

    // 峰值 EQ 双二阶（每声道一份状态）
    private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    init {
        val f0 = 2500.0; val q = 0.8; val gainDb = 5.0
        val a = 10.0.pow(gainDb / 40)
        val w = 2 * Math.PI * f0 / sampleRate
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0; b1 = -2 * cos(w) / a0; b2 = (1 - alpha * a) / a0
        a1 = -2 * cos(w) / a0; a2 = (1 - alpha / a) / a0
    }

    fun reset() {
        env = 1e-6; limGain = 1.0; z1.fill(0.0); z2.fill(0.0)
        // slowEnv / agcDb 故意保留：换集、拖动进度后不用重新适应
    }

    /** 当前拉平增益（dB），调试显示用 */
    val agcGainDb get() = agcDb

    fun process(buf: FloatArray, frames: Int) {
        val lv = level; val nt = night; val dl = dialog
        if (!lv && !nt && !dl) { limGain = 1.0; return }
        val c = channels
        for (f in 0 until frames) {
            val o = f * c
            var ms = 0.0
            for (ch in 0 until c) {
                var x = buf[o + ch].toDouble()
                if (dl) {
                    val y = b0 * x + z1[ch]
                    z1[ch] = b1 * x - a1 * y + z2[ch]
                    z2[ch] = b2 * x - a2 * y
                    x = y
                    buf[o + ch] = x.toFloat()
                }
                ms += x * x
            }
            ms /= c

            var gDb = 0.0
            if (lv) {
                if (ms > DB_GATE) slowEnv = slow * slowEnv + (1 - slow) * ms
                val want = (targetDb - 10 * log10(slowEnv)).coerceIn(agcMinDb, agcMaxDb)
                agcDb += (want - agcDb).coerceIn(-agcStep, agcStep)
                gDb += agcDb
            }
            if (nt) {
                val a = if (ms > env) atk else rel
                env = a * env + (1 - a) * ms
                val inDb = 10 * log10(env + 1e-12) + gDb
                if (inDb > compThreshDb) gDb -= (inDb - compThreshDb) * (1 - 1 / compRatio)
                gDb += compMakeupDb
            }
            val g = if (gDb == 0.0) 1.0 else 10.0.pow(gDb / 20)

            var peak = 0.0
            for (ch in 0 until c) peak = max(peak, abs(buf[o + ch] * g))
            limGain = if (peak * limGain > ceiling) ceiling / peak else min(1.0, limGain / limRel)
            val t = g * limGain
            for (ch in 0 until c) buf[o + ch] = (buf[o + ch] * t).toFloat().coerceIn(-1f, 1f)
        }
    }

    private val DB_GATE = 10.0.pow(gateDb / 10)
}
