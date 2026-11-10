package dev.personal.tv.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSearchTest {
    private fun hit(name: String, q: String) = SourceSearch.hit(Match.norm(name), Match.norm(q))

    @Test fun digitsNeedBoundary() {
        assertTrue(hit("007之雷霆谷", "007"))
        assertTrue(hit("新007外传", "007"))
        assertFalse(hit("苹果2007", "007"))
        assertFalse(hit("雪山飞狐2007", "007"))
        assertFalse(hit("0071号", "007"))
    }

    @Test fun chineseIsPlainContains() {
        assertTrue(hit("狂飙突进", "狂飙"))
        assertTrue(hit("重生之狂飙", "狂飙"))
        assertFalse(hit("繁花", "狂飙"))
    }
}
