package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.BlindRsa
import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Kilojoin v3: its terms, what its creator keeps, and the seat MACs. */
class CoinjoinV3Test {
    private fun key(n: Long) = BigInteger.valueOf(n)
    private fun pub(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    private fun coin(n: Long, value: Long, type: ScriptType) = CoinjoinTx.Coin("%064x".format(n), 0, value, pub(key(n)), type)
    private fun script(n: Long, type: ScriptType) = Address.scriptPubKey(pub(key(n)), type)

    @Test fun termsCarryTheCreatorAndBlindKey() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true)
        assertEquals(Protocol.V3, np.terms.version)
        assertNotNull(np.creatorSecret); assertNotNull(np.blindPriv)
        assertEquals(np.terms.creatorPub, NostrEvent.pubOf(BigInteger(np.creatorSecret, 16)))
        val ev = NostrEvent.sign(BigInteger(np.secret, 16), Protocol.KIND_POOL,
            listOf(listOf("d", np.terms.id), listOf("t", Protocol.TAG)), np.terms.toJson().toString())
        val back = Protocol.Terms.parse(ev)!!
        assertTrue(back.v3)
        assertEquals(np.terms.creatorPub, back.creatorPub)
        assertEquals(np.terms.blindKey, back.blindKey)
        // The private half fits the public one.
        val kp = BlindRsa.KeyPair.decode(np.blindPriv!!)
        assertEquals(np.terms.blindKey, kp.public.encode())

        // A v3 announcement without its keys, or with the pool key as the creator's, is not listed.
        val noKey = JSONObject(np.terms.toJson().toString()).apply { remove("blind_key") }
        assertNull(Protocol.Terms.parse(NostrEvent.sign(BigInteger(np.secret, 16), Protocol.KIND_POOL,
            listOf(listOf("d", np.terms.id)), noKey.toString())))
        val same = JSONObject(np.terms.toJson().toString()).put("creator", np.terms.poolPub)
        assertNull(Protocol.Terms.parse(NostrEvent.sign(BigInteger(np.secret, 16), Protocol.KIND_POOL,
            listOf(listOf("d", np.terms.id)), same.toString())))
    }

    @Test fun creatorStateKeepsItsKeys() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true)
        val c = coin(11, 150_000, ScriptType.P2WPKH)
        val st = PoolSession.newState(np.terms, true, np.secret, listOf(c), listOf(key(11)), listOf("m/84'/0'/0'/0/1"),
            script(41, ScriptType.P2WPKH), script(31, ScriptType.P2WPKH), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        val back = PoolSession.State.parse(JSONObject(st.toJson().toString()))
        assertEquals(np.creatorSecret, back.creatorSecret)
        assertEquals(np.blindPriv, back.blindPriv)
        assertTrue(back.terms.v3)
        assertEquals(np.terms.blindKey, back.terms.blindKey)
        // A member keeps none of them.
        val member = PoolSession.newState(np.terms, false, null, listOf(c), listOf(key(11)), listOf("m"),
            script(41, ScriptType.P2WPKH), script(31, ScriptType.P2WPKH))
        assertNull(member.creatorSecret); assertNull(member.blindPriv)
    }

    @Test fun historyKeepsWhenARoundWentOutAndEnded() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true)
        val c = coin(11, 150_000, ScriptType.P2WPKH)
        val st = PoolSession.newState(np.terms, false, null, listOf(c), listOf(key(11)), listOf("m"),
            script(41, ScriptType.P2WPKH), script(31, ScriptType.P2WPKH))
        st.phase = PoolSession.Phase.CONFIRMED; st.sentAt = 1_800_000_000; st.endedAt = 1_800_000_600
        val back = PoolSession.State.parse(JSONObject(st.toJson().toString()))
        assertEquals(1_800_000_000L, back.sentAt); assertEquals(1_800_000_600L, back.endedAt)
        // A round saved before the times were kept: it went out when its phase deadline was set.
        val old = JSONObject(st.toJson().toString()).apply { remove("sent_at"); remove("ended_at"); put("phase_deadline", 1_700_000_000L) }
        assertEquals(1_700_000_000L, PoolSession.State.parse(old).sentAt)
        assertEquals(0L, PoolSession.State.parse(old).endedAt)
        // One that never went out has no time of sending.
        old.put("phase", "ABORTED")
        assertEquals(0L, PoolSession.State.parse(old).sentAt)
    }

    @Test fun seatMacsAreBoundToWhatTheySay() {
        val token = "00112233445566778899aabbccddeeff"
        val m = Protocol.mac(token, "vote", "v1", true)
        assertTrue(Protocol.macOk(token, m, "vote", "v1", true))
        assertFalse(Protocol.macOk(token, m, "vote", "v1", false))     // a yes is not a no
        assertFalse(Protocol.macOk(token, m, "vote", "v2", true))      // nor another vote
        assertFalse(Protocol.macOk("ff" + token.drop(2), m, "vote", "v1", true))
        assertFalse(Protocol.macOk(null, m, "vote", "v1", true))
    }

    @Test fun rosterSeatCarriesItsProofs() {
        val c = coin(12, 150_000, ScriptType.P2TR)
        val seat = Protocol.Seat("th", listOf(c), null, 0, joinPub = "ab".repeat(32), proofs = listOf("3044"))
        val back = Protocol.Seat.parse(JSONObject(seat.toJson().toString()), ScriptType.P2TR)
        assertEquals(seat.joinPub, back.joinPub)
        assertEquals(seat.proofs, back.proofs)
        assertFalse(Protocol.Seat("th", listOf(c), null, 0).toJson().has("join_pub"))  // v1/v2 rosters unchanged
    }
}
