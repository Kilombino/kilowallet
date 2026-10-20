package com.kilombino.pyblockwatch.crypto

/**
 * Uniform Resources (BCR-2020-005), just enough to carry a PSBT as `ur:crypto-psbt` in an
 * animated QR, which is what SeedSigner (and Sparrow, Keystone, Passport…) read and write.
 *
 * A message is the CBOR byte string of the PSBT. When it does not fit one QR it is cut into
 * equal fragments and sent as a fountain code: parts 1..n are the fragments themselves, every
 * later part XORs a pseudo-random subset of them (chosen with xoshiro256** seeded from the part
 * number and the message's CRC32), so a reader that missed some frames still finishes. Each part
 * is the CBOR array [seqNum, seqLen, messageLen, checksum, fragment], written as minimal
 * bytewords with a CRC32 at the end: `ur:crypto-psbt/7-3/lpatax…`.
 *
 * Ported from the reference (Foundation Devices' Python port, which SeedSigner ships) and checked
 * against parts SeedSigner itself produced.
 */
object Ur {
    const val PSBT_TYPE = "crypto-psbt"

    // ------------------------------------------------------------------ bytewords (minimal)

    private const val WORDS =
        "ableacidalsoapexaquaarchatomauntawayaxisbackbaldbarnbeltbetabiasbluebodybragbrewbulbbuzzcalmcashcatschefcityclawcodecolacookcostcruxcurlcuspcyandarkdatadaysdelidicedietdoordowndrawdropdrumdulldutyeacheasyechoedgeepicevenexamexiteyesfactfairfernfigsfilmfishfizzflapflewfluxfoxyfreefrogfuelfundgalagamegeargemsgiftgirlglowgoodgraygrimgurugushgyrohalfhanghardhawkheathelphighhillholyhopehornhutsicedideaidleinchinkyintoirisironitemjadejazzjoinjoltjowljudojugsjumpjunkjurykeepkenokeptkeyskickkilnkingkitekiwiknoblamblavalazyleaflegsliarlimplionlistlogoloudloveluaulucklungmainmanymathmazememomenumeowmildmintmissmonknailnavyneednewsnextnoonnotenumbobeyoboeomitonyxopenovalowlspaidpartpeckplaypluspoempoolposepuffpumapurrquadquizraceramprealredorichroadrockroofrubyruinrunsrustsafesagascarsetssilkskewslotsoapsolosongstubsurfswantacotasktaxitenttiedtimetinytoiltombtoystriptunatwinuglyundouniturgeuservastveryvetovialvibeviewvisavoidvowswallwandwarmwaspwavewaxywebswhatwhenwhizwolfworkyankyawnyellyogayurtzapszerozestzinczonezoom"

    private val MINIMAL: Map<String, Int> = (0 until 256).associateBy { "${WORDS[it * 4]}${WORDS[it * 4 + 3]}" }

    private fun crc32(b: ByteArray): Long = java.util.zip.CRC32().apply { update(b) }.value

    private fun be32(v: Long) = ByteArray(4) { ((v ushr (8 * (3 - it))) and 0xff).toByte() }

    fun bytewordsEncode(body: ByteArray): String {
        val all = body + be32(crc32(body))
        val sb = StringBuilder(all.size * 2)
        for (b in all) { val i = b.toInt() and 0xff; sb.append(WORDS[i * 4]).append(WORDS[i * 4 + 3]) }
        return sb.toString()
    }

    fun bytewordsDecode(s: String): ByteArray {
        val t = s.lowercase()
        require(t.length % 2 == 0 && t.length >= 10) { "Not a UR." }
        val all = ByteArray(t.length / 2) { i -> (MINIMAL[t.substring(2 * i, 2 * i + 2)] ?: throw IllegalArgumentException("Not a UR.")).toByte() }
        val body = all.copyOfRange(0, all.size - 4)
        if (be32(crc32(body)).contentEquals(all.copyOfRange(all.size - 4, all.size))) return body
        // Some encoders (SeedSigner's Python port among them) drop a leading zero byte of the CRC.
        val short = all.copyOfRange(0, all.size - 3)
        val c = crc32(short)
        require(c < 0x1000000L && be32(c).copyOfRange(1, 4).contentEquals(all.copyOfRange(all.size - 3, all.size))) {
            "A QR frame was misread (checksum). Keep the camera on the code."
        }
        return short
    }

    // ------------------------------------------------------------------ CBOR (the bits needed)

    private fun cborHead(major: Int, v: Long): ByteArray = when {
        v < 24 -> byteArrayOf(((major shl 5) or v.toInt()).toByte())
        v < 0x100 -> byteArrayOf(((major shl 5) or 24).toByte(), v.toByte())
        v < 0x10000 -> byteArrayOf(((major shl 5) or 25).toByte(), (v ushr 8).toByte(), v.toByte())
        v < 0x100000000L -> byteArrayOf(((major shl 5) or 26).toByte()) + be32(v)
        else -> byteArrayOf(((major shl 5) or 27).toByte()) + ByteArray(8) { (v ushr (8 * (7 - it))).toByte() }
    }

