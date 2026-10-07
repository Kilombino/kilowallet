package com.kilombino.pyblockwatch.crypto

import java.math.BigInteger

/**
 * Silent Payments (BIP-352) — the SENDER side. Turns a recipient `sp1…` address and the coins
 * being spent into the Taproot output that only the recipient can detect and later spend.
 *
 * The recipient half (scanning the chain for payments) is the server's job — this wallet only
 * needs to construct the output when sending. The maths is an ECDH between the sum of the
 * spender's input keys and the recipient's scan key, tweaked into the recipient's spend key.
 * Pinned to the published BIP-352 "Simple send" vector so a wrong output can never be built.
 */
object SilentPayment {

    private val N = Secp256k1.N

    /** A decoded silent payment address: its scan and spend public keys. */
    data class Recipient(val scan: Secp256k1.Point, val spend: Secp256k1.Point)

    /** One coin being spent: its private key and the outpoint it comes from. */
    data class Input(val privateKey: BigInteger, val txid: String, val vout: Int)

    /** Decode an `sp1…` address into its scan and spend keys. */
    fun decodeAddress(address: String): Recipient {
        val payload = Bech32.decodeSilentPayment(address.trim())
            ?: throw IllegalArgumentException("Not a valid silent payment address (sp1…).")
        require(payload.size == 66) { "Unexpected silent payment payload (${payload.size} bytes, expected 66)." }
        return Recipient(
            Secp256k1.decompress(payload.copyOfRange(0, 33)),
            Secp256k1.decompress(payload.copyOfRange(33, 66)),
        )
    }

    /**
     * The scriptPubKey (`OP_1 <32-byte x-only>`) to pay [recipient] the [k]-th output, from
     * inputs whose keys are used as-is (P2WPKH — this wallet's only kind). Single recipient here;
     * `k` numbers multiple outputs to the same recipient.
     */
    fun outputScript(recipient: Recipient, inputs: List<Input>, k: Int = 0): ByteArray {
        require(inputs.isNotEmpty()) { "silent payment needs at least one input" }
        // a = sum of the input private keys; A = a·G is the sum of the input public keys.
        val a = inputs.fold(BigInteger.ZERO) { acc, i -> acc.add(i.privateKey).mod(N) }
        require(a.signum() != 0) { "sum of input keys is zero" }
        val sumPubkey = Secp256k1.compress(Secp256k1.publicPoint(a))

        // input_hash commits to the smallest outpoint and A, so the shared secret is unique to
        // this transaction and cannot be replayed with a different set of inputs.
        val smallest = inputs.map { outpoint(it.txid, it.vout) }.minWithOrNull(::lexCompare)!!
        val inputHash = BigInteger(1, Hashes.taggedHash("BIP0352/Inputs", smallest + sumPubkey)).mod(N)

        // ecdh = input_hash · a · B_scan
        val ecdh = Secp256k1.multiply(inputHash.multiply(a).mod(N), recipient.scan)
        val ecdhSer = Secp256k1.compress(ecdh)

        // t_k = hash(ecdh ‖ ser32(k)); the output key is B_spend + t_k·G.
        val tk = BigInteger(1, Hashes.taggedHash("BIP0352/SharedSecret", ecdhSer + ser32(k))).mod(N)
        val outputPoint = Secp256k1.add(recipient.spend, Secp256k1.multiply(tk, Secp256k1.G))
        val xOnly = Secp256k1.xOnly(outputPoint)
        return byteArrayOf(0x51, 0x20) + xOnly // OP_1 <32-byte Taproot output>
    }

    /** Internal (little-endian) outpoint: reversed txid ‖ 4-byte little-endian vout. */
    private fun outpoint(txid: String, vout: Int): ByteArray =
        Hashes.hexToBytes(txid).reversedArray() + byteArrayOf(
            vout.toByte(), (vout ushr 8).toByte(), (vout ushr 16).toByte(), (vout ushr 24).toByte(),
        )

    private fun ser32(i: Int): ByteArray = byteArrayOf(
        (i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte(),
    )

    /** Unsigned lexicographic comparison of two equal-length byte arrays. */
    private fun lexCompare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
