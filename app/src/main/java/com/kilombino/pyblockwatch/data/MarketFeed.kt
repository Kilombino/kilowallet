package com.kilombino.pyblockwatch.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * XBT price and mining figures, as shown in the mempool.kilombino.com header.
 * Everything here comes from public sources; the server just computes it once.
 */
data class MarketData(
    val xbtUsd: Double?,
    val xbtEur: Double?,
    val changePct: Double?,
    val high24Usd: Double?,
    val low24Usd: Double?,
    /** How many Poolsats (units of the SHA-256 Spamchain) one XBT is worth. */
    val xbtPoolsats: Long?,
    /** 24h change of [xbtPoolsats], in percent. */
    val poolsatsChangePct: Double?,
    /** Total network hashrate in H/s (average of the last 144 blocks). */
    val networkHashps: Double?,
    val height: Long?,
    val thsXbtDay: Double?,
    val thsUsdDay: Double?,
    val rentPoolsatsPerThDay: Double?,
    val rentUsdPerThDay: Double?,
    val kwhPerXbt: Double?,
    val yshValue: Double?,
    val yshUnit: String?,
    val chainSizeGB: Double?,
    val fetchedMs: Long,
) {
    /** Fiat value of [sats] XBT, or null when there is no price yet. */
    fun fiatValue(sats: Long, fiat: String): Double? {
        val px = if (fiat == "EUR") xbtEur else xbtUsd
        return px?.let { it * sats / 1e8 }
    }
}

/**
 * Client for https://mempool.kilombino.com/api/v1/blake2b/widget.
 *
 * Load protection (the server side has a 60 s cache and a per-IP limit; this is the
 * phone's half of the deal, so many installs never hurt the server or the mining):
 *  - never more often than the server's `pollMinutes` (at least 15 min);
 *  - on HTTP 429 it waits for `Retry-After` (at least 10 min);
 *  - every failure doubles the wait, up to 6 h, instead of retrying in step with everyone;
 *  - it is optional: without it the wallet works exactly as before, just with no prices.
 */
object MarketFeed {
    const val API = "https://mempool.kilombino.com/api/v1/blake2b/widget"
    const val SOURCE = "mempool.kilombino.com"

    private const val PREFS = "market"
    private const val KEY_JSON = "json"
    private const val KEY_FETCHED = "fetched"
    private const val KEY_NEXT = "next_allowed"
    private const val KEY_BACKOFF = "backoff_min"
    private const val KEY_LAST_TRY = "last_try"
    private const val KEY_LIMITED_UNTIL = "limited_until"
    private const val MIN_POLL_MIN = 15L
    private const val MAX_BACKOFF_MIN = 6 * 60L
    private const val STALE_MS = 45 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cached(ctx: Context): MarketData? {
        val p = prefs(ctx)
        val json = p.getString(KEY_JSON, null) ?: return null
        return runCatching { parse(json, p.getLong(KEY_FETCHED, 0L)) }.getOrNull()
    }

    fun isStale(m: MarketData): Boolean = System.currentTimeMillis() - m.fetchedMs > STALE_MS

    /**
     * Fetches fresh data if the server allows it right now; otherwise returns the cache.
     * [force] (the refresh button) skips the 15-min schedule and the failure back-off, so
     * one network hiccup no longer leaves the button dead for hours; it never skips a 429
     * and still waits at least one minute between attempts.
     */
    @Synchronized
    fun refresh(ctx: Context, force: Boolean = false): MarketData? {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val next = p.getLong(KEY_NEXT, 0L)
        val allowedByForce = force && now >= p.getLong(KEY_LIMITED_UNTIL, 0L) &&
            now - p.getLong(KEY_LAST_TRY, 0L) >= 60_000L
        if (now < next && !allowedByForce) return cached(ctx)
        p.edit().putLong(KEY_LAST_TRY, now).apply()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "KilombinoWallet")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == 429) {
                val retry = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 0L
                val until = now + maxOf(retry * 1000, 10 * 60_000L)
                p.edit().putLong(KEY_NEXT, until).putLong(KEY_LIMITED_UNTIL, until).apply()
                return cached(ctx)
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body, now) // validate before storing
            val poll = maxOf(JSONObject(body).optLong("pollMinutes", MIN_POLL_MIN), MIN_POLL_MIN)
            p.edit()
                .putString(KEY_JSON, body)
                .putLong(KEY_FETCHED, now)
                .putLong(KEY_NEXT, now + poll * 60_000L - 60_000L)
                .putLong(KEY_BACKOFF, MIN_POLL_MIN)
                .apply()
        } catch (e: Exception) {
            val backoff = min(p.getLong(KEY_BACKOFF, MIN_POLL_MIN) * 2, MAX_BACKOFF_MIN)
            p.edit().putLong(KEY_NEXT, now + backoff * 60_000L).putLong(KEY_BACKOFF, backoff).apply()
        } finally {
            conn?.disconnect()
        }
        return cached(ctx)
    }

    private fun JSONObject.num(k: String): Double? =
        if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

    private fun parse(json: String, fetchedMs: Long): MarketData {
        val o = JSONObject(json)
        return MarketData(
            xbtUsd = o.num("xbtUsd"),
            xbtEur = o.num("xbtEur"),
            changePct = o.num("changePct"),
            high24Usd = o.num("high24Usd"),
            low24Usd = o.num("low24Usd"),
            xbtPoolsats = o.num("xbtPoolsats")?.toLong(),
            poolsatsChangePct = o.num("poolsatsChangePct"),
            networkHashps = o.num("networkHashps"),
            height = o.num("height")?.toLong(),
            thsXbtDay = o.num("thsBtcDay"),
            thsUsdDay = o.num("thsUsdDay"),
            rentPoolsatsPerThDay = o.num("rentPoolsatsPerThDay"),
            rentUsdPerThDay = o.num("rentUsdPerThDay"),
            kwhPerXbt = o.num("kwhPerBtc"),
            yshValue = o.num("yshValue"),
            yshUnit = o.str("yshUnit"),
            chainSizeGB = o.num("chainSizeGB"),
            fetchedMs = fetchedMs,
        )
    }
}
