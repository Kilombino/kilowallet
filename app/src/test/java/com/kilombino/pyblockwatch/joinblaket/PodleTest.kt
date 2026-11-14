package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.joinblaket.Vectors.big
import com.kilombino.pyblockwatch.joinblaket.Vectors.hex
import com.kilombino.pyblockwatch.joinblaket.Vectors.objects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vector section `podle`. */
class PodleTest {
    private val v = Vectors.section("podle")
    private val x = big(v.getString("private_key"))
    private val utxo = v.getString("utxo")

    @Test fun numsPoints() {
        val nums = v.getJSONObject("nums_points")
        for (i in 0..9) assertEquals(nums.getString("$i"), Secp256k1.compress(Podle.numsPoint(i)).toHex())
        // Reference points from jmcore/podle.py beyond the vector's range.
        assertEquals("02aacc3145d04972d0527c4458629d328219feda92bef6ef6025878e3a252e105a", Secp256k1.compress(Podle.numsPoint(100)).toHex())
        assertEquals("02a0a8694820c794852110e5939a2c03f8482f81ed57396042c6b34557f6eb430a", Secp256k1.compress(Podle.numsPoint(255)).toHex())
    }

    @Test fun proofsForIndices0to2() {
        for (p in v.getJSONArray("proofs").objects()) {
            val i = p.getInt("index")
            assertEquals(p.getString("J"), Secp256k1.compress(Podle.numsPoint(i)).toHex())
            val c = Podle.generate(x, utxo, i)
            assertEquals(p.getString("P"), c.p.toHex())
            assertEquals(p.getString("P2"), c.p2.toHex())
            assertEquals(p.getString("commitment"), c.commitment.toHex())
            assertEquals(p.getString("commitment_wire"), c.wire)
            val k = Podle.nonce(x, Podle.nonceHash(utxo, i, c.p, c.p2))
            assertEquals(p.getString("nonce_k"), "%064x".format(k))
            assertEquals(p.getString("KG"), Secp256k1.compress(Secp256k1.publicPoint(k)).toHex())
            assertEquals(p.getString("KJ"), Secp256k1.compress(Secp256k1.multiply(k, Podle.numsPoint(i))).toHex())
            assertEquals(p.getString("e"), c.e.toHex())
            assertEquals(p.getString("s"), c.s.toHex())
            assertEquals(p.getBoolean("verify"), Podle.verify(c.p, c.p2, c.s, c.e, c.commitment))
            assertEquals("", p.getString("verify_error"))
        }
    }

    @Test fun badProofsRejected() {
        val c = Podle.generate(x, utxo, 2)
        // Index 2 is outside a 0..1 window; s, e, P2 or the commitment tampered.
        assertFalse(Podle.verify(c.p, c.p2, c.s, c.e, c.commitment, 0..1))
        val flip = { b: ByteArray -> b.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() } }
        assertFalse(Podle.verify(c.p, c.p2, flip(c.s), c.e, c.commitment))
        assertFalse(Podle.verify(c.p, c.p2, c.s, flip(c.e), c.commitment))
        assertFalse(Podle.verify(c.p, c.p2, c.s, c.e, flip(c.commitment)))
        val other = Podle.generate(x, utxo, 1)
        assertFalse(Podle.verify(c.p, other.p2, c.s, c.e, other.commitment))
        assertFalse(Podle.verify(c.p, c.p2, ByteArray(32), c.e, c.commitment))
        assertFalse(Podle.verify(c.p, c.p2, Secp256k1.N.toByteArray().takeLast(32).toByteArray(), c.e, c.commitment))
    }

    @Test fun revelationsAndBinding() {
        val c = Podle.generate(x, utxo, 0)
        val spk = v.getString("p2wpkh_scriptpubkey")
        assertEquals(spk, TakerTx.p2wpkh(c.p).toHex())
        assertEquals(v.getBoolean("binding_ok"), Podle.bindsTo(c.p, hex(spk)))
        assertFalse(Podle.bindsTo(Podle.generate(Vectors.key("other"), utxo, 0).p, hex(spk)))
        assertEquals(v.getString("revelation_legacy"), c.revelation())
        assertEquals(v.getString("revelation_extended"), c.revelation(spk, 961650))
        val r = Podle.parseRevelation(v.getString("revelation_extended"))
        assertEquals(spk, r.scriptPubKeyHex); assertEquals(961650, r.height)
        assertTrue(Podle.verify(r.p, r.p2, r.s, r.e, c.commitment))
    }
}