    fun cborBytes(b: ByteArray): ByteArray = cborHead(2, b.size.toLong()) + b

    private class Cbor(val b: ByteArray) {
        var o = 0
        fun head(expectMajor: Int): Long {
            require(o < b.size) { "Not a UR." }
            val first = b[o++].toInt() and 0xff
            require(first ushr 5 == expectMajor) { "Not a UR." }
            val info = first and 0x1f
            fun n(k: Int): Long { require(o + k <= b.size); var v = 0L; repeat(k) { v = (v shl 8) or (b[o++].toLong() and 0xff) }; return v }
            return when (info) { in 0..23 -> info.toLong(); 24 -> n(1); 25 -> n(2); 26 -> n(4); 27 -> n(8); else -> throw IllegalArgumentException("Not a UR.") }
        }
        fun uint() = head(0)
        fun bytes(): ByteArray { val l = head(2).toInt(); require(l >= 0 && o + l <= b.size) { "Not a UR." }; return b.copyOfRange(o, o + l).also { o += l } }
    }

    fun cborUnwrapBytes(b: ByteArray): ByteArray = Cbor(b).let { c -> c.bytes().also { require(c.o == b.size) { "Not a UR." } } }

    // ------------------------------------------------------------------ fountain code

    /** xoshiro256** seeded with SHA-256 of [seed], as the reference does. */
    internal class Xoshiro(seed: ByteArray) {
        private val s = LongArray(4)
        init {
            val d = Hashes.sha256(seed)
            for (i in 0 until 4) { var v = 0L; for (n in 0 until 8) v = (v shl 8) or (d[i * 8 + n].toLong() and 0xff); s[i] = v }
        }
        fun next(): Long {
            val result = java.lang.Long.rotateLeft(s[1] * 5, 7) * 9
            val t = s[1] shl 17
            s[2] = s[2] xor s[0]; s[3] = s[3] xor s[1]; s[1] = s[1] xor s[2]; s[0] = s[0] xor s[3]
            s[2] = s[2] xor t
            s[3] = java.lang.Long.rotateLeft(s[3], 45)
            return result
        }
        fun nextDouble(): Double = next().toULong().toDouble() / 18446744073709551616.0
        fun nextInt(low: Int, high: Int): Int = (nextDouble() * (high - low + 1) + low).toInt()
    }

    /** Walker's alias method, laid out exactly as the reference's RandomSampler. */
    private class Sampler(probs: DoubleArray) {
        val p = DoubleArray(probs.size); val alias = IntArray(probs.size)
        init {
            val n = probs.size; val total = probs.sum()
            val P = DoubleArray(n) { probs[it] * n / total }
            val S = ArrayList<Int>(); val L = ArrayList<Int>()
            for (i in n - 1 downTo 0) if (P[i] < 1) S += i else L += i
            while (S.isNotEmpty() && L.isNotEmpty()) {
                val a = S.removeAt(S.size - 1); val g = L.removeAt(L.size - 1)
                p[a] = P[a]; alias[a] = g
                P[g] += P[a] - 1
                if (P[g] < 1) S += g else L += g
            }
            while (L.isNotEmpty()) p[L.removeAt(L.size - 1)] = 1.0
            while (S.isNotEmpty()) p[S.removeAt(S.size - 1)] = 1.0
        }
        fun next(rng: Xoshiro): Int {
            val r1 = rng.nextDouble(); val r2 = rng.nextDouble()
            val i = (p.size * r1).toInt()
            return if (r2 < p[i]) i else alias[i]
        }
    }

    /** Which fragments part [seqNum] carries (0-based indexes). */
    internal fun chooseFragments(seqNum: Long, seqLen: Int, checksum: Long): Set<Int> {
        if (seqNum <= seqLen) return setOf((seqNum - 1).toInt())
        val rng = Xoshiro(be32(seqNum) + be32(checksum))
        val degree = Sampler(DoubleArray(seqLen) { 1.0 / (it + 1) }).next(rng) + 1
        val remaining = (0 until seqLen).toMutableList()
        val shuffled = ArrayList<Int>(seqLen)
        while (remaining.isNotEmpty()) shuffled += remaining.removeAt(rng.nextInt(0, remaining.size - 1))
        return shuffled.take(degree).toSet()
    }

    // ------------------------------------------------------------------ encoder

    /**
     * The frames of an animated QR, generated on demand. [maxFragment] bytes of message per frame
     * (the reference picks equal fragments no longer than that). A message that fits one frame
     * gives the single-part form `ur:crypto-psbt/<bytewords>` every time.
     */
    class Encoder(message: ByteArray, private val type: String = PSBT_TYPE, maxFragment: Int = 120, minFragment: Int = 10) {
        private val cbor = message
        private val checksum = crc32(message)
        private val fragmentLen: Int
        private val fragments: List<ByteArray>
        private var seq = 0L

