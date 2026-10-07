package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/**
 * BIP-32 PRIVATE derivation — the secret half, kept in its own file so the watch-only
 * [Bip32] stays a public-only object with nothing to leak.
 *
 * This is what a hot wallet adds: from a seed it grows the master key and walks a path
 * like m/84'/0'/0'/0/i down to the private scalar that signs one address's inputs. Both
 * hardened and normal steps are here (a watch-only xpub can only do the latter). Pinned by
 * the official BIP-32 test vectors.
 */
object Bip32Priv {

    private const val HARDENED = 0x80000000.toInt()

    data class ExtendedPrivKey(
        val key: BigInteger,       // the private scalar k
        val chainCode: ByteArray,
        val depth: Int,
    ) {
        /** 33-byte compressed public key for this node. */
        fun publicKey(): ByteArray = Secp256k1.compress(point())
        fun point(): Secp256k1.Point = Secp256k1.publicPoint(key)

        /** 32-byte big-endian serialisation of the private scalar. */
        fun keyBytes(): ByteArray {
            val b = key.toByteArray()
            val out = ByteArray(32)
            val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
            System.arraycopy(src, 0, out, 32 - src.size, src.size)
            return out
        }

        /** BIP-32 fingerprint: the first 4 bytes of hash160 of this node's public key. */
        fun fingerprint(): ByteArray = Hashes.hash160(publicKey()).copyOf(4)

        override fun equals(other: Any?): Boolean =
            other is ExtendedPrivKey && key == other.key && chainCode.contentEquals(other.chainCode)
        override fun hashCode(): Int = key.hashCode() * 31 + chainCode.contentHashCode()
    }

    /** SLIP-132 public version bytes for the extended key of a given BIP purpose. */
    private fun xpubVersion(purpose: Int): Int = when (purpose) {
        84 -> 0x04B24746 // zpub (native SegWit)
        49 -> 0x049D7CB2 // ypub (nested SegWit)
        else -> 0x0488B21E // xpub (legacy and, by convention, Taproot descriptors)
    }

    /**
     * The account-level extended PUBLIC key (e.g. a zpub for m/84'/0'/0') for [account],
     * ready to hand to the existing watch-only scanner. Deriving it here means the hot
     * wallet reuses the same address discovery as an imported xpub, with the private half
     * never leaving this object.
     */
    fun accountXpub(master: ExtendedPrivKey, purpose: Int, account: Int = 0): String {
        val coinNode = deriveChild(deriveChild(master, purpose, hardened = true), 0, hardened = true)
        val accountNode = deriveChild(coinNode, account, hardened = true)
        val childNumber = account or HARDENED
        val v = xpubVersion(purpose)
        val out = ByteArray(78)
        out[0] = (v ushr 24).toByte(); out[1] = (v ushr 16).toByte()
        out[2] = (v ushr 8).toByte(); out[3] = v.toByte()
        out[4] = 3 // depth: m / purpose' / coin' / account'
        System.arraycopy(coinNode.fingerprint(), 0, out, 5, 4)
        out[9] = (childNumber ushr 24).toByte(); out[10] = (childNumber ushr 16).toByte()
        out[11] = (childNumber ushr 8).toByte(); out[12] = childNumber.toByte()
        System.arraycopy(accountNode.chainCode, 0, out, 13, 32)
        System.arraycopy(accountNode.publicKey(), 0, out, 45, 33)
        return Base58.encodeChecked(out)
    }

    /** Master key from a BIP-39 seed: I = HMAC-SHA512("Bitcoin seed", seed). */
    fun fromSeed(seed: ByteArray): ExtendedPrivKey {
        val i = Hashes.hmacSha512("Bitcoin seed".toByteArray(Charsets.UTF_8), seed)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        require(il.signum() != 0 && il < Secp256k1.N) { "invalid master key (retry with different seed)" }
        return ExtendedPrivKey(il, i.copyOfRange(32, 64), 0)
    }

    /** CKDpriv at [index]. Set the top bit (or use [hardened]) for a hardened child. */
    fun deriveChild(parent: ExtendedPrivKey, index: Int, hardened: Boolean = false): ExtendedPrivKey {
        val idx = if (hardened) (index or HARDENED) else index
        val isHardened = (idx and HARDENED) != 0
        val data = if (isHardened) {
            // 0x00 || ser256(kpar) || ser32(i)
            byteArrayOf(0x00) + parent.keyBytes() + ser32(idx)
        } else {
            // serP(point(kpar)) || ser32(i)
            parent.publicKey() + ser32(idx)
        }
        val i = Hashes.hmacSha512(parent.chainCode, data)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        require(il < Secp256k1.N) { "derived scalar out of range at index $idx" }
        val childKey = il.add(parent.key).mod(Secp256k1.N)
        require(childKey.signum() != 0) { "derived zero key at index $idx" }
        return ExtendedPrivKey(childKey, i.copyOfRange(32, 64), parent.depth + 1)
    }

    /**
     * Derive a full path such as "m/84'/0'/0'/0/0". Accepts ' or h for hardened steps.
     */
    fun derivePath(master: ExtendedPrivKey, path: String): ExtendedPrivKey {
        var node = master
        for (part in path.split('/')) {
            val p = part.trim()
            if (p.isEmpty() || p == "m") continue
            val hardened = p.endsWith("'") || p.endsWith("h") || p.endsWith("H")
            val n = p.trimEnd('\'', 'h', 'H').toInt()
            node = deriveChild(node, n, hardened)
        }
        return node
    }

    private fun ser32(i: Int): ByteArray = byteArrayOf(
        (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte(),
    )
}
