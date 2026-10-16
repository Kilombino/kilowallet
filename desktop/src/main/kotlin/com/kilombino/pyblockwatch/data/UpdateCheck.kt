package com.kilombino.pyblockwatch.data

import com.kilombino.kilowallet.desktop.BuildInfo
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Schnorr
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * The desktop's update check, with the phone's API (the shared screen offers what it finds).
 * Releases live in Kilombino/kilowallet-desktop; betas count while the desktop app is a beta.
 *
 * A release is offered for download only when its SHA256SUMS file carries a valid BIP-340
 * signature (SHA256SUMS.sig) by the release key pinned below: the hash the download is then
 * checked against comes from that signed file, not from anything GitHub could change alone.
 */
object UpdateCheck {
    private const val API = "https://api.github.com/repos/Kilombino/kilowallet-desktop/releases?per_page=10"
    /** Kilombino's release key for the desktop app (x-only public key, BIP-340). */
    const val RELEASE_PUBKEY = "3e2fcd910f0e78ab222bacadb1c438f5deb7677319e8f95b01417a603c01f257"

    /** [apkUrl]: the file to install (AppImage, .deb, .msi or .dmg, whichever this copy is); [sha256]: from the signed sums. */
    class Release(val version: String, val url: String, val apkUrl: String?, val sha256: String? = null)

    private fun parse(v: String): Triple<List<Int>, Boolean, Int> {
        val clean = v.removePrefix("desktop-").removePrefix("v").trim()
        val nums = clean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pre = clean.contains('-')
        val preNum = if (pre) Regex("""(\d+)$""").find(clean.substringAfter('-'))?.value?.toIntOrNull() ?: 0 else 0
        return Triple(nums, pre, preNum)
    }

    /** True when [remote] is newer than [current]: by number, then a final beats its betas, then beta2 beats beta1. */
    fun isNewer(remote: String, current: String): Boolean {
        val (r, rPre, rN) = parse(remote); val (c, cPre, cN) = parse(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        if (cPre != rPre) return cPre
        return rPre && rN > cN
    }

    sealed class Outcome {
        class Newer(val release: Release) : Outcome()
        object UpToDate : Outcome()
        object Unreachable : Outcome()
    }

    fun check(current: String): Release? = (outcome(current) as? Outcome.Newer)?.release

    fun outcome(current: String): Outcome = runCatching<Outcome> {
        val list = JSONArray(get(API))
        val newest = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { !it.optBoolean("draft") }
            .maxWithOrNull { a, b -> if (isNewer(a.getString("tag_name"), b.getString("tag_name"))) 1 else if (isNewer(b.getString("tag_name"), a.getString("tag_name"))) -1 else 0 }
            ?: return Outcome.UpToDate
        val tag = newest.getString("tag_name")
        if (!isNewer(tag, current)) return Outcome.UpToDate
        val assets = newest.optJSONArray("assets") ?: JSONArray()
        fun asset(pred: (String) -> Boolean) = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { pred(it.optString("name")) }
        val file = asset { BuildInfo.releaseFile(it) }
        val sums = asset { it == "SHA256SUMS" }?.optString("browser_download_url")
        val sig = asset { it == "SHA256SUMS.sig" }?.optString("browser_download_url")
        val version = tag.removePrefix("desktop-").removePrefix("v")
        val sha = if (file != null && sums != null && sig != null) signedHash(get(sums), get(sig), file.getString("name")) else null
        // Without a valid signature the release is only offered as a page to open.
        Outcome.Newer(Release(version, newest.getString("html_url"), if (sha != null) file?.optString("browser_download_url") else null, sha))
    }.getOrDefault(Outcome.Unreachable)

    /** The hash of [name] in [sums], only when [sigHex] is the release key's signature of the whole file. */
    internal fun signedHash(sums: String, sigHex: String, name: String): String? {
        val sig = runCatching { Hashes.hexToBytes(sigHex.trim()) }.getOrNull()?.takeIf { it.size == 64 } ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sums.toByteArray(Charsets.UTF_8))
        if (!Schnorr.verify(Hashes.hexToBytes(RELEASE_PUBKEY), digest, sig)) return null
        return sums.lines().map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == name && it[0].matches(Regex("[0-9a-f]{64}")) }?.get(0)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Kilowallet-desktop")
        try { check(c.responseCode == 200) { "HTTP ${c.responseCode}" }; return c.inputStream.bufferedReader().use { it.readText() } }
        finally { c.disconnect() }
    }
}
