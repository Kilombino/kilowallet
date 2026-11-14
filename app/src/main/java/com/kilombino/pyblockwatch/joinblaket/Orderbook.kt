package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex

/** An offer whose fidelity bond proof verified for (our nick, its maker); not yet checked on chain. */
data class BondedOffer(val offer: Offer, val bond: BondProof) {
    /** `(txid, vout, scriptPubKey)`: offers sharing it are one bond claim (`taker/taker.py:848-899`). */
    val claim: Triple<String, Long, String>
        get() = Triple(bond.txid, bond.vout, bond.scriptPubKey.toHex())
}

/**
 * The `!orderbook` round (`DirectoryClient.fetch_orderbooks`, `_parse_offer_from_message`): ask
 * once, listen adaptively, keep the offers that carry a valid fidelity bond proof.
 */
object Orderbook {
    /** `orderbook_min_wait`, `orderbook_quiet_period`, `order_wait_time` (`taker/config.py:217-245`). */
    data class Timing(val minWaitMs: Long = 30_000, val quietMs: Long = 15_000, val maxWaitMs: Long = 120_000)

    /**
     * [offers]: bonded offers, one per `(maker, oid)`. [offerMessages]: offer frames heard, [dropped]:
     * those refused (bad signature, not for us, unparsable, no or invalid bond proof) or repeated.
     */
    data class Result(val offers: List<BondedOffer>, val offerMessages: Int, val dropped: Int)

    const val MAX_COLLECTED_MESSAGES = 10_000
    const val MAX_COLLECTED_BYTES = 32L * 1024 * 1024
    private val OFFER_PREFIXES = listOf("sw0absoffer", "sw0reloffer", "swabsoffer", "swreloffer")

    /** Python's lightweight `is_offer` while listening: an offer type anywhere in the line. */
    private fun looksLikeOffer(f: Wire.Frame) =
        (f.type == Wire.PRIVMSG || f.type == Wire.PUBMSG) && OFFER_PREFIXES.any { it in f.line }

    /**
     * GETPEERLIST, then PUBMSG `<nick>!PUBLIC!orderbook`, then listen in 1 s steps: at least
     * `minWait`, stop after `quiet` without a new offer, never past `maxWait` (both clamped into
     * `maxWait` like Python). This sends nothing else: it is exactly an orderbook watcher's request.
     */
    suspend fun fetch(client: DirectoryClient, timing: Timing = Timing(), now: () -> Long = System::currentTimeMillis): Result {
        client.getPeerlist()
        val minWait = minOf(timing.minWaitMs, timing.maxWaitMs)
        val quiet = if (timing.maxWaitMs > minWait) minOf(timing.quietMs, timing.maxWaitMs - minWait) else 0L
        client.sendPublic("orderbook")
        val start = now()
        var lastOffer = start
        val heard = ArrayList<Wire.Frame>()
        var bytes = 0L
        while (true) {
            val elapsed = now() - start
            if (elapsed >= timing.maxWaitMs) break
            if (elapsed >= minWait && quiet > 0 && now() - lastOffer >= quiet) break
            val f = client.receive(minOf(1_000L, timing.maxWaitMs - elapsed)) ?: continue
            if (!looksLikeOffer(f)) continue
            lastOffer = now()
            if (heard.size >= MAX_COLLECTED_MESSAGES || bytes + f.line.length > MAX_COLLECTED_BYTES) continue
            heard.add(f); bytes += f.line.length
        }
        return parse(heard, client.nick)
    }

    /**
     * The offers among [frames] for taker [ourNick], keyed by `(maker, oid)` (the copy with the
     * later certificate expiry wins, `taker/multi_directory.py:629-660`).
     *
     * A PRIVMSG must be addressed to us and its signature must verify (Python skips this check
     * on the orderbook path and relies on the directory's nick binding; checking it costs
     * nothing). Its bond proof is verified for `(ourNick, maker)`; a PUBMSG's for
     * `(maker, maker)`, as Python does.
     */
    fun parse(frames: List<Wire.Frame>, ourNick: String): Result {
        val byKey = LinkedHashMap<Pair<String, Long>, BondedOffer>()
        var count = 0
        for (f in frames) {
            if (!looksLikeOffer(f)) continue
            count++
            val parts = f.line.split("!", limit = 3)
            if (parts.size < 3 || parts[2].isBlank()) continue
            val (from, to, rest) = parts
            val content: String
            val proofNick: String
            if (f.type == Wire.PRIVMSG) {
                if (to != ourNick) continue
                val v = Nick.verifyPrivmsg(from, rest)
                if (!v.ok) continue
                content = "${v.command} ${v.data}"
                proofNick = ourNick
            } else {
                if (to != "PUBLIC") continue
                content = rest
                proofNick = from
            }
            val offer = Offers.parse(content, from) ?: continue
            val bond = offer.bondProof?.let(BondProof::decode) ?: continue
            if (bond.verify(from, proofNick) != null) continue
            val key = from to offer.oid
            val old = byKey[key]
            if (old == null || bond.certExpiryEncoded > old.bond.certExpiryEncoded) byKey[key] = BondedOffer(offer, bond)
        }
        return Result(byKey.values.toList(), count, count - byKey.size)
    }
}
