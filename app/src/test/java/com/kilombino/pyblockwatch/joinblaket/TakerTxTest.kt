package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import com.kilombino.pyblockwatch.crypto.TxParse
import com.kilombino.pyblockwatch.joinblaket.Vectors.big
import com.kilombino.pyblockwatch.joinblaket.Vectors.hex
import com.kilombino.pyblockwatch.joinblaket.Vectors.objects
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Vector section `transaction`: unsigned CoinJoin, unified 0x21 sighashes, signatures, final tx. */
class TakerTxTest {
    private val v = Vectors.section("transaction")
    private val cjAmount = v.getLong("cj_amount")

    private fun utxo(o: JSONObject) = TakerTx.Utxo(o.getString("txid"), o.getLong("vout"), o.getLong("value"), hex(o.getString("scriptpubkey")))

    private fun makers(): List<TakerTx.MakerPart> {
        val m = v.getJSONObject("makers")
        return m.keys().asSequence().toList().map { nick ->
            val d = m.getJSONObject(nick)
            TakerTx.MakerPart(nick, d.getJSONArray("utxos").objects().map(::utxo), d.getString("cj_addr"), d.getString("change_addr"), d.getLong("cjfee"), d.getLong("txfee"))
        }.sortedBy { it.nick } // insertion order of the generator: maker1, maker2
    }

    private fun build(shuffler: Shuffler = Shuffler.None, makers: List<TakerTx.MakerPart> = makers(), txFee: Long = v.getLong("tx_fee")): TakerTx.Unsigned {
        val t = v.getJSONObject("taker")
        return TakerTx.build(
            t.getJSONArray("utxos").objects().map(::utxo), t.getString("cj_address"), t.getString("change_address"),
            makers, cjAmount, txFee, v.getLong("locktime"), shuffler,
        )
    }

    @Test fun feesAndLocktime() {
        assertEquals(1, Vectors.all.getInt("version"))
        assertEquals("mainnet", v.getString("network")) // bc1 addresses; the wire network is "xbt"
        val offers = v.getJSONArray("offers").objects().map {
            Offer(it.getString("nick"), 0, OfferType.values().first { t -> t.wire == it.getString("ordertype") }, 27300, 10_000_000_000, it.getLong("txfee"), it.get("cjfee").toString())
        }
        val plan = v.getJSONObject("maker_fee_plan")
        assertEquals(plan.keys().asSequence().associateWith { plan.getLong(it) }, Fees.feePlan(offers, cjAmount))
        val nIn = 1 + 2 + 1
        assertEquals(v.getLong("tx_fee"), Fees.txFee(nIn, Fees.outputCount(2), v.getDouble("fee_rate_randomized")))

        val cases = v.getJSONObject("locktime_cases")
        fun scripted(vararg xs: Int): (Int, Int) -> Int { val q = ArrayDeque(xs.toList()); return { a, b -> q.removeFirst().also { assertTrue(it in a..b) } } }
        assertEquals(cases.getLong("height_961700_rng_[3]"), TakerTx.locktime(961700, scripted(3)))
        assertEquals(cases.getLong("height_961700_rng_[0,37]"), TakerTx.locktime(961700, scripted(0, 37)))
        assertEquals(cases.getLong("height_0"), TakerTx.locktime(0, scripted()))
        assertEquals(1L, TakerTx.locktime(50, scripted(0, 99)))
        assertEquals(v.getLong("locktime"), cases.getLong("height_961700_rng_[3]"))
    }

    @Test fun unsignedTransaction() {
        val tx = build()
        assertEquals(v.getString("unsigned_tx"), tx.serialize().toHex())
        assertEquals(v.getString("unsigned_tx_b64"), Base64.getEncoder().encodeToString(tx.serialize()))
        val meta = v.getJSONObject("metadata")
        assertEquals((0 until meta.getJSONArray("input_owners").length()).map { meta.getJSONArray("input_owners").getString(it) }, tx.inputOwners)
        val oo = meta.getJSONArray("output_owners")
        assertEquals((0 until oo.length()).map { TakerTx.OutputOwner(oo.getJSONArray(it).getString(0), oo.getJSONArray(it).getString(1)) }, tx.outputOwners)
        v.getJSONArray("outputs").objects().forEachIndexed { i, o ->
            assertEquals(o.getLong("value"), tx.outputs[i].value)
            assertEquals(o.getString("scriptpubkey"), tx.outputs[i].scriptPubKey.toHex())
        }
        assertEquals(0xfffffffeL, tx.sequence)
        // The wallet's own transaction parser reads it back.
        val parsed = TxParse.parse(tx.serialize().toHex())
        assertEquals(4, parsed.inputs.size); assertEquals(6, parsed.outputs.size)
    }

