package dev.personal.tv.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigStabilityTest {

    @Test fun duplicateSiteKeysAreDeduplicated() {
        val json = """
            {
              "sites": [
                {"key": "dup", "name": "A", "type": 1, "api": "https://a.example/api"},
                {"key": "dup", "name": "B", "type": 1, "api": "https://b.example/api"},
                {"key": "dup", "name": "C", "type": 1, "api": "https://c.example/api"},
                {"key": "", "name": "", "type": 1, "api": "https://d.example/api"}
              ]
            }
        """.trimIndent()
        val cfg = ConfigLoader.parse("https://example.com/cfg.json", json)
        assertEquals(listOf("dup", "dup#2", "dup#3", "site_3"), cfg.sites.map { it.key })
        assertEquals(4, cfg.sites.map { it.key }.distinct().size)
    }

    @Test fun malformedEncryptedConfigThrowsIllegalArgumentNotIndexOutOfBounds() {
        val err = runCatching { ConfigLoader.decode("242300") }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $err", err is IllegalArgumentException)
    }
}
