package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/**
 * WIF private keys (Wallet Import Format): Base58Check of 0x80 ‖ 32-byte key, with a trailing
 * 0x01 when the key's public key is compressed. Used to sweep a paper wallet or an exported
 * key into this wallet. Electrum's "p2wpkh:"-style prefixes are accepted and ignored: every
 * address form the key can have is checked anyway.
 */
object Wif {

    class Key(val privateKey: BigInteger, val compressed: Boolean) {
        val pubkey: ByteArray by lazy {
            val p = Secp256k1.multiply(privateKey, Secp256k1.G)
            if (compressed) Secp256k1.compress(p) else Secp256k1.uncompressed(p)
        }

        /** The address forms this key can hold coins in and this wallet can spend. */
        val sweepableTypes: List<ScriptType> get() =
            if (compressed) listOf(ScriptType.P2WPKH, ScriptType.P2TR, ScriptType.P2SH_P2WPKH, ScriptType.P2PKH)
            else listOf(ScriptType.P2PKH)
    }

    fun decode(text: String): Key {
        val s = text.trim().substringAfterLast(':').trim()
        val raw = try { Base58.decodeChecked(s) } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a valid private key (WIF): ${e.message}")
        }
        require(raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0x80) {
            if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0xEF) "That is a testnet key."
            else "Not a mainnet private key (WIF starting with 5, K or L)."
        }
        val compressed = when (raw.size) {
            33 -> false
            34 -> { require(raw[33].toInt() == 1) { "Unexpected WIF suffix." }; true }
            else -> throw IllegalArgumentException("Unexpected WIF length.")
        }
        val k = BigInteger(1, raw.copyOfRange(1, 33))
        require(k.signum() > 0 && k < Secp256k1.N) { "Private key out of range." }
        return Key(k, compressed)
    }
}
