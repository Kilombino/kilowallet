package com.kilombino.pyblockwatch.roblo

import android.util.Log
import android.webkit.JavascriptInterface
import com.kilombino.pyblockwatch.net.HttpLite
import com.kilombino.pyblockwatch.net.WsClient
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Roblosats client's bridge (window.AndroidAppRobosats), written again on Kilowallet's own
 * engines: storage in [RobloStore] (sealed with the keystore), every request over [RobloNet]
 * (Kilowallet's Tor, a circuit per robot), the clipboard, the share sheet, its own logs and
 * "keep the screen on". The same method names and answers as the Roblosats app's
 * WebAppInterface, so the client needs nothing special; methods it does not have here (Roblosats'
 * own updater, its notification service and battery settings) are simply absent, and the client
 * hides what depends on them.
 *
 * Closed list: only the methods below are callable from the page. The page never gets anything of
 * the wallet (no xpub, words, balances or coins).
 */
class RobloBridge(
    private val host: Host,
    private val store: RobloStore,
    private val net: RobloNet,
) {
    /** What the bridge needs from the screen that shows the page. */
    interface Host {
        /** Runs [js] in the page (on the main thread). */
        fun evaluate(js: String)
        fun copyToClipboard(text: String)
        fun share(text: String)
        fun keepScreenOn(on: Boolean)
        /** Loads the client again from the start (the page asks after a setting that needs it). */
        fun reload()
        /** "ON", "STARTING" or "OFF": the state of Kilowallet's Tor for this page. */
        fun torStatus(): String
        fun appVersion(): String
        /** This page's own log lines (console and bridge), for the client's "Recent logs". */
        fun recentLogs(): String
    }

    private val sockets = ConcurrentHashMap<String, WsClient>()
    private val inFlight = AtomicInteger(0)
    private val pool = ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_QUEUED)) { r ->
        Thread(r, "roblo-net").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    // ---------------------------------------------------------------- identity / small things

    /** {host, version, clearnet}: read once by the client to pick the coordinators' addresses. */
    @JavascriptInterface
    fun hostInfo(): String = JSONObject().put("host", "kilowallet").put("version", host.appVersion())
        .put("clearnet", store.clearnet()).toString()

    @JavascriptInterface
    fun appVersion(): String = host.appVersion()

    /** The WebAssembly build makes robot names and avatars itself: never asked here. */
    @JavascriptInterface
    fun generateRoboname(uuid: String, message: String) = reject(uuid, "not in this build")

    @JavascriptInterface
    fun generateRobohash(uuid: String, message: String) = reject(uuid, "not in this build")

    @JavascriptInterface
    fun copyToClipboard(message: String) { if (message.length <= MAX_TEXT) host.copyToClipboard(message) }

    @JavascriptInterface
    fun shareText(text: String) { if (text.length <= MAX_TEXT) host.share(text) }

    @JavascriptInterface
    fun keepScreenOn(on: Boolean) = host.keepScreenOn(on)

    @JavascriptInterface
    fun getTorStatus(uuid: String) {
        if (!validUuid(uuid)) return
        resolve(uuid, if (store.clearnet()) "OFF" else host.torStatus())
    }

    @JavascriptInterface
    fun getRecentLogs(uuid: String) {
        if (!validUuid(uuid)) return
        resolve(uuid, JSONObject().put("logs", host.recentLogs()).put("crash", "")
            .put("version", host.appVersion()).put("android", android.os.Build.VERSION.RELEASE ?: "")
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").toString())
    }

    /** No notification tapped yet in this phase: nothing to open. */
    @JavascriptInterface
    fun takePendingNavigation(): String = ""

    @JavascriptInterface
    fun restart() = host.reload()

    // ---------------------------------------------------------------- storage

    @JavascriptInterface
    fun getEncryptedStorage(uuid: String, key: String) {
        if (!validUuid(uuid)) return
        if (!RobloStore.validKey(key.trim())) { reject(uuid, "Invalid input parameters"); return }
        runCatching { store.get(key.trim()) }.onSuccess { resolve(uuid, it) }.onFailure { reject(uuid, "Error obtaining encrypted storage: $key") }
    }

    @JavascriptInterface
    fun setEncryptedStorage(uuid: String, key: String, value: String) {
        if (!validUuid(uuid)) return
        if (!RobloStore.validKey(key.trim())) { reject(uuid, "Invalid input parameters"); return }
        val ok = runCatching { store.set(key.trim(), value.trim()) }.getOrDefault(false)
        if (ok) resolve(uuid, key) else reject(uuid, "Error writing encrypted storage: $key")
    }

    @JavascriptInterface
    fun deleteEncryptedStorage(uuid: String, key: String) {
        if (!validUuid(uuid)) return
        if (!RobloStore.validKey(key.trim())) { reject(uuid, "Invalid input parameters"); return }
        runCatching { store.delete(key.trim()) }
        resolve(uuid, key)
    }

    // ---------------------------------------------------------------- HTTP

    @JavascriptInterface
    fun sendRequest(uuid: String, action: String, url: String, headers: String, body: String) {
        if (!validUuid(uuid)) return
        val method = if (action in setOf("GET", "POST", "PUT", "DELETE")) action else "GET"
        val hs = parseHeaders(headers) ?: run { reject(uuid, "Invalid headers"); return }
        submit(uuid) {
            val bytes = if (method == "POST" || method == "PUT") body.toByteArray(Charsets.UTF_8) else null
            val h = if (bytes != null && hs.keys.none { it.equals("Content-Type", true) }) hs + ("Content-Type" to "application/json; charset=utf-8") else hs
            val r = http(method, url, h, bytes)
            wrapJson(r.text(), r.headers)
        }
    }

    @JavascriptInterface
    fun sendBinary(uuid: String, url: String, headers: String, base64Data: String) {
        if (!validUuid(uuid)) return
        val hs = parseHeaders(headers) ?: run { reject(uuid, "Invalid headers"); return }
        submit(uuid) {
            val data = Base64.getMimeDecoder().decode(base64Data)
            val h = if (hs.keys.none { it.equals("Content-Type", true) }) hs + ("Content-Type" to "application/octet-stream") else hs
            http("PUT", url, h, data).text()
        }
    }

    @JavascriptInterface
    fun getBinary(uuid: String, url: String) {
        if (!validUuid(uuid)) return
        submit(uuid) { Base64.getEncoder().encodeToString(http("GET", url, emptyMap(), null).body) }
    }

    /** The on-chain rescue's explorer (one the user picks; it may be their own node at home). */
    @JavascriptInterface
    fun sendText(uuid: String, method: String, url: String, contentType: String, body: String) {
        if (!validUuid(uuid)) return
        if (method != "GET" && method != "POST") { reject(uuid, "Invalid request"); return }
        submit(uuid) {
            val bytes = if (method == "POST") body.toByteArray(Charsets.UTF_8) else null
            val h = if (bytes != null) mapOf("Content-Type" to contentType.ifBlank { "text/plain" }) else emptyMap()
            val r = http(method, url, h, bytes, userChosen = true)
            "{\"status\":${r.code},\"body\":\"${Base64.getEncoder().encodeToString(r.body)}\"}"
        }
    }

    private fun http(method: String, url: String, headers: Map<String, String>, body: ByteArray?, userChosen: Boolean = false): HttpLite.Response {
        val route = net.route(url, RobloNet.isolationKey(url, headers), userChosen)
        if (route is RobloNet.Route.Refused) error(route.why)
        return HttpLite.request(method, url, headers, body, net.connector(route))
    }

    private fun submit(uuid: String, work: () -> String) {
        if (closed) { reject(uuid, "closed"); return }
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT) { inFlight.decrementAndGet(); reject(uuid, "Too many requests"); return }
        try {
            pool.execute {
                try { resolve(uuid, work()) }
                catch (e: Throwable) { log("request failed: ${e.message}"); reject(uuid, "Request failed: ${e.message}") }
                finally { inFlight.decrementAndGet() }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) { inFlight.decrementAndGet(); reject(uuid, "Too many requests") }
    }

    // ---------------------------------------------------------------- WebSocket

    @JavascriptInterface
    fun openWS(uuid: String, path: String) {
        if (!validUuid(uuid)) return
        val route = net.route(path, RobloNet.isolationKey(path, emptyMap()))
        if (route is RobloNet.Route.Refused) { reject(uuid, "Invalid URL"); return }
        if (sockets.size >= MAX_SOCKETS) { reject(uuid, "Too many connections"); return }
        Thread({
            var ws: WsClient? = null
            val client = WsClient(path, net.connector(route),
                onText = { m -> if (sockets[path] === ws) onWs("onWSMessage", path, m) },
                onBinary = { b -> if (sockets[path] === ws) onWs("onWSMessage", path, b.joinToString("") { "%02x".format(it) }) },
                onClosed = { err ->
                    // Only the socket that is still the current one for its address may remove
                    // itself and tell the page (the client reconnects to the same address; an old
                    // one closing late must not drop the live one), as in the Roblosats app 0.9.10.
                    if (sockets.remove(path, ws)) { if (err != null) onWs("onWsError", path, null) else onWs("onWsClose", path, null) }
                })
            ws = client
            try {
                client.connect()
                if (closed) { client.close(); return@Thread }
                sockets.put(path, client)?.takeIf { it !== client }?.close()
                resolve(uuid, path)
            } catch (e: Throwable) {
                log("WebSocket failed: ${e.message}")
                reject(uuid, "false")
            }
        }, "roblo-ws").apply { isDaemon = true }.start()
    }

    @JavascriptInterface
    fun sendWsMessage(uuid: String, path: String, message: String) {
        if (!validUuid(uuid)) return
        if (sockets[path]?.send(message) == true) resolve(uuid, "true") else reject(uuid, "Error sending WebSocket message")
    }

    private fun onWs(fn: String, path: String, message: String?) {
        val args = if (message == null) "'${encodeForJavaScript(path)}'" else "'${encodeForJavaScript(path)}', '${encodeForJavaScript(message)}'"
        host.evaluate("window.AndroidRobosats && window.AndroidRobosats.$fn($args)")
    }

    /** The page goes away: every connection closes, nothing more is answered. */
    fun close() {
        closed = true
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- answers

    private fun resolve(uuid: String, result: String) {
        if (!validUuid(uuid) || closed) return
        host.evaluate("window.AndroidRobosats && window.AndroidRobosats.onResolvePromise('$uuid', '${encodeForJavaScript(result)}')")
    }

    private fun reject(uuid: String, error: String) {
        if (!validUuid(uuid) || closed) return
        host.evaluate("window.AndroidRobosats && window.AndroidRobosats.onRejectPromise('$uuid', '${encodeForJavaScript(error)}')")
    }

    private fun log(m: String) { runCatching { Log.i(TAG, m) } }

    companion object {
        const val TAG = "Roblo"
        const val MAX_IN_FLIGHT = 48
        const val MAX_QUEUED = 64
        const val MAX_SOCKETS = 32
        const val MAX_TEXT = 1_000_000
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun validUuid(s: String?) = s != null && UUID.matches(s)

        /** A string safe inside a single-quoted JavaScript literal (and inside an HTML page). */
        fun encodeForJavaScript(input: String?): String {
            if (input == null) return ""
            val sb = StringBuilder(input.length + 16)
            for (c in input) when (c) {
                '\\' -> sb.append("\\\\"); '\'' -> sb.append("\\'"); '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003C"); '>' -> sb.append("\\u003E"); '&' -> sb.append("\\u0026")
                '\u0000' -> {}
                ' ' -> sb.append("\\u2028"); ' ' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
            return sb.toString()
        }

        /** The page's headers ({"name": "value"}), or null when they are not that. */
        fun parseHeaders(json: String): Map<String, String>? = runCatching {
            val o = JSONObject(json.ifBlank { "{}" })
            val m = LinkedHashMap<String, String>()
            for (k in o.keys()) m[k] = o.optString(k)
            m
        }.getOrNull()

        /** {"json": <the answer>, "headers": {...}} as the client parses it; a non-JSON answer as a string. */
        fun wrapJson(body: String, headers: Map<String, String>): String {
            val trimmed = body.trim()
            val json = if (trimmed.isEmpty()) "null" else if (runCatching { org.json.JSONTokener(trimmed).nextValue(); isJson(trimmed) }.getOrDefault(false)) trimmed else JSONObject.quote(body)
            val hs = JSONObject(); headers.forEach { (k, v) -> hs.put(k, v) }
            return "{\"json\":$json, \"headers\": $hs}"
        }

        private fun isJson(s: String): Boolean = runCatching {
            when (s.first()) { '{' -> { JSONObject(s); true }; '[' -> { org.json.JSONArray(s); true }; else -> s == "null" || s == "true" || s == "false" || s.toDoubleOrNull() != null || (s.startsWith('"') && s.endsWith('"')) }
        }.getOrDefault(false)
    }
}