        init {
            require(message.isNotEmpty())
            var len = message.size
            for (count in 1..maxOf(1, message.size / minFragment)) {
                len = (message.size + count - 1) / count
                if (len <= maxFragment) break
            }
            fragmentLen = len
            fragments = (0 until (message.size + len - 1) / len).map { i ->
                ByteArray(len).also { f -> val from = i * len; message.copyInto(f, 0, from, minOf(message.size, from + len)) }
            }
        }

        val seqLen: Int get() = fragments.size
        val isSinglePart: Boolean get() = fragments.size == 1

        fun nextPart(): String {
            if (isSinglePart) return "ur:$type/${bytewordsEncode(cbor)}"
            seq = (seq + 1) % 0x100000000L
            val data = ByteArray(fragmentLen)
            for (i in chooseFragments(seq, seqLen, checksum)) for (k in 0 until fragmentLen) data[k] = (data[k].toInt() xor fragments[i][k].toInt()).toByte()
            val part = cborHead(4, 5) + cborHead(0, seq) + cborHead(0, seqLen.toLong()) + cborHead(0, cbor.size.toLong()) +
                cborHead(0, checksum) + cborBytes(data)
            return "ur:$type/$seq-$seqLen/${bytewordsEncode(part)}"
        }
    }

    /** A PSBT as the frames SeedSigner scans. */
    fun psbtEncoder(psbt: ByteArray, maxFragment: Int = 120) = Encoder(cborBytes(psbt), PSBT_TYPE, maxFragment)

    // ------------------------------------------------------------------ decoder

    /**
     * Collects scanned frames until the message is whole. Feed every QR text to [receive]; it
     * returns true once [result] is ready. Frames of another message (another checksum or type)
     * start over, so a stale code on screen cannot get mixed in.
     */
    class Decoder {
        var type: String? = null; private set
        private var seqLen = 0
        private var messageLen = 0
        private var checksum = -1L
        private var fragmentLen = 0
        private val simple = HashMap<Int, ByteArray>()
        private val mixed = ArrayList<Pair<MutableSet<Int>, ByteArray>>()
        private val seen = HashSet<Long>()
        var result: ByteArray? = null; private set

        /** How far along: fragments known / needed (0..1). */
        val progress: Float get() = if (seqLen == 0) 0f else simple.size.toFloat() / seqLen

        fun receive(text: String): Boolean {
            if (result != null) return true
            val t = text.trim().lowercase()
            require(t.startsWith("ur:")) { "Not a UR." }
            val path = t.substring(3).split("/")
            val ty = path[0]
            if (path.size == 2) {                            // single part
                type = ty; result = bytewordsDecode(path[1]); return true
            }
            require(path.size == 3) { "Not a UR." }
            val c = Cbor(bytewordsDecode(path[2]))
            require(c.head(4) == 5L) { "Not a UR." }
            val n = c.uint(); val len = c.uint().toInt(); val mlen = c.uint().toInt(); val crc = c.uint(); val data = c.bytes()
            require(len in 1..10_000 && mlen > 0 && data.isNotEmpty()) { "Not a UR." }
            if (crc != checksum || ty != type || len != seqLen || mlen != messageLen || data.size != fragmentLen) {
                type = ty; seqLen = len; messageLen = mlen; checksum = crc; fragmentLen = data.size
                simple.clear(); mixed.clear(); seen.clear()
            }
            if (!seen.add(n)) return false
            add(chooseFragments(n, seqLen, checksum).toMutableSet(), data.copyOf())
            if (simple.size == seqLen) {
                val all = ByteArray(seqLen * fragmentLen)
                for (i in 0 until seqLen) simple.getValue(i).copyInto(all, i * fragmentLen)
                val msg = all.copyOfRange(0, messageLen)
                if (crc32(msg) != checksum) { simple.clear(); mixed.clear(); seen.clear(); return false }
                result = msg
                return true
            }
            return false
        }

        private fun xorInto(a: ByteArray, b: ByteArray) { for (k in a.indices) a[k] = (a[k].toInt() xor b[k].toInt()).toByte() }

        private fun add(idx: MutableSet<Int>, data: ByteArray) {
            // Strip the fragments already known out of this part.
            for (i in idx.toList()) simple[i]?.let { xorInto(data, it); idx.remove(i) }
            when (idx.size) {
                0 -> return
                1 -> {
                    val i = idx.first()
                    if (simple.containsKey(i)) return
                    simple[i] = data
                    // A new fragment may reduce the waiting mixed parts.
                    val pending = mixed.toList(); mixed.clear()
                    pending.forEach { (s, d) -> add(s, d) }
                }
                else -> mixed += idx to data
            }
        }

        /** The PSBT inside a finished `crypto-psbt`. */
        fun psbt(): ByteArray {
            val r = result ?: error("The QR code is not complete yet.")
            require(type == PSBT_TYPE) { "This QR code is not a PSBT (it is ur:$type)." }
            return cborUnwrapBytes(r)
        }
    }
}
