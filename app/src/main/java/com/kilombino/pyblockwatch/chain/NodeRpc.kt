package com.kilombino.pyblockwatch.chain

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Base58
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** One way to reach the node's RPC: a URL (http://host:port, or a .onion through Tor) and its login. */
/**
 * [pin]: for https (a StartOS node serves its RPC over TLS with its own certificate authority),
 * the SHA-256 of the certificate trusted when the connection was added; any other is refused.
 */
data class RpcConn(val url: String, val user: String, val pass: String, val pin: String = "") {
    val isOnion: Boolean get() = runCatching { URL(url).host.endsWith(".onion") }.getOrDefault(false)
    val isTls: Boolean get() = url.startsWith("https://", ignoreCase = true)

    fun toJson(): JSONObject = JSONObject().put("url", url).put("user", user).put("pass", pass).put("pin", pin)

    companion object {
        fun fromJson(o: JSONObject) = RpcConn(o.getString("url"), o.optString("user"), o.optString("pass"), o.optString("pin"))

        /**
         * A connection from what a QR or a paste gives: `http://user:pass@host:port`,
         * `btcrpc://user:pass@host:port`, `user:pass@host:port`, or plain `host:port`.
         */
        fun parse(text: String): RpcConn {
            var t = text.trim()
            t = t.replaceFirst(Regex("^(btcrpc|bitcoin-rpc|rpc)://", RegexOption.IGNORE_CASE), "http://")
            if (!t.contains("://")) t = "http://$t"
            val u = URL(t)
            require(u.host.isNotEmpty()) { "No host in that address." }
            val (user, pass) = (u.userInfo ?: "").split(":", limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val port = if (u.port > 0) u.port else 8332
            return RpcConn("${u.protocol}://${u.host}:$port/", java.net.URLDecoder.decode(user, "UTF-8"), java.net.URLDecoder.decode(pass, "UTF-8"))
        }
    }
}

/** Reading the chain from the user's own node by RPC: its connections and the wallet's account key. */
data class RpcNode(val conns: List<RpcConn>, val accountKey: String, val type: ScriptType)

/**
 * The Electrum questions the wallet asks (per-address history, balance, coins), answered by the
 * user's own node instead of an Electrum server. The node gets a watch-only descriptor wallet
 * for the account (public key only), which tracks history and the mempool; on connect everything
 * is read once and then served from memory. The keys never reach the node.
 */
class NodeRpcBackend(private val node: RpcNode) {
    private var conn: RpcConn? = null
    private val descKey by lazy { toVersion(node.accountKey, 0x0488B21E) }
    val walletName by lazy { "kilowallet-" + Hashes.sha256(descKey.toByteArray()).toHex().take(10) }

    private val history = HashMap<String, MutableSet<Pair<String, Int>>>()
    /** TLS: the pin the current request must match ("" = record whatever comes, for TEST). */
    private var pinFor = ""
    /** TLS: the fingerprint of the certificate the last request saw. */
    var lastFingerprint: String? = null
        private set
    private var testing = false
    private val utxos = HashMap<String, MutableList<ElectrumClient.Utxo>>()

    class NodeException(message: String) : ElectrumException(message)

