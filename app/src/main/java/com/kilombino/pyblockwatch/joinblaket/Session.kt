package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.util.Base64

/**
 * The taker <-> maker CoinJoin messages (`taker/coinjoin_session.py`, `maker/…`), as pure
 * encoders and decoders. Every one travels as a signed PRIVMSG `from!to!<command> <data>
 * <pubkey_hex> <sig_b64>`; `fill`, `pubkey`, `push` and `error` carry plaintext data, `auth`,
 * `ioauth`, `tx` and `sig` carry `base64(nonce || crypto_box)` under the round's [Nacl.Box].
 */
object Session {
    private val b64 = Base64.getEncoder()

    /** The PRIVMSG frame `{"type": 685, "line": "<us>!<to>!<command> <signed data>"}`. */
    fun privmsg(id: NickIdentity, to: String, command: String, data: String): String =
        Wire.envelope(Wire.PRIVMSG, "${id.nick}!$to!$command ${id.signData(data)}")

    /** `!fill` data: `<oid> <cj_amount> <taker_nacl_pubkey_hex> P<commitment_hex>`. */
    fun fillData(oid: Long, cjAmount: Long, naclPublic: ByteArray, commitmentWire: String): String =
        "$oid $cjAmount ${naclPublic.toHex()} $commitmentWire"

    /** `!auth` data: the PoDLE revelation, encrypted. */
    fun authData(box: Nacl.Box, revelation: String, nonce: ByteArray? = null): String = encrypt(box, revelation, nonce)

    /** `!tx` data: `base64(unsigned tx)`, encrypted. */
    fun txData(box: Nacl.Box, unsignedTx: ByteArray, nonce: ByteArray? = null): String =
        encrypt(box, b64.encodeToString(unsignedTx), nonce)

    /** `!push` data: `base64(signed tx)`, plaintext. */
    fun pushData(signedTx: ByteArray): String = b64.encodeToString(signedTx)

    private fun encrypt(box: Nacl.Box, text: String, nonce: ByteArray?) =
        if (nonce == null) box.encrypt(text) else box.encrypt(text, nonce)

    /** A received PRIVMSG, split and authenticated ([verified]) by [Nick.verifyPrivmsg]. */
    data class Incoming(val from: String, val to: String, val command: String, val data: String, val verified: Boolean)

    /** Split a PRIVMSG frame `from!to!rest`; null for any other frame type or shape. */
    fun parsePrivmsg(frame: Wire.Frame): Incoming? {
        if (frame.type != Wire.PRIVMSG) return null
        val parts = frame.line.split("!", limit = 3)
        if (parts.size != 3) return null
        val v = Nick.verifyPrivmsg(parts[0], parts[2])
        return Incoming(parts[0], parts[1], v.command, v.data, v.ok)
    }

    /**
     * What the taker accepts while waiting for makers: a verified privmsg to [ourNick] from one
     * of [expectedMakers] (`taker/multi_directory.py` response collection).
     */
    fun accepts(m: Incoming, ourNick: String, expectedMakers: Collection<String>): Boolean =
        m.verified && m.to == ourNick && m.from in expectedMakers

    /** `!pubkey` data: the maker's NaCl key and its advertised features. */
    data class Pubkey(val naclPublic: ByteArray, val features: Set<String>)

    /** Parse `<maker_nacl_pubkey_hex> [features=a,b]`. */
    fun parsePubkey(data: String): Pubkey {
        val t = data.trim().split(Regex("\\s+"))
        val key = Wire.hex(t[0])
        require(key.size == 32) { "NaCl key must be 32 bytes" }
        val features = t.drop(1).firstOrNull { it.startsWith("features=") }
            ?.removePrefix("features=")?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        return Pubkey(key, features)
    }

    /** A decrypted `!ioauth`: `<utxo_list> <auth_pub_hex> <cj_addr> <change_addr> <btc_sig_b64>`. */
    data class IoAuth(val utxos: List<Utxos.Ref>, val authPub: String, val cjAddress: String, val changeAddress: String, val btcSig: String)

    /** Decrypt and split `!ioauth` data (the first token is the ciphertext); throws if malformed. */
    fun decodeIoauth(box: Nacl.Box, data: String): IoAuth {
        val plain = box.decryptString(data.trim().split(Regex("\\s+"))[0])
        val p = plain.trim().split(Regex("\\s+"))
        require(p.size >= 5) { "ioauth needs 5 parts" }
        return IoAuth(Utxos.parseList(p[0]), p[1], p[2], p[3], p[4])
    }

    /** The plaintext of one `!sig`: `base64(u8(len(sig)) || sig || u8(len(pub)) || pub)`. */
    fun encodeSig(sig: ByteArray, pubkey: ByteArray): String =
        b64.encodeToString(byteArrayOf(sig.size.toByte()) + sig + byteArrayOf(pubkey.size.toByte()) + pubkey)

