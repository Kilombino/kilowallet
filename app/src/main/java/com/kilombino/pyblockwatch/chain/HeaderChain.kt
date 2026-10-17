package com.kilombino.pyblockwatch.chain

import com.kilombino.pyblockwatch.crypto.BlockHeader
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import java.io.File
import java.math.BigInteger

/**
 * The BTC (BLAKE2b) chain's block headers, verified on this phone from a checkpoint built into
 * the app, so a payment can be proven against blocks the server cannot make up (SPV, as
 * Electrum or Sparrow do on a desktop).
 *
 * Every header the server hands over must link to the one before it, carry the height it claims,
 * have exactly the difficulty the consensus rules give it (Bitcoin's retarget every 2016 blocks,
 * at most ×4 either way) and meet its own target. Faking one block costs mining a real one at
 * the network's difficulty. A transaction is then proven by its merkle branch against the
 * verified header of its block.
 *
 * Kept in the scan cache directory; a reorganisation the server shows is followed only when its
 * branch is valid and longer than the one held.
 */
object HeaderChain {
    /** A difficulty period's first block, checked against Kilombino's node: 14 Oct 2026. */
    const val CHECKPOINT_HEIGHT = 975_744
    const val CHECKPOINT_HASH = "00000000000000007f85bb387c9128bc606a483ad7a7784701517effadca9c34"
    private const val CHECKPOINT_RAW =
        "000000a0914d7dc839e917db1d227113b0517f95ac01a8369f244b8b0000000000000000a02efc7e74d0a8eb4a6264d4369f994a" +
        "c640b7348885453174553d282bc55030d801c46a2ad80019b3a4d855126bb672d801c46a00000000b18cf0180c00000000000000" +
        "000000000b00042fb22c6a4c4863dfc52519e00a20f73e0080e30e000000000000000000000000000000000000000000000000" +
        "000000000000000000"

    const val INTERVAL = 2016

