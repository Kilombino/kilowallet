package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Psbt
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * PSBT export/import for the watch-only wallet. The signer tests run against a real Bitcoin
 * Knots (BLAKE2b) wallet holding the BIP-39 test seed "abandon … about" when KNOTS_PSBT_CLI is
 * set to a bitcoin-cli command line for that wallet (e.g. "bitcoin-cli -datadir=… -rpcwallet=psbt-test").
 */
class PsbtTest {
    private val seed = Hashes.hexToBytes("5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4")
    private val master = Bip32Priv.fromSeed(seed)
    private val fp = Hashes.hexToBytes("73c5da0a")

    private fun key(path: String) = Bip32Priv.derivePath(master, path).key
    private fun pub(path: String) = Secp256k1.compress(Secp256k1.publicPoint(key(path)))
    private fun origin(path: String) = Psbt.Origin.parse("[73c5da0a/$path]")

    private fun coin(i: Int, type: ScriptType, path: String, value: Long) =
        Psbt.Coin("%064x".format(i + 1), i, value, type, pub(path), origin(path))

    private val coins = listOf(
        coin(0, ScriptType.P2WPKH, "84'/0'/0'/0/0", 50_000),
        coin(1, ScriptType.P2SH_P2WPKH, "49'/0'/0'/0/1", 30_000),
        coin(2, ScriptType.P2TR, "86'/0'/0'/0/2", 20_000),
    )
    private val outputs = listOf(
        TxBuilder.Output(Address.decodeToScriptPubKey("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"), 60_000),
        TxBuilder.Output(Address.scriptPubKey(pub("86'/0'/0'/1/0"), ScriptType.P2TR), 39_000),
    )
    private val change = mapOf(1 to Psbt.Change(pub("86'/0'/0'/1/0"), ScriptType.P2TR, origin("86'/0'/0'/1/0")))

    private fun knots(vararg args: String): String? {
        val cli = System.getenv("KNOTS_PSBT_CLI") ?: return null
        val p = ProcessBuilder(cli.split(" ") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { out }
        return out
    }

    @Test fun roundTripAndOrigins() {
        val b = Psbt.create(coins, outputs, change)
        val p = Psbt.parse(b)
        assertEquals(3, p.inputs.size); assertEquals(2, p.outputs.size)
        assertTrue(Psbt.serialize(p).contentEquals(b))
        assertEquals(listOf(0x80000054L, 0x80000000L, 0x80000000L), Psbt.Origin.parse("[73C5DA0A/84h/0'/0H]")!!.path)
        assertTrue(Psbt.Origin.parse(" ") == null)
    }

    @Test fun unsignedOrDifferentIsRefused() {
        val b = Psbt.create(coins, outputs, change)
        try { Psbt.finish(b, b, coins, outputs); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not signed")) }
        val other = Psbt.create(coins, listOf(outputs[0], TxBuilder.Output(outputs[1].scriptPubKey, 38_000)), change)
        try { Psbt.finish(b, other, coins, outputs); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("not the one you exported")) }
    }

    @Test fun knotsSignsUnifiedAndWeAccept() {
        val b = Psbt.create(coins, outputs, change)
        val r = JSONObject(knots("walletprocesspsbt", Psbt.base64(b)) ?: return assumeTrue(false))
        assertTrue(r.toString(), r.getBoolean("complete"))
        val signed = Psbt.finish(b, Psbt.decodeText(r.getString("psbt")), coins, outputs)
        // The same ECDSA signatures a hot wallet makes (RFC 6979 + Core's low-R grind):
        // compare the non-Taproot witnesses with our own signing.
        val ours = TxBuilder.build(coins.map { c ->
            val path = mapOf(0 to "84'/0'/0'/0/0", 1 to "49'/0'/0'/0/1", 2 to "86'/0'/0'/0/2")[c.vout]!!
            TxBuilder.Input(c.txid, c.vout, c.value, key(path), c.pubkey, c.sequence, c.type)
        }, outputs, unified = true, grindLowR = true)
        assertEquals(ours.txid, signed.txid)
        val final = JSONObject(knots("finalizepsbt", r.getString("psbt"))!!)
        // Knots' own finished transaction differs only in the Taproot signature (random aux).
        assertEquals(final.getString("hex").length, signed.rawHex.length)
        assertEquals(final.getString("hex").substring(0, 300), signed.rawHex.substring(0, 300))
    }

    @Test fun oldStyleSignatureIsRefused() {
        // A signer that ignores the 0x21 request and signs plain ALL (BIP-143) makes a signature
        // that is also valid on the SHA-256 chain. (Knots never does: on this chain its wallet
        // signs unified even when asked for ALL.) Forge one and check it is refused.
        val c = coins.take(1); val o = outputs.take(1)
        val b = Psbt.create(c, o)
        val input = TxBuilder.Input(c[0].txid, c[0].vout, c[0].value, key("84'/0'/0'/0/0"), c[0].pubkey, c[0].sequence, c[0].type)
        val sig = TxBuilder.witnessSignature(2, listOf(input), o, 0, 0, unified = false)
        val p = Psbt.parse(b)
        val forged = Psbt.serialize(Psbt.Parsed(p.global, listOf(p.inputs[0] + (byteArrayOf(0x02) + c[0].pubkey to sig)), p.outputs))
        try { Psbt.finish(b, forged, c, o); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("old way")) }
        // And a unified signature from the right key passes.
        val good = TxBuilder.witnessSignature(2, listOf(input), o, 0, 0, unified = true)
        val ok = Psbt.serialize(Psbt.Parsed(p.global, listOf(p.inputs[0] + (byteArrayOf(0x02) + c[0].pubkey to good)), p.outputs))
        Psbt.finish(b, ok, c, o)
        // A unified signature over a different message does not.
        val wrong = TxBuilder.witnessSignature(2, listOf(input.copy(value = 49_999)), o, 0, 0, unified = true)
        val bad = Psbt.serialize(Psbt.Parsed(p.global, listOf(p.inputs[0] + (byteArrayOf(0x02) + c[0].pubkey to wrong)), p.outputs))
        try { Psbt.finish(b, bad, c, o); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("does not verify")) }
    }
}
