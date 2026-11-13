package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.joinblaket.Vectors.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Vector section `offer`: the offer privmsg, the fidelity bond proof, its scripts and value. */
class OfferTest {
    private val v = Vectors.section("offer")
    private val maker = v.getString("maker_nick")
    private val taker = v.getString("taker_nick")

    @Test fun offerLine() {
        val line = v.getString("privmsg_line")
        val (from, to, rest) = line.split("!", limit = 3)
        assertEquals(maker, from); assertEquals(taker, to)
        // The offer privmsg is signed like any other (data includes "!tbond <proof>").
        val verified = Nick.verifyPrivmsg(from, rest)
        assertTrue(verified.ok)
        assertEquals("sw0reloffer", verified.command)
        val o = Offers.parse(rest, from)!!
        val want = v.getJSONObject("parsed_offer")
        assertEquals(want.getString("counterparty"), o.counterparty)
        assertEquals(want.getLong("oid"), o.oid)
        assertEquals(want.getString("ordertype"), o.type.wire)
        assertEquals(want.getLong("minsize"), o.minsize)
        assertEquals(want.getLong("maxsize"), o.maxsize)
        assertEquals(want.getLong("txfee"), o.txfee)
        assertEquals(want.getString("cjfee"), o.cjfee)
        assertEquals(v.getString("proof_b64"), o.bondProof)
        // The maker re-signs the same data the vector shows.
        assertEquals(rest, "sw0reloffer " + NickIdentity(Vectors.key("maker1-nick")).signData(verified.data))
    }

    @Test fun offerParsingEdgeCases() {
        assertEquals("0.00001", Offers.parse("sw0reloffer 1 27300 100000 0 1E-5", maker)!!.cjfee)
        assertEquals("500", Offers.parse("sw0absoffer 1 27300 100000 0 500", maker)!!.cjfee)
        assertEquals(OfferType.SW_ABSOLUTE, Offers.parse("swabsoffer 1 27300 100000 0 500", maker)!!.type)
        assertNull(Offers.parse("sw0reloffer 1 27300 100000 0", maker))            // 5 fields
        assertNull(Offers.parse("sw0reloffer 1 27300 100000 0 -0.1", maker))       // negative
        assertNull(Offers.parse("sw0reloffer 1 27300 100000 -5 0.1", maker))       // negative txfee
        assertNull(Offers.parse("sw0reloffer 1 27300 100000 0 1E-9999", maker))    // absurd exponent
        assertNull(Offers.parse("sw0absoffer 1 27300 100000 0 0.5", maker))        // abs fee not an int
        assertNull(Offers.parse("sw0reloffer 1 27300 100000 0 0.1", "notanick"))
        assertNull(Offers.parse("fill 1 2 3 4 5", maker))
    }

    @Test fun bondProofFields() {
        val p = BondProof.decode(v.getString("proof_b64"))!!
        val f = v.getJSONObject("proof_fields")
        val raw = Base64.getDecoder().decode(v.getString("proof_b64"))
        assertEquals(f.getString("nick_sig_padded"), raw.copyOfRange(0, 72).toHex())
        assertEquals(f.getString("nick_sig_padded"), p.nickSig.toHex())
        assertEquals(f.getString("cert_sig_padded"), p.certSig.toHex())
        assertEquals(f.getString("cert_pub"), p.certPub.toHex())
        assertEquals(f.getInt("cert_expiry_encoded"), p.certExpiryEncoded)
        assertEquals(f.getString("utxo_pub"), p.utxoPub.toHex())
        assertEquals(f.getString("txid_display"), p.txid)
        assertEquals(f.getLong("vout"), p.vout)
        assertEquals(f.getLong("locktime"), p.locktime)
        // Generator keys behind the proof.
        assertEquals(p.utxoPub.toHex(), NickIdentity(Vectors.big(v.getString("bond_private_key"))).pubkeyHex)
        assertEquals(p.certPub.toHex(), NickIdentity(Vectors.big(v.getString("cert_private_key"))).pubkeyHex)
        // Makers set the expiry to ((height + 2) // 2016) + 1.
        assertEquals((v.getLong("block_height_for_cert") + 2) / 2016 + 1, p.certExpiryEncoded.toLong())

        val b = v.getJSONObject("parsed_bond")
        assertEquals(b.getString("utxo_txid"), p.txid)
        assertEquals(b.getLong("utxo_vout"), p.vout)
        assertEquals(b.getLong("locktime"), p.locktime)
        assertEquals(b.getString("utxo_pub"), p.utxoPub.toHex())
        assertEquals(b.getString("cert_pub"), p.certPub.toHex())
        assertEquals(b.getLong("cert_expiry"), p.certExpiryHeight)
        assertEquals(b.getString("redeem_script"), p.freezeScript.toHex())
        assertEquals(b.getString("p2wsh_script"), p.scriptPubKey.toHex())
        assertEquals(maker, b.getString("maker_nick")); assertEquals(taker, b.getString("taker_nick"))
    }