    /** Decrypt one `!sig` and return (signature with its hash-type byte, pubkey). Missing `=` padding is tolerated. */
    fun decodeSig(box: Nacl.Box, data: String): Pair<ByteArray, ByteArray> {
        val plain = box.decryptString(data.trim().split(Regex("\\s+"))[0]).trim()
        val raw = Base64.getDecoder().decode(plain + "=".repeat((4 - plain.length % 4) % 4))
        require(raw.isNotEmpty()) { "empty !sig" }
        val sl = raw[0].toInt() and 0xFF
        require(raw.size > 1 + sl) { "truncated !sig" }
        val pl = raw[1 + sl].toInt() and 0xFF
        require(raw.size >= 2 + sl + pl) { "truncated !sig" }
        return raw.copyOfRange(1, 1 + sl) to raw.copyOfRange(2 + sl, 2 + sl + pl)
    }
}

/**
 * The taker's checks on a maker's `!ioauth` (`taker/coinjoin_session.py:795-999,1070-1184`),
 * all fatal for that maker. The chain lookup is injected: values and scriptPubKeys come from
 * OUR view of the chain, never from the maker.
 */
object IoauthCheck {
    const val MAX_MAKER_UTXOS = 15

    /** What our chain lookup says about an outpoint. */
    data class ChainUtxo(val value: Long, val scriptPubKey: ByteArray, val confirmations: Int)

    sealed class Result {
        /** The maker passed; its inputs carry our looked-up values. */
        data class Ok(val part: TakerTx.MakerPart, val authPub: String) : Result()
        /** Dropped; [unavailable] = our backend failed, not proof the maker lied. */
        data class Fail(val reason: String, val unavailable: Boolean = false) : Result()
    }

    /**
     * Checks in Python's order: btc_sig by `auth_pub` over the maker's NaCl key hex; at most
     * [maxMakerUtxos] inputs; no duplicate; each exists unspent with confirmations > 0 ([lookup]
     * returns null for spent/missing and throws if the backend is unavailable); no outpoint in
     * [taken] (ours and other makers'); change `inputs - cj_amount - txfee + cjfee >= 27300`;
     * `P2WPKH(auth_pub)` is one of the inputs; both addresses decode on [network].
     */
    fun check(
        io: Session.IoAuth, makerNaclPublic: ByteArray, nick: String, cjAmount: Long,
        offerTxfee: Long, cjfee: Long, taken: Set<Pair<String, Long>>,
        lookup: (txid: String, vout: Long) -> ChainUtxo?, maxMakerUtxos: Int = MAX_MAKER_UTXOS,
        network: JbNetwork = JbNetwork.MAINNET,
    ): Result {
        val authPub = runCatching { Wire.hex(io.authPub) }.getOrNull()
            ?: return Result.Fail("auth pubkey is not hex")
        if (!JmSig.verifyMessage(makerNaclPublic.toHex(), io.btcSig, authPub)) return Result.Fail("btc_sig verification failed")
        if (io.utxos.size > maxMakerUtxos) return Result.Fail("more than $maxMakerUtxos inputs")
        val seen = HashSet<Pair<String, Long>>()
        val utxos = ArrayList<TakerTx.Utxo>()
        for (ref in io.utxos) {
            val op = ref.txid.lowercase() to ref.vout
            if (!seen.add(op)) return Result.Fail("declared duplicate input")
            val c = try { lookup(op.first, op.second) } catch (e: Exception) {
                return Result.Fail("error verifying UTXO: ${e.message}", unavailable = true)
            } ?: return Result.Fail("UTXO is spent or does not exist")
            if (c.confirmations <= 0) return Result.Fail("UTXO is unconfirmed")
            utxos.add(TakerTx.Utxo(op.first, op.second, c.value, c.scriptPubKey))
        }
        if (seen.any { it in taken }) return Result.Fail("input already used by another participant")
        if (Fees.makerChange(utxos.sumOf { it.value }, cjAmount, offerTxfee, cjfee) < Fees.MAKER_DUST) {
            return Result.Fail("maker change below ${Fees.MAKER_DUST}")
        }
        if (authPub.size != 33 || utxos.none { it.scriptPubKey.contentEquals(TakerTx.p2wpkh(authPub)) }) {
            return Result.Fail("authentication key matches no declared UTXO")
        }
        if (listOf(io.cjAddress, io.changeAddress).any { runCatching { network.scriptPubKey(it) }.isFailure }) {
            return Result.Fail("invalid output address")
        }
        return Result.Ok(TakerTx.MakerPart(nick, utxos, io.cjAddress, io.changeAddress, cjfee, offerTxfee), io.authPub)
    }
}
