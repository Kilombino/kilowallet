package com.kilombino.pyblockwatch.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer published release on GitHub (Kilombino/kilowallet). Only the latest
 * non-beta release counts. Whoever runs GitHub sees the request (an IP asking about this
 * repository), so it can be switched off in settings.
 */
object UpdateCheck {
    private const val API = "https://api.github.com/repos/Kilombino/kilowallet/releases/latest"

    class Release(val version: String, val url: String, val apkUrl: String?)

    /** "0.20.0-beta3" → [0, 20, 0] plus whether it is a pre-release. */
    private fun parse(v: String): Pair<List<Int>, Boolean> {
        val clean = v.removePrefix("v").trim()
        val nums = clean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        return nums to clean.contains('-')
    }

    /** True when [remote] is newer than [current] (a final release beats its own betas). */
    fun isNewer(remote: String, current: String): Boolean {
        val (r, rPre) = parse(remote); val (c, cPre) = parse(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return cPre && !rPre
    }

    /** The newer release, or null when up to date or unreachable. Blocking: call off the UI thread. */
    fun check(current: String): Release? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Kilowallet")
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        c.disconnect()
        val tag = o.getString("tag_name")
        if (!isNewer(tag, current)) return null
        val assets = o.optJSONArray("assets")
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
        Release(tag.removePrefix("v"), o.getString("html_url"), apk)
    }.getOrNull()
}
