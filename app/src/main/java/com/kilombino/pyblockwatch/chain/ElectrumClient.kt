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
    /** The server actually in use: [requested], or for the SHA-256 default one of [Chain.publicServers]. */
    var endpoint: NodeEndpoint = requested
        private set

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
    }

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

    /**
     * Connect, capturing the certificate. We do not reject on mismatch here — the
     * caller decides how to present that to the user, because silently failing and
     * silently accepting are both wrong.
     */
    fun connect() {
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
        ssl.soTimeout = READ_TIMEOUT_MS
        try {
            ssl.startHandshake()
        } catch (e: Exception) {
            runCatching { ssl.close() }
            if (fingerprintChanged) throw CertificateChangedException(endpoint, serverFingerprint ?: "")
            // A node on the user's own network (Fulcrum's plain port, usually 50001) often
            // has no TLS at all: there, and only there, talk to it in the clear. Never for a
            // server we hold a pin for: that would be a downgrade around the pin.
            if (isLocal(endpoint.host) && pinnedFingerprint == null) return connectPlain()
            throw ElectrumException("Could not establish TLS with ${endpoint}: ${e.message}", e)
        }
        socket = ssl
        reader = BufferedReader(InputStreamReader(ssl.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(ssl.outputStream, Charsets.UTF_8))

        val version = call("server.version", JSONArray().put("PyBlockWatch").put("1.4"))
        serverVersion = (version as? JSONArray)?.optString(0) ?: version?.toString()
    }

    /** Plain TCP, for a node on the local network only (see [isLocal]). */
    private fun connectPlain() {
        val raw = connectRaw()
        serverFingerprint = null
        socket = raw
        reader = BufferedReader(InputStreamReader(raw.inputStream, Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(raw.outputStream, Charsets.UTF_8))
        val version = call("server.version", JSONArray().put("PyBlockWatch").put("1.4"))
        serverVersion = (version as? JSONArray)?.optString(0) ?: version?.toString()
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
        ssl.soTimeout = READ_TIMEOUT_MS
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
        val version = call("server.version", JSONArray().put("PyBlockWatch").put("1.4"))
        serverVersion = (version as? JSONArray)?.optString(0) ?: version?.toString()
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
        val r = call("blockchain.headers.subscribe", JSONArray()) as? JSONObject
            ?: throw ElectrumException("unexpected response from headers.subscribe")
        return r.getInt("height")
    }

    fun balance(scriptHash: String): ScriptHashBalance {
        val r = call("blockchain.scripthash.get_balance", JSONArray().put(scriptHash)) as? JSONObject
            ?: throw ElectrumException("unexpected response from get_balance")
        return ScriptHashBalance(r.optLong("confirmed"), r.optLong("unconfirmed"))
    }

    /**
     * The Electrum status of a scripthash: a hash of its whole history, or null when it has
     * none. It changes exactly when the history does, so one call tells whether anything
     * happened since last time.
     */
    fun status(scriptHash: String): String? =
        call("blockchain.scripthash.subscribe", JSONArray().put(scriptHash))?.takeIf { it != JSONObject.NULL }?.toString()

    /** One entry of an address's on-chain history. height <= 0 means still in the mempool. */
    data class HistoryItem(val txid: String, val height: Int)

    /** Full history touching this scripthash — tx ids and their block heights (0 = mempool). */
    fun history(scriptHash: String): List<HistoryItem> {
        val r = call("blockchain.scripthash.get_history", JSONArray().put(scriptHash)) as? JSONArray
            ?: return emptyList()
        return (0 until r.length()).map {
            val o = r.getJSONObject(it)
            HistoryItem(o.optString("tx_hash"), o.optInt("height"))
        }
    }

    /** Number of transactions touching this scripthash — how we detect a used address. */
    fun historyCount(scriptHash: String): Int {
        val r = call("blockchain.scripthash.get_history", JSONArray().put(scriptHash)) as? JSONArray
        return r?.length() ?: 0
    }

    /** One spendable output under a scripthash. `height <= 0` means it is still unconfirmed. */
    data class Utxo(val txid: String, val vout: Int, val value: Long, val height: Int)

    /** The unspent outputs a scripthash controls — the coins a send can draw on. */
    fun listUnspent(scriptHash: String): List<Utxo> {
        val r = call("blockchain.scripthash.listunspent", JSONArray().put(scriptHash)) as? JSONArray
            ?: return emptyList()
        return (0 until r.length()).map {
            val o = r.getJSONObject(it)
            Utxo(o.optString("tx_hash"), o.optInt("tx_pos"), o.optLong("value"), o.optInt("height"))
        }
    }

    /**
     * Estimated fee to confirm within [blocks], in BTC per kilobyte. The server returns -1
     * when it has no estimate (common on a young, quiet chain), which the caller floors with
     * [relayFee]. Multiply by 1e5 to get sats/vByte.
     */
    fun estimateFeePerKb(blocks: Int): Double {
        val r = call("blockchain.estimatefee", JSONArray().put(blocks))
        return (r as? Number)?.toDouble() ?: -1.0
    }

    /** The server's minimum relay fee, in BTC per kilobyte — the floor a transaction must clear. */
    fun relayFeePerKb(): Double {
        val r = call("blockchain.relayfee", JSONArray())
        return (r as? Number)?.toDouble() ?: 0.0
    }

    /** The raw (hex) transaction for [txid]; throws when the server does not know it. */
    fun transaction(txid: String): String {
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
        val o = call("blockchain.transaction.get_merkle", JSONArray().put(txid).put(height)) as? JSONObject
            ?: throw ElectrumException("no merkle proof for $txid")
        val m = o.getJSONArray("merkle")
        return (0 until m.length()).map { m.getString(it) } to o.getInt("pos")
    }

    /** The raw header (hex) of block [height]. */
    fun blockHeader(height: Int): String =
        call("blockchain.block.header", JSONArray().put(height))?.toString()
            ?: throw ElectrumException("no header for block $height")

    /** Broadcast a raw (hex) transaction. Returns the txid, or throws with the server's reason. */
    fun broadcast(rawTxHex: String): String {
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
