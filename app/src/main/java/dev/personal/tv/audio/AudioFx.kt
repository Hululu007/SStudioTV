package dev.personal.tv.audio

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.reflect.KProperty

/** 声音设置（记住在本机）+ 给播放器用的处理器 */
object AudioFx {
    private var sp: SharedPreferences? = null

    private class Pref(private val key: String, def: Boolean) {
        val state = mutableStateOf(def)
        fun load(p: SharedPreferences) { state.value = p.getBoolean(key, state.value) }
        operator fun getValue(o: Any?, p: KProperty<*>) = state.value
        operator fun setValue(o: Any?, p: KProperty<*>, v: Boolean) { state.value = v; sp?.edit()?.putBoolean(key, v)?.apply() }
    }

    private val nightPref = Pref("night", true)
    private val levelPref = Pref("level", true)
    private val dialogPref = Pref("dialog", false)
    var night by nightPref
    var level by levelPref
    var dialog by dialogPref

    /** 当前拉平增益（dB），播放器上显示 */
    @Volatile var agcDb = 0.0
        internal set

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("audio", Context.MODE_PRIVATE)
        sp = p
        listOf(nightPref, levelPref, dialogPref).forEach { it.load(p) }
    }

    /** ExoPlayer 用这个 RenderersFactory，声音就会经过 [Processor] */
    @OptIn(UnstableApi::class)
    fun renderers(ctx: Context) = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(Processor()))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
    }

    /**
     * Media3 音频处理器：PCM 16 位 / float 进来，过 [LoudnessDsp] 再出去。
     * 一直处于激活状态，开关在播放中途切换立刻生效，不用重建播放器。
     * （AC3 之类直通给功放的音频不经过这里，那种情况由功放自己的夜间模式管）
     */
    @OptIn(UnstableApi::class)
    class Processor : BaseAudioProcessor() {
        private var dsp: LoudnessDsp? = null
        private var buf = FloatArray(0)

        override fun onConfigure(f: AudioFormat): AudioFormat {
            if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) throw UnhandledAudioFormatException(f)
            return f
        }

        override fun onFlush() {
            val f = inputAudioFormat
            val d = dsp
            // 同样的格式（拖进度、换集）保留拉平状态，只清掉瞬时状态
            if (d != null && d.sampleRate == f.sampleRate && d.channels == f.channelCount) d.reset()
            else dsp = LoudnessDsp(f.sampleRate, f.channelCount)
        }

        override fun onReset() { dsp = null }

        override fun queueInput(input: ByteBuffer) {
            val n = input.remaining()
            if (n == 0) return
            val d = dsp ?: LoudnessDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount).also { dsp = it }
            d.night = night; d.level = level; d.dialog = dialog
            val out = replaceOutputBuffer(n)
            val inp = input.order(ByteOrder.nativeOrder())
            val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
            val samples = if (float) n / 4 else n / 2
            if (buf.size < samples) buf = FloatArray(samples)
            val p0 = inp.position()
            if (float) for (i in 0 until samples) buf[i] = inp.getFloat(p0 + i * 4)
            else for (i in 0 until samples) buf[i] = inp.getShort(p0 + i * 2) / 32768f
            d.process(buf, samples / d.channels)
            agcDb = if (level) d.agcGainDb else 0.0
            if (float) for (i in 0 until samples) out.putFloat(buf[i])
            else for (i in 0 until samples) out.putShort((buf[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            inp.position(inp.limit())
            out.flip()
        }
    }
}
