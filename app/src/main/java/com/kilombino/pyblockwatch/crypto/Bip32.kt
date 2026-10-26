package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/** Which script type an extended public key implies, per SLIP-132 version bytes. */
enum class ScriptType(val label: String, val explain: String) {
    P2PKH("Legacy", "Addresses that start with 1. Bitcoin's original format."),
    P2SH_P2WPKH("Nested SegWit", "Addresses that start with 3. SegWit wrapped for older wallets."),
    P2WPKH("Native SegWit", "Addresses that start with bc1q. The most widely used."),
    P2TR("Taproot", "Addresses that start with bc1p. The newest, and cheaper to spend."),
}

/**
 * BIP-32 public derivation.
 *
 * Only the PUBLIC half is implemented — there is deliberately no CKDpriv here and
 * no way to hold a private key. A watch-only wallet that cannot represent a secret
 * cannot leak one.
 */
object Bip32 {

    /** SLIP-132 version bytes → script type. */
    private val VERSIONS = mapOf(
        0x0488B21E to ScriptType.P2PKH,        // xpub
        0x049D7CB2 to ScriptType.P2SH_P2WPKH,  // ypub
        0x04B24746 to ScriptType.P2WPKH,       // zpub
    )

    data class ExtendedPubKey(
        val point: Secp256k1.Point,
        val chainCode: ByteArray,
        val depth: Int,
        val scriptType: ScriptType,
    ) {
        /** 33-byte compressed SEC encoding of this node's public key. */
        fun pubkey(): ByteArray = Secp256k1.compress(point)

        override fun equals(other: Any?): Boolean =
            other is ExtendedPubKey && point == other.point && chainCode.contentEquals(other.chainCode)

        override fun hashCode(): Int = point.hashCode() * 31 + chainCode.contentHashCode()
    }

    /** The child number an extended key was derived at (hardened = +2^31), from its own bytes. */
    fun childNumber(encoded: String): Long {
        val raw = Base58.decodeChecked(encoded.trim())
        require(raw.size == 78)
        return (9 until 13).fold(0L) { n, i -> (n shl 8) or (raw[i].toLong() and 0xff) }
    }

    /**
     * Parse an xpub / ypub / zpub. Throws with a readable message on anything
     * malformed — this string is typed by a human, so the error surfaces in the UI.
     */
    fun parseExtendedPubKey(encoded: String): ExtendedPubKey {
        val raw = try {
            Base58.decodeChecked(encoded.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Not a valid extended key: ${e.message}")
        }
        require(raw.size == 78) { "Unexpected length (${raw.size} bytes, expected 78)." }

        val version = ((raw[0].toInt() and 0xFF) shl 24) or ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val scriptType = VERSIONS[version]
            ?: throw IllegalArgumentException(
                "Unknown prefix. Use xpub, ypub or zpub (not an xprv private key)."
            )

        val depth = raw[4].toInt() and 0xFF
        val chainCode = raw.copyOfRange(13, 45)
        val keyBytes = raw.copyOfRange(45, 78)
        require(keyBytes[0].toInt() == 0x02 || keyBytes[0].toInt() == 0x03) {
            "This looks like an extended PRIVATE key. Never enter it here: use the public one (xpub/ypub/zpub)."
        }
        return ExtendedPubKey(Secp256k1.decompress(keyBytes), chainCode, depth, scriptType)
    }

    /**
     * CKDpub: derive the non-hardened child at [index].
     *
     * I = HMAC-SHA512(cpar, serP(Kpar) ‖ ser32(i));  Ki = point(IL) + Kpar;  ci = IR
     */
    fun deriveChild(parent: ExtendedPubKey, index: Int): ExtendedPubKey {
        require(index >= 0) { "hardened derivation is impossible from a public key" }
        val data = parent.pubkey() + byteArrayOf(
            (index ushr 24).toByte(), (index ushr 16).toByte(),
            (index ushr 8).toByte(), index.toByte(),
        )
        val i = Hashes.hmacSha512(parent.chainCode, data)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        val ir = i.copyOfRange(32, 64)

        // BIP-32 says: if IL ≥ n, or the result is the point at infinity, skip this
        // index. Astronomically unlikely, but the spec is explicit about it.
        require(il < Secp256k1.N) { "derived scalar out of range at index $index" }
        val child = Secp256k1.add(Secp256k1.multiplyG(il), parent.point)
        require(!child.isInfinity) { "derived point at infinity at index $index" }

        return ExtendedPubKey(child, ir, parent.depth + 1, parent.scriptType)
    }

    /** Derive `parent/chain/index`, e.g. `0/5` for the sixth receive address. */
    fun derivePath(parent: ExtendedPubKey, chain: Int, index: Int): ExtendedPubKey =
        deriveChild(deriveChild(parent, chain), index)
}
