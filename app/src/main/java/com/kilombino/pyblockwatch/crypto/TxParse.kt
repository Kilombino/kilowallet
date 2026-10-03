package com.kilombino.pyblockwatch.crypto

/** A raw transaction read back into its parts: enough to recognise a Lightning channel close. */
object TxParse {

    class In(val txid: String, val vout: Int, val scriptSig: ByteArray, val sequence: Long, val witness: List<ByteArray>)
    class Out(val value: Long, val scriptPubKey: ByteArray)
    class Tx(val version: Long, val inputs: List<In>, val outputs: List<Out>, val locktime: Long)

    fun parse(hex: String): Tx {
        val b = Hashes.hexToBytes(hex)
        var o = 0
        fun u8() = b[o++].toInt() and 0xFF
        fun le(n: Int): Long { var v = 0L; for (i in 0 until n) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i)); o += n; return v }
        fun varint(): Long = when (val n = u8()) { 0xfd -> le(2); 0xfe -> le(4); 0xff -> le(8); else -> n.toLong() }
        fun bytes(n: Int) = b.copyOfRange(o, o + n).also { o += n }
        val version = le(4)
        val segwit = b[o] == 0.toByte() && b[o + 1] == 1.toByte()
        if (segwit) o += 2
        val raw = (0 until varint().toInt()).map {
            val txid = with(Hashes) { bytes(32).reversedArray().toHex() }
            val vout = le(4).toInt()
            val ss = bytes(varint().toInt())
            Triple(txid to vout, ss, le(4))
        }
        val outs = (0 until varint().toInt()).map { val v = le(8); Out(v, bytes(varint().toInt())) }
        val wit = if (segwit) raw.map { (0 until varint().toInt()).map { bytes(varint().toInt()) } } else raw.map { emptyList() }
        val locktime = le(4)
        require(o == b.size) { "trailing bytes in transaction" }
        return Tx(version, raw.mapIndexed { i, r -> In(r.first.first, r.first.second, r.second, r.third, wit[i]) }, outs, locktime)
    }

    /** A 2-of-2 P2WSH multisig witness script: OP_2 <33> <33> OP_2 OP_CHECKMULTISIG. */
    private fun isTwoOfTwo(ws: ByteArray) = ws.size == 71 && ws[0] == 0x52.toByte() && ws[1] == 0x21.toByte() &&
        ws[35] == 0x21.toByte() && ws[69] == 0x52.toByte() && ws[70] == 0xae.toByte()

    /** Every input spends a Lightning funding output (2-of-2 P2WSH multisig). */
    fun spendsChannelFunding(tx: Tx): Boolean = tx.inputs.isNotEmpty() && tx.inputs.all { i ->
        i.scriptSig.isEmpty() && i.witness.size == 4 && i.witness[0].isEmpty() && isTwoOfTwo(i.witness[3])
    }

    /**
     * A commitment (force close) hides the commitment number in the locktime (upper byte 0x20)
     * and the sequence (upper byte 0x80); a cooperative close does not.
     */
    fun isCommitment(tx: Tx): Boolean =
        (tx.locktime ushr 24) == 0x20L && tx.inputs.all { (it.sequence ushr 24) == 0x80L }
}
