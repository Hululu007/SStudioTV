package dev.personal.tv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选集按哪条线路显示：名字取自模拟器上实测的线路（2026-11《后会友期》《现在就出发 第四季》） */
class LineScoreTest {
    private fun eps(vararg n: String) = n.map { Episode(it, "u/$it") }
    private operator fun Episode.plus(l: List<Episode>) = listOf(this) + l
    private fun best(lines: List<Pair<String, List<Episode>>>, title: String): Int {
        val s = Eps.score(lines, title)
        return s.indices.maxByOrNull { s[it].value }!!
    }

    @Test fun groups() {
        assertEquals(0, Eps.group("第4期（上）"))
        assertEquals(0, Eps.group("20261001先导片上"))
        assertEquals(1, Eps.group("加更版第1期"))
        assertEquals(1, Eps.group("2026.10.22_沉浸版第1期_4K.mp4"))
        assertEquals(1, Eps.group("20261023抢鲜版"))
        assertEquals(2, Eps.group("20260905回顾特辑"))
        assertEquals(2, Eps.group("直播回放 1022"))
        assertEquals(2, Eps.group("官宣海报"))
        assertEquals(2, Eps.group("20261001先导片沙滩小黑脚纯享"))
        assertEquals(2, Eps.group("20261108第5期下纯享·歌词追击"))
        assertEquals(1, Eps.group("20261109第5期加更"))
    }

    @Test fun reuploadTimestampIsNoise() {
        val t = "后会友期"
        assertEquals(
            Eps.key("[3.7GB]2026.10.23_抢鲜版第1期_4K.mp4【H‑后‑H‑友‑Q】", t),
            Eps.key("[3.7GB]2026.10.23_抢鲜版第1期_4K_1029195914.mp4【H‑后‑H‑友‑Q】", t),
        )
    }

    @Test fun scrambledTitleInFileName() {
        val t = "后会友期"
        assertEquals("10.22 第1期", Eps.label("[7.1GB]2026.10.22_第1期_4K.mp4【H‑后‑H‑友‑Q】", t))
        assertEquals("10.22 沉浸版第1期", Eps.label("[4.7GB]2026.10.22_沉浸版第1期_4K.mp4【H【后!＠会!＃友!＆其月】】", t))
        assertEquals("10.22 第2期", Eps.label("20261022(第2期)", t))
    }

    @Test fun mixedNamingLosesToConsensus() {
        val t = "后会友期"
        val dated = eps(
            "2026.10.22_第1期", "2026.10.22_第2期", "2026.10.23_抢鲜版第1期", "2026.10.23_加更版第1期",
            "2026.10.29_第3期", "2026.10.30_加更版第2期", "2026.11.05_第4期（上）", "2026.11.05_第4期（下）", "2026.11.06_加更版第3期",
        )
        val bare = eps("2026-10-22", "2026-10-23", "2026-10-29", "2026-10-30", "2026-11-05", "2026-11-06")
        // 贱片：「第01集」和「20261029下」两套名字混在一条里
        val mixed = eps("第01集", "第02集", "第03集", "第04集", "第05集", "第06集", "第07集", "第08集", "20261029下", "20261030沉浸版上", "20261030加更版", "20261105上")
        val q = eps("第1期", "沉浸版第1期", "加更版第1期", "第2期", "第3期", "加更版第2期", "第4期上", "第4期下")
        val junk = eps("后会友期 reaction 合集", "现在就出发 第4期 片段", "嘉宾混剪", "第1期解说", "幕后花絮", "名场面1", "名场面2", "名场面3")
        // B 站合集：「1029-01」…「1029-25」是同一期切的片段，对不上别的站
        val clips = eps(*(1..25).map { "1029-%02d".format(it) }.toTypedArray())
        val lines = listOf("jp" to mixed, "jp" to mixed, "jp" to mixed, "bili" to junk, "bili2" to clips) +
            (1..6).map { "d$it" to dated } + (1..4).map { "b$it" to bare } + (1..3).map { "q$it" to q }
        val i = best(lines, t)
        assertTrue("选了 ${lines[i].first}", lines[i].first.startsWith("d"))
        val s = Eps.score(lines, t)
        assertTrue(s[0].value < s[5].value && s[3].value < s[5].value && s[4].value < s[5].value)
    }