    /**
     * The difficulty ("bits") of every period below the checkpoint, from Kilombino's node: one
     * 8-hex-digit value per 2016 blocks, from block 0. Below the checkpoint no header chain is
     * held, so a block's header must carry exactly its period's difficulty: faking one costs a
     * real block of that period, not just any work above a floor. The BLAKE2b activation split
     * the period starting at 961,632: its blocks from 961,640 on have [ACTIVATION_BITS].
     */
    private const val PERIOD_BITS =
        "1d00ffff1d00ffff1d00ffff1d00ffff1d00ffff1d00ffff1d00ffff1d00ffff1d00ffff1d00ffff1d00ffff1d00ffff" +
        "1d00ffff1d00ffff1d00ffff1d00ffff1d00d86a1d00c4281d00be711d008cc31c6546571c43b3e51c387f6f1c381375" +
        "1c2a11151c20bca71c16546f1c13ec531c159c241c0f675c1c0eba641c0d31421c0ae4931c05a3f41c0168fd1c010c5a" +
        "1c00ba181c00800e1b6920981b5bede61b4766ed1b31b2a31b2f8e9d1b1e7eca1b1532631b0e72561b098b2a1b081cd2" +
        "1b0559531b04864c1b0404cb1b038dee1b02fa291b0285521b01cc261b012dcd1b00dc311b00f3391b00cbbd1b00b5ac" +
        "1b0098fa1a6a93b31a44b9f21a2694211a1d932f1a1321851a0c2a121a0abbcf1a09ec041a08e1e51a094a861a096fe3" +
        "1a098ea51a09ee5d1a0b6d4b1a0df0ca1a0e119a1a0f61b11a0e86681a0e76ba1a0d69d71a0cd43f1a0c290b1a0c309c" +
        "1a0b350c1a0b32871a0a507e1a0aa1e31a0b1ef71a09ae021a0a8b5f1a0a98d61a09b78a1a0994311a08fd2e1a083cc9" +
        "1a07a85e1a06dfbe1a063a381a05db8b1a057e081a0575ef1a0513c51a04faeb1a04e0ea1a04fa621a05a16b1a0529b1" +
        "1a05a6b11a051f3c1a04985c1a03d74b1a0375fa1a02816e1a022fbe1a01de941a01aa3d1a017fe91a0161641a011337" +
        "1a00de151a00c94e1a00a4291a0089681972dbf219548732194152571931679c19262222191cdc201916b0ca19100ab6" +
        "190afc85190867f319070bfb190612421904ba6e1903a30c1903071f190266661901f52c1901a36e19015f5319012026" +
        "190102b11900db991900b3aa19009d8c1900896c187c305318692842185d859a1851aba218415fd1183f6be6183aaea2" +
        "1837ba62182e1c58182815ee1824dbe9181fb893181f6973181e8dc0181bc330181b4861181b7b74181bdd7c181b0dca" +
        "1819012f181aa3c01818bb8718178d3a18172ec0181788f218163c71181717f0181713dd181686f518171a8b18162043" +
        "1816418e181586c8181508151814dd04181443c418134dc1181287ba1812147218120f141811a9541810b289180f1e76" +
        "180de64f180bc409180a95911809b31b180928f01807a1141806b99f1806f0a81806a4c3180696f41806274b18062776" +
        "1805a8fa1805843618059ba018053fd6180526fd180526691805722818050edc1804fb081804de5e18048ed4180440c4" +
        "180455d2180451741803e6d41803d58918038b85180375ff180343791802cc4718029ab918027e93180262df18024fb1" +
        "1802335a18021c7318021b3e1801f6a71801d85418019eaf18018b7e18018d3018015ddc18014735180130e018013ce9" +
        "1801310b1800ff181800fa731800eb301800c1bd1800ce4b1800d0f61800b0ed18009645180091c1177e578c176c2146" +
        "1761e9f8175d97dc175589a317514a4917502ab71749500d1745fb531743eca917415a491738f84117376f5617347a28" +
        "17365a17172f4f7b172c0da71729d72d172819a117275a1f1725c19117272fbd17272d92172a4e2f1731d97c17371ef4" +
        "173218a5172fd63317306835172e6f88172e5b50172e6117172c1f6c172c071d172c4e111729ff381729fb451725bb76" +
        "1725fd031723792c171f0d9b171f3a08171c3039171ba3d1171a213e1717abf517160f241715a35c17148edf171620d1" +
        "1715b23e1715dbd21715bcd0171465f217130c78171232ff17121ad417122cbc1711011917143b41171320bc1711a333" +
        "17117a39171297f617147f351711d4f21711d51917103a151710b4f817109bac171007ea17103a12170e92aa170e95de" +
        "170e134e1710c433170ffedd170eb156170f1372170f2217170da8a1170d8457170d21b9170cf4e3170d1f8c170cdf6f" +
        "170c2a48170bef93170da863170b3ce9170d5f7b170e1ef9171398ce1714962417136aa21712180b170ffaa0170f48e4" +
        "170ed0eb170e2632170e0408170cfecf170c69ea170c9a13170ba21f170b98ab170b8c8b170a9080170a8bb4170a1078" +
        "170a3773170a40c01709d8971709f8d917097275170901ba17096a2017094b6a170984cc1709a7af170a2a041709fd7e" +
        "1709ed88170916201708c8941708f9ae1707e7721707a4291707a81217079e15170838301707f5901708417e17077ce2" +
        "1707272017073039170689a3170676811706023e1705e0b21705c7391705dd011705ae3a17057e691705606d17058ebe" +
        "170538941705610217055f5b17050f7b170532ae1704ed7f1704e90f17049ca91704819417045a121704245017042e95" +
        "1703e8b31703d8691704005a1703ba5d170371b117038c1217035a59170362d317034219170331db1703629a170355f0" +
        "17035cb817035d2517038a6d17036e3a17031abe17033d761703255b1703098c17032f1417030ecd1702f1281702c4e4" +
        "1702c0701702b563170297fa1702905c17028c6117029a8a1702772617028bb1170282811702796c17025105170248b6" +
        "17025ced170250491702377417023a041702681617023aa61702349e17022cb317022b91170211ac1701fa381701ddb4" +
        "1701eb211701cdfb1701d9361701e2a01701e63a1701e6051701ebf21701fca117023c7e1701f3031701f0cc17021a91" +
        "170206841702136917021ff017020f791702068f170240c317021a421702369d17023ad41702353d1a00f0b5193c2d40" +
        "190f0b501903c2d4190141c01900edba"
    private const val ACTIVATION_BITS = 0x1a008d4fL

