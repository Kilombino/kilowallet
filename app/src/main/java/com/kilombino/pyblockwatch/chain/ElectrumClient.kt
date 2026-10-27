package com.kilombino.pyblockwatch.chain

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** What the server said about one scripthash. */
data class ScriptHashBalance(val confirmed: Long, val unconfirmed: Long) {
    val total: Long get() = confirmed + unconfirmed
}

open class ElectrumException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A minimal Electrum protocol 1.4 client.
 *
 * Three things here are not obvious and were established by probing the real
 * servers rather than read from a spec:
 *
 *  1. **`jsonrpc: "2.0"` is mandatory.** Frigate (the SHA-256 server) rejects any
 *     request without that field with `-32600 Invalid Request`. Fulcrum tolerates
 *     its absence, so a client tested only against Fulcrum breaks on Frigate.
 *
 *  2. **Certificates are self-signed** — Frigate's even says `CN=localhost`. That
 *     is normal for Electrum servers and it means CA validation is meaningless
 *     here. Instead we pin: the SHA-256 fingerprint is remembered on first connect
 *     and any later change is surfaced to the user rather than silently accepted.
 *
 *  3. **The first TLS handshake can take ~40 seconds.** Frigate is slow to respond
 *     on a cold connection, so the timeouts are deliberately generous; a 10-second
 *     timeout looks like "server down" when it is merely slow.
 */
