package com.kilombino.pyblockwatch.ark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArkDeriveTest {
    private val abandon = (List(11) { "abandon" } + "about")

    /** Computed apart (Python: HMAC-SHA512 over the BIP-39 seed, first 32 bytes as 24 words). */
    @Test fun knownVector() = assertEquals(
        "clock day coffee drill fat observe slab load skate forum buzz health observe double life skill regret pony top cage auto velvet liar risk",
        ArkDerive.words(abandon, "").joinToString(" "))

    @Test fun thePassphraseCounts() = assertNotEquals(ArkDerive.words(abandon, ""), ArkDerive.words(abandon, "x"))
}