    @Test fun recapDatesDoNotWin() {
        val t = "现在就出发"
        val typical = eps(
            "20261001先导片上", "20261001先导片下", "20260905回顾特辑", "20261008第1期上", "20261008第1期下", "20261009加更",
            "20261015第2期上", "20261015第2期下", "20261016加更", "20261022第3期上", "20261022第3期下", "20261023加更",
        )
        // 黄人：只有日期，夹着几期回顾特辑的日期，条数最多
        val bare = eps(
            "2026-09-05", "2026-09-12", "2026-09-19", "2026-09-26", "2026-10-01", "2026-10-08", "2026-10-09",
            "2026-10-15", "2026-10-16", "2026-10-22", "2026-10-23",
        )
        val dupes = eps("第1期上", "第1期上", "第1期上", "第1期下", "第2期上", "第2期下")
        val lines = listOf("hr" to bare, "hr" to bare, "sd" to dupes) + (1..5).map { "s$it" to typical }
        val i = best(lines, t)
        assertTrue("选了 ${lines[i].first}", lines[i].first.startsWith("s"))
    }

    @Test fun bareDatesOfRecapsLose() {
        // 实测《现在就出发 第四季》：文才只写日期（九月是回顾特辑），FF 把花絮特辑、超前彩蛋的日子也当成一期
        val t = "现在就出发"
        val wencai = eps(
            "20260905", "20260906", "20260912", "20260913", "20260919", "20260920", "20260926", "20260927",
            "20261001上", "20261001下", "20261010上", "20261010下", "20261011上", "20261011下", "20261012", "20261013", "20261015",
            "20261017上", "20261017下", "20261018上", "20261018下", "20261019", "20261020", "20261022", "20261023",
        )
        val ff = eps(
            "第20261010期", "第20261010期捣蛋", "第20261010期纯享", "第20261011期", "第20261011期纯享", "第20261012期", "第20261013期",
            "第20261015期", "第20261017期", "第20261017期纯享上", "第20261018期", "第20261019期", "第20261020期", "第20261022期", "第20261023期",
        )
        val pig = eps(
            "20261001先导片上", "20261001先导片下", "20260905回顾特辑", "20260906回顾特辑", "20260912回顾特辑", "20260913回顾特辑",
            "20260919回顾特辑", "20260920回顾特辑", "20260926回顾特辑", "20260927回顾特辑", "20261010第1期上", "20261010第1期上捣蛋鱼医生纯享",
            "20261011第1期下", "20261012加更第1期", "20261013第1期还有加更", "20261015第1期花絮特辑", "20261017第2期上", "20261018第2期下",
            "20261019第2期加更", "20261020第2期还有加更", "20261022第2期花絮特辑", "20261023第3期超前彩蛋",
        )
        // 小猪投屏①：同一份名单两种写法拼在一起，126 个
        val twice = Episode("第4期下", "x") + pig + pig.map { it.copy(name = it.name.replace("第", "·第")) }
        val lines = (1..6).map { "w$it" to wencai } + (1..5).map { "f$it" to ff } + listOf("pig" to pig, "hr" to pig, "pig1" to twice)
        val i = best(lines, t)
        assertTrue("选了 ${lines[i].first}", lines[i].first == "pig" || lines[i].first == "hr")
    }

    @Test fun fasterSiteWinsForSeries() {
        val common = eps(*(1..40).map { "第${it}集" }.toTypedArray())
        val ahead = eps(*(1..42).map { "第${it}集" }.toTypedArray())
        val lines = (1..8).map { "s$it" to common } + listOf("fast" to ahead)
        assertEquals("fast", lines[best(lines, "")].first)
    }

    @Test fun englishFileNamesLose() {
        val plain = eps(*(1..76).map { "第${it}集" }.toTypedArray())
        val files = eps(*(1..76).map { "Empresses.in.the.Palace.Chapter.%02d.mp4".format(it) }.toTypedArray())
        // 搜到一半：英文文件名那条先到
        assertEquals("pan", listOf("pan" to files, "a" to plain).let { it[best(it, "后宫·甄嬛传")] }.first.let { if (it == "a") "pan" else "x" })
    }

