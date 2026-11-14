package com.kilombino.pyblockwatch.joinblaket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DirectoryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One connection to a JoinMarket directory, as a taker that never listens
 * (`jmcore/directory_client.py`, `jmcore/network.py`).
 *
 * [connect] opens the TCP stream (through [proxy] with the name resolved by the proxy, so a
 * `.onion` works with Tor's SOCKS port), sends the HANDSHAKE with `NOT-SERVING-ONION`, checks the
 * DN_HANDSHAKE reply and runs JMP-0005 nick authentication against [directoryId]. After that a
 * reader coroutine answers every PING with a PONG, keeps [peers] from PEERLIST frames and queues
 * every other frame for [receive]. Privmsgs are signed with [identity] ([sendPrivate]).
 *
 * Limits are Python's: 2 MiB per received line, 1,024 queued frames / 8 MiB, five consecutive
 * unreadable frames, 64-byte nicks and 300-byte locations in peerlists, 20,000 peers. A nick-auth
 * frame after the handshake is a protocol violation and closes the connection. DISCONNECT (801)
 * is never sent: [close] just closes the socket.
 */
class DirectoryClient(
    val host: String,
    val port: Int,
    val identity: NickIdentity,
    /** The wire network: `xbt` on mainnet, `xbt-regtest` in the lab. */
    val network: String = "xbt",
    /**
     * The nick-auth identity expected for this endpoint (for an onion, its lowercase `host:port`).
     * Null means no `nick_auth` feature is offered, which [requireVerified] refuses.
     */
    val directoryId: String? = null,
    /** `require_verified`: the directory must advertise and complete nick authentication. */
    val requireVerified: Boolean = true,
    /** SOCKS proxy (Tor). Required for a `.onion`; optional otherwise. */
    private val proxy: Proxy? = null,
    /** Non-onion directories only on regtest unless explicitly allowed (`allow_clearnet_connections`). */
    private val allowClearnet: Boolean = network == "xbt-regtest",
    /** `connection_timeout`: TCP + SOCKS + circuit, and the wait for the handshake reply. */
    private val connectTimeoutMs: Int = 120_000,
    /** Wait for the first PEERLIST chunk on a directory with `peerlist_features`. */
    private val peerlistTimeoutMs: Long = 60_000,
    /** A peerlist answer is complete after this long without a new chunk. */
    private val peerlistQuietMs: Long = 5_000,
) : Closeable {
    /** A peer as the directory lists it: `nick;location[;D][;F:feat+feat]`. */
    data class Peer(val nick: String, val location: String, val features: Set<String>)

    val nick: String get() = identity.nick

    /** True once the directory accepted our nick proof (807 `ok`). */
    @Volatile var authenticated = false
        private set
    @Volatile var motd: String? = null
        private set
    @Volatile var directoryNick: String? = null
        private set
    @Volatile var directoryFeatures: Set<String> = emptySet()
        private set

    private val peerMap = ConcurrentHashMap<String, Peer>()
    /** The peers currently listed (nicks that left with `;D` are removed). */
    val peers: Map<String, Peer> get() = HashMap(peerMap)

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = Channel<Wire.Frame>(Channel.UNLIMITED)
    private val queuedFrames = AtomicInteger()
    private val queuedBytes = AtomicLong()
    @Volatile private var peerlistSink: Channel<List<Peer>>? = null
    @Volatile private var lastPeerlistRequest = 0L
    @Volatile private var closed = false

    companion object {
        const val MAX_RECEIVED_LINE = 2 * 1024 * 1024
        /** The directory drops any line longer than its `max_line_length`; refused here first. */
        const val MAX_SENT_LINE = 64 * 1024
        const val MAX_QUEUED_FRAMES = 1_024
        const val MAX_QUEUED_BYTES = 8L * 1024 * 1024
        const val MAX_CONSECUTIVE_ERRORS = 5
        const val MAX_PEERS = 20_000
        const val MAX_NICK_BYTES = 64
        const val MAX_LOCATION_BYTES = 300
        const val MAX_FEATURES = 64
        const val NICK_AUTH_STEP_MS = 30_000
        const val PEERLIST_MIN_INTERVAL_MS = 60_000L
        const val NOT_SERVING_ONION = "NOT-SERVING-ONION"

        fun isOnion(host: String) = host.trim().lowercase().trimEnd('.').endsWith(".onion")

        private val FIELD_DELIMITERS = setOf(',', ';', '!')

        private fun safeField(s: String, delimiters: Set<Char>) =
            s.isNotEmpty() && s.all { it !in delimiters && !it.isWhitespace() && !it.isISOControl() }

        /**
         * `parse_peerlist_entry` for every entry of one PEERLIST line: (peer, left) pairs. Entries
         * without `;` (reference-implementation metadata) and malformed ones are skipped.
         */
        fun parsePeerlist(line: String): List<Pair<Peer, Boolean>> = line.split(",").mapNotNull { entry ->
            if (entry.isBlank() || ';' !in entry) return@mapNotNull null
            val parts = entry.split(";")
            val (nick, location) = parts[0] to parts[1]
            if (!safeField(nick, FIELD_DELIMITERS) || !safeField(location, FIELD_DELIMITERS)) return@mapNotNull null
            if (nick.toByteArray().size > MAX_NICK_BYTES || location.toByteArray().size > MAX_LOCATION_BYTES) return@mapNotNull null
            var left = false
            var features = emptySet<String>()
            for (p in parts.drop(2)) when {
                p == "D" -> left = true
                p.startsWith("F:") -> {
                    val s = p.substring(2)
                    if (',' in s) return@mapNotNull null
                    features = s.split('+').filter { it.isNotEmpty() }.toSet()
                    if (features.size > MAX_FEATURES || features.any { !safeField(it, FIELD_DELIMITERS + '+') }) return@mapNotNull null
                }
            }
            Peer(nick, location, features) to left
        }
    }

    /**
     * Connect and handshake (`DirectoryClient.connect` / `_handshake` / `_authenticate_nick`).
     * Throws [DirectoryException] on any refusal; the socket is closed then. Cancelling the
     * caller closes the socket at once, so a hanging Tor circuit does not hold the coroutine.
     */
    suspend fun connect() = coroutineScope {
        check(socket == null && !closed) { "connect() once per client" }
        if (requireVerified && directoryId == null) {
            throw DirectoryException("No expected nick authentication directory identity configured for $host:$port")
        }
        var done = false
        val watchdog = launch { try { awaitCancellation() } finally { if (!done) closeQuietly() } }
        try {
            withContext(Dispatchers.IO) { open(); handshake() }
        } catch (e: Exception) {
            closeQuietly()
            throw if (e is DirectoryException) e else DirectoryException("Connection failed: ${e.message}", e)
        } finally {
            done = true; watchdog.cancel()
        }
        scope.launch { readLoop() }
        Unit
    }

    private fun open() {
        val onion = isOnion(host)
        if (!onion && !allowClearnet) {
            throw DirectoryException("Refusing direct TCP connection to non-onion directory $host:$port")
        }
        if (onion && proxy == null) throw DirectoryException("$host is a .onion: it needs Tor")
        val s = if (proxy != null) Socket(proxy) else Socket()
        socket = s
        val target = if (proxy != null) InetSocketAddress.createUnresolved(host, port) else InetSocketAddress(host, port)
        s.connect(target, connectTimeoutMs)
        s.tcpNoDelay = true
        input = s.getInputStream().buffered()
        output = s.getOutputStream()
    }

    private fun handshake() {
        val line = NickAuth.handshakeLine(nick, network, nickAuth = directoryId != null)
        sendBlocking(Wire.envelope(Wire.HANDSHAKE, line))
        val reply = readFrame(connectTimeoutMs) ?: throw DirectoryException("No handshake reply")
        NickAuth.checkDirectoryReply(reply, network, requireVerified)?.let { throw DirectoryException(it) }
        val o = org.json.JSONObject(reply.line)
        motd = o.optString("motd").takeIf { o.has("motd") }
        directoryNick = o.optString("nick").takeIf { o.has("nick") }
        directoryFeatures = o.optJSONObject("features")?.let { f -> f.keys().asSequence().filter { f.opt(it) == true }.toSet() }.orEmpty()
        if (directoryId != null && "nick_auth" in directoryFeatures) authenticate(line, directoryId)
    }

    private fun authenticate(handshakeLine: String, expectedId: String) {
        val step = minOf(connectTimeoutMs, NICK_AUTH_STEP_MS)
        val ch = readFrame(step) ?: throw DirectoryException("Nick authentication timed out")
        if (ch.type != Wire.NICK_AUTH_CHALLENGE) throw DirectoryException("Unexpected nick authentication challenge type: ${ch.type}")
        val challenge = runCatching { NickAuth.parseChallenge(ch.line) }.getOrNull()
            ?: throw DirectoryException("Invalid nick authentication response")
        if (challenge.directoryId != expectedId) {
            throw DirectoryException("Nick authentication directory-id mismatch: expected $expectedId, received ${challenge.directoryId}")
        }
        sendBlocking(Wire.envelope(Wire.NICK_AUTH_PROOF, NickAuth.proofLine(identity, challenge, handshakeLine)))
        val result = readFrame(step) ?: throw DirectoryException("Nick authentication timed out")
        if (result.type != Wire.NICK_AUTH_RESULT) throw DirectoryException("Unexpected nick authentication result type: ${result.type}")
        if (!NickAuth.resultOk(result.line)) throw DirectoryException("Directory rejected nick authentication")
        authenticated = true
    }

    /** One frame within [timeoutMs] (null on timeout); used only before the reader starts. */
    private fun readFrame(timeoutMs: Int): Wire.Frame? {
        val s = socket ?: throw DirectoryException("Not connected")
        s.soTimeout = timeoutMs
        val text = try { readLine() } catch (_: java.net.SocketTimeoutException) { return null }
            ?: throw DirectoryException("Connection closed by the directory")
        return runCatching { Wire.parse(text) }.getOrElse { throw DirectoryException("Unreadable frame from the directory") }
    }

    /** Bytes up to `\n` (at most [MAX_RECEIVED_LINE]), `\r\n` stripped; null at end of stream. */
    private fun readLine(): String? {
        val inp = input ?: throw DirectoryException("Not connected")
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (buf.size() >= MAX_RECEIVED_LINE) throw DirectoryException("Message too large")
            buf.write(b)
        }
        return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }

    private fun sendBlocking(frame: String) {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SENT_LINE) throw DirectoryException("Message too large for the directory: ${bytes.size} bytes")
        val out = output ?: throw DirectoryException("Not connected")
        try {
            synchronized(writeLock) { out.write(bytes); out.write(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())); out.flush() }
        } catch (e: java.io.IOException) {
            throw DirectoryException("Send failed: ${e.message}", e)
        }
    }

    /** Send one raw frame (already a `{"type", "line"}` JSON string). */
    suspend fun send(frame: String) {
        if (closed) throw DirectoryException("Connection closed")
        withContext(Dispatchers.IO) { sendBlocking(frame) }
    }

    /** `send_public_message`: `<nick>!PUBLIC!<message>`, unsigned. */
    suspend fun sendPublic(message: String) = send(Wire.envelope(Wire.PUBMSG, "$nick!PUBLIC!$message"))

    /** `send_private_message`: `<nick>!<to>!<command> <data> <pubkey> <sig>`, signed over `data + onion-network`. */
    suspend fun sendPrivate(to: String, command: String, data: String) = send(Session.privmsg(identity, to, command, data))

    /**
     * The next PRIVMSG/PUBMSG (or other non-control) frame, or null after [timeoutMs]. PING,
     * PEERLIST and nick-auth frames never come out here. Throws once the connection is gone.
     */
    suspend fun receive(timeoutMs: Long): Wire.Frame? {
        val f = try {
            withTimeoutOrNull(timeoutMs) { frames.receive() }
        } catch (e: ClosedReceiveChannelException) {
            throw DirectoryException("Connection closed")
        } catch (e: DirectoryException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw DirectoryException(e.message ?: "Connection lost", e)
        } ?: return null
        queuedFrames.decrementAndGet(); queuedBytes.addAndGet(-f.line.length.toLong())
        return f
    }

    /**
     * GETPEERLIST and its chunked answer (`_fetch_peerlist_result`): the first chunk within
     * [peerlistTimeoutMs] (the handshake timeout for a directory without `peerlist_features`),
     * then chunks until 5 s pass without one. At most once per 60 s: null when rate-limited or
     * when nothing came. Every PEERLIST also updates [peers].
     */
    suspend fun getPeerlist(): List<Peer>? {
        val now = System.currentTimeMillis()
        if (now - lastPeerlistRequest < PEERLIST_MIN_INTERVAL_MS) return null
        lastPeerlistRequest = now
        val sink = Channel<List<Peer>>(Channel.UNLIMITED)
        peerlistSink = sink
        try {
            val start = System.currentTimeMillis()
            send(Wire.envelope(Wire.GETPEERLIST, ""))
            val first = if ("peerlist_features" in directoryFeatures) peerlistTimeoutMs else connectTimeoutMs.toLong()
            val got = LinkedHashMap<String, Peer>()
            got.putAll((withTimeoutOrNull(first) { sink.receive() } ?: return null).associateBy { it.nick })
            val deadline = start + first + peerlistQuietMs
            while (true) {
                val left = minOf(peerlistQuietMs, deadline - System.currentTimeMillis())
                if (left <= 0) break
                val chunk = withTimeoutOrNull(left) { sink.receive() } ?: break
                got.putAll(chunk.associateBy { it.nick })
            }
            return got.values.toList()
        } finally {
            peerlistSink = null
        }
    }

    private fun readLoop() {
        var errors = 0
        try {
            socket?.soTimeout = 0
            while (!closed) {
                val text = readLine() ?: throw DirectoryException("Connection closed by the directory")
                val f = runCatching { Wire.parse(text) }.getOrNull()
                if (f == null) {
                    if (++errors >= MAX_CONSECUTIVE_ERRORS) throw DirectoryException("Too many consecutive errors ($errors)")
                    continue
                }
                errors = 0
                when (f.type) {
                    Wire.PING -> sendBlocking(Wire.envelope(Wire.PONG, ""))
                    Wire.PEERLIST -> onPeerlist(f.line)
                    Wire.NICK_AUTH_CHALLENGE, Wire.NICK_AUTH_PROOF, Wire.NICK_AUTH_RESULT ->
                        throw DirectoryException("Out-of-order nick authentication message type: ${f.type}")
                    else -> {
                        if (queuedFrames.incrementAndGet() > MAX_QUEUED_FRAMES ||
                            queuedBytes.addAndGet(f.line.length.toLong()) > MAX_QUEUED_BYTES) {
                            throw DirectoryException("Directory message buffer limit exceeded")
                        }
                        frames.trySend(f)
                    }
                }
            }
            frames.close()
        } catch (e: Exception) {
            frames.close(if (closed) null else (e as? DirectoryException ?: DirectoryException("Connection lost: ${e.message}", e)))
        } finally {
            closeQuietly()
        }
    }

    private fun onPeerlist(line: String) {
        val entries = parsePeerlist(line)
        val listed = ArrayList<Peer>()
        for ((peer, left) in entries) {
            if (left) { peerMap.remove(peer.nick); continue }
            if (!peerMap.containsKey(peer.nick) && peerMap.size >= MAX_PEERS) throw DirectoryException("Directory active peer limit exceeded")
            // Features are merged, never downgraded (`_merge_peer_features`).
            val merged = peer.copy(features = (peerMap[peer.nick]?.features.orEmpty() + peer.features).take(MAX_FEATURES).toSet())
            peerMap[peer.nick] = merged
            listed.add(merged)
        }
        peerlistSink?.trySend(listed)
    }

    val isConnected: Boolean get() = !closed && socket?.isConnected == true

    private fun closeQuietly() {
        closed = true
        runCatching { socket?.close() }
        frames.close()
    }

    /** Close the socket (no DISCONNECT frame, as Python) and stop the reader. */
    override fun close() {
        closeQuietly()
        scope.cancel()
    }
}
