package com.kilombino.pyblockwatch.joinblaket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/** What a taker round needs from "the directory": one [DirectoryClient] or a [DirectoryPool]. */
interface Directory : Closeable {
    /** Our nick, the same on every directory. */
    val nick: String
    /** True once every connected directory accepted our nick proof. */
    val authenticated: Boolean
    suspend fun connect()
    suspend fun sendPublic(message: String)
    suspend fun sendPrivate(to: String, command: String, data: String)
    /** The next PRIVMSG/PUBMSG, or null after [timeoutMs]; throws once no connection is left. */
    suspend fun receive(timeoutMs: Long): Wire.Frame?
    suspend fun getPeerlist(): List<DirectoryClient.Peer>?
}

/**
 * Several JoinBlaket directories as one (`jmcore` `MultiDirectoryClient`): the same nick on all
 * of them, so a maker announced on any of them can be reached.
 *
 * - [connect] connects to all at once and keeps those that answer; it fails only when none
 *   does. Each one runs its own handshake and nick authentication ([DirectoryClient]).
 * - [sendPublic] goes to every directory still connected.
 * - [sendPrivate] goes through ONE directory: the one the peer last wrote to us through, else one
 *   whose peerlist has it, else the first connected; on a send error, the next one. A privmsg is
 *   signed over its data and the fixed `onion-network` host id, so it verifies whichever
 *   directory carries it, and a maker never gets the same command twice.
 * - [receive] merges what every directory delivers. A frame identical to one already delivered
 *   (the same signed message heard through two directories) is dropped; offers repeated by a
 *   maker on several directories are deduplicated by [Orderbook.parse] anyway.
 * - A directory that drops is left out; [receive] throws once all of them have dropped.
 */
class DirectoryPool(
    private val clients: List<DirectoryClient>,
    private val log: (String) -> Unit = {},
) : Directory {
    init {
        require(clients.isNotEmpty()) { "no directories" }
        require(clients.map { it.nick }.toSet().size == 1) { "one nick on every directory" }
    }

    private class Alive(val client: DirectoryClient, val index: Int)

    override val nick: String get() = clients.first().nick
    @Volatile private var live: List<Alive> = emptyList()
    override val authenticated: Boolean get() = live.isNotEmpty() && live.all { it.client.authenticated }
    /** How many directories are connected now. */
    val connected: Int get() = live.size
    /** The `host:port` of each connected directory. */
    val connectedEndpoints: List<String> get() = live.map { "${it.client.host}:${it.client.port}" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = Channel<Wire.Frame>(Channel.UNLIMITED)
    /** Peer nick -> index of the directory it last wrote to us through. */
    private val heardVia = ConcurrentHashMap<String, Int>()
    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > SEEN_FRAMES
    }

    companion object {
        /** How many recent frames are remembered to drop a copy heard through another directory. */
        const val SEEN_FRAMES = 4_096
    }

    override suspend fun connect() = coroutineScope {
        check(live.isEmpty()) { "connect() once" }
        val results = clients.mapIndexed { i, c ->
            async {
                try { c.connect(); Alive(c, i) } catch (e: DirectoryException) {
                    log("directory ${c.host}:${c.port}: ${e.message}"); c.close(); null
                }
            }
        }.awaitAll()
        val ok = results.filterNotNull()
        if (ok.isEmpty()) throw DirectoryException("No JoinBlaket directory could be reached")
        live = ok
        for (a in ok) scope.launch { pump(a) }
        Unit
    }

    /** Forward one directory's frames into [frames]; drop it when it goes. */
    private suspend fun pump(a: Alive) {
        try {
            while (true) {
                val f = a.client.receive(60_000) ?: continue
                val from = f.line.substringBefore('!', "")
                if (from.isNotEmpty()) heardVia[from] = a.index
                val fresh = synchronized(seen) { seen.put("${f.type} ${f.line}", true) == null }
                if (fresh) frames.trySend(f)
            }
        } catch (e: DirectoryException) {
            drop(a, e.message)
        }
    }

    private fun drop(a: Alive, why: String?) {
        val left = synchronized(this) { live = live - a; live.size }
        log("directory ${a.client.host}:${a.client.port} dropped: $why ($left left)")
        a.client.close()
        if (left == 0) frames.close(DirectoryException("Connection to every JoinBlaket directory lost"))
    }

    override suspend fun sendPublic(message: String) {
        var sent = 0
        var last: DirectoryException? = null
        for (a in live) {
            try { a.client.sendPublic(message); sent++ } catch (e: DirectoryException) { last = e; drop(a, e.message) }
        }
        if (sent == 0) throw last ?: DirectoryException("Connection closed")
    }

    /** The connected directories in the order to try for [to]. */
    internal fun routeFor(to: String): List<DirectoryClient> {
        val now = live
        val via = heardVia[to]
        val first = now.firstOrNull { it.index == via }
            ?: now.firstOrNull { to in it.client.peers }
        return (listOfNotNull(first) + (now - setOfNotNull(first))).map { it.client }
    }

    override suspend fun sendPrivate(to: String, command: String, data: String) {
        var last: DirectoryException? = null
        for (c in routeFor(to)) {
            try { c.sendPrivate(to, command, data); return } catch (e: DirectoryException) {
                last = e; live.firstOrNull { it.client === c }?.let { drop(it, e.message) }
            }
        }
        throw last ?: DirectoryException("Connection closed")
    }

    override suspend fun receive(timeoutMs: Long): Wire.Frame? = try {
        withTimeoutOrNull(timeoutMs) { frames.receive() }
    } catch (e: ClosedReceiveChannelException) {
        throw DirectoryException("Connection closed")
    }

    override suspend fun getPeerlist(): List<DirectoryClient.Peer>? = coroutineScope {
        val lists = live.map { a -> async { runCatching { a.client.getPeerlist() }.getOrNull() } }.awaitAll()
        if (lists.all { it == null }) null
        else lists.filterNotNull().flatten().associateBy { it.nick }.values.toList()
    }

    override fun close() {
        scope.cancel()
        clients.forEach { runCatching { it.close() } }
        frames.close()
    }
}
