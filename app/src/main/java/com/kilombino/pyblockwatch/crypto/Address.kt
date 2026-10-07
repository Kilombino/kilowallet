package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/**
 * Address and scriptPubKey derivation, plus the Electrum "scripthash" both of our
 * servers index by.
 *
 * Both chains share Bitcoin's address formats and the same genesis block — the
 * BLAKE2b fork changed the proof-of-work, not the key derivation — so one code
 * path serves both and the same xpub is meaningful on either side.
 */
object Address {

    private const val HRP_MAINNET = "bc"
    private const val P2PKH_VERSION = 0x00
    private const val P2SH_VERSION = 0x05

    /**
     * The scriptPubKey a RECIPIENT address pays to — the inverse of [encode]. Accepts
     * P2PKH (1…), P2SH (3…), and native SegWit / Taproot (bc1…). Throws with a readable
     * message on anything malformed, since this string is pasted by a human before a send.
     */
    fun decodeToScriptPubKey(address: String): ByteArray {
        val a = address.trim()
        if (a.length > 3 && a.substring(0, 3).lowercase() == "bc1") {
            val sw = Bech32.decodeSegwit(HRP_MAINNET, a)
                ?: throw IllegalArgumentException("Not a valid bc1 address (checksum or format).")
            val op = if (sw.witnessVersion == 0) 0x00 else (0x50 + sw.witnessVersion)
            return byteArrayOf(op.toByte(), sw.program.size.toByte()) + sw.program
        }
        val raw = try {
            Base58.decodeChecked(a)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a valid address: ${e.message}")
        }
        require(raw.size == 21) { "Unexpected address payload length." }
        val version = raw[0].toInt() and 0xFF
        val h = raw.copyOfRange(1, 21)
        return when (version) {
            P2PKH_VERSION ->
                byteArrayOf(0x76, 0xa9.toByte(), 0x14) + h + byteArrayOf(0x88.toByte(), 0xac.toByte())
            P2SH_VERSION -> byteArrayOf(0xa9.toByte(), 0x14) + h + byteArrayOf(0x87.toByte())
            else -> throw IllegalArgumentException("Unknown address version byte 0x%02x.".format(version))
        }
    }

    /** The scriptPubKey a given public key locks coins to, under [type]. */
    fun scriptPubKey(pubkey: ByteArray, type: ScriptType): ByteArray {
        val h160 = Hashes.hash160(pubkey)
        return when (type) {
            // OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG
            ScriptType.P2PKH ->
                byteArrayOf(0x76, 0xa9.toByte(), 0x14) + h160 + byteArrayOf(0x88.toByte(), 0xac.toByte())
            // OP_HASH160 <20 = hash160(redeemScript)> OP_EQUAL, redeemScript = OP_0 <20>
            ScriptType.P2SH_P2WPKH -> {
                val redeem = byteArrayOf(0x00, 0x14) + h160
                byteArrayOf(0xa9.toByte(), 0x14) + Hashes.hash160(redeem) + byteArrayOf(0x87.toByte())
            }
            // OP_0 <20>
            ScriptType.P2WPKH -> byteArrayOf(0x00, 0x14) + h160
            // OP_1 <32 = taproot output key>
            ScriptType.P2TR -> byteArrayOf(0x51, 0x20) + taprootOutputKey(pubkey)
        }
    }

    /**
     * BIP-86 key-path Taproot output key: lift the internal key to a point, add
     * `int(TapTweak(P)) · G`, and take the result's x. No script tree.
     */
    fun taprootOutputKey(pubkey: ByteArray): ByteArray {
        val xOnly = pubkey.copyOfRange(1, 33)               // drop the compressed parity byte
        val internal = Secp256k1.liftX(BigInteger(1, xOnly))
        val t = BigInteger(1, Hashes.taggedHash("TapTweak", xOnly)).mod(Secp256k1.N)
        val output = Secp256k1.add(internal, Secp256k1.multiply(t, Secp256k1.G))
        return Secp256k1.xOnly(output)
    }

    /** The human-readable address for a public key under [type]. */
    fun encode(pubkey: ByteArray, type: ScriptType): String {
        val h160 = Hashes.hash160(pubkey)
        return when (type) {
            ScriptType.P2PKH -> Base58.encodeChecked(byteArrayOf(P2PKH_VERSION.toByte()) + h160)
            ScriptType.P2SH_P2WPKH -> {
                val redeem = byteArrayOf(0x00, 0x14) + h160
                Base58.encodeChecked(byteArrayOf(P2SH_VERSION.toByte()) + Hashes.hash160(redeem))
            }
            ScriptType.P2WPKH -> Bech32.encodeSegwit(HRP_MAINNET, 0, h160)
            ScriptType.P2TR -> Bech32.encodeSegwit(HRP_MAINNET, 1, taprootOutputKey(pubkey))
        }
    }

    /**
     * Electrum indexes by `sha256(scriptPubKey)` **reversed** — the byte order trips
     * up nearly everyone implementing this the first time.
     */
    fun electrumScriptHash(scriptPubKey: ByteArray): String {
        val digest = Hashes.sha256(scriptPubKey)
        digest.reverse()
        return with(Hashes) { digest.toHex() }
    }

    /** Convenience: pubkey → the scripthash to ask the server about. */
    fun scriptHashFor(pubkey: ByteArray, type: ScriptType): String =
        electrumScriptHash(scriptPubKey(pubkey, type))
}
