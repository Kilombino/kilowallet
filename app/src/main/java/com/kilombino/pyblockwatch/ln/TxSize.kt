package com.kilombino.pyblockwatch.ln

/** A child spending one Taproot or SegWit v0 coin to one output: about 111 vB. */
const val CPFP_CHILD_VSIZE = 111L

/**
 * The child's fee rate so that parent and child together pay [target] sat/vB: the pair's
 * size at that rate, less what the parent already pays, over the child's size; never below
 * [target] itself.
 */
fun cpfpChildRate(target: Long, parentVsize: Long, parentFee: Long, childVsize: Long): Long {
    val need = target * (parentVsize + childVsize) - parentFee
    return maxOf(target, (need + childVsize - 1) / childVsize)
}

/** A raw transaction's virtual size and the sum of its outputs, read from its hex. */
data class TxSize(val vsize: Long, val outputs: Long) {
    companion object {
        fun of(hex: String): TxSize {
            val b = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            var i = 0
            fun u8() = b[i++].toInt() and 0xff
            fun le(n: Int): Long { var v = 0L; for (k in 0 until n) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k)); i += n; return v }
            fun vi(): Long = when (val x = u8()) { 0xfd -> le(2); 0xfe -> le(4); 0xff -> le(8); else -> x.toLong() }
            i = 4
            val segwit = b[4].toInt() == 0 && b[5].toInt() == 1
            if (segwit) i += 2
            val nin = vi()
            repeat(nin.toInt()) { i += 36; val n = vi().toInt(); i += n + 4 }
            val nout = vi()
            var sum = 0L
            repeat(nout.toInt()) { sum += le(8); val n = vi().toInt(); i += n }
            val baseEnd = i
            if (segwit) repeat(nin.toInt()) { repeat(vi().toInt()) { val n = vi().toInt(); i += n } }
            i += 4
            require(i == b.size) { "not a whole transaction" }
            // Stripped size: without marker, flag and witnesses.
            val stripped = if (segwit) (baseEnd - 2) + 4 else b.size
            val weight = stripped * 3L + b.size
            return TxSize((weight + 3) / 4, sum)
        }
    }
}
