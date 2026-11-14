package com.kilombino.pyblockwatch.coinjoin

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil

/**
 * The JoinBlaket market (JoinMarket on BTC BLAKE2b), as start10's orderbook watcher sees it:
 * the offers of makers with a verified fidelity bond. It feeds "PAY THROUGH JOINBLAKET" on the
 * send screen: how many makers there are, the smallest and largest amount they take, and what a
 * CoinJoin of a given amount would cost (makers' fees + mining fee).
 *
 * Source: https://mempool.kilombino.com/joinblaket/market.json (no nicks, no bond UTXOs).
 */
object JoinBlaketMarket {
    const val URL_MARKET = "https://mempool.kilombino.com/joinblaket/market.json"

    data class Offer(val rel: Boolean, val minsize: Long, val maxsize: Long, val cjfee: Double, val txfee: Long, val bondValue: Long) {
        /** This maker's fee for a CoinJoin of [amount] sats. */
        fun fee(amount: Long): Long = if (rel) ceil(amount * cjfee).toLong() else cjfee.toLong()
        fun takes(amount: Long): Boolean = amount in minsize..maxsize
    }

    data class Market(val makers: Int, val minAmount: Long?, val maxAmount: Long?, val offers: List<Offer>, val height: Int?)

    data class Estimate(
        val makers: Int,          // makers that take this amount (the CoinJoin goes on with all of them, up to [MAX_MAKERS])
        val makerFees: Long,      // what they charge in total
        val miningFee: Long,      // the whole CoinJoin's mining fee, which the taker pays
    ) {
        val total: Long get() = makerFees + miningFee
    }

    /** Jam asks for 8-10 makers; the estimate never counts more than that. */
    const val MAX_MAKERS = 9

    fun parse(json: String): Market {
        val o = JSONObject(json)
        val arr = o.optJSONArray("offers")
        val offers = (0 until (arr?.length() ?: 0)).map { i ->
            val x = arr!!.getJSONObject(i)
            Offer(
                rel = x.optString("type") == "rel",
                minsize = x.getLong("minsize"),
                maxsize = x.getLong("maxsize"),
                cjfee = x.optString("cjfee", "0").toDoubleOrNull() ?: 0.0,
                txfee = x.optLong("txfee", 0),
                bondValue = x.optLong("bond_value", 0),
            )
        }
        return Market(
            makers = o.optInt("makers", 0),
            minAmount = if (o.isNull("min_amount")) null else o.optLong("min_amount"),
            maxAmount = if (o.isNull("max_amount")) null else o.optLong("max_amount"),
            offers = offers,
            height = if (o.isNull("height")) null else o.optInt("height"),
        )
    }

    /** Blocking fetch (call off the main thread). */
    fun fetch(): Market {
        val c = URL(URL_MARKET).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Kilowallet")
        try {
            if (c.responseCode != 200) throw IllegalStateException("market HTTP ${c.responseCode}")
            return parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /**
     * What a CoinJoin of [amount] sats would cost at [feeRate] sat/vB: the cheapest offers that take
     * the amount (up to [MAX_MAKERS]), and a mining fee for one P2WPKH input and two outputs per
     * maker plus the taker's input, CoinJoin output and change. Null if no maker takes the amount.
     */
    fun estimate(market: Market, amount: Long, feeRate: Double, takerInputs: Int = 1): Estimate? {
        val chosen = market.offers.filter { it.takes(amount) }.sortedBy { it.fee(amount) }.take(MAX_MAKERS)
        if (chosen.isEmpty()) return null
        val n = chosen.size
        val vbytes = 11 + 68 * (n + takerInputs) + 31 * (2 * n + 2)
        val mining = ceil(vbytes * feeRate).toLong() - chosen.sumOf { it.txfee }
        return Estimate(n, chosen.sumOf { it.fee(amount) }, maxOf(0L, mining))
    }
}