    @Test fun englishTitlePrefixTrimmed() {
        val t = "后宫·甄嬛传"
        val names = listOf(
            "[3.2GB]Empresses.in.the.Palace.2011.E01.WEB-DL.4k.H265.10bit.AAC.mp4【后宫·甄嬛传】",
            "[2.6GB]后宫·甄嬛传.Empresses.in.the.Palace.2011.E02.WEB-DL.4k.H265.10bit.AAC-AIU.mp4【H 后宫·甄嬛传】",
            "Empresses.in.the.Palace.S01E76.2011.1080p.WEB-DL.mp4",
        )
        assertEquals(listOf("1", "2", "76"), names.map { Eps.label(it, t) })
        assertTrue(names.all { Eps.isNumber(it, t) })
        // 英文名后面不是集号的不动
        assertEquals("Show.Name.Behind.The.Scenes", Eps.clean("Show.Name.Behind.The.Scenes.mp4"))
    }

    @Test fun subtitleWordsAreNotExtras() {
        for (n in listOf("第12集 直播事故", "第08集 海报风波", "第5集 官宣", "第3期 直播带货大战", "EP05 Live and Let Die")) assertTrue(n, !Eps.parse(n).extra)
        for (n in listOf("1029 直播", "20261029 官宣海报", "直播回放 第3期", "1022 路透")) assertTrue(n, Eps.parse(n).extra)
        val e = eps("第10集", "第11集", "第12集 直播事故", "第13集")
        assertEquals(2, Eps.nextIndex(e, "第11集"))
    }

    @Test fun releaseGroupKeepsEpisodeInfo() {
        assertEquals("5", Eps.parse("Running.Man-EP05.mp4").num)
        assertEquals("5", Eps.parse("Show.S01-E05.mkv").num)
        assertEquals("加更", Eps.parse("20261022-PLUS.mp4").tag)
        assertEquals("1", Eps.label("[3.2GB]Empresses.in.the.Palace.2011.E01.WEB-DL.4k.H265.10bit.AAC-AIU.mp4【后宫·甄嬛传】", "后宫·甄嬛传"))
        assertEquals("1", Eps.label("Show.E01.1080p.WEB-DL.H264-CHDWEB.mkv"))
    }

    @Test fun bracketTagsSurviveTitleStrip() {
        assertEquals("纯享", Eps.parse("20261022【兄弟团纯享】", "奔跑吧兄弟").tag)
        assertEquals("花絮", Eps.parse("第3期【幕后花絮】", "幕后之王").tag)
        assertEquals("特别", Eps.parse("第3期[SP]", "奔跑吧").tag)
        assertTrue(Eps.parse("第1集[A]", "某剧").key != Eps.parse("第1集[B]", "某剧").key)
    }

    @Test fun englishTitleVariants() {
        assertEquals("1", Eps.label("Running.Man.S01.E01.mp4"))
        assertEquals("1", Eps.label("Running.Man.EP.01.mp4"))
        assertTrue(Eps.parse("Live EP3").tag == "直播" || Eps.parse("Live EP3").rest.isNotEmpty())
        assertEquals("加更", Eps.parse("Plus E02").tag)
        assertEquals("2001", Eps.parse("ep.2001.mp4").num)
    }

    @Test fun newYearIsFresh() {
        val base = eps("20261211", "20261218", "20261225")
        val fast = eps("20261211", "20261218", "20261225", "20270101")
        val lines = (1..5).map { "s$it" to base } + ("fast" to fast)
        assertEquals("fast", lines[best(lines, "")].first)
    }

    @Test fun nextSkipsAltCuts() {
        val line = eps("第1期上", "第1期下", "沉浸版第1期", "加更版第1期", "第2期上")
        assertEquals(3, Eps.nextAfter(line, 1))
        assertEquals(3, Eps.nextAfter(line, 2))
    }
}
