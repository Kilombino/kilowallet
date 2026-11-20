package com.kilombino.pyblockwatch.roblo

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Serves the bundled Roblosats client (assets/roblo/) at https://appassets.androidplatform.net/roblo/,
 * as androidx's WebViewAssetLoader does (without adding that library): a secure origin of its own,
 * so the page has WebCrypto, workers and WebAssembly without file:// and its universal access.
 * Everything else the page tries to load is refused: it has no network of its own.
 */
object RobloAssets {
    /**
     * No remote origin at all: scripts, styles, images, sounds, workers and WebAssembly only from
     * the bundle; connect-src 'self' blocks fetch/XHR/WebSocket to anywhere (the bridge does that).
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

    /** The asset path for [url] under assets/roblo/, or null when it is not one of ours. */
    fun assetPath(url: Uri): String? = assetPath(url.scheme, url.host, url.path)

    fun assetPath(scheme: String?, host: String?, path: String?): String? {
        if (scheme != "https" || host != RobloWeb.ORIGIN_HOST) return null
        if (path == null) return null
        if (!path.startsWith("/roblo/")) return null
        val rel = path.removePrefix("/roblo/").ifEmpty { "index.html" }
        if (rel.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return "roblo/$rel"
    }

    fun mimeOf(path: String): String = MIME[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    fun serve(assets: AssetManager, url: Uri): WebResourceResponse {
        val path = assetPath(url) ?: return refused()
        return runCatching {
            val bytes = assets.open(path).use { it.readBytes() }
            val mime = mimeOf(path)
            val headers = HashMap<String, String>()
            headers["Cache-Control"] = "no-store"
            headers["X-Content-Type-Options"] = "nosniff"
            if (mime == "text/html") headers["Content-Security-Policy"] = CSP
            val charset = if (mime.startsWith("text/") || mime == "application/json") "utf-8" else null
            WebResourceResponse(mime, charset, 200, "OK", headers, ByteArrayInputStream(bytes))
        }.getOrElse { WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))) }
    }

    private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
