package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.Nip44
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Coinjoin building blocks: NIP-44 against the official vectors (paulmillr/nip44), NIP-01
 * event ids, and a two-person coinjoin signed, checked and assembled end to end.
 */
class CoinjoinTest {
    private fun hex(s: String) = Hashes.hexToBytes(s)
    private fun pubX(sec: String) = Secp256k1.xOnly(Secp256k1.multiply(BigInteger(sec, 16), Secp256k1.G))

    @Test fun nip44ConversationKey() {
        assertEquals("3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1",
            Nip44.conversationKey(BigInteger("315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268", 16),
                hex("c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133")).toHex())
        assertEquals("4d14f36e81b8452128da64fe6f1eae873baae2f444b02c950b90e43553f2178b",
            Nip44.conversationKey(BigInteger("a1e37752c9fdc1273be53f68c5f74be7c8905728e8de75800b94262f9497c86e", 16),
                hex("03bb7947065dde12ba991ea045132581d0954f042c84e06d8c00066e23c1a800")).toHex())
    }

    @Test fun nip44MessageKeysAndPadding() {
        val (k, n, h) = Nip44.messageKeys(hex("a1a3d60f3470a8612633924e91febf96dc5366ce130f658b1f0fc652c20b3b54"),
            hex("e1e6f880560d6d149ed83dcc7e5861ee62a5ee051f7fde9975fe5d25d2a02d72"))
        assertEquals("f145f3bed47cb70dbeaac07f3a3fe683e822b3715edb7c4fe310829014ce7d76", k.toHex())
        assertEquals("c4ad129bb01180c0933a160c", n.toHex())
        assertEquals("027c1db445f05e2eee864a0975b0ddef5b7110583c8c192de3732571ca5838c4", h.toHex())
        for ((len, padded) in listOf(16 to 32, 32 to 32, 33 to 64, 37 to 64, 45 to 64, 49 to 64, 64 to 64,
                65 to 96, 100 to 128, 111 to 128, 200 to 224, 250 to 256))
            assertEquals("len $len", padded, Nip44.paddedLen(len))
    }

    @Test fun nip44EncryptDecrypt() {
        data class V(val sec1: String, val sec2: String, val nonce: String, val plain: String, val payload: String)
        val vs = listOf(
            V("0000000000000000000000000000000000000000000000000000000000000001", "0000000000000000000000000000000000000000000000000000000000000002",
                "0000000000000000000000000000000000000000000000000000000000000001", "a",
                "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"),
            V("0000000000000000000000000000000000000000000000000000000000000002", "0000000000000000000000000000000000000000000000000000000000000001",
                "f00000000000000000000000000000f00000000000000000000000000000000f", "🍕🫃",
                "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj"),
            V("5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a", "4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d",
                "b635236c42db20f021bb8d1cdff5ca75dd1a0cc72ea742ad750f33010b24f73b",
                "表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀",
                "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs="),
        )
        for (v in vs) {
            val ck = Nip44.conversationKey(BigInteger(v.sec1, 16), pubX(v.sec2))
            assertEquals(v.payload, Nip44.encrypt(v.plain, ck, hex(v.nonce)))
            assertEquals(v.plain, Nip44.decrypt(v.payload, ck))
            // The other side derives the same key and reads it too.
            assertEquals(v.plain, Nip44.decrypt(v.payload, Nip44.conversationKey(BigInteger(v.sec2, 16), pubX(v.sec1))))
        }
        val ck = Nip44.conversationKey(BigInteger.ONE, pubX("02"))
        val p = Nip44.encrypt("hola", ck)
        val bad = p.substring(0, 10) + (if (p[10] == 'A') 'B' else 'A') + p.substring(11)
        assertNull(Nip44.decrypt(bad, ck))
    }

    @Test fun nostrEventIdAndSignature() {
        // Serialisation exactly as NIP-01 says: no escaping of "/", escapes for quotes and newlines.
        assertEquals("[0,\"ab\",1,1,[[\"p\",\"x/y\"]],\"a\\\"b\\nc/d\"]",
            NostrEvent.serialize("ab", 1, 1, listOf(listOf("p", "x/y")), "a\"b\nc/d"))
        val k = BigInteger("3", 16)
        val ev = NostrEvent.sign(k, 2023, listOf(listOf("p", "00".repeat(32))), "contenido con / y \"comillas\"", 1700000000)
        assertTrue(ev.verify())
        assertFalse(ev.copy(content = ev.content + " ").verify())
        assertEquals(64, ev.id.length); assertEquals(128, ev.sig.length)
    }

