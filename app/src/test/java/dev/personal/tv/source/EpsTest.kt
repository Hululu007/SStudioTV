package dev.personal.tv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpsTest {
    private fun eps(vararg n: String) = n.map { Episode(it, "u/$it") }

    @Test fun seriesNamesAreNumbers() {
        assertTrue(Eps.numeric(eps("第1集", "第02集", "EP03", "04", "第5集HD")))
        assertEquals("12", Eps.key("第12集 高清"))
        assertEquals("第 3 集", Eps.text("第03集", 3))
    }

    @Test fun varietyNamesAreNotNumbers() {
        assertFalse(Eps.numeric(eps("第1期上", "第1期下", "第2期上")))
        assertFalse(Eps.numeric(eps("20241101期", "20241108期")))
    }

    @Test fun sameEpisodeAcrossSites() {
        assertEquals(Eps.key("第01期(上)"), Eps.key("1期 上"))
        assertEquals(Eps.key("第20241101期"), Eps.key("2024-11-01期"))
        assertEquals(Eps.key("奔跑吧 20241101期", "奔跑吧"), Eps.key("20241101", ""))
        assertTrue(Eps.key("20241101期上") != Eps.key("20241101期下"))
        val line = eps("20241025期", "20241101期上", "20241101期下")
        assertEquals(2, Eps.indexOf(line, "2024-11-01 下"))
        assertEquals(-1, Eps.indexOf(line, "20241108期"))
    }

    @Test fun labels() {
        assertEquals("11.01 上", Eps.label("奔跑吧 第20241101期(上)", "奔跑吧"))
        assertEquals("11.08", Eps.label("2024-11-08"))
        assertEquals("加更版", Eps.label("加更版"))
        assertEquals("第1期", Eps.label("第1期"))
        assertEquals("7", Eps.label("07"))
        assertEquals("0317", Eps.label("种地吧 0317", "种地吧"))
        assertEquals("0317", Eps.text("0317", 3, numericLine = false))
        assertEquals("第 3 集", Eps.text("0317", 3, numericLine = true))
    }

    @Test fun seriesWithExtrasStaysNumeric() {
        // 真实线路：正片里夹着陪看、彩蛋，最后一集叫「第30集已完结」
        val line = eps("第01集", "第01集彩蛋", "第02集", "陪看1", "第03集", "第30集已完结")
        assertTrue(Eps.numeric(line))
        assertEquals("30", Eps.key("第30集已完结"))
        assertEquals(4, Eps.indexOfNumber(line, 3))
        assertEquals(1, Eps.indexOfNumber(eps("a", "b", "c"), 2))
        // 综艺里有一个名字就按名字
        assertFalse(Eps.numeric(eps("第1期", "第1期加更", "第2期"), variety = true))
        // 按「期」分的也按名字
        assertFalse(Eps.numeric(eps("第1期", "第2期", "第3期")))
    }

    @Test fun numbersSurviveNoiseRemoval() {
        // 720 / 1080 是集数，不是清晰度；带 p 的才是
        assertEquals(720, Eps.number("第720集"))
        assertEquals(1080, Eps.number("第1080集 1080P"))
        assertEquals(1, Eps.number("第1集 4K60"))
        assertEquals(1, Eps.number("第1集 2160P HDR"))
        assertEquals(1, Eps.number("第1集 国语版"))
        assertEquals(1, Eps.number("Episode 01"))
        assertEquals(1, Eps.number("S01E01"))
        assertEquals(12, Eps.number("第十二集"))
        assertEquals("1", Eps.parse("第一期").num)
        assertEquals(null, Eps.number("第一期"))
        // 总集篇、合集不算序号，也不会变成 125 / 102
        assertEquals("12.5", Eps.parse("第12.5集").num)
        assertFalse(Eps.isNumber("第12.5集"))
        assertEquals("1-2", Eps.parse("第01-02集").num)
        assertFalse(Eps.isNumber("第01-02集"))
    }

    @Test fun looseMatchAcrossSites() {
        val line = eps("20240308期", "20240315期：张三做客", "20240315期加更")
        assertEquals(1, Eps.indexOf(line, "20240315期"))
        assertEquals(1, Eps.indexOf(line, "0315期"))
        assertEquals(2, Eps.indexOf(line, "0315加更版"))
        assertEquals(1, Eps.indexOf(line, "3月15日"))
        assertEquals(0, Eps.indexOf(eps("第1期上", "第1期下"), "第1期 (上)"))
        assertEquals(1, Eps.indexOf(eps("第1期上", "第1期下"), "第一期下"))
        // 片名带「期」、夹一条「下期预告」的电视剧还是按序号
        assertTrue(Eps.numeric(eps("青春期第1集", "青春期第2集", "青春期第3集", "下期预告"), "青春期"))
        assertTrue(Eps.numeric(eps("第1集", "第2集", "第3集", "下期预告")))
        // 「0317」这种按日期排的不是第 317 集
        assertFalse(Eps.numeric(eps("0310", "0317", "0324")))
        assertTrue(Eps.numeric(eps("0316", "0317", "0318")))
    }

    @Test fun nextSkipsExtrasAndGaps() {
        val v = eps("第1期", "第1期加更", "第2期", "第2期花絮", "第3期")
        assertEquals(1, Eps.nextIndex(v, "第1期", numericLine = false))
        assertEquals(4, Eps.nextIndex(v, "第2期", numericLine = false))
        assertEquals(-1, Eps.nextIndex(v, "第3期", numericLine = false))
        val s = eps("第01集", "第01集彩蛋", "第02集", "陪看1", "第03集")
        assertEquals(2, Eps.nextIndex(s, "第01集", numericLine = true))
        // 分段线路里没有第 300 集，不按位置凑
        assertEquals(-1, Eps.indexOfNumber(eps("第501集", "第502集", "第503集"), 2))
    }

    @Test fun commonPrefixTrimmed() {
        val l = Eps.trimCommon(eps("奔跑吧第七季第1期", "奔跑吧第七季第2期", "奔跑吧第七季第3期"))
        assertEquals("第1期", l[0].name)
        // 前缀里有数字（日期）、或者是「陪看」这种词，不切
        assertEquals("2024年11月01日", Eps.trimCommon(eps("2024年11月01日", "2024年11月08日", "2024年11月15日"))[0].name)
        assertEquals("陪看1", Eps.trimCommon(eps("陪看1", "陪看2", "陪看3"))[0].name)
    }

    @Test fun newestFirstGetsReversed() {
        assertEquals("第1期", Eps.ascending(eps("第3期", "第2期", "第1期")).first().name)
        assertEquals("20241025期", Eps.ascending(eps("20241108期", "加更版", "20241101期", "20241025期")).first().name)
        val line = eps("20241108期", "20241101期", "20241025期")
        assertEquals("20241025期", Eps.ascending(line).first().name)
        val mixed = eps("20241108期", "加更")
        assertEquals(mixed, Eps.ascending(mixed))
    }

    @Test fun lexicallySortedLinesGetSorted() {
        // 真实线路：按字符串排的
        val lex = eps("20230129", "第10期(一) 兄弟团", "第10期(二) 搭鸡舍", "第10期加更 拖车", "第11期 PUA", "第1期上 稻田", "第1期下 抢收", "第20期(一) 粤菜", "第2期 生活特辑")
        assertEquals(
            listOf("第1期上 稻田", "第1期下 抢收", "第2期 生活特辑", "第10期(一) 兄弟团", "第10期(二) 搭鸡舍", "第10期加更 拖车", "第11期 PUA", "第20期(一) 粤菜", "20230129"),
            Eps.ascending(lex).map { it.name },
        )
        // 上下期反着放、加更放在正片前面
        assertEquals(listOf("第1期上", "第1期下", "第2期"), Eps.ascending(eps("第1期下", "第1期上", "第2期")).map { it.name })
        assertEquals(listOf("第26期", "第26期加更版", "第27期"), Eps.ascending(eps("第26期加更版", "第26期", "第27期")).map { it.name })
        // 顺序本来就对的、夹着陪看的电视剧不动
        val s = eps("第01集", "第01集彩蛋", "第02集", "陪看1", "第03集")
        assertEquals(s, Eps.ascending(s))
        // 写错的日期（50230518）跟着前一个，不跑到最前面
        val d = eps("20230514(加更版)", "50230518", "20230518", "20230129")
        assertEquals(listOf("20230129", "20230514(加更版)", "50230518", "20230518"), Eps.ascending(d).map { it.name })
    }

    @Test fun panFileNamesBecomeNumbers() {
        // 网盘线路：文件名里的「24集全」不能当成集号，整条线路要按 1、2、3 显示
        val line = eps("[536.8MB]01_1080P.mp4【第1季（2022）24集全】", "[542.7MB]02_1080P.mp4【第1季（2022）24集全】", "[555.9MB]03_1080P.mp4【第1季（2022）24集全】")
        assertEquals(listOf(1, 2, 3), line.map { Eps.number(it.name, "间谍过家家 第一季") })
        assertTrue(Eps.numeric(line, "间谍过家家 第一季"))
        assertEquals("1", Eps.label("[536.8MB]01_1080P.mp4【第1季（2022）24集全】"))
        assertEquals("第 2 集", Eps.text(line[1].name, 2, "间谍过家家 第一季"))
    }

    @Test fun longEpisodeNamesAreShortened() {
        assertEquals("1 任务1 <枭>行动", Eps.label("第1话 任务1 <枭>行动"))
        assertEquals("3 Operation Strix", Eps.label("EP03 Operation Strix 1080P"))
        assertEquals("第3话预告", Eps.label("第3话预告"))
        assertEquals("第3期上", Eps.label("第3期上"))
        assertEquals("1(普通话版)", Eps.label("1(普通话版)"))
        val long = Eps.label("第12话 阿尼亚与达米安的第一次期中考试大作战")
        assertTrue(long, long.endsWith("…") && long.length <= 12)
        assertEquals("12 阿尼亚与达米安的第一次期中考试大作战", Eps.fullLabel("第12话 阿尼亚与达米安的第一次期中考试大作战"))
    }

    @Test fun scrambledTitleAndQualityRemoved() {
        // 片名拆开插符号防屏蔽，「4KHDR」「高码」是清晰度
        val t = "小城良方"
        assertEquals(1, Eps.number("1 4KHDR 【X - 小 - 城..良--方/ 高码】", t))
        assertEquals(2, Eps.number("2 【X - 小 - 城..良-方】", t))
        assertEquals("1", Eps.label("1 4KHDR 【X - 小 - 城..良--方/ 高码】", t))
        assertEquals("# 乡音一吵吵，归途", Eps.fullLabel("# 乡音一吵吵，归途", t))
    }
}
