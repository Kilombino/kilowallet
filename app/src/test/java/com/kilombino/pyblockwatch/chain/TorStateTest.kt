package com.kilombino.pyblockwatch.chain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorStateTest {
    /** An empty guards.json kept Arti down for good ("corrupted data in persistent state"): it goes, readable ones stay. */
    @Test fun unreadableStateFilesAreDeleted() {
        val state = kotlin.io.path.createTempDirectory("arti-state").toFile()
        val empty = java.io.File(state, "guards.json").apply { writeText("") }
        val cut = java.io.File(state, "sub/circuit_timeouts.json").apply { parentFile.mkdirs(); writeText("{\"a\": [1, 2") }
        val good = java.io.File(state, "state.json").apply { writeText("{\"ok\": true}") }
        val zeros = java.io.File(state, "zeros.json").apply { writeBytes(ByteArray(64)) }
        val other = java.io.File(state, "lock").apply { writeText("") }
        Tor.repairState(state)
        assertFalse(empty.exists()); assertFalse(cut.exists()); assertFalse(zeros.exists())
        assertTrue(good.exists()); assertTrue(other.exists())
        state.deleteRecursively()
    }
}