    @Test fun bondProofVerification() {
        val p = BondProof.decode(v.getString("proof_b64"))!!
        val want = v.getJSONObject("verify_fidelity_bond_proof")
        assertEquals(want.getBoolean("ok"), p.verify(maker, taker) == null)
        assertEquals(want.getString("error"), p.verify(maker, taker) ?: "")
        assertEquals(want.getLong("cert_expiry_height"), p.certExpiryHeight)
        assertEquals(v.getBoolean("verify_with_wrong_taker_nick"), p.verify(maker, maker) == null)
        assertEquals("nick signature verification failed", p.verify(maker, maker))
        assertFalse(p.expiredAt(p.certExpiryHeight)); assertTrue(p.expiredAt(p.certExpiryHeight + 1))

        val raw = Base64.getDecoder().decode(v.getString("proof_b64"))
        fun with(i: Int, b: Int) = BondProof.decode(Base64.getEncoder().encodeToString(raw.copyOf().also { it[i] = b.toByte() }))!!
        // Another expiry (cert signature no longer matches), another locktime (still verifies:
        // the proof does not sign it, the chain lookup of the P2WSH does), no DER header.
        assertEquals("certificate signature verification failed", with(177, 0x00).verify(maker, taker))
        assertNull(with(248, 0x81).verify(maker, taker))
        assertTrue(with(248, 0x81).scriptPubKey.toHex() != v.getString("bond_p2wsh_scriptpubkey"))
        val noHeader = raw.copyOf().also { java.util.Arrays.fill(it, 0, 72, 0xff.toByte()) }
        assertEquals("nick signature DER header not found", BondProof.decode(Base64.getEncoder().encodeToString(noHeader))!!.verify(maker, taker))
        assertNull(BondProof.decode(Base64.getEncoder().encodeToString(raw.copyOf(251))))
        assertNotNull(BondProof.decode(v.getString("proof_b64")))
    }

    @Test fun bondScriptsAndValue() {
        val pub = hex(v.getJSONObject("proof_fields").getString("utxo_pub"))
        val lock = v.getJSONObject("proof_fields").getLong("locktime")
        val script = Bond.freezeScript(pub, lock)
        assertEquals(v.getString("freeze_script"), script.toHex())
        assertEquals(v.getString("bond_p2wsh_scriptpubkey"), Bond.p2wsh(script).toHex())
        assertEquals(v.getString("bond_address"), Bond.address(Bond.p2wsh(script)))
        val i = v.getJSONObject("bond_value_inputs")
        assertEquals(i.getDouble("interest_rate"), Bond.INTEREST_RATE, 0.0)
        assertEquals(i.getDouble("exponent"), Bond.EXPONENT, 0.0)
        assertEquals(v.getLong("bond_value"), Bond.value(i.getLong("utxo_value"), i.getLong("confirmation_time"), i.getLong("locktime"), i.getLong("current_time")))
        // After the locktime the value decays to zero.
        assertEquals(0L, Bond.value(100_000_000, 1_760_000_000, 1_798_761_600, 1_798_761_600 + 100L * 31_556_952))
        // CScriptNum edge cases.
        assertEquals("00", Bond.pushNumber(0).toHex()); assertEquals("60", Bond.pushNumber(16).toHex())
        assertEquals("0111", Bond.pushNumber(17).toHex()); assertEquals("028000", Bond.pushNumber(128).toHex())
        assertEquals("0500000080" + "00", Bond.pushNumber(0x80000000L).toHex())
    }
}