class ElectrumClient(
    private val requested: NodeEndpoint,
    private val pinnedFingerprint: String?,
) {
    /** Set when the endpoint is the user's own node by RPC: then every question goes to it instead. */
    private val node: NodeRpcBackend? = requested.rpc?.let { NodeRpcBackend(it) }

    /** True when answers come from the user's own node (no SPV check needed against it). */
    val isOwnNode: Boolean get() = node != null

    /** The server actually in use: [requested], or for the SHA-256 default one of [Chain.publicServers]. */
    var endpoint: NodeEndpoint = requested
        private set

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        /** Through Tor (an onion, or every server with [throughTor]): as long as the node's RPC over Tor waits, and more to read. */
        private const val TOR_CONNECT_TIMEOUT_MS = 60_000
        private const val TOR_READ_TIMEOUT_MS = 90_000

        /** A SOCKS proxy into the app's Tor (started if needed); null when Tor can't run or didn't come up. Set by [Tor.install]. */
        @Volatile var torProxy: (() -> java.net.Proxy?)? = null

        /**
         * "Connect to the server through Tor": every Electrum server (the user's, the default one and the
         * spamchain's public ones) is reached through Tor, so it never sees this phone's address. TLS and
         * the pin stay as they are. A .onion always goes through Tor, whatever this says.
         */
        @Volatile var throughTor: Boolean = false

        /** Onion servers that answered in plain TCP (Fulcrum's 50001 behind an onion): tried that way first next time. */
        private val plainOnions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun isOnion(host: String): Boolean = host.trim().lowercase().trimEnd('.').endsWith(".onion")
    }

    /** Whether this connection goes through Tor. */
    private val viaTor: Boolean get() = isOnion(endpoint.host) || throughTor
    private val readTimeout: Int get() = if (viaTor) TOR_READ_TIMEOUT_MS else READ_TIMEOUT_MS

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var nextId = 0

    /** SHA-256 fingerprint of the certificate this connection actually presented. */
    var serverFingerprint: String? = null
        private set

    /** True when a pin was supplied and the server presented a different certificate. */
    var fingerprintChanged: Boolean = false
        private set

    var serverVersion: String? = null
        private set

    /** The protocol version the server agreed to: "1.4" up to "1.6". */
    var protocol: String = "1.4"
        private set

    /**
     * Agree on a protocol version: anything from 1.4 to 1.6. A server whose chain has headers
     * that are not 80 bytes (BLAKE2b's are 164) sends them in batches only from 1.6 on, so
     * one that refuses blockchain.block.headers below it (Shulcrum 2.1) can still be verified;
     * a server that knows only 1.4 keeps it. Nothing the app calls changes between them but
     * blockchain.relayfee, gone in 1.6 (see [relayFeePerKb]); headers are read in either form.
     */
    private fun negotiate() {
        val version = call("server.version", JSONArray().put("PyBlockWatch").put(JSONArray().put("1.4").put("1.6")))
        serverVersion = (version as? JSONArray)?.optString(0) ?: version?.toString()
        protocol = (version as? JSONArray)?.optString(1)?.takeIf { it.isNotEmpty() } ?: "1.4"
    }

    /**
     * Connect, capturing the certificate. We do not reject on mismatch here — the
     * caller decides how to present that to the user, because silently failing and
     * silently accepting are both wrong.
     */
    fun connect() {
        node?.let { it.connect(); serverVersion = it.version; return }
        // The SHA-256 chain is read from well-known public servers: try them in turn,
        // starting with the one that answered last time.
        if (requested != NodeEndpoint.default(Chain.SHA256)) return connectTo(requested, public = false)
        var last: Exception? = null
        for (candidate in Chain.publicServersInOrder()) {
            try {
                connectTo(candidate, public = true)
                Chain.rememberWorking(candidate)
                return
            } catch (e: Exception) {
                close(); last = e
            }
        }
        throw ElectrumException("No public SHA-256 server answered: ${last?.message}", last)
    }

    private fun connectTo(target: NodeEndpoint, public: Boolean) {
        endpoint = target
        fingerprintChanged = false
        if (public) return connectPublic()
        // An onion that answered in plain TCP before: that way first (TLS is tried again if it fails).
        // So is Fulcrum's usual plain port (50001), the one most onions publish.
        if (isOnion(target.host) && ("${target.host}:${target.port}" in plainOnions || target.port == 50001)) {
            if (runCatching { connectPlain() }.isSuccess) return
            close(); plainOnions.remove("${target.host}:${target.port}")
        }
        val raw = connectRaw()
        val ctx = SSLContext.getInstance("TLS")
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = Hashes.sha256(chain[0].encoded).toHex()
                serverFingerprint = fp
                if (pinnedFingerprint != null && !pinnedFingerprint.equals(fp, ignoreCase = true)) {
                    // The pin is enforced: nothing at all is said to a server whose certificate
                    // changed until the user has checked and accepted the new one.
                    fingerprintChanged = true
                    throw java.security.cert.CertificateException("certificate changed")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        ctx.init(null, arrayOf(capture), java.security.SecureRandom())

        val ssl = ctx.socketFactory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
        // A plain port behind an onion never answers a TLS hello: don't wait the whole read timeout for it.
        ssl.soTimeout = if (isOnion(endpoint.host)) 30_000 else readTimeout
        try {
            ssl.startHandshake()
            ssl.soTimeout = readTimeout
        } catch (e: Exception) {
            runCatching { ssl.close() }
            if (fingerprintChanged) throw CertificateChangedException(endpoint, serverFingerprint ?: "")
            // A node on the user's own network (Fulcrum's plain port, usually 50001) often
            // has no TLS at all: there, and only there, talk to it in the clear. Never for a
            // server we hold a pin for: that would be a downgrade around the pin.
            if (isLocal(endpoint.host) && pinnedFingerprint == null) return connectPlain()
            // An onion is its own authentication and encryption (its address is its key): Fulcrum's plain
            // port behind one (usually 50001) is fine. Never for any other host.
            if (isOnion(endpoint.host)) return connectPlain()
            throw ElectrumException("Could not establish TLS with ${endpoint}: ${e.message}", e)
        }
        socket = ssl
        reader = BufferedReader(InputStreamReader(ssl.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(ssl.outputStream, Charsets.UTF_8))

        negotiate()
    }

    /** Plain TCP, for a node on the local network or a .onion only (see [isLocal], [isOnion]). */
    private fun connectPlain() {
        check(isLocal(endpoint.host) || isOnion(endpoint.host)) { "plain TCP only on your own network or to a .onion" }
        val raw = connectRaw()
        serverFingerprint = null
        socket = raw
        reader = BufferedReader(InputStreamReader(raw.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(raw.outputStream, Charsets.UTF_8))
        negotiate()
        if (isOnion(endpoint.host)) plainOnions.add("${endpoint.host}:${endpoint.port}")
    }

    /** A private-network address (192.168.x.x, 10.x, 172.16–31.x, localhost, *.local, fd00::/8). */
    private fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.startsWith("127.")) return true
        if (h.startsWith("10.") || h.startsWith("192.168.")) return true
        if (h.startsWith("172.")) h.split('.').getOrNull(1)?.toIntOrNull()?.let { if (it in 16..31) return true }
        if (h.startsWith("fd") || h.startsWith("fe80:")) return true
        return false
    }

    /**
     * A public server has a normal certificate (Let's Encrypt and the like) that renews
     * every few months, so it is checked like a browser would — signed by a trusted
     * authority, for this hostname — instead of pinned. And it must really follow the
     * SHA-256 chain: its tip header is 80 bytes there, 164 on BLAKE2b.
     */
    private fun connectPublic() {
        val raw = connectRaw()
        val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
            .createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
        ssl.soTimeout = readTimeout
        try {
            ssl.startHandshake()
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, ssl.session)) {
                throw ElectrumException("certificate is not for ${endpoint.host}")
            }
        } catch (e: Exception) {
            runCatching { ssl.close() }
            throw ElectrumException("Could not establish TLS with ${endpoint}: ${e.message}", e)
        }
        serverFingerprint = Hashes.sha256(ssl.session.peerCertificates[0].encoded).toHex()
        socket = ssl
        reader = BufferedReader(InputStreamReader(ssl.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(ssl.outputStream, Charsets.UTF_8))
        negotiate()
        val tip = call("blockchain.headers.subscribe", JSONArray()) as? JSONObject
        val headerHex = tip?.optString("hex").orEmpty()
        if (headerHex.length != 160) throw ElectrumException("$endpoint does not follow the SHA-256 chain")
    }

    /**
     * Open the TCP socket, trying every resolved address.
     *
     * Both hostnames publish AAAA records through AirVPN's dynamic DNS, and a host
     * with no IPv6 default route will hang on those until the connect timeout. So we
     * walk all addresses and keep the first that answers instead of trusting the
     * resolver's ordering.
     */
    private fun connectRaw(): Socket {
        if (viaTor) return connectTor()
        val addresses = try {
            InetAddress.getAllByName(endpoint.host)
        } catch (e: Exception) {
            throw ElectrumException("Could not resolve ${endpoint.host}: ${e.message}", e)
        }
        // IPv4 first: when IPv6 is advertised but unroutable, this avoids a stall.
        val ordered = addresses.sortedBy { it.address.size }
        var last: Exception? = null
        for (addr in ordered) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(addr, endpoint.port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                s.tcpNoDelay = true
                return s
            } catch (e: Exception) {
                last = e
            }
        }
        throw ElectrumException("Could not connect to $endpoint: ${last?.message}", last)
    }

    /**
     * The TCP socket through Tor's SOCKS port, the name resolved by Tor (never here: a .onion has no
     * DNS, and asking the local resolver for a clearnet name would tell it which server). Without Tor
     * nothing is sent at all, never directly instead.
     */
    private fun connectTor(): Socket {
        val proxy = torProxy?.invoke() ?: throw ElectrumException(
            if (isOnion(endpoint.host)) "${endpoint.host} is a .onion: it needs Tor, and Tor is not running on this phone (or did not start)."
            else "Connecting through Tor is on, but Tor is not running on this phone (or did not start): nothing was sent without it.")
        val s = Socket(proxy)
        try {
            s.connect(InetSocketAddress.createUnresolved(endpoint.host, endpoint.port), TOR_CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { s.close() }
            throw ElectrumException("Could not reach $endpoint through Tor: ${e.message}", e)
        }
        s.soTimeout = TOR_READ_TIMEOUT_MS
        s.tcpNoDelay = true
        return s
    }

    /** One JSON-RPC round trip. Requests are newline-delimited. */
    @Synchronized
    private fun call(method: String, params: JSONArray): Any? {
        val w = writer ?: throw ElectrumException("client not connected")
        val r = reader ?: throw ElectrumException("client not connected")
        val id = nextId++
        val req = JSONObject()
            .put("jsonrpc", "2.0")   // Frigate rejects the request without this.
            .put("id", id)
            .put("method", method)
            .put("params", params)
        try {
            w.write(req.toString()); w.write("\n"); w.flush()
        } catch (e: Exception) {
            throw ElectrumException("Lost the connection while sending $method: ${e.message}", e)
        }

        // Skip any subscription notification that arrives before our reply.
        while (true) {
            val line = try {
                r.readLine()
            } catch (e: Exception) {
                throw ElectrumException("Lost the connection while waiting for $method: ${e.message}", e)
            } ?: throw ElectrumException("The server closed the connection during $method")

            val obj = try { JSONObject(line) } catch (e: Exception) { continue }
            if (obj.has("error") && !obj.isNull("error")) {
                throw ElectrumException("$method: ${obj.get("error")}")
            }
            if (obj.optInt("id", -1) != id) continue   // notification, not our answer
            return obj.opt("result")
        }
    }

    fun blockHeight(): Int {
        node?.let { return it.blockHeight() }
        val r = call("blockchain.headers.subscribe", JSONArray()) as? JSONObject
            ?: throw ElectrumException("unexpected response from headers.subscribe")
        return r.getInt("height")
    }

    /** The server's tip header (hex): 80 bytes on the spamchain, 164 on BTC. */
    fun tipHeaderHex(): String {
        if (node != null) throw ElectrumException("no header from a node")
        val r = call("blockchain.headers.subscribe", JSONArray()) as? JSONObject
            ?: throw ElectrumException("unexpected response from headers.subscribe")
        return r.getString("hex")
    }

    fun balance(scriptHash: String): ScriptHashBalance {
        node?.let { return it.balance(scriptHash) }
        val r = call("blockchain.scripthash.get_balance", JSONArray().put(scriptHash)) as? JSONObject
            ?: throw ElectrumException("unexpected response from get_balance")
        return ScriptHashBalance(r.optLong("confirmed"), r.optLong("unconfirmed"))
    }

    /**
     * The Electrum status of a scripthash: a hash of its whole history, or null when it has
     * none. It changes exactly when the history does, so one call tells whether anything
     * happened since last time.
     */
    fun status(scriptHash: String): String? = if (node != null) node.status(scriptHash) else
        call("blockchain.scripthash.subscribe", JSONArray().put(scriptHash))?.takeIf { it != JSONObject.NULL }?.toString()

    /**
     * [status] of many scripthashes in one go: every request is written first and the answers
     * read as they come (by id), so a scan waits for one round trip per batch instead of one
     * per address.
     */
    fun statuses(scriptHashes: List<String>): List<String?> {
        if (node != null || scriptHashes.size <= 1) return scriptHashes.map { status(it) }
        return batch("blockchain.scripthash.subscribe", scriptHashes)
            .map { it?.takeIf { r -> r != JSONObject.NULL }?.toString() }
    }

    /** [listUnspent] of many scripthashes, batched like [statuses]. */
    fun listUnspentMany(scriptHashes: List<String>): List<List<Utxo>> {
        if (node != null || scriptHashes.size <= 1) return scriptHashes.map { listUnspent(it) }
        return batch("blockchain.scripthash.listunspent", scriptHashes).map { utxos(it as? JSONArray) }
    }

    /** One request per scripthash, all written before any answer is read; the answers in order. */
    private fun batch(method: String, scriptHashes: List<String>): List<Any?> {
        // Servers cap how many requests may wait at once: a long list goes in rounds.
        if (scriptHashes.size > 50) return scriptHashes.chunked(50).flatMap { batch(method, it) }
        val what = method.substringAfterLast('.')
        val w = writer ?: throw ElectrumException("client not connected")
        val r = reader ?: throw ElectrumException("client not connected")
        val ids = scriptHashes.map { sh ->
            val id = nextId++
            val req = JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("method", method).put("params", JSONArray().put(sh))
            try { w.write(req.toString()); w.write("\n") } catch (e: Exception) {
                throw ElectrumException("Lost the connection while sending $what: ${e.message}", e)
            }
            id
        }
        try { w.flush() } catch (e: Exception) { throw ElectrumException("Lost the connection: ${e.message}", e) }
        val want = ids.toHashSet()
        val got = HashMap<Int, Any?>()
        while (got.size < ids.size) {
            val line = try { r.readLine() } catch (e: Exception) {
                throw ElectrumException("Lost the connection while waiting for $what: ${e.message}", e)
            } ?: throw ElectrumException("The server closed the connection during $what")
            val obj = try { JSONObject(line) } catch (e: Exception) { continue }
            val id = obj.optInt("id", -1)
            if (id !in want) continue   // a notification, not one of our answers
            if (obj.has("error") && !obj.isNull("error")) throw ElectrumException("$what: ${obj.get("error")}")
            got[id] = obj.opt("result")
        }
        return ids.map { got[it] }
    }

    /** One entry of an address's on-chain history. height <= 0 means still in the mempool. */
    data class HistoryItem(val txid: String, val height: Int)

    /** Full history touching this scripthash — tx ids and their block heights (0 = mempool). */
    fun history(scriptHash: String): List<HistoryItem> {
        node?.let { return it.history(scriptHash) }
        val r = call("blockchain.scripthash.get_history", JSONArray().put(scriptHash)) as? JSONArray
            ?: return emptyList()
        return (0 until r.length()).map {
            val o = r.getJSONObject(it)
            HistoryItem(o.optString("tx_hash"), o.optInt("height"))
        }
    }

    /** Number of transactions touching this scripthash — how we detect a used address. */
    fun historyCount(scriptHash: String): Int {
        node?.let { return it.history(scriptHash).size }
        val r = call("blockchain.scripthash.get_history", JSONArray().put(scriptHash)) as? JSONArray
        return r?.length() ?: 0
    }

    /** One spendable output under a scripthash. `height <= 0` means it is still unconfirmed. */
    data class Utxo(val txid: String, val vout: Int, val value: Long, val height: Int)

    /**
     * Whether someone else's coin is unspent with exactly [value] (a coinjoin seat). Over
     * Electrum, by its scripthash; on a node by RPC, with gettxout: its watch-only wallet only
     * knows our own addresses, so listUnspent would say no to every coin that is not ours.
     */
    fun isUnspent(scriptHash: String, txid: String, vout: Int, value: Long, script: ByteArray? = null): Boolean {
        // By RPC the coin's own script is checked too (Electrum's scripthash already ties it to one).
        node?.let { return it.isUnspent(txid, vout, value, script) }
        return listUnspent(scriptHash).any { it.txid == txid && it.vout == vout && it.value == value }
    }

    /** The unspent outputs a scripthash controls — the coins a send can draw on. */
    fun listUnspent(scriptHash: String): List<Utxo> {
        node?.let { return it.listUnspent(scriptHash) }
        return utxos(call("blockchain.scripthash.listunspent", JSONArray().put(scriptHash)) as? JSONArray)
    }

    private fun utxos(r: JSONArray?): List<Utxo> = if (r == null) emptyList() else (0 until r.length()).map {
        val o = r.getJSONObject(it)
        Utxo(o.optString("tx_hash"), o.optInt("tx_pos"), o.optLong("value"), o.optInt("height"))
    }

    /**
     * Estimated fee to confirm within [blocks], in BTC per kilobyte. The server returns -1
     * when it has no estimate (common on a young, quiet chain), which the caller floors with
     * [relayFee]. Multiply by 1e5 to get sats/vByte.
     */
    fun estimateFeePerKb(blocks: Int): Double {
        node?.let { return it.estimateFeePerKb(blocks) }
        val r = call("blockchain.estimatefee", JSONArray().put(blocks))
        return (r as? Number)?.toDouble() ?: -1.0
    }

    /** The server's minimum relay fee, in BTC per kilobyte — the floor a transaction must clear. */
    fun relayFeePerKb(): Double {
        node?.let { return it.relayFeePerKb() }
        if (protocolAtLeast("1.6")) {
            // blockchain.relayfee is gone in 1.6; mempool.get_info carries the same number.
            val info = call("mempool.get_info", JSONArray()) as? JSONObject
            return info?.optDouble("minrelaytxfee", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        }
        val r = call("blockchain.relayfee", JSONArray())
        return (r as? Number)?.toDouble() ?: 0.0
    }

    private fun protocolAtLeast(v: String): Boolean {
        val a = protocol.split('.').map { it.toIntOrNull() ?: 0 }
        val b = v.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }

    /** The raw (hex) transaction for [txid]; throws when the server does not know it. */
    fun transaction(txid: String): String {
        node?.let { return it.transaction(txid) }
        val raw = call("blockchain.transaction.get", JSONArray().put(txid).put(false))?.toString()
            ?: throw ElectrumException("unknown transaction $txid")
        // Never trust the server's word for what a transaction says: it must hash to the txid
        // asked for, or a lying server could feed a made-up one (e.g. to a "speed up" that
        // copies its recipients).
        val got = runCatching { com.kilombino.pyblockwatch.crypto.TxParse.txid(com.kilombino.pyblockwatch.crypto.TxParse.parse(raw)) }.getOrNull()
        if (!txid.equals(got, ignoreCase = true)) throw ElectrumException("The server returned a transaction that is not $txid")
        return raw
    }

    /** Electrum's merkle branch for [txid] in block [height]: (branch hashes, position). */
    fun merkle(txid: String, height: Int): Pair<List<String>, Int> {
        if (node != null) throw ElectrumException("no merkle proofs from a node")
        val o = call("blockchain.transaction.get_merkle", JSONArray().put(txid).put(height)) as? JSONObject
            ?: throw ElectrumException("no merkle proof for $txid")
        val m = o.getJSONArray("merkle")
        return (0 until m.length()).map { m.getString(it) } to o.getInt("pos")
    }

    /**
     * Raw headers (hex, one per block) from [start], at most [count]; servers cap a call
     * (usually at 2016) and stop at their tip. Protocol 1.4 answers one hex string, 1.6 a list.
     */
    fun headers(start: Int, count: Int): List<String> {
        if (node != null) throw ElectrumException("no header batches from a node")
        val o = call("blockchain.block.headers", JSONArray().put(start).put(count)) as? JSONObject
            ?: throw ElectrumException("unexpected response from block.headers")
        o.optJSONArray("headers")?.let { a -> return (0 until a.length()).map { a.getString(it) } }
        val n = o.optInt("count")
        if (n <= 0) return emptyList()
        val hex = o.getString("hex")
        if (hex.length % n != 0) throw ElectrumException("block.headers: ${hex.length / 2} bytes for $n headers")
        val size = hex.length / n
        return (0 until n).map { hex.substring(it * size, (it + 1) * size) }
    }

    /** The raw header (hex) of block [height]. */
    fun blockHeader(height: Int): String =
        call("blockchain.block.header", JSONArray().put(height))?.toString()
            ?: throw ElectrumException("no header for block $height")

    /** Broadcast a raw (hex) transaction. Returns the txid, or throws with the server's reason. */
    fun broadcast(rawTxHex: String): String {
        node?.let { return it.broadcast(rawTxHex) }
        val r = call("blockchain.transaction.broadcast", JSONArray().put(rawTxHex))
        val txid = r?.toString()
        if (txid == null || txid.length != 64) {
            throw ElectrumException("The server rejected the transaction: $txid")
        }
        return txid
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null; reader = null; writer = null
    }
}

/** The server's TLS certificate is not the pinned one; [fingerprint] is the new one, for the user to check. */
class CertificateChangedException(val endpoint: NodeEndpoint, val fingerprint: String) :
    ElectrumException("The certificate of $endpoint has CHANGED; nothing was sent to it. Check the new fingerprint before trusting it.")