    private fun call(c: RpcConn, method: String, vararg params: Any?, wallet: String? = null, timeoutMs: Int = 60_000): Any? {
        val target = if (wallet == null) c.url else c.url.trimEnd('/') + "/wallet/" + java.net.URLEncoder.encode(wallet, "UTF-8")
        val proxy = if (c.isOnion) (torProxy?.invoke() ?: throw NodeException("${c.url} is a .onion: turn on Tor first.")) else null
        val body = JSONObject().put("jsonrpc", "1.0").put("id", "kw").put("method", method)
            .put("params", JSONArray().also { a -> params.forEach { a.put(it ?: JSONObject.NULL) } }).toString()
        val auth = "Basic " + Base64.getEncoder().encodeToString("${c.user}:${c.pass}".toByteArray())
        if (c.isTls && c.pin.isEmpty() && !testing) throw NodeException("${c.url} has no trusted certificate yet: remove it and add it again.")
        pinFor = c.pin
        val (code, text) = try { post(URL(target), body, auth, proxy, if (c.isOnion) 60_000 else 10_000, timeoutMs) } catch (e: java.net.SocketException) {
            // Through Tor, "connection refused" means the .onion was reached but nothing listens on that port.
            if (c.isOnion && e.message?.contains("refused", true) == true)
                throw NodeException("The .onion was reached, but nothing answers on port ${URL(target).port}. Copy the full address, port included, from your node's RPC interface.")
            throw e
        }
        if (code == 401 || code == 403) throw NodeException("The node refused the RPC login (user or password).")
        if (text.isBlank()) throw NodeException("HTTP $code from the node")
        val o = runCatching { JSONObject(text) }.getOrElse {
            throw NodeException("${c.url} answered (HTTP $code), but it is not a Bitcoin node's RPC.")
        }
        if (!o.isNull("error")) throw NodeException(o.getJSONObject("error").optString("message"))
        return o.opt("result")
    }

