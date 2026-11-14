package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.offerVec
import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.proofFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Bond values against a chain view, and the bonded-only maker choice. */
class SelectionTest {
    private val bond = BondProof.decode(offerVec.getString("proof_b64"))!!
    private val inputs = offerVec.getJSONObject("bond_value_inputs")
    private val offer = Offers.parse(offerVec.getString("privmsg_line").split("!", limit = 3)[2], offerVec.getString("maker_nick"))!!

    /** A chain with the vectors' bond confirmed 10 blocks deep at the vectors' confirmation time. */
    private inner class FakeChain(
        val tip: Int = 962_000,
        val utxo: IoauthCheck.ChainUtxo? = IoauthCheck.ChainUtxo(inputs.getLong("utxo_value"), bond.scriptPubKey, 10),
        val fail: Boolean = false,
    ) : ChainLookup {
        var lookups = 0
        override fun height() = tip
        override fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray?): IoauthCheck.ChainUtxo? {
            lookups++
            if (fail) throw java.io.IOException("server gone")
            assertTrue(scriptPubKey!!.contentEquals(bond.scriptPubKey))
            return utxo.takeIf { txid == bond.txid && vout == bond.vout }
        }
        override fun blockTime(height: Int): Long {
            assertEquals(tip - 10 + 1, height)
            return inputs.getLong("confirmation_time")
        }
    }

    private val now = inputs.getLong("current_time")
    private fun valued(chain: ChainLookup, offers: List<BondedOffer> = listOf(BondedOffer(offer, bond))) = BondValues.value(offers, chain, now)

    @Test fun bondValueFromTheChain() {
        val v = valued(FakeChain()).single()
        assertEquals(offerVec.getLong("bond_value"), v.value)
        assertNull(v.problem)
    }

    @Test fun bondsThatDoNotCount() {
        // Certificate expired: current height above cert_expiry * 2016; not even looked up.
        val expired = FakeChain(tip = bond.certExpiryHeight.toInt() + 1)
        assertTrue(valued(expired).single().problem!!.contains("expired")); assertEquals(0, expired.lookups)
        assertEquals("UTXO not found or spent", valued(FakeChain(utxo = null)).single().problem)
        assertEquals("UTXO unconfirmed", valued(FakeChain(utxo = IoauthCheck.ChainUtxo(100_000_000, bond.scriptPubKey, 0))).single().problem)
        assertEquals("ScriptPubKey mismatch", valued(FakeChain(utxo = IoauthCheck.ChainUtxo(100_000_000, ByteArray(34), 10))).single().problem)
        val down = valued(FakeChain(fail = true)).single()
        assertEquals(0L, down.value); assertTrue(down.problem!!.startsWith("lookup failed"))
    }

    @Test fun offersSharingAClaimShareOneLookup() {
        val chain = FakeChain()
        val vs = valued(chain, listOf(BondedOffer(offer, bond), BondedOffer(offer.copy(oid = 1), bond), BondedOffer(offer.copy(counterparty = "J5other"), bond)))
        assertEquals(1, chain.lookups)
        assertTrue(vs.all { it.value == offerVec.getLong("bond_value") })
    }

    // --- maker choice ---

    private val taker = offerVec.getString("taker_nick")
    private fun txid(i: Int) = "%064x".format(i)
    private fun mk(nick: String, value: Long, cjfee: String = "0.0002", bondTx: Int = nick.hashCode(), oid: Long = 0, abs: Boolean = false) =
        ValuedOffer(
            Offer(nick, oid, if (abs) OfferType.SW0_ABSOLUTE else OfferType.SW0_RELATIVE, 27_300, 1_000_000_000, 0, cjfee),
            BondProof.decode(proofFor(nick, taker, txid(bondTx)))!!, value,
        )

    private fun chosen(r: MakerSelection.Result) = (r as MakerSelection.Result.Chosen).offers.map { it.offer.counterparty }.sorted()

    @Test fun cheapestPerMakerThenPerBondAndOnlyBonded() {
        val offers = listOf(
            mk("J5a", 10, "0.001", oid = 0), mk("J5a", 10, "0.0002", oid = 1),   // same maker: the cheaper oid 1
            mk("J5b", 10, "0.0005", bondTx = 7), mk("J5c", 10, "0.0001", bondTx = 7), // same bond: the cheaper J5c
            mk("J5d", 0),                                                       // bond did not verify
            mk("J5e", 10, "0.0003"),                                            // fee not on the grid
            mk("J5f", 10, "600", abs = true),                                   // above the 500 sat cap
        )
        val r = MakerSelection.choose(offers, 30_000_000, n = 10) as MakerSelection.Result.Chosen
        assertEquals(listOf("J5a" to 1L, "J5c" to 0L), r.offers.map { it.offer.counterparty to it.offer.oid }.sortedBy { it.first })
        assertEquals(mapOf("J5a" to 6_000L, "J5c" to 3_000L), r.fees)
        assertEquals(9_000L, r.totalFee)
    }

    @Test fun goesOnWithFewerDownToMinimumMakers() {
        val two = listOf(mk("J5a", 10), mk("J5b", 10))
        assertEquals(listOf("J5a", "J5b"), chosen(MakerSelection.choose(two, 30_000_000, n = 9, minimumMakers = 2)))
        val tooFew = MakerSelection.choose(two, 30_000_000, n = 9, minimumMakers = 3) as MakerSelection.Result.Failed
        assertTrue(tooFew.reason.startsWith("Not enough offers"))
        // Enough offers, but only one bonded and acceptable.
        val one = MakerSelection.choose(two + mk("J5c", 0), 30_000_000, n = 3, minimumMakers = 2, ignored = setOf("J5b"))
        assertTrue((one as MakerSelection.Result.Failed).reason.startsWith("Not enough makers with a fidelity bond"))
        assertTrue(MakerSelection.choose(emptyList(), 30_000_000, n = 1) is MakerSelection.Result.Failed)
    }

    @Test fun picksAreWeightedByBondValue() {
        val pool = listOf(mk("J5big", 9_000), mk("J5small", 1_000), mk("J5tiny", 1))
        val rng = Random(1)
        val counts = HashMap<String, Int>()
        repeat(2_000) {
            val pick = MakerSelection.weightedChoose(pool, 1, rng).single().offer.counterparty
            counts[pick] = (counts[pick] ?: 0) + 1
        }
        assertTrue(counts.getValue("J5big") in 1_700..1_900)
        assertTrue((counts["J5tiny"] ?: 0) < 10)
        // Without replacement: two picks are two different makers; a pool that fits is taken whole.
        assertEquals(2, MakerSelection.weightedChoose(pool, 2, rng).map { it.offer.counterparty }.toSet().size)
        assertEquals(pool, MakerSelection.weightedChoose(pool, 3, rng))
    }
}
