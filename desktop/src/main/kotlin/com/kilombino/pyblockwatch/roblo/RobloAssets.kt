package com.kilombino.pyblockwatch.roblo

import com.kilombino.pyblockwatch.BuildConfig
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The desktop's copy of Android's RobloAssets: the bundled Roblosats client, served by the JCEF
 * page at https://appassets.androidplatform.net/roblo/ (the same origin as on the phone, so the
 * same client zip works on both). The zip is the pinned one (roblo-client/CLIENT): it is read
 * from the app once, its SHA-256 checked against [BuildConfig.ROBLO_CLIENT_SHA256], and served
 * from memory. Anything outside /roblo/ or not in the zip is refused; index.html carries the CSP
 * (no remote origin at all) and gets the bridge's shim injected before the client's own scripts.
 */
object RobloAssets {
    const val ORIGIN_HOST = "appassets.androidplatform.net"
    const val BASE = "https://$ORIGIN_HOST/roblo/"

    /**
     * No remote origin at all: scripts, styles, images, sounds, workers and WebAssembly only from
     * the bundle; connect-src 'self' blocks fetch/XHR/WebSocket to anywhere (the bridge does that).
     * The same policy as Android's.
     */
    const val CSP = "default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; " +
        "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' data: blob:; " +
        "font-src 'self' data:; connect-src 'self' data: blob:; worker-src 'self' blob:; " +
        "object-src 'none'; base-uri 'self'; form-action 'none'; frame-src 'none'"

    private val MIME = mapOf(
        "html" to "text/html", "js" to "text/javascript", "css" to "text/css", "wasm" to "application/wasm",
        "json" to "application/json", "mp3" to "audio/mpeg", "webp" to "image/webp", "png" to "image/png",
        "svg" to "image/svg+xml", "woff2" to "font/woff2", "woff" to "font/woff", "ttf" to "font/ttf",
        "txt" to "text/plain", "asc" to "text/plain", "ico" to "image/x-icon", "jpg" to "image/jpeg",
    )

    /** The path for [scheme]://[host][path] inside the bundle ("roblo/…"), or null when it is not one of ours. */
    fun assetPath(scheme: String?, host: String?, path: String?): String? {
        if (scheme != "https" || host != ORIGIN_HOST) return null
        if (path == null) return null
        if (!path.startsWith("/roblo/")) return null
        val rel = path.removePrefix("/roblo/").ifEmpty { "index.html" }
        if (rel.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return "roblo/$rel"
    }

    /** [assetPath] for a whole URL (its query and fragment ignored). */
    fun assetPath(url: String): String? = runCatching { java.net.URI(url) }.getOrNull()?.let { assetPath(it.scheme, it.host, it.rawPath) }

    fun mimeOf(path: String): String = MIME[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /** One answer of the page's local origin. */
    class Answer(val status: Int, val mime: String, val headers: Map<String, String>, val body: ByteArray)

    @Volatile private var files: Map<String, ByteArray>? = null

    /**
     * The client's files ("roblo/<path>" → bytes), from the zip in the app (resource
     * roblo/roblo-client.zip), checked against the pinned hash the first time.
     */
    fun files(): Map<String, ByteArray> = files ?: synchronized(this) {
        files ?: run {
            val zip = RobloAssets::class.java.getResourceAsStream("/roblo/roblo-client.zip")?.use { it.readBytes() }
                ?: error("the Roblosats client is not in this build")
            check(sha256(zip) == BuildConfig.ROBLO_CLIENT_SHA256) { "the Roblosats client in this build is not the pinned one" }
            unzip(zip).also { files = it }
        }
    }

    internal fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    internal fun unzip(zip: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() }) continue
                out["roblo/$name"] = z.readBytes()
            }
        }
        return out
    }

    /**
     * The answer for [url]: the file from the bundle, or 403 for anything not ours, 404 for what is
     * not in it. index.html gets [shim] (a <script>, the bridge for this page) put first in <head>.
     */
    fun answer(url: String, shim: () -> String, bundle: Map<String, ByteArray> = files()): Answer {
        val path = assetPath(url) ?: return Answer(403, "text/plain", emptyMap(), ByteArray(0))
        val bytes = bundle[path] ?: return Answer(404, "text/plain", emptyMap(), ByteArray(0))
        val mime = mimeOf(path)
        val headers = LinkedHashMap<String, String>()
        headers["Cache-Control"] = "no-store"
        headers["X-Content-Type-Options"] = "nosniff"
        if (mime == "text/html") {
            headers["Content-Security-Policy"] = CSP
            return Answer(200, mime, headers, inject(String(bytes, Charsets.UTF_8), shim()).toByteArray(Charsets.UTF_8))
        }
        return Answer(200, mime, headers, bytes)
    }

    /** [html] with [script] as the first thing in its <head> (before any of the client's scripts). */
    fun inject(html: String, script: String): String {
        val tag = "<script>$script</script>"
        val i = html.indexOf("<head>")
        return if (i >= 0) html.substring(0, i + 6) + tag + html.substring(i + 6) else tag + html
    }
}
