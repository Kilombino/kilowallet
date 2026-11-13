package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.joinblaket.Vectors.hex
import com.kilombino.pyblockwatch.joinblaket.Vectors.objects
import com.kilombino.pyblockwatch.joinblaket.Vectors.strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Vector section `session`: every PRIVMSG frame of one maker's exchange, rebuilt byte for byte
 * from the generator's fixed keys and nonces, and decoded from the taker's side.
 */
class SessionTest {
    private val v = Vectors.section("session")
    private val enc = Vectors.section("encryption")
    private val txv = Vectors.section("transaction")
    private val taker = NickIdentity(Vectors.big(Vectors.section("nick").getString("private_key")))
    private val maker = NickIdentity(Vectors.key("maker1-nick"))
    private val takerBox = Nacl.Box(hex(enc.getString("taker_private")), hex(enc.getString("maker_public")))
    private val makerBox = Nacl.Box(hex(enc.getString("maker_private")), hex(enc.getString("taker_public")))
    private fun nonce(label: String) = Vectors.h(label).copyOfRange(0, 24)

    private fun incoming(frame: String): Session.Incoming = Session.parsePrivmsg(Wire.parse(frame))!!

    @Test fun nicks() {
        assertEquals(v.getString("taker_nick"), taker.nick)
        assertEquals(v.getString("maker_nick"), maker.nick)
    }

    @Test fun fill() {
        val f = v.getJSONObject("fill")
        val c = Podle.generate(Vectors.big(Vectors.section("podle").getString("private_key")), Vectors.section("podle").getString("utxo"), 0)
        val data = Session.fillData(0, txv.getLong("cj_amount"), Nacl.publicKey(hex(enc.getString("taker_private"))), c.wire)
        assertEquals(f.getString("data"), data)
        assertEquals(f.getString("frame"), Session.privmsg(taker, maker.nick, "fill", data))
        val m = incoming(f.getString("frame"))
        assertTrue(m.verified); assertEquals("fill", m.command); assertEquals(data, m.data)
    }

    @Test fun pubkey() {
        val p = v.getJSONObject("pubkey")
        assertEquals(p.getString("frame"), Session.privmsg(maker, taker.nick, "pubkey", p.getString("data")))
        val m = incoming(p.getString("frame"))
        assertTrue(Session.accepts(m, taker.nick, listOf(maker.nick)))
        assertFalse(Session.accepts(m, "J5someoneElseOOO", listOf(maker.nick)))
        assertFalse(Session.accepts(m, taker.nick, listOf("J5otherMakerOOOO")))
        val pk = Session.parsePubkey(m.data)
        assertEquals(enc.getString("maker_public"), pk.naclPublic.toHex())
        assertEquals(setOf("neutrino_compat"), pk.features)
        // A relayed frame claiming another sender fails verification.
        val forged = Wire.parse(p.getString("frame")).let { Wire.Frame(it.type, it.line.replaceFirst(maker.nick, "J5CmDejLa1bhfAsp")) }
        assertFalse(Session.parsePrivmsg(forged)!!.verified)
    }

    @Test fun auth() {
        val a = v.getJSONObject("auth")
        val ct = Session.authData(takerBox, a.getString("plaintext"), hex(a.getString("nonce")))
        assertEquals(a.getString("ciphertext"), ct)
        assertEquals(a.getString("frame"), Session.privmsg(taker, maker.nick, "auth", ct))
        assertEquals(Vectors.section("podle").getString("revelation_extended"), a.getString("plaintext"))
        assertEquals(a.getString("plaintext"), makerBox.decryptString(incoming(a.getString("frame")).data))
    }

    private fun ioauth(): Session.IoAuth {
        val f = v.getJSONObject("ioauth")
        val m = incoming(f.getString("frame"))
        assertTrue(m.verified); assertEquals("ioauth", m.command)
        return Session.decodeIoauth(takerBox, m.data)
    }

    @Test fun ioauthFrameAndDecoding() {
        val f = v.getJSONObject("ioauth")
        assertEquals(f.getString("btc_sig"), JmSig.signMessage(Vectors.key("maker1-utxo-a"), enc.getString("maker_public").toByteArray()))
        assertEquals(f.getString("ciphertext"), makerBox.encrypt(f.getString("plaintext"), nonce("nonce-ioauth")))
        assertEquals(f.getString("frame"), Session.privmsg(maker, taker.nick, "ioauth", f.getString("ciphertext")))
        val io = ioauth()
        assertEquals(f.getString("utxo_list"), Utxos.formatList(io.utxos, extended = true))
        assertEquals(f.getJSONArray("parsed_utxos").strings(), io.utxos.map { it.extended })
        assertEquals(f.getString("auth_pub"), io.authPub)
        assertEquals(f.getString("btc_sig"), io.btcSig)
        val m1 = txv.getJSONObject("makers").getJSONObject(maker1)
        assertEquals(m1.getString("cj_addr"), io.cjAddress); assertEquals(m1.getString("change_addr"), io.changeAddress)
        assertEquals(f.getBoolean("btc_sig_valid"), JmSig.verifyMessage(enc.getString("maker_public"), io.btcSig, hex(io.authPub)))
    }

    private val maker1 = "J5maker1OOOOOOOO"

    /** Our chain view: the transaction vector's maker1 UTXOs, confirmed. */
    private fun chain(confirmations: Int = 10, overrides: Map<String, Long> = emptyMap()): (String, Long) -> IoauthCheck.ChainUtxo? {
        val utxos = txv.getJSONObject("makers").getJSONObject(maker1).getJSONArray("utxos").objects()
        return { txid, vout ->
            utxos.firstOrNull { it.getString("txid") == txid && it.getLong("vout") == vout }?.let {
                IoauthCheck.ChainUtxo(overrides[txid] ?: it.getLong("value"), hex(it.getString("scriptpubkey")), confirmations)
            }
        }
    }