    /**
     * One HTTP POST. A node's RPC is plain HTTP (it has no TLS), so it goes over a socket of its
     * own: the app's network policy keeps requiring TLS for everything else. A .onion goes
     * through Tor's SOCKS proxy, with the name resolved by Tor. https:// uses the platform.
     */
    private fun post(u: URL, body: String, auth: String, proxy: java.net.Proxy?, connectMs: Int, readMs: Int): Pair<Int, String> {
        if (u.protocol == "https") {
            // The node's own certificate (StartOS signs it with its own CA, unknown to the phone):
            // trusted by its fingerprint, pinned when the connection was added.
            var leaf: java.security.cert.X509Certificate? = null
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    leaf = chain.firstOrNull()
                    val fp = leaf?.let { Hashes.sha256(it.encoded).toHex() }
                    if (pinFor.isNotEmpty() && fp != pinFor) throw java.security.cert.CertificateException("CHANGED:$fp")
                }
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), java.security.SecureRandom()) }
            val h = (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as javax.net.ssl.HttpsURLConnection
            h.sslSocketFactory = ctx.socketFactory
            h.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } // an IP or .local: the pin is the check
            h.requestMethod = "POST"; h.doOutput = true; h.connectTimeout = connectMs; h.readTimeout = readMs
            h.setRequestProperty("Authorization", auth); h.setRequestProperty("Content-Type", "application/json")
            try {
                h.outputStream.use { it.write(body.toByteArray()) }
            } catch (e: javax.net.ssl.SSLException) {
                val m = generateSequence(e as Throwable) { it.cause }.mapNotNull { it.message }.firstOrNull { it.startsWith("CHANGED:") }
                if (m != null) throw NodeException("The certificate of ${u.host} has CHANGED (now ${m.removePrefix("CHANGED:").take(16)}…); nothing was sent. If you changed it, remove the connection and add it again.")
                throw e
            }
            lastFingerprint = leaf?.let { Hashes.sha256(it.encoded).toHex() }
            val code = h.responseCode
            val text = (if (code < 400) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            h.disconnect(); return code to text
        }
        val port = if (u.port > 0) u.port else 80
        val sock = if (proxy != null) java.net.Socket(proxy) else java.net.Socket()
        sock.use { so ->
            val addr = if (proxy != null) java.net.InetSocketAddress.createUnresolved(u.host, port) else java.net.InetSocketAddress(u.host, port)
            so.connect(addr, connectMs); so.soTimeout = readMs
            val bytes = body.toByteArray()
            val path = (u.path.ifEmpty { "/" }) + (u.query?.let { "?$it" } ?: "")
            val head = "POST $path HTTP/1.1\r\nHost: ${u.host}:$port\r\nAuthorization: $auth\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            so.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
            val input = java.io.BufferedInputStream(so.getInputStream())
            fun line(): String { val b = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
            val status = line().split(" ").getOrNull(1)?.toIntOrNull() ?: throw NodeException("no HTTP answer from ${u.host}")
            var length = -1; var chunked = false
            while (true) {
                val h = line(); if (h.isEmpty()) break
                val (k, v) = h.split(":", limit = 2).let { it[0].trim().lowercase() to (it.getOrNull(1)?.trim() ?: "") }
                if (k == "content-length") length = v.toIntOrNull() ?: -1
                if (k == "transfer-encoding" && v.contains("chunked", true)) chunked = true
            }
            val out = java.io.ByteArrayOutputStream()
            if (chunked) {
                while (true) {
                    val n = line().substringBefore(';').trim().toInt(16); if (n == 0) break
                    val buf = ByteArray(n); var got = 0
                    while (got < n) { val r = input.read(buf, got, n - got); if (r < 0) break; got += r }
                    out.write(buf, 0, got); line()
                }
            } else if (length >= 0) {
                val buf = ByteArray(length); var got = 0
                while (got < length) { val r = input.read(buf, got, length - got); if (r < 0) break; got += r }
                out.write(buf, 0, got)
            } else input.copyTo(out)
            return status to out.toString(Charsets.UTF_8.name())
        }
    }

    private fun call(method: String, vararg params: Any?, wallet: String? = null, timeoutMs: Int = 60_000): Any? =
        call(conn ?: throw NodeException("not connected"), method, *params, wallet = wallet, timeoutMs = timeoutMs)

    private fun desc(branch: Int): String = when (node.type) {
        ScriptType.P2WPKH -> "wpkh($descKey/$branch/*)"
        ScriptType.P2SH_P2WPKH -> "sh(wpkh($descKey/$branch/*))"
        ScriptType.P2PKH -> "pkh($descKey/$branch/*)"
        ScriptType.P2TR -> "tr($descKey/$branch/*)"
    }

    /** "Bitcoin node 29.x (RPC)" for the status line. */
    var version: String = "your node (RPC)"
        private set

    /**
     * The first connection that answers, on the BLAKE2b chain; then the node's watch-only wallet
     * (made the first time, looking back from the oldest coin the account still holds) and
     * everything about it, read once.
     */
    fun connect(progress: (String) -> Unit = {}) {
        var last: Exception? = null
        for (c in node.conns) {
            try {
                val dep = call(c, "getdeploymentinfo") as JSONObject
                val d = dep.optJSONObject("deployments")
                val blake = dep.optJSONObject("blake2b")?.optBoolean("active") == true ||
                    dep.optJSONObject("hardfork")?.optBoolean("active") == true ||
                    d?.optJSONObject("blake2b")?.optBoolean("active") == true
                if (!blake) throw NodeException("${c.url} is not on the BLAKE2b chain.")
                conn = c
                version = ((call(c, "getnetworkinfo") as JSONObject).optString("subversion").trim('/')) + " (RPC)"
                break
            } catch (e: Exception) { last = e }
        }
        if (conn == null) throw (last as? ElectrumException ?: NodeException(last?.message ?: "No connection to the node answered."))
        ensureWallet(progress)
        load()
    }

    private fun ensureWallet(progress: (String) -> Unit) {
        val loaded = (call("listwallets") as JSONArray).let { a -> (0 until a.length()).map { a.getString(it) } }
        if (walletName in loaded) return
        if (runCatching { call("loadwallet", walletName) }.isSuccess) return
        progress("first time on this node: looking for this wallet's coins (a few minutes)")
        val since = autoSince()
        call("createwallet", walletName, true, true, "", false, true, true)
        val req = JSONArray()
        for (b in 0..1) {
            val d = (call("getdescriptorinfo", desc(b)) as JSONObject).getString("descriptor")
            req.put(JSONObject().put("desc", d).put("range", JSONArray().put(0).put(999))
                .put("timestamp", since ?: "now").put("internal", b == 1).put("active", false))
        }
        progress("the node is reading this wallet's history")
        call("importdescriptors", req, wallet = walletName, timeoutMs = 3_600_000)
    }

    /** Where the history starts: the oldest coin still held, never before what a pruned node keeps. */
    private fun autoSince(): Long? {
        val desc = JSONArray()
        for (b in 0..1) desc.put(JSONObject().put("desc", desc(b)).put("range", 999))
        val r = call("scantxoutset", "start", desc, timeoutMs = 1_800_000) as JSONObject
        val u = r.getJSONArray("unspents")
        val minH = (0 until u.length()).map { u.getJSONObject(it).getInt("height") }.filter { it > 0 }.minOrNull() ?: return null
        val info = call("getblockchaininfo") as JSONObject
        val from = maxOf(minH - 6, if (info.optBoolean("pruned")) info.optInt("pruneheight") else 0)
        return (call("getblockheader", call("getblockhash", from)) as JSONObject).getLong("time") - 7200
    }

    private fun sats(btc: Double) = Math.round(btc * 1e8)

    /** Everything the wallet's addresses did, read from the node's wallet into memory. */
    private fun load() {
        history.clear(); utxos.clear()
        val ids = LinkedHashSet<String>()
        val lt = call("listtransactions", "*", 10_000, 0, true, wallet = walletName) as JSONArray
        for (k in 0 until lt.length()) ids += lt.getJSONObject(k).getString("txid")
        val lu = call("listunspent", 0, 9_999_999, JSONArray(), true, wallet = walletName) as JSONArray
        for (k in 0 until lu.length()) {
            val o = lu.getJSONObject(k)
            ids += o.getString("txid")
            val sh = Address.electrumScriptHash(Hashes.hexToBytes(o.getString("scriptPubKey")))
            val conf = o.getInt("confirmations")
            val h = if (conf > 0) tip() - conf + 1 else 0
            utxos.getOrPut(sh) { mutableListOf() } += ElectrumClient.Utxo(o.getString("txid"), o.getInt("vout"), sats(o.getDouble("amount")), h)
        }
        // Each wallet transaction, decoded: its outputs to our scripts and the coins of ours it spends.
        val outScripts = HashMap<String, String>() // "txid:vout" -> scripthash, for inputs
        // A payment to ourselves (a CPFP, a consolidation) never shows in listtransactions, which
        // leaves change out; if something later spent it, it is found through that spend's inputs.
        val queue = ArrayDeque(ids)
        val decodedMap = LinkedHashMap<String, JSONObject>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id in decodedMap) continue
            val t = wtx(id) ?: continue
            decodedMap[id] = t
            val vin = t.optJSONObject("decoded")?.optJSONArray("vin") ?: continue
            for (k in 0 until vin.length()) vin.getJSONObject(k).optString("txid").takeIf { it.length == 64 && it !in decodedMap }?.let { queue += it }
        }
        val decoded = decodedMap.entries.map { it.key to it.value }
        for ((id, t) in decoded) {
            val vout = t.getJSONObject("decoded").getJSONArray("vout")
            for (k in 0 until vout.length()) {
                val o = vout.getJSONObject(k)
                outScripts["$id:${o.getInt("n")}"] = Address.electrumScriptHash(Hashes.hexToBytes(o.getJSONObject("scriptPubKey").getString("hex")))
            }
        }
        for ((id, t) in decoded) {
            val conf = t.optInt("confirmations")
            if (conf < 0) continue
            if (conf == 0 && runCatching { call("getmempoolentry", id) }.isFailure) continue // replaced or dropped
            val h = if (conf > 0) t.optInt("blockheight") else 0
            val d = t.getJSONObject("decoded")
            val touched = HashSet<String>()
            val vout = d.getJSONArray("vout")
            for (k in 0 until vout.length()) outScripts["$id:${vout.getJSONObject(k).getInt("n")}"]?.let { touched += it }
            val vin = d.getJSONArray("vin")
            for (k in 0 until vin.length()) {
                val i = vin.getJSONObject(k)
                outScripts["${i.optString("txid")}:${i.optInt("vout")}"]?.let { touched += it }
            }
            for (sh in touched) history.getOrPut(sh) { mutableSetOf() } += id to h
        }
    }

    private var tipCache = -1
    private fun tip(): Int { if (tipCache < 0) tipCache = (call("getblockcount") as Number).toInt(); return tipCache }

    /** The wallet transaction with its hex and decoded form (cached once confirmed: it never changes). */
    private fun wtx(txid: String): JSONObject? {
        txCache[walletName + txid]?.let { return it }
        if (walletName + txid in notOurs) return null
        val t = runCatching { call("gettransaction", txid, true, true, wallet = walletName) as JSONObject }.getOrNull()
            ?: return null.also { notOurs += walletName + txid } // someone else's (an input of a payment to us)
        if (t.optInt("confirmations") >= 6) txCache[walletName + txid] = t
        return t
    }

    fun blockHeight(): Int = tip()

    fun balance(sh: String): ScriptHashBalance {
        val u = utxos[sh].orEmpty()
        return ScriptHashBalance(u.filter { it.height > 0 }.sumOf { it.value }, u.filter { it.height <= 0 }.sumOf { it.value })
    }

    fun status(sh: String): String? {
        val h = history[sh] ?: return null
        if (h.isEmpty()) return null
        return Hashes.sha256(h.sortedBy { it.first }.joinToString("") { "${it.first}:${it.second}:" }.toByteArray()).toHex()
    }

    fun history(sh: String): List<ElectrumClient.HistoryItem> =
        history[sh].orEmpty().map { ElectrumClient.HistoryItem(it.first, it.second) }.sortedBy { if (it.height <= 0) Int.MAX_VALUE else it.height }

    fun listUnspent(sh: String): List<ElectrumClient.Utxo> = utxos[sh].orEmpty()

    /** Any coin, ours or not, unspent with exactly [value] sats (mempool spends count as spent). */
    fun isUnspent(txid: String, vout: Int, value: Long): Boolean {
        val o = call("gettxout", txid, vout, true) as? JSONObject ?: return false
        return Math.round(o.getDouble("value") * 1e8) == value
    }

    fun estimateFeePerKb(blocks: Int): Double = runCatching {
        (call("estimatesmartfee", blocks) as JSONObject).let { if (it.has("feerate")) it.getDouble("feerate") else -1.0 }
    }.getOrDefault(-1.0)

    fun relayFeePerKb(): Double = runCatching { (call("getnetworkinfo") as JSONObject).getDouble("relayfee") }.getOrDefault(0.0)

    fun transaction(txid: String): String {
        wtx(txid)?.optString("hex")?.takeIf { it.isNotEmpty() }?.let { return it }
        return call("getrawtransaction", txid) as? String ?: throw NodeException("The node does not know $txid (it needs txindex for others' transactions).")
    }

    fun broadcast(rawTxHex: String): String = call("sendrawtransaction", rawTxHex) as String

    companion object {
        /** A SOCKS proxy into Tor, for .onion connections; null while Tor is not running. */
        @Volatile var torProxy: (() -> java.net.Proxy?)? = null

        private val txCache = ConcurrentHashMap<String, JSONObject>()
        private val notOurs: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun toVersion(key: String, v: Int): String {
            val raw = Base58.decodeChecked(key)
            raw[0] = (v ushr 24).toByte(); raw[1] = (v ushr 16).toByte(); raw[2] = (v ushr 8).toByte(); raw[3] = v.toByte()
            return Base58.encodeChecked(raw)
        }

        /** Try [c] and say what is there: chain, height and node version, or why it failed. */
        /** Try [c]: (what is there, the TLS certificate's fingerprint for https). Accepts any certificate, to show it. */
        fun test(c: RpcConn): Pair<String, String?> {
            val b = NodeRpcBackend(RpcNode(listOf(c), "", ScriptType.P2WPKH)).apply { testing = true }
            val info = b.call(c, "getblockchaininfo") as JSONObject
            val dep = b.call(c, "getdeploymentinfo") as JSONObject
            val blake = dep.toString().contains(Regex("\"(blake2b|hardfork)\":\\{[^}]*\"active\":true"))
            val v = (b.call(c, "getnetworkinfo") as JSONObject).optString("subversion").trim('/')
            return ("$v · block ${info.getInt("blocks")}" + if (blake) " · BLAKE2b ✓" else " · NOT on BLAKE2b") to b.lastFingerprint
        }
    }
}