    /** The difficulty block [height] (below the checkpoint) has on the real chain, or null. */
    internal fun periodBits(height: Int): Long? {
        if (height < 0 || height >= CHECKPOINT_HEIGHT) return null
        if (height >= Chain.BLAKE2B_FORK_HEIGHT && height < (Chain.BLAKE2B_FORK_HEIGHT / INTERVAL + 1) * INTERVAL) return ACTIVATION_BITS
        val i = height / INTERVAL * 8
        if (i + 8 > PERIOD_BITS.length) return null
        return PERIOD_BITS.substring(i, i + 8).toLong(16)
    }
    /** The least BLAKE2b work a block before the checkpoint must show: just easier than the easiest real one (0x1a00f0b5). */
    const val BLAKE2B_FLOOR_BITS = 0x1a00ffffL
    private const val TIMESPAN = 14L * 24 * 60 * 60
    private val POW_LIMIT = BigInteger("00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16)
    private const val SIZE = 164
    /** How far back a reorganisation is looked for before the server is called wrong. */
    private const val MAX_REORG = 144

    @Volatile private var dir: File? = null
    private val chain = ArrayList<BlockHeader.Parsed>()
    private val hashes = ArrayList<String>()
    /** txid@blockhash proven so far: a block's contents never change. */
    private val proven = HashSet<String>()

    /**
     * The highest verified height. Read without the lock: a sync holds it while it waits on the
     * network, and the settings screen must not wait with it.
     */
    @Volatile private var verifiedTip = CHECKPOINT_HEIGHT
    val tip: Int get() = verifiedTip
    private fun mark() { verifiedTip = CHECKPOINT_HEIGHT + chain.size - 1 }

    /** The checkpoint alone, when no directory was set (a process that never looked at a chain). */
    private fun ensure() {
        if (chain.isNotEmpty()) return
        val cp = BlockHeader.parse(CHECKPOINT_RAW)
        chain += cp; hashes += BlockHeader.blockHash(cp)
        mark()
    }

    @Synchronized
    fun useDir(d: File) {
        if (dir == d && chain.isNotEmpty()) return
        dir = d
        chain.clear(); hashes.clear(); proven.clear()
        val cp = BlockHeader.parse(CHECKPOINT_RAW)
        check(BlockHeader.blockHash(cp) == CHECKPOINT_HASH) { "the built-in checkpoint does not hash to its block" }
        chain += cp; hashes += CHECKPOINT_HASH
        // Headers verified on an earlier run; anything that does not start from the checkpoint is dropped.
        runCatching {
            val f = File(d, FILE)
            if (f.exists()) {
                val b = f.readBytes()
                if (b.size >= SIZE && b.size % SIZE == 0 && b.copyOfRange(0, SIZE).contentEquals(cp.raw)) {
                    for (o in SIZE until b.size step SIZE) {
                        val h = BlockHeader.Parsed(b.copyOfRange(o, o + SIZE))
                        val hash = BlockHeader.blockHash(h)
                        if (prevHash(h) != hashes.last()) break
                        chain += h; hashes += hash
                    }
                }
            }
        }
        seenBelow.clear()
        val seenFile = File(d, SEEN)
        firstRun = !seenFile.exists()
        runCatching { if (firstRun) seenFile.createNewFile() else seenFile.readLines().filter(String::isNotBlank).let(seenBelow::addAll) }
        runCatching { File(d, PROVEN).takeIf { it.exists() }?.readLines()?.let { proven.addAll(it.filter(String::isNotBlank)) } }
        mark()
    }

    /** The verified hash of block [height], or null outside what is verified. */
    @Synchronized
    fun hashAt(height: Int): String? { ensure(); return hashes.getOrNull(height - CHECKPOINT_HEIGHT) }

    /**
     * Brings the verified chain up to the server's tip. Returns the verified tip, or null when
     * the server's headers do not verify (it is lying, or on another chain): nothing it said is
     * kept then. A server that is merely behind leaves the verified chain as it is.
     */
    @Synchronized
    fun sync(client: ElectrumClient): Int? {
        if (client.isOwnNode) return null
        ensure()
        val serverTip = client.blockHeight()
        val held = CHECKPOINT_HEIGHT + chain.size - 1
        if (serverTip <= held) {
            // Behind or level: its header at its tip must be ours, or it shows another branch.
            if (serverTip < CHECKPOINT_HEIGHT) return null
            val theirs = BlockHeader.blockHash(BlockHeader.parse(client.headers(serverTip, 1).first()))
            return if (theirs == hashAt(serverTip)) held else null
        }
        var next = held + 1
        // A reorganisation is tried on the chain as it was: if the other branch fails to verify
        // anywhere, or does not carry more work than the blocks it would replace, nothing of it
        // is kept and the verified chain stays as it was.
        var before: Pair<List<BlockHeader.Parsed>, List<String>>? = null
        var replacedWork = BigInteger.ZERO
        var forkAt = 0 // index in [chain] of the last block both branches share
        fun undo(): Int? { before?.let { (c, h) -> chain.clear(); chain += c; hashes.clear(); hashes += h; mark() }; return null }
        try {
            while (next <= serverTip) {
                val batch = client.headers(next, minOf(INTERVAL, serverTip - next + 1)).map(BlockHeader::parse)
                if (batch.isEmpty()) break
                if (prevHash(batch.first()) != hashes.last()) {
                    // The server's branch leaves ours somewhere below: find where, and follow it there.
                    if (before != null) return undo()
                    val fork = forkPoint(client) ?: return null
                    before = ArrayList(chain) to ArrayList(hashes)
                    forkAt = fork - CHECKPOINT_HEIGHT
                    replacedWork = chain.subList(forkAt + 1, chain.size).fold(BigInteger.ZERO) { a, h -> a + work(h) }
                    truncateTo(fork)
                    next = fork + 1
                    continue
                }
                for ((k, h) in batch.withIndex()) {
                    val height = next + k
                    if (!valid(h, height)) { if (before != null) return undo(); save(); return null }
                    chain += h; hashes += BlockHeader.blockHash(h)
                }
                mark()
                next += batch.size
            }
            if (before != null) {
                val newWork = chain.subList(forkAt + 1, chain.size).fold(BigInteger.ZERO) { a, h -> a + work(h) }
                if (newWork <= replacedWork) return undo()
            }
        } catch (e: Exception) {
            undo()
            throw e
        }
        save()
        return CHECKPOINT_HEIGHT + chain.size - 1
    }

    /**
     * Whether [txid] is in block [height], by its merkle branch against the verified header:
     * true or false, or null when the block is not verified yet or the server gave no branch.
     * Below the checkpoint, where no header is held, the header the server gives must meet its
     * own target at no less than [floorBits]; from the BLAKE2b activation on, it must be a BLAKE2b
     * header and the floor is [BLAKE2B_FLOOR_BITS] (the activation cut the difficulty: blocks
     * 961640–967679 are easier than [floorBits], down to 0x1a00f0b5).
     */
    @Synchronized
    fun proves(client: ElectrumClient, txid: String, height: Int, floorBits: Long): Boolean? {
        if (client.isOwnNode) return true // your own node validated the block itself
        ensure()
        if (isProven(txid, height)) return true
        if (height < CHECKPOINT_HEIGHT) return runCatching {
            val header = BlockHeader.parse(client.blockHeader(height))
            val blake = height >= Chain.BLAKE2B_FORK_HEIGHT
            val floor = if (blake) BLAKE2B_FLOOR_BITS else floorBits
            val (branch, pos) = client.merkle(txid, height)
            // The block's own period difficulty, exactly; a BLAKE2b block also carries its height.
            (!blake || (header.extended && embeddedHeight(header) == height.toLong())) &&
                header.bits == periodBits(height) &&
                BlockHeader.inBlock(txid, branch, pos, header) && BlockHeader.meetsItsTarget(header) &&
                BlockHeader.target(header.bits) <= BlockHeader.target(floor)
        }.getOrNull()?.also { if (it) remember(belowKey(txid, height)) }
        val header = chain.getOrNull(height - CHECKPOINT_HEIGHT) ?: return null
        val key = "$txid@${hashes[height - CHECKPOINT_HEIGHT]}"
        val (branch, pos) = runCatching { client.merkle(txid, height) }.getOrNull() ?: return null
        val ok = BlockHeader.inBlock(txid, branch, pos, header)
        if (ok) remember(key)
        return ok
    }

    /** Whether [txid] was already proven in block [height] (of the verified chain, or below the checkpoint): no server call. */
    @Synchronized
    fun isProven(txid: String, height: Int): Boolean {
        if (height < CHECKPOINT_HEIGHT) return belowKey(txid, height) in proven
        val hash = hashes.getOrNull(height - CHECKPOINT_HEIGHT) ?: return false
        return "$txid@$hash" in proven
    }

    /**
     * The key of a proof below the checkpoint. It names the rules it was made under, so proofs
     * made under easier rules (before 1.1.3: any work above a floor) are made again, not kept.
     */
    private fun belowKey(txid: String, height: Int) = "$txid@h$height#$BELOW_RULES"
    private const val BELOW_RULES = 2

    /**
     * Transactions below the checkpoint this install has seen. The first run with this list (a
     * new or restored wallet, or the first after updating) notes every one it finds; afterwards a
     * transaction that first turns up in a block below the checkpoint was not there before in a
     * block that old, which is what a lying server faking a payment would show, so [Scanner]
     * shows it as not proven.
     */
    private val seenBelow = HashSet<String>()
    @Volatile private var firstRun = true

    /** Whether [txid], in block [height] below the checkpoint, may be proven from one header. */
    @Synchronized
    fun knownBelow(txid: String): Boolean {
        if (txid in seenBelow) return true
        if (!firstRun) return false
        seenBelow += txid
        runCatching { dir?.let { File(it, SEEN).appendText(txid + "\n") } }
        return true
    }

    private fun remember(key: String) {
        proven += key
        runCatching { dir?.let { File(it, PROVEN).appendText(key + "\n") } }
    }

    // ------------------------------------------------------------------------------- rules

    /** Header [h] at [height], on top of the verified chain's last header. */
    private fun valid(h: BlockHeader.Parsed, height: Int): Boolean {
        if (h.raw.size != SIZE || !h.extended) return false
        val prev = chain.last()
        if (prevHash(h) != hashes.last()) return false
        if (embeddedHeight(h) != height.toLong()) return false
        if (h.bits != expectedBits(height, prev.bits, prev.time) { chain.getOrNull(it - CHECKPOINT_HEIGHT)?.time }) return false
        return BlockHeader.meetsItsTarget(h)
    }

    /**
     * The difficulty block [height] must have: the previous block's, except on a period's first
     * block, where it is retargeted from how long the last 2016 blocks took (clamped to ×4).
     * [timeAt] gives a verified block's time.
     */
    internal fun expectedBits(height: Int, prevBits: Long, prevTime: Long, timeAt: (Int) -> Long?): Long? {
        if (height % INTERVAL != 0) return prevBits
        val first = timeAt(height - INTERVAL) ?: return null
        val span = (prevTime - first).coerceIn(TIMESPAN / 4, TIMESPAN * 4)
        val t = BlockHeader.target(prevBits).multiply(BigInteger.valueOf(span)).divide(BigInteger.valueOf(TIMESPAN))
        return compact(t.min(POW_LIMIT))
    }

    /** The work a header stands for, as Bitcoin counts it: 2^256 / (target + 1). */
    private fun work(h: BlockHeader.Parsed): BigInteger =
        BigInteger.ONE.shiftLeft(256).divide(BlockHeader.target(h.bits).add(BigInteger.ONE))

    /** The compact ("bits") encoding of a target, as Bitcoin's GetCompact. */
    internal fun compact(target: BigInteger): Long {
        var n = (target.bitLength() + 7) / 8
        var c = if (n <= 3) target.shiftLeft(8 * (3 - n)).toLong() else target.shiftRight(8 * (n - 3)).toLong()
        c = c and 0xffffff
        if (c and 0x800000L != 0L) { c = c ushr 8; n++ }
        return c or (n.toLong() shl 24)
    }

    private fun prevHash(h: BlockHeader.Parsed): String = h.raw.copyOfRange(4, 36).reversedArray().toHex()
    private fun embeddedHeight(h: BlockHeader.Parsed): Long =
        (0 until 4).fold(0L) { a, i -> a or ((h.raw[128 + i].toLong() and 0xff) shl (8 * i)) }
    private val BlockHeader.Parsed.time: Long
        get() = (0 until 4).fold(0L) { a, i -> a or ((raw[68 + i].toLong() and 0xff) shl (8 * i)) }

    /** The highest height at which the server's branch and ours agree, within [MAX_REORG]. */
    private fun forkPoint(client: ElectrumClient): Int? {
        val top = CHECKPOINT_HEIGHT + chain.size - 1
        val low = maxOf(CHECKPOINT_HEIGHT, top - MAX_REORG)
        val theirs = runCatching { client.headers(low, top - low + 1) }.getOrNull() ?: return null
        var fork: Int? = null
        for ((k, hex) in theirs.withIndex()) {
            if (BlockHeader.blockHash(BlockHeader.parse(hex)) == hashAt(low + k)) fork = low + k else break
        }
        return fork
    }

    private fun truncateTo(height: Int) {
        val keep = height - CHECKPOINT_HEIGHT + 1
        while (chain.size > keep) { chain.removeAt(chain.size - 1); hashes.removeAt(hashes.size - 1) }
        mark()
    }

    private fun save() {
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            val tmp = File(d, "$FILE.tmp")
            tmp.outputStream().use { o -> chain.forEach { o.write(it.raw) } }
            tmp.renameTo(File(d, FILE))
        }
    }

    private const val FILE = "headers-blake2b.bin"
    private const val PROVEN = "proven-blake2b.txt"
    private const val SEEN = "seen-below-blake2b.txt"

    /** For tests: start over from the checkpoint alone, without a directory. */
    @Synchronized
    internal fun resetForTest() {
        dir = null; chain.clear(); hashes.clear(); proven.clear()
        val cp = BlockHeader.parse(CHECKPOINT_RAW)
        chain += cp; hashes += BlockHeader.blockHash(cp)
        mark()
    }

    /** For tests: verify and append raw headers as a server would hand them over. */
    @Synchronized
    internal fun appendForTest(raw: List<String>): Boolean {
        for (hex in raw) {
            val h = BlockHeader.parse(hex)
            if (!valid(h, CHECKPOINT_HEIGHT + chain.size)) return false
            chain += h; hashes += BlockHeader.blockHash(h)
            mark()
        }
        return true
    }
}
