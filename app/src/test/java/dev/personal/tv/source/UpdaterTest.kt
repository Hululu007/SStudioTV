package dev.personal.tv.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versions() {
        assertTrue(Updater.newer("0.9.5", "0.9.4"))
        assertTrue(Updater.newer("0.9.10", "0.9.9"))
        assertTrue(Updater.newer("1.0", "0.9.9"))
        assertTrue(Updater.newer("0.9.4.1", "0.9.4"))
        assertFalse(Updater.newer("0.9.4", "0.9.4"))
        assertFalse(Updater.newer("0.9.3", "0.9.4"))
        assertFalse(Updater.newer("0.9", "0.9.0"))
    }
}