    @Test fun shuffledBuildKeepsTheSameTransactionContent() {
        val plain = build()
        val shuffled = build(Shuffler.Secure)
        assertEquals(plain.inputs.map { it.outpoint }.toSet(), shuffled.inputs.map { it.outpoint }.toSet())
        assertEquals(plain.outputs.map { it.value to it.scriptPubKey.toHex() }.toSet(), shuffled.outputs.map { it.value to it.scriptPubKey.toHex() }.toSet())
        shuffled.inputs.forEachIndexed { i, u -> assertEquals(plain.inputOwners[plain.indexOf(u.txid, u.vout)], shuffled.inputOwners[i]) }
    }

    @Test fun dustAndInsufficientMakers() {
        // Taker change at or below 2730 goes to the miner; a maker with negative change is refused.
        val noChange = build(txFee = 45_000_000 - cjAmount - 6500 - 2730)
        assertTrue(noChange.outputOwners.none { it == TakerTx.OutputOwner("taker", "change") })
        val poor = makers().map { if (it.nick == "J5maker2OOOOOOOO") it.copy(utxos = it.utxos.map { u -> u.copy(value = 29_000_000) }) else it }
        assertThrows(IllegalArgumentException::class.java) { build(makers = poor) }
    }

    @Test fun sighashesSignaturesAndFinalTx() {
        val tx = build()
        val witnesses = HashMap<Int, Pair<ByteArray, ByteArray>>()
        for (inp in v.getJSONArray("inputs").objects()) {
            val i = inp.getInt("index")
            val key = big(inp.getString("private_key"))
            val pub = hex(inp.getString("pubkey"))
            assertEquals(inp.getString("pubkey"), Secp256k1.compress(Secp256k1.publicPoint(key)).toHex())
            assertEquals(inp.getString("owner"), tx.inputOwners[i])
            assertEquals(inp.getString("txid"), tx.inputs[i].txid); assertEquals(inp.getLong("vout"), tx.inputs[i].vout)
            assertEquals(inp.getLong("value"), tx.inputs[i].value)
            assertEquals(inp.getString("scriptpubkey"), tx.inputs[i].scriptPubKey.toHex())
            assertEquals(inp.getString("script_code"), TakerTx.scriptCode(pub).toHex())
            val pre = TakerTx.unifiedPreimage(tx, i, pub)
            assertEquals(202, pre.size)
            assertEquals(inp.getString("unified_preimage"), pre.toHex())
            assertEquals(inp.getString("unified_sighash"), TakerTx.sighash(tx, i, pub).toHex())

            // Same sighash through the wallet's own TxBuilder path, and the BIP-143 contrast value.
            val txbInputs = tx.inputs.mapIndexed { j, u ->
                val k = big(v.getJSONArray("inputs").getJSONObject(j).getString("private_key"))
                TxBuilder.Input(u.txid, u.vout.toInt(), u.value, k, Secp256k1.compress(Secp256k1.publicPoint(k)), tx.sequence)
            }
            assertEquals(inp.getString("unified_sighash"), TxBuilder.unifiedSighash(2, txbInputs, tx.outputs, i, tx.locktime).toHex())
            assertEquals(inp.getString("bip143_sighash_0x01_for_contrast"), TxBuilder.sighash(2, txbInputs, tx.outputs, i, tx.locktime).toHex())

            val sig = TakerTx.signInput(tx, i, key)
            assertEquals(inp.getString("signature"), sig.toHex())
            val w = inp.getJSONArray("witness")
            assertEquals(w.getString(0), sig.toHex()); assertEquals(w.getString(1), pub.toHex())
            assertEquals(inp.getBoolean("verify_0x21"), TakerTx.verifyInput(tx, i, sig, pub))
            // The same DER with a 0x01 hash-type byte must be refused (replayable on SHA-256).
            val sig01 = sig.copyOf().also { it[it.size - 1] = 0x01 }
            assertEquals(inp.getBoolean("verify_same_der_with_0x01_byte"), TakerTx.verifyInput(tx, i, sig01, pub))
            assertEquals(inp.getString("sig_wire_b64"), Session.encodeSig(sig, pub))
            witnesses[i] = sig to pub
        }
        val signed = TakerTx.assemble(tx, witnesses)
        assertEquals(v.getString("signed_tx"), signed.toHex())
        assertEquals(v.getString("txid"), tx.txid())
        assertEquals(v.getString("txid"), TxParse.txid(TxParse.parse(signed.toHex())))
        assertThrows(IllegalArgumentException::class.java) { TakerTx.assemble(tx, witnesses - 3) }
    }

