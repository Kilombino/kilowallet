package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.NativeSecp
import com.kilombino.pyblockwatch.crypto.Schnorr
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * libsecp256k1 (through the engine library) and the Kotlin code must give identical bytes:
 * same public keys, same RFC 6979 ECDSA (with and without Core's low-R grind), same BIP-340.
 * Runs only when KILOMBINO_ARK_HOST_LIB points at a host build of the engine.
 */
class NativeSecpTest {
    private fun <T> kotlinOnly(f: () -> T): T {
        NativeSecp.enabled = false
        try { return f() } finally { NativeSecp.enabled = true }
    }

    @Test fun nativeAndKotlinAgree() {
        assumeTrue("needs the host engine library", NativeSecp.available)
        val rnd = SecureRandom()
        repeat(300) {
            val k = BigInteger(256, rnd).mod(Secp256k1.N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
            val msg = ByteArray(32).also(rnd::nextBytes)
            val aux = ByteArray(32).also(rnd::nextBytes)
            assertEquals(kotlinOnly { Secp256k1.publicPoint(k) }, Secp256k1.publicPoint(k))
            for (lowR in listOf(false, true))
                assertEquals(kotlinOnly { Ecdsa.sign(k, msg, lowR) }, Ecdsa.sign(k, msg, lowR))
            assertArrayEquals(kotlinOnly { Schnorr.sign(k, msg, aux) }, Schnorr.sign(k, msg, aux))
        }
    }

    @Test fun edgeKeys() {
        assumeTrue("needs the host engine library", NativeSecp.available)
        val msg = ByteArray(32) { it.toByte() }
        for (k in listOf(BigInteger.ONE, Secp256k1.N.subtract(BigInteger.ONE))) {
            assertEquals(kotlinOnly { Ecdsa.sign(k, msg, true) }, Ecdsa.sign(k, msg, true))
            assertArrayEquals(kotlinOnly { Schnorr.sign(k, msg, ByteArray(32)) }, Schnorr.sign(k, msg, ByteArray(32)))
        }
    }
}
