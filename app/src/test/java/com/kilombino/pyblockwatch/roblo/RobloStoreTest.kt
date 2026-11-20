package com.kilombino.pyblockwatch.roblo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The client's storage (sealed at rest) and its section in the .kab backup. */
class RobloStoreTest {
    private class Mem : RobloStore.KeyValues {
        val m = LinkedHashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun keys() = m.keys.toSet()
    }
    /** A stand-in for the keystore: reversible, but never the plain text at rest. */
    private class Rot(var broken: Boolean = false) : RobloStore.Sealer {
        override fun seal(plain: String) = if (broken) null else "sealed1:" + plain.reversed()
        override fun open(sealed: String) = if (sealed.startsWith("sealed1:")) sealed.removePrefix("sealed1:").reversed() else null
    }

    @Test fun valuesAreSealedAtRest() {
        val mem = Mem(); val s = RobloStore(mem, Rot())
        assertTrue(s.set("garage_slots", "{\"token\":\"secret-token\"}"))
        assertEquals("{\"token\":\"secret-token\"}", s.get("garage_slots"))
        assertTrue(mem.m.values.none { it.contains("secret-token") })
        // A fresh store on the same preferences (the app restarted) reads it back.
        assertEquals("{\"token\":\"secret-token\"}", RobloStore(mem, Rot()).get("garage_slots"))
    }

    @Test fun missingIsEmptyAndDeleteRemoves() {
        val s = RobloStore(Mem(), Rot())
        assertEquals("", s.get("settings_language"))
        s.set("settings_language", "es"); s.delete("settings_language")
        assertEquals("", s.get("settings_language"))
    }

    @Test fun badKeysAreRefused() {
        val s = RobloStore(Mem(), Rot())
        for (k in listOf("", "../x", "a b", "a/b", "x".repeat(201), "a\"b", "a\nb"))
            assertFalse(k, RobloStore.validKey(k))
        for (k in listOf("garage_slots", "settings_fontsize_basic", "bond_return_AbC123-_.", "onchain_rescue_explorer"))
            assertTrue(k, RobloStore.validKey(k))
        assertTrue(runCatching { s.set("../x", "1") }.isFailure)
    }

    @Test fun aKeystoreThatFailsWritesNothing() {
        val mem = Mem(); val s = RobloStore(mem, Rot(broken = true))
        assertFalse(s.set("garage_key", "words"))
        assertTrue(mem.m.isEmpty())
    }

    @Test fun torUnlessClearnetIsChosen() {
        val s = RobloStore(Mem(), Rot())
        assertFalse(s.clearnet())
        s.set(RobloStore.USE_PROXY, "true"); assertFalse(s.clearnet())
        s.set(RobloStore.USE_PROXY, "false"); assertTrue(s.clearnet())
    }

    @Test fun backupRoundTripOnAnotherPhone() {
        val a = RobloStore(Mem(), Rot())
        a.set("garage_slots", "{\"a\":1}"); a.set("garage_key", "{\"key\":\"k\"}"); a.set("settings_language", "ca")
        assertTrue(a.hasRobots())
        val exported = JSONObject(a.export().toString()) // through text, as in the .kab
        assertEquals(1, exported.getInt("v"))
        val mem = Mem(); val b = RobloStore(mem, Rot())
        assertFalse(b.hasRobots())
        assertEquals(3, b.import(exported))
        assertEquals("{\"a\":1}", b.get("garage_slots")); assertEquals("ca", b.get("settings_language"))
        assertTrue(b.hasRobots())
        assertTrue(mem.m.values.all { it.startsWith("sealed1:") })
    }

    @Test fun anImportSkipsBadKeysAndRefusesANewerFormat() {
        val s = RobloStore(Mem(), Rot())
        val o = JSONObject().put("v", 1).put("items", JSONObject().put("../evil", "x").put("garage_slots", "{}"))
        assertEquals(1, s.import(o))
        assertTrue(runCatching { s.import(JSONObject().put("v", 99).put("items", JSONObject())) }.isFailure)
    }
}