    private class Person(val seed: Int, val value: Long) {
        val key: BigInteger = BigInteger.valueOf(1000L + seed)
        val pub: ByteArray = Secp256k1.compress(Secp256k1.multiply(key, Secp256k1.G))
        val coin = CoinjoinTx.Coin(Hashes.sha256(byteArrayOf(seed.toByte())).toHex(), seed, value, pub)
        private fun script(n: Int) = Address.scriptPubKey(
            Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(5000L + 100 * seed + n), Secp256k1.G)), ScriptType.P2WPKH)
        val mix = script(1)
        val changeScript = script(2)
    }

    @Test fun twoPersonCoinjoin() {
        val amount = 100_000L; val rate = 2.0
        val a = Person(1, 150_000); val b = Person(2, amount + CoinjoinTx.feeShare(rate, false))
        val ca = CoinjoinTx.change(a.value, amount, rate)!!; val cb = CoinjoinTx.change(b.value, amount, rate)!!
        assertEquals(150_000 - amount - CoinjoinTx.feeShare(rate, true), ca)
        assertEquals(0L, cb) // exactly the minimum: no change
        assertNull(CoinjoinTx.change(amount, amount, rate)) // too small

        val plan = CoinjoinTx.plan(listOf(a.coin, b.coin), listOf(a.mix, b.mix),
            listOf(TxBuilder.Output(a.changeScript, ca)), amount)
        assertEquals(3, plan.outputs.size)
        assertEquals(2, plan.outputs.count { it.value == amount })
        assertNull(CoinjoinTx.checkOurs(plan, a.mix, TxBuilder.Output(a.changeScript, ca), amount, rate))
        assertNull(CoinjoinTx.checkOurs(plan, b.mix, null, amount, rate))
        assertNotNull(CoinjoinTx.checkOurs(plan, b.changeScript, null, amount, rate)) // not our output

        // Order does not depend on who lists what first: same plan, same txid.
        val plan2 = CoinjoinTx.plan(listOf(b.coin, a.coin), listOf(b.mix, a.mix),
            listOf(TxBuilder.Output(a.changeScript, ca)), amount)
        assertEquals(CoinjoinTx.txid(plan), CoinjoinTx.txid(plan2))

        val sa = CoinjoinTx.sign(plan, a.coin, a.key); val sb = CoinjoinTx.sign(plan2, b.coin, b.key)
        assertTrue(CoinjoinTx.verify(plan, a.coin, sa)); assertTrue(CoinjoinTx.verify(plan, b.coin, sb))
        assertFalse(CoinjoinTx.verify(plan, a.coin, sb)) // b's signature is not a's
        // A signature made for another set of outputs does not fit this transaction.
        val other = CoinjoinTx.plan(listOf(a.coin, b.coin), listOf(a.mix, b.changeScript),
            listOf(TxBuilder.Output(a.changeScript, ca)), amount)
        assertFalse(CoinjoinTx.verify(other, a.coin, sa))

        val tx = CoinjoinTx.assemble(plan, mapOf(a.coin.outpoint to sa, b.coin.outpoint to sb))
        assertEquals(CoinjoinTx.txid(plan), tx.txid)
        // Fee: each paid their share; b's leftover (none) and a's share add up.
        assertEquals(CoinjoinTx.feeShare(rate, true) + CoinjoinTx.feeShare(rate, false), plan.fee)
        assertTrue("rate ${plan.fee.toDouble() / tx.vbytes}", plan.fee.toDouble() / tx.vbytes >= rate)
    }

    @Test fun ownershipProof() {
        val a = Person(3, 200_000)
        val proof = CoinjoinTx.proveOwnership("pool1", "ab".repeat(32), a.coin, a.key)
        assertTrue(CoinjoinTx.checkOwnership("pool1", "ab".repeat(32), a.coin, proof))
        assertFalse(CoinjoinTx.checkOwnership("pool2", "ab".repeat(32), a.coin, proof))
        assertFalse(CoinjoinTx.checkOwnership("pool1", "cd".repeat(32), a.coin, proof))
        assertFalse(CoinjoinTx.checkOwnership("pool1", "ab".repeat(32), a.coin.copy(value = 1), proof))
    }

    @Test fun plansRefuseCheating() {
        val amount = 100_000L
        val a = Person(4, 200_000); val b = Person(5, 200_000)
        // Same coin twice, a mix output paying an input address, an output used twice.
        runCatching { CoinjoinTx.plan(listOf(a.coin, a.coin), listOf(a.mix, b.mix), emptyList(), amount) }.also { assertTrue(it.isFailure) }
        runCatching { CoinjoinTx.plan(listOf(a.coin, b.coin), listOf(a.mix, b.coin.script), emptyList(), amount) }.also { assertTrue(it.isFailure) }
        runCatching { CoinjoinTx.plan(listOf(a.coin, b.coin), listOf(a.mix, a.mix), emptyList(), amount) }.also { assertTrue(it.isFailure) }
    }
    /**
     * Against a real node: with KILOJOIN_REGTEST=<file> holding "seed txid vout value" lines
     * (coins of [Person] keys funded on a BLAKE2b regtest), write the signed coinjoin to
     * <file>.tx so a script can testmempoolaccept and mine it. Skipped otherwise.
     */
    @Test fun regtestCoinjoin() {
        val path = System.getenv("KILOJOIN_REGTEST") ?: return
        val amount = 100_000L; val rate = 3.0
        val people = java.io.File(path).readLines().filter { it.isNotBlank() }.map { l ->
            val (seed, txid, vout, value) = l.trim().split(" ")
            val p = Person(seed.toInt(), value.toLong())
            p to CoinjoinTx.Coin(txid, vout.toInt(), value.toLong(), p.pub)
        }
        val changes = people.mapNotNull { (p, c) ->
            CoinjoinTx.change(c.value, amount, rate)!!.takeIf { it > 0 }?.let { TxBuilder.Output(p.changeScript, it) }
        }
        val plan = CoinjoinTx.plan(people.map { it.second }, people.map { it.first.mix }, changes, amount)
        val sigs = people.associate { (p, c) -> c.outpoint to CoinjoinTx.sign(plan, c, p.key) }
        sigs.forEach { (op, sig) -> assertTrue(CoinjoinTx.verify(plan, plan.coins.first { it.outpoint == op }, sig)) }
        java.io.File("$path.tx").writeText(CoinjoinTx.assemble(plan, sigs).rawHex)
    }
}
