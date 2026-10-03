package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.XChaCha20Poly1305

/**
 * LND's static channel backup (channel.backup): what the app reads from it to list a node's
 * channels. The file is XChaCha20-Poly1305 encrypted with SHA-256 of the compressed public key
 * at m/1017'/0'/7'/0/0 of the node's seed. It holds no balances and no signatures: per channel,
 * the funding outpoint, the peer, the capacity and which keys of the seed the channel uses.
 */
object Scb {

    class Channel(
        val version: Int,
        val isInitiator: Boolean,
        val fundingTxid: String,
        val fundingVout: Int,
        val shortChannelId: String,
        val remoteNode: String,
        val capacity: Long,
        val localCsvDelay: Int,
        /** Key indices in the families 0 (multisig), 1 (revocation), 3 (payment), 4 (delay), 2 (htlc). */
        val localKeyIndex: Map<Int, Int>,
        val remoteCsvDelay: Int,
        val remoteMultisig: ByteArray,
        val remoteRevocation: ByteArray,
        val remotePayment: ByteArray,
        val remoteDelay: ByteArray,
        val remoteHtlc: ByteArray,
        val shaChainIndex: Int,
    ) {
        /** Taproot channels fund a MuSig2 key, not a 2-of-2 script. */
        val isTaproot: Boolean get() = version in 5..7
        val hasAnchors: Boolean get() = version in 2..7

        /** The P2WSH 2-of-2 funding script for a pre-taproot channel, keys sorted as BOLT 3 says. */
        fun fundingScript(localMultisig: ByteArray): ByteArray {
            val (a, b) = if (compare(localMultisig, remoteMultisig) < 0) localMultisig to remoteMultisig
                else remoteMultisig to localMultisig
            val ws = byteArrayOf(0x52, 0x21) + a + byteArrayOf(0x21) + b + byteArrayOf(0x52, 0xae.toByte())
            return byteArrayOf(0x00, 0x20) + Hashes.sha256(ws)
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) { val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF); if (d != 0) return d }
        return 0
    }

    class WrongSeed : Exception("This channel.backup was not made by this LND seed.")

    fun encryptionKey(root: Bip32Priv.ExtendedPrivKey): ByteArray =
        Hashes.sha256(Bip32Priv.derivePath(root, "m/1017'/0'/7'/0/0").publicKey())

    fun decode(file: ByteArray, root: Bip32Priv.ExtendedPrivKey): List<Channel> {
        require(file.size > 24 + 16) { "Too short to be a channel.backup." }
        val nonce = file.copyOfRange(0, 24)
        val plain = XChaCha20Poly1305.open(encryptionKey(root), nonce, file.copyOfRange(24, file.size), nonce)
            ?: throw WrongSeed()
        var o = 0
        fun u8() = plain[o++].toInt() and 0xFF
        fun be(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or (plain[o++].toLong() and 0xFF) }; return v }
        fun bytes(n: Int) = plain.copyOfRange(o, o + n).also { o += n }
        require(u8() == 0) { "Unknown channel.backup version." }
        val count = be(4).toInt()
        return (0 until count).map {
            val version = u8() and 0x3F // upper bits flag extra close data (and unified sigs in this fork)
            val len = be(2).toInt()
            val end = o + len
            val initiator = u8() != 0
            bytes(32) // chain hash
            val txid = bytes(32).reversedArray().toHex()
            val vout = be(2).toInt()
            val scid = be(8)
            val scidText = "${scid ushr 40}x${(scid ushr 16) and 0xFFFFFF}x${scid and 0xFFFF}"
            val remote = bytes(33).toHex()
            val addrLen = be(2).toInt(); o += addrLen // addresses
            val capacity = be(8)
            val localCsv = be(2).toInt()
            val keys = HashMap<Int, Int>()
            repeat(5) { val fam = be(4).toInt(); keys[fam] = be(4).toInt() }
            val remoteCsv = be(2).toInt()
            val rm = bytes(33); val rr = bytes(33); val rp = bytes(33); val rd = bytes(33); val rh = bytes(33)
            bytes(33)                     // shachain root pubkey (zero when stored by locator)
            be(4)                         // shachain family
            val shaIndex = be(4).toInt()
            o = end
            Channel(version, initiator, txid, vout, scidText, remote, capacity, localCsv, keys,
                remoteCsv, rm, rr, rp, rd, rh, shaIndex)
        }
    }
}
