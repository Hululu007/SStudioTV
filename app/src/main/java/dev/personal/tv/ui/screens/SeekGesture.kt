package dev.personal.tv.ui.screens

/**
 * 遥控器快进快退的手势状态机（纯 Kotlin，时间由调用方传入，方便单测）。
 *
 * - 按一下就松：松开时提交 ±10 秒。
 * - 按住不放（系统会连发 KeyDown）：从第二个 KeyDown 起进入「预览」，目标位置随按住时间加速移动，
 *   此时不 seek；松开时只提交一次。
 * - 取消（返回、面板打开、失去画面、切后台、KeyUp 丢了超时）：丢弃预览，不 seek。
 * - 直播 / 不可 seek：整个手势无效；时长未知：只有单击 ±10 秒，按住不预览。
 */
class SeekGesture {
    /** 0 = 没在按；-1 后退；+1 快进 */
    var dir = 0; private set
    var previewing = false; private set
    /** 预览目标位置（毫秒），仅 [previewing] 时有意义 */
    var targetMs = 0L; private set

    val active get() = dir != 0

    private var downs = 0
    private var startedAt = 0L
    private var lastAt = 0L
    private var durMs = 0L
    private var startPosMs = 0L
    private var sourceKey: Any? = null

    /**
     * 一次 KeyDown（含系统连发）。[key] 区分来源（方向键 / 媒体键），按着一个键时别的键的按下被吞掉。
     * 返回 true 表示手势接管了这个事件。
     */
    fun down(key: Any, dir: Int, nowMs: Long, posMs: Long, durMs: Long, seekable: Boolean): Boolean {
        if (dir == 0 || !seekable) return false
        if (active) {
            if (key != sourceKey || dir != this.dir) return true
            this.durMs = durMs
            downs++
            if (durMs <= 0) { lastAt = nowMs; return true }
            if (!previewing) {
                previewing = true
                // 从按下那一刻的位置起步（预览期间不 seek，播放位置还在走，不能用当前位置）
                targetMs = clamp(startPosMs + dir * STEP_MS, durMs)
            } else {
                val dt = (nowMs - lastAt).coerceIn(0L, MAX_DT_MS)
                val held = nowMs - startedAt
                // rate 是 视频秒/现实秒，dt 是现实毫秒，乘积正好是视频毫秒
                targetMs = clamp(targetMs + (dir * rate(held) * dt).toLong(), durMs)
            }
            lastAt = nowMs
            return true
        }
        this.dir = dir; sourceKey = key; downs = 1
        previewing = false; targetMs = posMs
        startedAt = nowMs; lastAt = nowMs; this.durMs = durMs; startPosMs = posMs
        return true
    }

    /**
     * KeyUp。返回要 seek 到的绝对位置（毫秒）；没有要提交的（取消过、别的键、没在按）返回 null。
     * 手势随即结束。
     */
    fun up(key: Any, posMs: Long): Long? {
        if (!active || key != sourceKey) return null
        val r = if (previewing) targetMs else clamp(posMs + dir * STEP_MS, durMs)
        reset()
        return r
    }

    /** 丢弃手势，不提交。 */
    fun cancel() = reset()

    /** 按着但很久没收到事件（KeyUp 丢了）：调用方周期性检查，true 时应当 [cancel]。 */
    fun stale(nowMs: Long) = active && nowMs - lastAt > STALE_MS

    private fun reset() { dir = 0; downs = 0; previewing = false; sourceKey = null }

    private fun clamp(ms: Long, durMs: Long): Long {
        val hi = if (durMs > 0) (durMs - END_GUARD_MS).coerceAtLeast(0) else Long.MAX_VALUE
        return ms.coerceIn(0L, hi)
    }

    companion object {
        const val STEP_MS = 10_000L
        const val END_GUARD_MS = 1_000L
        const val MAX_DT_MS = 250L
        const val STALE_MS = 1_200L

        /** 按住 [heldMs] 毫秒时的移动速度（视频秒 / 现实秒） */
        fun rate(heldMs: Long): Double = when {
            heldMs < 2_000 -> 20.0
            heldMs < 4_000 -> 60.0
            else -> 120.0
        }
    }
}
