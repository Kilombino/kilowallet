package com.kilombino.pyblockwatch.roblo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** roblo-client/CLIENT pins the Roblosats client like tor-engine/ENGINE pins Arti. */
class RobloClientPinTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "roblo-client/CLIENT").exists() }
    private val pin = File(root, "roblo-client/CLIENT").readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }

    @Test fun thePinIsComplete() {
        for (k in listOf("repo", "tag", "commit", "file", "build", "sha256")) assertTrue("missing $k", !pin[k].isNullOrBlank())
        assertTrue(pin.getValue("sha256").matches(Regex("[0-9a-f]{64}")))
        assertTrue(pin.getValue("commit").matches(Regex("[0-9a-f]{40}")))
        assertEquals("roblo-client-${pin["tag"]}.zip", pin["file"])
        assertTrue(pin.getValue("build").contains(pin.getValue("tag")))
    }

    /** When the zip is there (it is not committed), it is the pinned one and carries its license. */
    @Test fun theZipMatchesWhenPresent() {
        val zip = File(root, "roblo-client/" + pin["file"])
        if (!zip.exists()) return
        val sha = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(pin["sha256"], sha)
        java.util.zip.ZipFile(zip).use { z ->
            assertTrue(z.getEntry("index.html") != null); assertTrue(z.getEntry("LICENSE") != null)
            val source = z.getInputStream(z.getEntry("SOURCE")).reader().readText()
            assertTrue(source.contains("commit=${pin["commit"]}"))
        }
    }
}