    @Test fun makerSignatureMatchingAndNegatives() {
        val tx = build()
        val ins = v.getJSONArray("inputs").objects()
        fun w(i: Int) = hex(ins[i].getString("signature")) to hex(ins[i].getString("pubkey"))
        // maker1 sends its two signatures in reverse order: still matched to the right inputs.
        val m1 = TakerTx.matchMakerSignatures(tx, "J5maker1OOOOOOOO", listOf(w(2), w(1)))!!
        assertEquals(setOf(1, 2), m1.keys)
        assertArrayEquals(w(1).first, m1.getValue(1).first)
        // One signature missing, or a duplicate instead of the second: the maker fails.
        assertNull(TakerTx.matchMakerSignatures(tx, "J5maker1OOOOOOOO", listOf(w(1))))
        assertNull(TakerTx.matchMakerSignatures(tx, "J5maker1OOOOOOOO", listOf(w(1), w(1))))
        // A signature for another maker's input does not count.
        assertNull(TakerTx.matchMakerSignatures(tx, "J5maker2OOOOOOOO", listOf(w(1))))

        // Tampered amount: if our view of a maker input's value differs, its signature fails.
        val (sig3, pub3) = w(3)
        val tampered = TakerTx.Unsigned(
            tx.inputs.mapIndexed { i, u -> if (i == 3) u.copy(value = u.value + 1) else u }, tx.outputs, tx.locktime, tx.inputOwners, tx.outputOwners,
        )
        assertTrue(TakerTx.verifyInput(tx, 3, sig3, pub3))
        assertFalse(TakerTx.verifyInput(tampered, 3, sig3, pub3))
        // Tampered output amount, wrong key for the input, sighash 0x01 signed for real.
        val moreToTaker = TakerTx.Unsigned(tx.inputs, tx.outputs.mapIndexed { i, o -> if (i == 0) o.copy(value = o.value + 1) else o }, tx.locktime, tx.inputOwners, tx.outputOwners)
        assertFalse(TakerTx.verifyInput(moreToTaker, 3, sig3, pub3))
        assertFalse(TakerTx.verifyInput(tx, 3, sig3, hex(ins[1].getString("pubkey"))))
        val txbInputs = tx.inputs.mapIndexed { j, u ->
            val k = big(ins[j].getString("private_key"))
            TxBuilder.Input(u.txid, u.vout.toInt(), u.value, k, Secp256k1.compress(Secp256k1.publicPoint(k)), tx.sequence)
        }
        val legacy = TxBuilder.witnessSignature(2, txbInputs, tx.outputs, 3, tx.locktime, unified = false)
        assertEquals(0x01, legacy.last().toInt())
        assertFalse(TakerTx.verifyInput(tx, 3, legacy, pub3))
        // Our own key must own the input we sign.
        assertThrows(IllegalArgumentException::class.java) { TakerTx.signInput(tx, 1, big(ins[0].getString("private_key"))) }
    }

    @Test fun minerFeeOfTheVectorTx() {
        val tx = build()
        val totalIn = tx.inputs.sumOf { it.value }
        val totalOut = tx.outputs.sumOf { it.value }
        // tx_fee + maker txfee contributions.
        assertEquals(v.getLong("tx_fee") + 150, totalIn - totalOut)
        assertNull(Fees.checkMinerFee(totalIn, totalOut, tx.inputs.size, tx.outputs.size))
        assertEquals(Hashes.doubleSha256(tx.serialize()).reversedArray().toHex(), tx.txid())
    }
}
