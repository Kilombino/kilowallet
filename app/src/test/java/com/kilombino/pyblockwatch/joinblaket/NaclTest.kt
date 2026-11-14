package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.joinblaket.Vectors.hex
import com.kilombino.pyblockwatch.joinblaket.Vectors.objects
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/**
 * NaCl crypto_box against the vector section `encryption` and against libsodium itself
 * (`nacl-kat.json`, made with PyNaCl 1.5 in JoinBlaket's venv: Bernstein's Alice/Bob keys and
 * nonce from "Cryptography in NaCl", then random keys with messages of 0..4097 bytes).
 */
class NaclTest {
    @Test fun vectorSection() {
        val v = Vectors.section("encryption")
        val takerSk = hex(v.getString("taker_private")); val makerSk = hex(v.getString("maker_private"))
        assertEquals(v.getString("taker_public"), Nacl.publicKey(takerSk).toHex())
        assertEquals(v.getString("maker_public"), Nacl.publicKey(makerSk).toHex())
        val taker = Nacl.Box(takerSk, hex(v.getString("maker_public")))
        val maker = Nacl.Box(makerSk, hex(v.getString("taker_public")))
        assertEquals(v.getString("shared_key"), taker.sharedKey.toHex())
        assertEquals(v.getString("shared_key_maker_side"), maker.sharedKey.toHex())

        val wire = taker.encrypt(v.getString("plaintext"), hex(v.getString("nonce")))
        assertEquals(v.getString("wire_b64"), wire)
        assertEquals(v.getString("decrypted_by_maker"), maker.decryptString(wire))
        val reply = maker.encrypt(v.getString("reply_plaintext"), hex(v.getString("reply_nonce")))
        assertEquals(v.getString("reply_wire_b64"), reply)
        assertEquals(v.getString("reply_plaintext"), taker.decryptString(reply))
    }

    @Test fun libsodiumKnownAnswers() {
        val cases = JSONArray(Vectors.text("/joinblaket/nacl-kat.json")).objects()
        assertEquals(16, cases.size)
        for (c in cases) {
            val a = hex(c.getString("sk_a")); val b = hex(c.getString("sk_b"))
            assertEquals(c.getString("pk_a"), Nacl.publicKey(a).toHex())
            assertEquals(c.getString("pk_b"), Nacl.publicKey(b).toHex())
            assertEquals(c.getString("x25519"), Nacl.x25519(a, hex(c.getString("pk_b"))).toHex())
            val box = Nacl.Box(a, hex(c.getString("pk_b")))
            assertEquals(c.getString("shared"), box.sharedKey.toHex())
            val nonce = hex(c.getString("nonce")); val msg = hex(c.getString("msg"))
            val sealed = box.seal(msg, nonce)
            assertEquals(c.getString("box"), sealed.copyOfRange(24, sealed.size).toHex())
            val other = Nacl.Box(b, hex(c.getString("pk_a")))
            assertArrayEquals(msg, other.decrypt(Base64.getEncoder().encodeToString(sealed)))
        }
    }

    @Test fun tamperedOrWrongKeyRejected() {
        val v = Vectors.section("encryption")
        val maker = Nacl.Box(hex(v.getString("maker_private")), hex(v.getString("taker_public")))
        val raw = Base64.getDecoder().decode(v.getString("wire_b64"))
        for (i in listOf(0, 24, 30, raw.size - 1)) { // nonce, MAC, ciphertext
            val bad = raw.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) { maker.decrypt(Base64.getEncoder().encodeToString(bad)) }
        }
        val stranger = Nacl.Box(Vectors.h("stranger"), hex(v.getString("taker_public")))
        assertThrows(IllegalArgumentException::class.java) { stranger.decrypt(v.getString("wire_b64")) }
        assertNull(Nacl.secretboxOpen(ByteArray(32), ByteArray(24), ByteArray(15)))
        // An all-zero (small-order) public key gives an all-zero DH result: refused like libsodium.
        assertThrows(IllegalArgumentException::class.java) { Nacl.Box(Vectors.h("x"), ByteArray(32)) }
    }

    @Test fun rfc7748Vector() {
        // RFC 7748 section 5.2, first test vector.
        val k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", Nacl.x25519(k, u).toHex())
    }
}
