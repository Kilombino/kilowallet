package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import com.kilombino.pyblockwatch.crypto.TxParse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Kilojoin v2: Taproot rounds, several coins per person, and that v1 pools look exactly as
 * before to wallets that only speak v1.
 */
class CoinjoinV2Test {
    private fun key(n: Long) = BigInteger.valueOf(n)
    private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    private fun coin(n: Long, value: Long, type: ScriptType, vout: Int = 0) =
        CoinjoinTx.Coin("%064x".format(n), vout, value, pub(key(n)), type)
    private fun script(n: Long, type: ScriptType) = Address.scriptPubKey(pub(key(n)), type)

    /** Two people, A with [aCoins] coins and B with one; returns the plan and every party's data. */
    private fun round(type: ScriptType, aCoins: Int, amount: Long = 100_000, rate: Double = 2.0): Triple<CoinjoinTx.Plan, List<CoinjoinTx.Coin>, List<Int>> {
        val a = (1..aCoins).map { coin(10L + it, 130_000L / aCoins + 1_000, type) }
        val b = listOf(coin(20, 130_000, type))
        val aChange = CoinjoinTx.change(a.sumOf { it.value }, amount, rate, a.size, type)!!
        val bChange = CoinjoinTx.change(b.sumOf { it.value }, amount, rate, 1, type)!!
        val changes = listOfNotNull(
            if (aChange > 0) TxBuilder.Output(script(31, type), aChange) else null,
            if (bChange > 0) TxBuilder.Output(script(32, type), bChange) else null,
        )
        val plan = CoinjoinTx.plan(a + b, listOf(script(41, type), script(42, type)), changes, amount)
        return Triple(plan, a + b, listOf(a.size, 1))
    }

    @Test fun taprootRoundSignsVerifiesAndChecks() {
        val (plan, coins, seats) = round(ScriptType.P2TR, 1)
        val sigs = coins.associate { c ->
            val k = key(c.txid.toLong(16))
            c.outpoint to CoinjoinTx.sign(plan, c, k)
        }
        for (c in coins) {
            val sig = sigs.getValue(c.outpoint)
            assertEquals(65, sig.size)                 // Schnorr + the unified hash byte
            assertEquals(0x21, sig.last().toInt() and 0xff)
            assertTrue(CoinjoinTx.verify(plan, c, sig))
            // Someone else's signature does not pass for this coin.
            assertFalse(CoinjoinTx.verify(plan, c, sigs.values.first { !it.contentEquals(sig) }))
        }
        assertNull(CoinjoinTx.checkOurs(plan, script(41, ScriptType.P2TR),
            plan.outputs.first { it.scriptPubKey.contentEquals(script(31, ScriptType.P2TR)) }, 100_000, 2.0, seats))
        val tx = CoinjoinTx.assemble(plan, sigs)
        val parsed = TxParse.parse(tx.rawHex)
        assertTrue(parsed.inputs.all { it.witness.size == 1 && it.witness[0].size == 65 })
        assertTrue(parsed.outputs.all { it.scriptPubKey.size == 34 && it.scriptPubKey[0] == 0x51.toByte() })
        assertEquals(CoinjoinTx.txid(plan), tx.txid)
    }

    @Test fun severalCoinsPerPersonPayTheirOwnFee() {
        for (type in listOf(ScriptType.P2WPKH, ScriptType.P2TR)) {
            val (plan, coins, seats) = round(type, 3)
            assertEquals(4, plan.coins.size)
            val sigs = coins.associate { it.outpoint to CoinjoinTx.sign(plan, it, key(it.txid.toLong(16))) }
            assertTrue(coins.all { CoinjoinTx.verify(plan, it, sigs.getValue(it.outpoint)) })
            assertNull(CoinjoinTx.checkOurs(plan, script(42, type), plan.outputs.firstOrNull { it.scriptPubKey.contentEquals(script(32, type)) },
                100_000, 2.0, seats))
            // The fee covers the real size at the rate, within the per-person rounding.
            val vsize = CoinjoinTx.assemble(plan, sigs).vbytes
            assertTrue("$type fee ${plan.fee} for $vsize vB", plan.fee >= vsize * 2.0 * 0.95)
            // Three coins pay for three inputs: more than one coin of the same total would.
            assertTrue(CoinjoinTx.feeShare(2.0, true, 3, type) > CoinjoinTx.feeShare(2.0, true, 1, type))
        }
    }