    private fun check(io: Session.IoAuth = ioauth(), lookup: (String, Long) -> IoauthCheck.ChainUtxo? = chain(),
                      taken: Set<Pair<String, Long>> = emptySet(), cjfee: Long = 6000, max: Int = 15) =
        IoauthCheck.check(io, hex(enc.getString("maker_public")), maker1, txv.getLong("cj_amount"), 0, cjfee, taken, lookup, max)

    @Test fun ioauthChecksPass() {
        val r = check() as IoauthCheck.Result.Ok
        val m1 = txv.getJSONObject("makers").getJSONObject(maker1)
        assertEquals(m1.getJSONArray("utxos").objects().map { it.getString("txid") to it.getLong("value") }, r.part.utxos.map { it.txid to it.value })
        assertEquals(6000L, r.part.cjfee)
    }

    @Test fun ioauthChecksFail() {
        val io = ioauth()
        fun fail(r: IoauthCheck.Result) = (r as IoauthCheck.Result.Fail).reason
        val otherSig = JmSig.signMessage(Vectors.key("maker1-utxo-a"), "not the nacl key".toByteArray())
        assertTrue(fail(check(io.copy(btcSig = otherSig))).contains("btc_sig"))
        assertTrue(fail(check(io, max = 1)).contains("inputs"))
        assertTrue(fail(check(io.copy(utxos = io.utxos + io.utxos[0]))).contains("duplicate"))
        assertTrue(fail(check(io, chain(confirmations = 0))).contains("unconfirmed"))
        assertTrue(fail(check(io, { _, _ -> null })).contains("spent"))
        val down = check(io, { _, _ -> throw java.io.IOException("node down") }) as IoauthCheck.Result.Fail
        assertTrue(down.unavailable)
        assertTrue(fail(check(io, taken = setOf(io.utxos[1].txid to io.utxos[1].vout))).contains("already used"))
        // Our lookup's value counts, not anything the maker says: a small input leaves dust change.
        assertTrue(fail(check(io, chain(overrides = mapOf(io.utxos[0].txid to 15_000_000L)))).contains("change"))
        // A valid btc_sig by a key that owns none of the declared inputs.
        val strangerSig = JmSig.signMessage(taker.privateKey, enc.getString("maker_public").toByteArray())
        assertTrue(fail(check(io.copy(authPub = taker.pubkeyHex, btcSig = strangerSig))).contains("authentication key"))
        assertTrue(fail(check(io.copy(cjAddress = "bc1qnotanaddress"))).contains("address"))
        assertTrue(fail(check(io.copy(changeAddress = "tb1qkw3fk3nr780h7lwcqvmn7drrwvxedc63g9mfm7"))).contains("address"))
    }

    @Test fun txSigPushError() {
        val t = v.getJSONObject("tx")
        val unsigned = Base64.getDecoder().decode(t.getString("plaintext"))
        assertEquals(txv.getString("unsigned_tx_b64"), t.getString("plaintext"))
        val ct = Session.txData(takerBox, unsigned, hex(t.getString("nonce")))
        assertEquals(t.getString("ciphertext"), ct)
        assertEquals(t.getString("frame"), Session.privmsg(taker, maker.nick, "tx", ct))
        assertEquals(t.getString("plaintext"), makerBox.decryptString(incoming(t.getString("frame")).data))

        val sigs = v.getJSONArray("sig").objects()
        val makerInputs = txv.getJSONArray("inputs").objects().filter { it.getString("owner") == maker1 }
        val decoded = sigs.mapIndexed { i, s ->
            assertEquals(s.getString("ciphertext"), makerBox.encrypt(s.getString("plaintext"), nonce("nonce-sig-$i")))
            assertEquals(s.getString("line"), Session.privmsg(maker, taker.nick, "sig", s.getString("ciphertext")))
            assertEquals(makerInputs[i].getString("sig_wire_b64"), s.getString("plaintext"))
            val m = incoming(s.getString("line"))
            assertTrue(m.verified); assertEquals("sig", m.command)
            val (sig, pub) = Session.decodeSig(takerBox, m.data)
            assertEquals(makerInputs[i].getString("signature"), sig.toHex())
            assertEquals(makerInputs[i].getString("pubkey"), pub.toHex())
            sig to pub
        }
        assertEquals(2, decoded.size)

        val signed = hex(txv.getString("signed_tx"))
        assertEquals(v.getJSONObject("push").getString("frame"), Session.privmsg(taker, maker.nick, "push", Session.pushData(signed)))

        val e = v.getJSONObject("error").getString("frame")
        val text = "Transaction verification failed: CJ output value too low"
        assertEquals(e, Session.privmsg(maker, taker.nick, "error", text))
        val m = incoming(e)
        assertTrue(m.verified); assertEquals("error", m.command); assertEquals(text, m.data)
    }

    @Test fun sigPaddingAndGarbage() {
        val plain = txv.getJSONArray("inputs").getJSONObject(1).getString("sig_wire_b64")
        val unpadded = makerBox.encrypt(plain.trimEnd('='), nonce("x"))
        assertEquals(txv.getJSONArray("inputs").getJSONObject(1).getString("signature"), Session.decodeSig(takerBox, unpadded).first.toHex())
        assertThrows(IllegalArgumentException::class.java) { Session.decodeSig(takerBox, "AAAA") }
        assertThrows(IllegalArgumentException::class.java) { Session.decodeSig(takerBox, makerBox.encrypt("AQ==", nonce("y"))) }
        assertNull(Session.parsePrivmsg(Wire.Frame(Wire.PUBMSG, "a!PUBLIC!orderbook")))
    }
}
