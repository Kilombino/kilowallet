package com.kilombino.pyblockwatch.coinjoin

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Several seats of one phone in a pool (how they are kept, and older saved rounds), and the perpetual
 * coinjoin's decisions, round after round, with the chain and the relay played by a fake.
 */
class SeatsAndPerpetualTest {
    private fun key(n: Long) = BigInteger.valueOf(n)
    private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    private fun coin(n: Long, value: Long) = CoinjoinTx.Coin("%064x".format(n), 0, value, pub(key(n)))
    private fun script(n: Long) = Address.scriptPubKey(pub(key(n)), ScriptType.P2WPKH)

    // ------------------------------------------------------------------------------- seats

    @Test fun firstSeatKeepsThePoolIdAndAnotherGetsItsOwn() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true)
        val id = np.terms.id
        assertEquals("", CoinjoinHub.newSeatId(id, emptySet()))
        assertEquals("", CoinjoinHub.newSeatId(id, setOf("other")))
        val second = CoinjoinHub.newSeatId(id, setOf(id))
        assertEquals(8, second.length)
        val third = CoinjoinHub.newSeatId(id, setOf(id, PoolSession.State.keyOf(id, second)))
        assertNotEquals(second, third)
        assertEquals(id, PoolSession.State.keyOf(id, ""))
        assertEquals("$id#$second", PoolSession.State.keyOf(id, second))
        assertEquals(id, PoolSession.State.poolOf("$id#$second"))
        assertEquals(id, PoolSession.State.poolOf(id))
    }

    @Test fun anotherSeatRoundTripsAndAnOldSavedRoundKeepsItsKey() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true)
        val a = PoolSession.newState(np.terms, false, null, coin(11, 150_000), key(11), "a", script(41), script(31))
        val b = PoolSession.newState(np.terms, false, null, listOf(coin(12, 150_000)), listOf(key(12)), listOf("b"),
            listOf(script(42)), script(32), seatId = "0a1b2c3d", joinAfter = 1_234_567, perpId = "feed")
        assertEquals(np.terms.id, a.key)
        assertEquals(np.terms.id + "#0a1b2c3d", b.key)
        // Each seat its own join key: nothing ties the two join requests together.
        assertNotEquals(a.joinSecret, b.joinSecret)
        val back = PoolSession.State.parse(JSONObject(b.toJson().toString()))
        assertEquals(b.key, back.key); assertEquals(1_234_567L, back.joinAfter); assertEquals("feed", back.perpId)
        // A round saved by 1.3.2 or older has none of the new fields: it is the pool's first seat, as it was.
        val old = JSONObject(a.toJson().toString()).apply { remove("seat_id"); remove("join_after"); remove("perp_id") }
        val o = PoolSession.State.parse(old)
        assertEquals(np.terms.id, o.key); assertEquals(0L, o.joinAfter); assertNull(o.perpId)
    }

    @Test fun theCreatorTakesAnotherSeatInItsPrivatePoolWithItsKey() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, private = true, v3 = true)
        val creator = PoolSession.newState(np.terms, true, np.secret, listOf(coin(21, 150_000)), listOf(key(21)), listOf("c"),
            listOf(script(51)), script(52), password = "secreto", creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        val seat = PoolSession.newState(np.terms, false, null, listOf(coin(22, 150_000)), listOf(key(22)), listOf("d"),
            listOf(script(53)), script(54), seatId = "11112222",
            passwordKey = com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(creator.pwKey!!))
        // The same proof a member who typed the password would send.
        val joinPub = NostrEvent.pubOf(BigInteger(seat.joinSecret, 16))
        assertEquals(Protocol.passwordProof(Protocol.passwordKey(np.terms.id, "secreto"), joinPub), seat.pwProof)
    }

    // ------------------------------------------------------------------------------- perpetual

    private inner class Fake : Remixer.Env {
        var now = 1_000_000L
        val seats = HashMap<String, PoolSession.State>()
        var unspent: Boolean? = true
        var estimate: Double? = 1.0
        var pools = mutableListOf<Protocol.Terms>()
        val busy = mutableSetOf<String>()
        val joined = mutableListOf<String>(); val created = mutableListOf<Pair<Long, Double>>()
        var outputValue = 0L
        private var n = 0
        override fun now() = now
        override fun delay() = 30L
        override fun seat(key: String) = seats[key]
        override fun outputOf(txid: String, script: ByteArray) = 3 to outputValue
        override fun unspent(r: Remix) = unspent
        override fun feeEstimate() = estimate
        override fun openPools() = pools
        override fun busyPools() = busy
        override fun join(r: Remix, t: Protocol.Terms): Remixer.NewSeat { joined += t.id; n++; return Remixer.NewSeat("seat$n", 100 + n, script(900L + n)) }
        override fun create(r: Remix, amount: Long, rate: Double): Remixer.NewSeat { created += amount to rate; n++; return Remixer.NewSeat("seat$n", 100 + n, script(900L + n)) }
        var firstUnspent: Boolean? = true
        val reopened = mutableListOf<FirstPool>()
        override fun firstUnspent(r: Remix) = firstUnspent
        override fun reopen(r: Remix): Remixer.NewSeat { reopened += r.first!!; n++; return Remixer.NewSeat("seat$n", r.seatIndex, r.first!!.mixScript) }
    }

    /** A seat state in [phase], of a pool of [amount], with [txid] once sent. */
    private fun seat(phase: PoolSession.Phase, amount: Long = 100_000, txid: String? = null, reason: String = "",
                     terms: Protocol.Terms = PoolSession.newPool(amount, 2.0, 5, 1, v3 = true).terms): PoolSession.State {
        return PoolSession.newState(terms, false, null, coin(31, amount + 5_000), key(31), "x", script(61), script(62)).also {
            it.phase = phase; it.txid = txid; it.reason = reason
        }
    }

    private fun pool(amount: Long, rate: Double = 2.0, outputs: Int = 1, private: Boolean = false, script: ScriptType = ScriptType.P2WPKH,
                     peers: Int = 2, hours: Int = 3): Protocol.Terms =
        PoolSession.newPool(amount, rate, 5, hours, private = private, script = script, v3 = true, outputs = outputs).terms.copy(peers = peers)

    private fun fresh(f: Fake, cap: Double = 2.0) = Remix("r1", ScriptType.P2WPKH, cap = cap, rate = 2.0, minPeers = 2, maxPeers = 5, hours = 6,
        seat = "seat0", seatIndex = 7, seatScript = script(70)).also { f.seats["seat0"] = seat(PoolSession.Phase.CONFIRMED, txid = "aa".repeat(32)) }

    @Test fun roundAfterRoundWithAWaitBetween() {
        val f = Fake(); val r = fresh(f)
        f.outputValue = 100_000
        // The first round confirmed: its mixed output is the coin now, and the next look is after a wait.
        assertTrue(Remixer(f).step(r))
        assertEquals(Remix.State.WAITING, r.state); assertEquals(1, r.rounds); assertEquals(100_000L, r.value)
        assertEquals(3, r.vout); assertEquals(7, r.index); assertEquals(f.now + 30, r.nextAt)
        assertTrue(f.joined.isEmpty() && f.created.isEmpty())
        // Not before the wait is over.
        f.now += 10; assertTrue(!Remixer(f).step(r))
        // No pool open: one with the whole coin, no change, at the first pool's rate.
        f.now += 30; assertTrue(Remixer(f).step(r))
        assertEquals(Remix.State.SEATED, r.state); assertEquals(1, f.created.size)
        val (amount, rate) = f.created.first()
        assertEquals(2.0, rate, 0.0)
        assertEquals(0L, CoinjoinTx.changeOf(100_000, amount, 2.0, 1, ScriptType.P2WPKH, 1))
        // That round confirms too: the coin shrinks by its fee share, and it goes on.
        f.seats[r.seat!!] = seat(PoolSession.Phase.CONFIRMED, amount, txid = "bb".repeat(32))
        f.outputValue = amount
        assertTrue(Remixer(f).step(r)); assertEquals(2, r.rounds); assertEquals(amount, r.value)
        assertEquals(101, r.index) // the seat's own mixed output
    }

    @Test fun joinsAPoolWithNoChangeFirst() {
        val f = Fake(); val r = fresh(f); f.outputValue = 100_000
        Remixer(f).step(r); f.now = r.nextAt
        val exact = CoinjoinTx.noChangeAmount(100_000, 1, 2.0, ScriptType.P2WPKH, 1)!!
        val withChange = pool(50_000); val noChange = pool(exact); val tooBig = pool(150_000)
        val taproot = pool(exact, script = ScriptType.P2TR); val several = pool(exact / 2, outputs = 2)
        val privat = pool(exact, private = true); val dear = pool(exact - 2_000, rate = 5.0)
        f.pools = mutableListOf(withChange, tooBig, taproot, several, privat, dear, noChange)
        assertTrue(Remixer(f).step(r))
        assertEquals(listOf(noChange.id), f.joined)
        // Only one with change left: it takes that one (the change is not remixed).
        val r2 = fresh(f); f.joined.clear()
        Remixer(f).step(r2); f.now = r2.nextAt
        f.pools = mutableListOf(withChange, tooBig, taproot, several, privat, dear)
        Remixer(f).step(r2)
        assertEquals(listOf(withChange.id), f.joined)
    }

    @Test fun neverAPoolOfOursOrOneThatRefusedIt() {
        val f = Fake(); val r = fresh(f); f.outputValue = 100_000
        Remixer(f).step(r); f.now = r.nextAt
        val a = pool(50_000); val b = pool(60_000)
        f.pools = mutableListOf(a, b); f.busy += b.id
        Remixer(f).step(r)
        assertEquals(listOf(a.id), f.joined)
        // a refuses it: back to waiting, and a is not tried again.
        f.seats[r.seat!!] = seat(PoolSession.Phase.REJECTED, a.amount, reason = "the pool is full", terms = a)
        assertTrue(Remixer(f).step(r)); assertEquals(Remix.State.WAITING, r.state); assertTrue(a.id in r.tried)
        f.now = r.nextAt; f.joined.clear(); f.busy.clear()
        Remixer(f).step(r)
        assertEquals(listOf(b.id), f.joined)
    }

    @Test fun aCancelledPoolIsOpenedAgain() {
        val f = Fake(); val r = fresh(f); f.outputValue = 100_000
        Remixer(f).step(r); f.now = r.nextAt
        Remixer(f).step(r); assertEquals(1, f.created.size)
        f.seats[r.seat!!] = seat(PoolSession.Phase.ABORTED, reason = "the pool expired")
        assertTrue(Remixer(f).step(r)); assertEquals(Remix.State.WAITING, r.state)
        assertEquals(1, r.rounds) // nothing mixed, nothing paid
        f.now = r.nextAt; Remixer(f).step(r)
        assertEquals(2, f.created.size); assertEquals(Remix.State.SEATED, r.state)
        // The seat gone from the list altogether: the same.
        f.seats.remove(r.seat!!)
        assertTrue(Remixer(f).step(r)); assertEquals(Remix.State.WAITING, r.state)
    }

    @Test fun stopsWhenSpentOverTheCapOrTooSmall() {
        // Spent some other way.
        Fake().let { f -> val r = fresh(f); f.outputValue = 100_000; Remixer(f).step(r); f.now = r.nextAt
            f.unspent = false; Remixer(f).step(r)
            assertEquals(Remix.State.STOPPED, r.state); assertEquals(Remix.MOVED, r.reason); assertTrue(f.created.isEmpty()) }
        // The server out of reach: it asks again a minute later, it doesn't stop.
        Fake().let { f -> val r = fresh(f); f.outputValue = 100_000; Remixer(f).step(r); f.now = r.nextAt
            f.unspent = null; Remixer(f).step(r)
            assertEquals(Remix.State.WAITING, r.state); assertEquals(f.now + 60, r.nextAt) }
        // Fees up on the chain: it goes on at the fee chosen, it doesn't stop.
        Fake().let { f -> val r = fresh(f, cap = 2.0); f.outputValue = 100_000; Remixer(f).step(r); f.now = r.nextAt
            f.estimate = 3.5; Remixer(f).step(r)
            assertEquals(Remix.State.SEATED, r.state); assertEquals(2.0, f.created.single().second, 0.0) }
        // Only a pool at exactly the fee chosen is joined: neither above nor below it.
        Fake().let { f -> val r = fresh(f, cap = 2.0); f.outputValue = 100_000; Remixer(f).step(r); f.now = r.nextAt
            f.pools = mutableListOf(pool(50_000, rate = 2.5)); Remixer(f).step(r)
            assertTrue(f.joined.isEmpty()); assertEquals(1, f.created.size) }
        Fake().let { f -> val r = fresh(f, cap = 2.0); f.outputValue = 100_000; Remixer(f).step(r); f.now = r.nextAt
            f.pools = mutableListOf(pool(50_000, rate = 1.5)); Remixer(f).step(r)
            assertTrue(f.joined.isEmpty()); assertEquals(1, f.created.size) }
        // Below a pool's minimum.
        Fake().let { f -> val r = fresh(f); f.outputValue = 10_100; Remixer(f).step(r); f.now = r.nextAt
            Remixer(f).step(r)
            assertEquals(Remix.State.STOPPED, r.state); assertEquals(Remix.BELOW_MINIMUM, r.reason) }
    }

    @Test fun firstRoundCancelledStopsAndTurnedOffMidRoundKeepsTheCoin() {
        val f = Fake()
        val r = Remix("r2", ScriptType.P2WPKH, cap = 2.0, rate = 2.0, minPeers = 2, maxPeers = 5, hours = 6, seat = "s", seatIndex = 3, seatScript = script(71))
        f.seats["s"] = seat(PoolSession.Phase.ABORTED, reason = "the pool expired")
        Remixer(f).step(r)
        assertEquals(Remix.State.STOPPED, r.state); assertEquals(Remix.FIRST_CANCELLED, r.reason)
        // Off while in a round: the round ends as any other, and its output is the coin it would go on with.
        val q = fresh(f); q.state = Remix.State.STOPPED; q.reason = Remix.OFF; f.outputValue = 100_000
        assertTrue(Remixer(f).step(q))
        assertEquals(Remix.State.STOPPED, q.state); assertNull(q.seat); assertNotNull(q.txid); assertEquals(1, q.rounds)
        assertTrue(f.created.isEmpty() && f.joined.isEmpty())
    }

    @Test fun savedAndReadBackAsItWas() {
        val f = Fake(); val r = fresh(f); f.outputValue = 100_000; Remixer(f).step(r)
        r.tried += "abc"; r.coinScript = script(70)
        val back = Remix.parseList(Remix.listJson(listOf(r))).single()
        assertEquals(r.toJson().toString(), back.toJson().toString())
        assertEquals(r.outpoint, back.outpoint)
        assertTrue(Remix.parseList("not json").isEmpty())
    }

    @Test fun createRateAndAmount() {
        val r = Remix("r", ScriptType.P2WPKH, cap = 3.0, rate = 2.0, minPeers = 2, maxPeers = 5, hours = 6)
        // Always the fee chosen, whatever the chain asks.
        assertEquals(3.0, Remixer.createRate(r, null)!!, 0.0)
        assertEquals(3.0, Remixer.createRate(r, 1.0)!!, 0.0)
        assertEquals(3.0, Remixer.createRate(r, 9.0)!!, 0.0)
        r.cap = 1.5 // changed on its card
        assertEquals(1.5, Remixer.createRate(r, 1.0)!!, 0.0)
        assertNull(Remixer.createAmount(10_050, 2.0, ScriptType.P2WPKH))
        assertNotNull(Remixer.createAmount(10_300, 2.0, ScriptType.P2WPKH))
    }

    // ------------------------------------------------------------------------------- the first pool again

    /** A first pool of two coins (one on a change address), from real keys of a test wallet. */
    private fun firstPool(private: Boolean = false): Triple<FirstPool, com.kilombino.pyblockwatch.crypto.Bip32Priv.ExtendedPrivKey,
        com.kilombino.pyblockwatch.crypto.Bip32Priv.ExtendedPrivKey> {
        val master = com.kilombino.pyblockwatch.crypto.Bip32Priv.fromSeed(ByteArray(32) { 7 })
        val recv = com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(master, "m/84'/0'/0'/0")
        val chg = com.kilombino.pyblockwatch.crypto.Bip32Priv.derivePath(master, "m/84'/0'/0'/1")
        val k1 = com.kilombino.pyblockwatch.crypto.Bip32Priv.deriveChild(recv, 4); val k2 = com.kilombino.pyblockwatch.crypto.Bip32Priv.deriveChild(chg, 9)
        val coins = listOf(CoinjoinTx.Coin("%064x".format(41), 0, 60_000, k1.publicKey()), CoinjoinTx.Coin("%064x".format(42), 1, 50_000, k2.publicKey()))
        val f = FirstPool(100_000, 2.0, 2, 5, 6, 2, coins, listOf("m/84'/0'/0'/0/4", "m/84'/0'/0'/1/9"), script(77), script(78), private)
        return Triple(f, recv, chg)
    }

    private fun waitingFirst(f: Fake, first: FirstPool?) = Remix("rf", ScriptType.P2WPKH, cap = 2.0, rate = 2.0, minPeers = 2, maxPeers = 5,
        hours = 6, seat = "s0", seatIndex = 33, seatScript = first?.mixScript ?: script(77), first = first).also {
        f.seats["s0"] = seat(PoolSession.Phase.ABORTED, reason = "the pool expired")
    }

    @Test fun firstPoolWithNobodyOpensAgainTheSame() {
        val (fp, _, _) = firstPool()
        val f = Fake(); val r = waitingFirst(f, fp)
        // Expired with nobody: not stopped, it waits and opens the same pool again.
        assertTrue(Remixer(f).step(r)); assertEquals(Remix.State.WAITING, r.state); assertNull(r.txid)
        assertEquals(f.now + 30, r.nextAt)
        f.now = r.nextAt
        assertTrue(Remixer(f).step(r)); assertEquals(Remix.State.SEATED, r.state)
        assertEquals(1, f.reopened.size); assertTrue(f.reopened.single() === fp)
        assertTrue(f.joined.isEmpty() && f.created.isEmpty())
        // The same mixed-output address and receive index as before.
        assertTrue(r.seatScript!!.contentEquals(fp.mixScript)); assertEquals(33, r.seatIndex)
        // Again with nobody, again the same pool.
        f.seats[r.seat!!] = seat(PoolSession.Phase.ABORTED, reason = "the creator closed the pool")
        Remixer(f).step(r); f.now = r.nextAt; Remixer(f).step(r)
        assertEquals(2, f.reopened.size); assertEquals(Remix.State.SEATED, r.state)
        // Now it gets a round: the mixed coin goes on to the next pools, and the first pool is dropped.
        f.seats[r.seat!!] = seat(PoolSession.Phase.CONFIRMED, txid = "cc".repeat(32)); f.outputValue = 100_000
        assertTrue(Remixer(f).step(r)); assertEquals(Remix.State.WAITING, r.state); assertEquals(1, r.rounds); assertNull(r.first)
        assertEquals(33, r.index); assertEquals(100_000L, r.value)
        f.now = r.nextAt; Remixer(f).step(r)
        assertEquals(1, f.created.size); assertEquals(2, f.reopened.size)
    }

    @Test fun firstPoolCoinsSpentStopIt() {
        val (fp, _, _) = firstPool()
        val f = Fake(); val r = waitingFirst(f, fp)
        Remixer(f).step(r); f.now = r.nextAt
        f.firstUnspent = null // the server out of reach: asks again later, doesn't stop
        Remixer(f).step(r); assertEquals(Remix.State.WAITING, r.state); assertEquals(f.now + 60, r.nextAt)
        f.now = r.nextAt; f.firstUnspent = false
        Remixer(f).step(r)
        assertEquals(Remix.State.STOPPED, r.state); assertEquals(Remix.MOVED, r.reason); assertTrue(f.reopened.isEmpty())
    }

    @Test fun aPrivateOrOldFirstPoolStillStops() {
        val f = Fake(); val r = waitingFirst(f, firstPool(private = true).first)
        Remixer(f).step(r); assertEquals(Remix.State.STOPPED, r.state); assertEquals(Remix.FIRST_CANCELLED, r.reason)
        val g = Fake(); val q = waitingFirst(g, null)
        Remixer(g).step(q); assertEquals(Remix.State.STOPPED, q.state); assertEquals(Remix.FIRST_CANCELLED, q.reason)
    }

    @Test fun firstPoolSeatRebuiltFromTheBranchKeys() {
        val (fp, recv, chg) = firstPool()
        assertTrue(fp.needsChangeKey)
        val pick = Perpetual.firstPick(fp, ScriptType.P2WPKH) { if (it == 0) recv else chg }
        assertEquals(fp.coins.map { it.outpoint }, pick.coins.map { "${it.txid}:${it.vout}" })
        assertTrue(pick.coins.indices.all { pick.coins[it].coinKey.publicKey().contentEquals(fp.coins[it].pubkey) })
        assertTrue(pick.mixScripts.single().contentEquals(fp.mixScript)); assertTrue(pick.changeScript.contentEquals(fp.changeScript))
        // The same seat as before: same terms, coins, change.
        val np = PoolSession.newPool(fp.amount, fp.rate, fp.maxPeers, fp.hours, fp.minPeers, maxInputs = fp.maxInputs, v3 = true)
        val st = PoolSession.newState(np.terms, true, np.secret, pick.txCoins(), pick.coins.map { it.coinKey.key }, pick.coins.map { it.coinPath },
            pick.mixScripts, pick.changeScript, creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        assertEquals(110_000L - 100_000 - CoinjoinTx.feeShare(2.0, true, 2), st.seat.changeValue)
        // Without the change branch's key it can't sign the coin on a change address.
        try { Perpetual.firstPick(fp, ScriptType.P2WPKH) { if (it == 0) recv else null }; org.junit.Assert.fail() } catch (_: IllegalStateException) {}
        // Nor with another wallet's keys.
        try { Perpetual.firstPick(fp, ScriptType.P2WPKH) { chg }; org.junit.Assert.fail() } catch (_: IllegalStateException) {}
        // Kept and read back as it was.
        val back = FirstPool.parse(JSONObject(fp.toJson().toString()))
        assertEquals(fp.toJson().toString(), back.toJson().toString())
        val r = Remix("x", ScriptType.P2WPKH, cap = 2.0, rate = 2.0, minPeers = 2, maxPeers = 5, hours = 6, first = fp)
        assertEquals(fp.toJson().toString(), Remix.parseList(Remix.listJson(listOf(r))).single().first!!.toJson().toString())
    }

    @Test fun branchKeysKeptOnlyWhileNeeded() {
        val (fp, _, _) = firstPool()
        fun rx(id: String, st: Remix.State, first: FirstPool?, seat: String? = null) =
            Remix(id, ScriptType.P2WPKH, cap = 2.0, rate = 2.0, minPeers = 2, maxPeers = 5, hours = 6, state = st, seat = seat, first = first)
        // Waiting for its first round with a coin on a change address: both keys.
        assertEquals(setOf(ScriptType.P2WPKH) to setOf(ScriptType.P2WPKH), Perpetual.keysNeeded(listOf(rx("a", Remix.State.WAITING, fp))))
        // First round done: only the receive branch's.
        assertEquals(setOf(ScriptType.P2WPKH) to emptySet<ScriptType>(), Perpetual.keysNeeded(listOf(rx("a", Remix.State.WAITING, null))))
        // Stopped (and in no round): none.
        assertEquals(emptySet<ScriptType>() to emptySet<ScriptType>(), Perpetual.keysNeeded(listOf(rx("a", Remix.State.STOPPED, fp))))
        // First pool's coins only on receive addresses: the change branch's is not needed.
        val recvOnly = FirstPool(fp.amount, fp.rate, 2, 5, 6, 1, fp.coins.take(1), fp.coinPaths.take(1), fp.mixScript, null, false)
        assertEquals(emptySet<ScriptType>(), Perpetual.keysNeeded(listOf(rx("a", Remix.State.SEATED, recvOnly, "s"))).second)
    }
}