    @Test fun oneAddressTypePerRound() {
        val a = coin(11, 150_000, ScriptType.P2TR)
        val b = coin(12, 150_000, ScriptType.P2WPKH)
        assertTrue(runCatching {
            CoinjoinTx.plan(listOf(a, b), listOf(script(41, ScriptType.P2TR), script(42, ScriptType.P2TR)), emptyList(), 100_000)
        }.isFailure)
        // A SegWit output in a Taproot round is refused too.
        assertTrue(runCatching {
            CoinjoinTx.plan(listOf(a, coin(13, 150_000, ScriptType.P2TR)),
                listOf(script(41, ScriptType.P2TR), script(42, ScriptType.P2WPKH)), emptyList(), 100_000)
        }.isFailure)
    }

    @Test fun termsVersions() {
        val (v1, _) = PoolSession.newPool(100_000, 2.0, 5, 1)
        assertEquals(Protocol.V1, v1.version)
        val j1 = v1.toJson()
        assertEquals(1, j1.getInt("v"))
        assertFalse(j1.has("script") || j1.has("max_inputs"))   // what v1 wallets have always seen

        val (v2, secret) = PoolSession.newPool(100_000, 2.0, 5, 1, script = ScriptType.P2TR, maxInputs = 4)
        assertEquals(Protocol.VERSION, v2.version)
        val ev = NostrEvent.sign(BigInteger(secret, 16), Protocol.KIND_POOL,
            listOf(listOf("d", v2.id), listOf("t", Protocol.TAG)), v2.toJson().toString())
        val back = Protocol.Terms.parse(ev)!!
        assertEquals(ScriptType.P2TR, back.script)
        assertEquals(4, back.maxInputs)
        assertEquals(2, back.version)

        // SegWit with several coins each is v2 as well.
        assertEquals(Protocol.VERSION, PoolSession.newPool(100_000, 2.0, 5, 1, maxInputs = 2).terms.version)
    }

    @Test fun seatsAndStateRoundTrip() {
        val one = Protocol.Seat("th", coin(11, 150_000, ScriptType.P2WPKH), null, 0)
        val j = one.toJson()
        assertFalse(j.has("coins"))                               // a v1 seat is unchanged
        assertEquals(one.coin.outpoint, Protocol.Seat.parse(j).coin.outpoint)

        val coins = listOf(coin(11, 60_000, ScriptType.P2TR), coin(12, 60_000, ScriptType.P2TR, 1))
        val many = Protocol.Seat("th", coins, script(31, ScriptType.P2TR), 5_000)
        val back = Protocol.Seat.parse(JSONObject(many.toJson().toString()), ScriptType.P2TR)
        assertEquals(coins.map { it.outpoint }, back.coins.map { it.outpoint })
        assertTrue(back.coins.all { it.type == ScriptType.P2TR })

        // The change signature over one coin is still the v1 message.
        assertTrue(Protocol.changeMessage("p", listOf(coins[0]), null, 0)
            .contentEquals(com.kilombino.pyblockwatch.crypto.Hashes.sha256("kilojoin/v1/change|p|${coins[0].outpoint}||0".toByteArray())))

        val (terms, secret) = PoolSession.newPool(100_000, 2.0, 5, 1, script = ScriptType.P2TR, maxInputs = 3)
        val st = PoolSession.newState(terms, true, secret, coins, listOf(key(11), key(12)), listOf("m/86'/0'/0'/0/1", "m/86'/0'/0'/0/2"),
            script(41, ScriptType.P2TR), script(31, ScriptType.P2TR))
        assertEquals(2, st.joinProofs.size)
        val st2 = PoolSession.State.parse(JSONObject(st.toJson().toString()))
        assertEquals(st.coinPaths, st2.coinPaths)
        assertEquals(st.joinProofs, st2.joinProofs)
        assertEquals(ScriptType.P2TR, st2.terms.script)
        assertEquals(2, st2.seat.coins.size)
        assertTrue(st2.seat.coins.all { it.type == ScriptType.P2TR })
        // Each coin proves its own ownership.
        assertTrue(coins.indices.all {
            CoinjoinTx.checkOwnership(terms.id, terms.poolPub, coins[it], com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(st.joinProofs[it]))
        })

        // Coins of the wrong type, or more than the pool allows, never make a seat.
        assertTrue(runCatching {
            PoolSession.newState(terms, true, secret, listOf(coin(13, 150_000, ScriptType.P2WPKH)), listOf(key(13)), listOf("x"),
                script(41, ScriptType.P2TR), script(31, ScriptType.P2TR))
        }.isFailure)
        val (one1, s1) = PoolSession.newPool(100_000, 2.0, 5, 1)
        assertTrue(runCatching {
            PoolSession.newState(one1, true, s1, listOf(coin(14, 60_000, ScriptType.P2WPKH), coin(15, 60_000, ScriptType.P2WPKH)),
                listOf(key(14), key(15)), listOf("a", "b"), script(41, ScriptType.P2WPKH), script(31, ScriptType.P2WPKH))
        }.isFailure)
    }
}
