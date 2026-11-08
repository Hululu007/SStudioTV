package dev.personal.tv.data

import dev.personal.tv.source.Episode
import dev.personal.tv.source.Eps

/**
 * 「继续观看」点进去放哪一集。首页、详情页都用这一个，免得两边算出来不一样。
 * [ep]：按序号的是集数；按名字的是 [Resume.target] 拿到的那条线路里的位置（只是提示）。
 * [name]：分集名，播放时按它到各条线路里找同一集；按豆瓣集数选的为 null。
 * [resume]：没看完，接着上次的位置。
 */
data class Target(val ep: Int, val name: String?, val resume: Boolean) {
    /** 按钮上的字：「第 3 集」「03.17 上」 */
    fun text(t: Title) = Eps.text(name, name?.let { Eps.number(it, t.name) } ?: ep, t.name)
}

object Resume {
    /**
     * [eps]：选集按哪条线路显示（线路还没搜过就是 null）。
     * 没看完：接着这一集。看完了：先在线路里按名字找下一集，再用存进度时算好的下一集；
     * 按序号的电视剧还可以按豆瓣集数 +1。都不知道（线路没搜过、已经是最后一集）返回 null，交给详情页。
     */
    fun target(t: Title, p: Progress, eps: List<Episode>?): Target? {
        if (!t.isEpisodic || !p.finishedEp) return Target(p.ep, p.epName, true)
        val name = p.epName
        if (!eps.isNullOrEmpty() && name != null) {
            val numeric = Eps.numeric(eps, t.name, t.isVariety)
            val i = Eps.nextIndex(eps, name, t.name, numeric)
            if (i >= 0) return Target(Eps.number(eps[i].name, t.name) ?: (i + 1), eps[i].name, false)
        }
        val named = p.isNamed(t.name)
        p.nextName?.let { n -> return Target(Eps.number(n, t.name) ?: (p.ep + 1), n, false) }
        if (!named && p.ep < t.airedEps) return Target(p.ep + 1, null, false)
        return null
    }
}
