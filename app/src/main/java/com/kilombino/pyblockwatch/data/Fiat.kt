package com.kilombino.pyblockwatch.data

import java.util.Locale
import kotlin.math.abs

/**
 * The fiat currencies the wallet can show values in. USD and EUR always; the rest once the
 * market feed brings their exchange rate (mempool.kilombino.com: the ECB's daily reference,
 * and open.er-api.com for what the ECB does not publish). Without a rate a currency is not
 * offered, and a choice whose rate went missing shows "–" like any missing price.
 */
object Fiat {
    /** [symbol] goes before the number; null writes the code after it ("1,234.56 RON"). */
    data class Currency(val code: String, val symbol: String?, val decimals: Int)

    val ALL: List<Currency> = listOf(
        Currency("USD", "$", 2), Currency("EUR", "€", 2), Currency("GBP", "£", 2), Currency("JPY", "¥", 0),
        Currency("CHF", null, 2), Currency("CNY", "CN¥", 2), Currency("RON", null, 2), Currency("PLN", null, 2),
        Currency("CZK", null, 2), Currency("HUF", null, 0), Currency("SEK", null, 2), Currency("NOK", null, 2),
        Currency("DKK", null, 2), Currency("ISK", null, 0), Currency("TRY", "₺", 2), Currency("CAD", "CA$", 2),
        Currency("AUD", "A$", 2), Currency("NZD", "NZ$", 2), Currency("BRL", "R$", 2), Currency("MXN", "MX$", 2),
        Currency("ARS", null, 2), Currency("CLP", null, 0), Currency("COP", null, 0), Currency("PEN", null, 2),
        Currency("UYU", null, 2), Currency("VES", null, 2), Currency("INR", "₹", 2), Currency("KRW", "₩", 0),
        Currency("HKD", "HK$", 2), Currency("SGD", "S$", 2), Currency("ZAR", null, 2), Currency("ILS", "₪", 2),
        Currency("THB", null, 2), Currency("IDR", null, 0), Currency("PHP", "₱", 2), Currency("MYR", null, 2),
    )

    private val byCode = ALL.associateBy { it.code }

    fun of(code: String): Currency = byCode[code] ?: Currency(code, null, 2)

    /** The ones with a price in [m]: USD and EUR always, the rest when the feed has their rate. */
    fun available(m: MarketData?): List<Currency> =
        ALL.filter { it.code == "USD" || it.code == "EUR" || m?.fiatPerUsd?.containsKey(it.code) == true }

    /** "$1,234.56", "¥157,880", "1,234.56 RON". */
    fun format(value: Double, code: String): String {
        val c = of(code)
        val n = String.format(Locale.US, "%,.${c.decimals}f", value)
        return if (c.symbol != null) c.symbol + n else "$n ${c.code}"
    }

    /** Like [format] but for an amount being typed: no thousands separators, the currency's decimals. */
    fun plain(value: Double, code: String): String {
        val d = of(code).decimals
        return if (abs(value) < 1e15) String.format(Locale.US, "%.${d}f", value) else ""
    }

    /** "≈ $ –" style placeholder when there is no price yet. */
    fun missing(code: String): String = of(code).symbol?.let { "$it –" } ?: "– $code"

    /**
     * Sats for [fiat] units of [code] at [perCoin] (fiat per whole coin), or null when the text
     * is not a number or there is no price. Accepts a comma as the decimal mark.
     */
    fun toSats(fiat: String, perCoin: Double?): Long? {
        val v = parse(fiat) ?: return null
        if (perCoin == null || perCoin <= 0 || v < 0) return null
        return Math.round(v / perCoin * 1e8)
    }

    /**
     * A typed amount: "12.5", "12,5", "1,234.56" or "1.234,56" (the last mark is the decimal one
     * when both appear; a lone mark is decimal too). Null if it is not a number.
     */
    fun parse(text: String): Double? {
        val t = text.trim().replace(" ", "").replace(" ", "")
        if (t.isEmpty()) return null
        val dot = t.lastIndexOf('.'); val comma = t.lastIndexOf(',')
        val normal = when {
            dot >= 0 && comma >= 0 -> if (dot > comma) t.replace(",", "") else t.replace(".", "").replace(',', '.')
            comma >= 0 -> if (t.count { it == ',' } == 1) t.replace(',', '.') else t.replace(",", "")
            t.count { it == '.' } > 1 -> t.replace(".", "")
            else -> t
        }
        return normal.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
