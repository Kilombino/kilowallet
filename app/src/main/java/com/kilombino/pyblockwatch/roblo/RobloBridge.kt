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
        /** The garage was saved (a robot or an order changed): the background watcher may start. */
        fun garageChanged() {}
        /** The BTC explorer of Kilowallet's settings ("" when none). */
        fun explorer(): String = ""
        /** The navigation a notification left for the page; "" when none. */
        fun takeNavigation(): String = ""
        /** Which Kilowallet app shows the page (client kw4): "android", "desktop" or "ios". */
        fun platform(): String = "android"
        /**
         * The wallet functions this app offers the page now (client kw4: each button shows only
         * when its own one is listed). A Kilowallet without Lightning leaves out walletPayInvoice
         * and walletInvoice.
         */
        fun features(): List<String> = WALLET_FEATURES
    }

    private val sockets = ConcurrentHashMap<String, WsClient>()
    private val inFlight = AtomicInteger(0)
    private val pool = ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, LinkedBlockingQueue(MAX_QUEUED)) { r ->
        Thread(r, "roblo-net").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    // ---------------------------------------------------------------- identity / small things

    /**
     * {host, version, clearnet, platform, features}: read by the client to pick the coordinators'
     * addresses and (kw4) which wallet buttons to show.
     */
    @JavascriptInterface
    fun hostInfo(): String = JSONObject().put("host", "kilowallet").put("version", host.appVersion())
        .put("clearnet", store.clearnet()).put("platform", host.platform())
        .put("features", org.json.JSONArray(host.features().filter { it in WALLET_FEATURES })).toString()

    @JavascriptInterface
    fun appVersion(): String = host.appVersion()

    /** 0.9.11: Clearnet mode, read synchronously while the page starts (its first connection already uses it). */
    @JavascriptInterface
    fun isClearnet(): Boolean = store.clearnet()

    /**
     * 0.9.11: the page's network settings, in place (no restart): Clearnet mode and the coordinators'
     * clearnet hosts, the only ones that then go direct. Kilowallet always has its own Tor: useProxy
     * is kept as the client sends it. Open connections close so they reconnect the new way.
     */
    @JavascriptInterface
    fun setNetworkMode(useProxy: Boolean, clearnet: Boolean, hosts: String) {
        val clean = hosts.split(',').map { it.trim().lowercase() }
            .filter { it.matches(Regex("^[a-z0-9.-]{1,253}$")) && !it.endsWith(".onion") }.take(32)
        val changed = store.clearnet() != clearnet
        runCatching {
            store.set(RobloStore.CLEARNET, clearnet.toString())
            store.set(RobloStore.CLEARNET_HOSTS, clean.joinToString(","))
            store.set(RobloStore.USE_PROXY, "true")
        }
        if (changed) dropConnections()
    }

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
        // Tor runs in both modes (onion coordinators): its real state.
        resolve(uuid, host.torStatus())
    }

    @JavascriptInterface
    fun getRecentLogs(uuid: String) {
        if (!validUuid(uuid)) return
        resolve(uuid, JSONObject().put("logs", host.recentLogs()).put("crash", "")
            .put("version", host.appVersion()).put("android", android.os.Build.VERSION.RELEASE ?: "")
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").toString())
    }

    /** The order a tapped notification asked to open ({path, robot}), once; "" when none. */
    @JavascriptInterface
    fun takePendingNavigation(): String = host.takeNavigation()

    @JavascriptInterface
    fun restart() = host.reload()

    // ---------------------------------------------------------------- wallet (native sheets)

    init {
        RobloWallet.answer = { uuid, ok, value -> if (ok) resolve(uuid, value) else reject(uuid, value) }
    }

    /** Pay [sats] to [address] from the hot wallet (sheet: coins, fee, fingerprint); resolves the txid. */
    @JavascriptInterface
    fun walletPay(uuid: String, address: String, sats: Double, orderRef: String, feeHint: Double) {
        if (!validUuid(uuid)) return
        val amount = sats.toLong()
        RobloWallet.checkPay(address, amount, orderRef)?.let { reject(uuid, it); return }
        if (!RobloWallet.offer(RobloWallet.Request.Pay(uuid, address.trim(), amount, orderRef, if (feeHint.isFinite()) feeHint else 0.0)))
            reject(uuid, "busy")
    }

    /** Lock a hold invoice with the Lightning node; resolves "locked" once its HTLC is out. */
    @JavascriptInterface
    fun walletPayInvoice(uuid: String, bolt11: String, orderRef: String) {
        if (!validUuid(uuid)) return
        RobloWallet.checkInvoice(bolt11, orderRef)?.let { reject(uuid, it); return }
        if (!RobloWallet.offer(RobloWallet.Request.PayInvoice(uuid, bolt11.trim(), orderRef))) reject(uuid, "busy")
    }

    /** An address of the hot wallet, picked in the RECEIVE menu and accepted with its derivation. */
    @JavascriptInterface
    fun walletAddress(uuid: String, purpose: String, orderRef: String) {
        if (!validUuid(uuid)) return
        RobloWallet.checkAddress(purpose, orderRef)?.let { reject(uuid, it); return }
        if (!RobloWallet.offer(RobloWallet.Request.Address(uuid, purpose, orderRef))) reject(uuid, "busy")
    }

    /** An invoice from the Lightning node for the payout. */
    @JavascriptInterface
    fun walletInvoice(uuid: String, sats: Double, memo: String) {
        if (!validUuid(uuid)) return
        val amount = sats.toLong()
        if (amount !in 1..RobloWallet.MAX_SATS) { reject(uuid, "bad amount"); return }
        if (!RobloWallet.offer(RobloWallet.Request.Invoice(uuid, amount, memo.take(100)))) reject(uuid, "busy")
    }

    /** The explorer Kilowallet uses for BTC (its settings), for the client's links. */
    @JavascriptInterface
    fun hostExplorer(): String = host.explorer()

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
        if (ok && key.trim() == "garage_slots") host.garageChanged()
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
        val t0 = System.nanoTime()
        val r = HttpLite.request(method, url, headers, body, net.connector(route))
        // Timings per route for "Recent logs" and for comparing Tor with clearnet (no token, no body).
        log("$method ${hostPath(url)} ${if (route is RobloNet.Route.Tor) "tor" else "direct"} ${r.code} ${(System.nanoTime() - t0) / 1_000_000} ms")
        return r
    }

    /** "kilobot.kilombino.com/api/book" (no query: it may carry a token hash). */
    private fun hostPath(url: String) = runCatching { java.net.URI(url).let { (it.host ?: "") + (it.path ?: "") } }.getOrDefault("?")

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
            val client = WsClient(path, net.connector(route), pingEveryMs = 20_000, deadAfterMs = 50_000,
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
                val t0 = System.nanoTime()
                client.connect()
                log("WS ${hostPath(path)} ${if (route is RobloNet.Route.Tor) "tor" else "direct"} open ${(System.nanoTime() - t0) / 1_000_000} ms")
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

    /**
     * The phone lost its network: every WebSocket ends now with an error, so the page shows
     * "disconnected" at once and reconnects when the network is back (instead of believing it is
     * still connected until the ping notices, up to a minute later).
     */
    fun dropConnections() {
        sockets.values.toList().forEach { runCatching { it.abort() } }
    }

    /** The page goes away: every connection closes, nothing more is answered. */
    fun close() {
        closed = true
        // a sheet still open for this page has nobody to answer any more
        RobloWallet.request.value?.let { RobloWallet.request.value = null }
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
        /** Every wallet function the bridge has (the client kw4 shows a button per listed one). */
        val WALLET_FEATURES = listOf("walletPay", "walletPayInvoice", "walletAddress", "walletInvoice")
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
