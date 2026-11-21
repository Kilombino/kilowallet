package com.kilombino.pyblockwatch.roblo

import com.kilombino.pyblockwatch.net.HttpLite
import com.kilombino.pyblockwatch.net.Socks5
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest

/**
 * Where each request of the Roblosats client goes. Kilowallet's Tor (Arti) by default, with a
 * circuit of its own per robot (SOCKS username "roblo:<hash>"), so the coordinator can't tie two
 * robots of this phone together by their Tor exit or circuit. A .onion always through Tor.
 * Clearnet only when chosen in Roblosats' own settings. Addresses on this phone or the local
 * network are refused (the page must not reach the phone's other services), except in a debug
 * build for the regtest lab, and for the rescue explorer the user types (it may be their own node).
 */
class RobloNet(
    /** Tor's SOCKS address, waiting for it to come up; null when Tor can't run. */
    private val torSocks: () -> InetSocketAddress?,
    /** Clearnet mode chosen in Roblosats' settings. */
    private val clearnet: () -> Boolean,
    /** Debug builds: the lab on 127.0.0.1 / the emulator's host. Never in a release or beta. */
    private val allowLocal: Boolean = false,
    /** In Clearnet mode only these hosts (the coordinators' clearnet addresses) go direct. */
    private val clearnetHosts: () -> Set<String> = { emptySet() },
) {
    sealed class Route {
        object Direct : Route()
        data class Tor(val isolation: String) : Route()
        data class Refused(val why: String) : Route()
    }

    /**
     * The route for [url]. [isolationKey]: what tells this robot apart (its token's hash); the
     * same key always gets the same circuit. [userChosen]: an address the user typed (the rescue
     * explorer), which may be on the local network.
     */
    fun route(url: String, isolationKey: String, userChosen: Boolean = false): Route {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return Route.Refused("bad address")
        val scheme = u.scheme?.lowercase()
        if (scheme !in setOf("http", "https", "ws", "wss")) return Route.Refused("only http(s) and ws(s)")
        val host = u.host?.lowercase()?.trim('[', ']') ?: return Route.Refused("no host")
        if (host.endsWith(".onion")) return Route.Tor(isolation(isolationKey))
        if (isLocal(host)) return if (allowLocal || userChosen) Route.Direct else Route.Refused("local addresses are not reachable from Roblosats")
        // 0.9.11: only the coordinators' own clearnet hosts go direct; anything else (an explorer,
        // another site) keeps going through Tor even in Clearnet mode.
        return if (clearnet() && host in clearnetHosts()) Route.Direct else Route.Tor(isolation(isolationKey))
    }

    /** Opens the connection for [route]: through Tor with its isolation, or directly; TLS on top when asked. */
    fun connector(route: Route): HttpLite.Connector = HttpLite.Connector { host, port, tls ->
        val raw = when (route) {
            is Route.Direct -> Socks5.direct(host, port)
            is Route.Tor -> {
                val socks = torSocks() ?: error("Tor is not running")
                Socks5.connect(socks, host, port, route.isolation)
            }
            is Route.Refused -> error(route.why)
        }
        if (tls) Socks5.tls(raw, host, port) else raw
    }

    companion object {
        /** "roblo:" + 16 hex of SHA-256(key): Arti's circuit for this robot. */
        fun isolation(key: String): String =
            "roblo:" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        /**
         * What identifies the robot in a request: the token hash in its Authorization header
         * ("Token <sha256> | Public … | Private …") or in a chat WebSocket's address
         * (?token_sha256_hex=…). Requests of no robot (the book, coordinator info, relays) share one.
         */
        fun isolationKey(url: String, headers: Map<String, String>): String {
            headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
                ?.let { Regex("Token\\s+([^\\s|]+)").find(it)?.groupValues?.get(1) }
                ?.let { return "robot:$it" }
            Regex("[?&]token_sha256_hex=([0-9A-Za-z]+)").find(url)?.groupValues?.get(1)?.let { return "robot:$it" }
            return "anon"
        }

        /** This phone or the local network: loopback, private, link-local, unique-local, "localhost". */
        fun isLocal(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h == "localhost" || h.endsWith(".localhost") || h == "0.0.0.0" || h == "::" || h == "::1") return true
            val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(h)
            if (v4 != null) {
                val (a, b) = v4.destructured.let { it.component1().toInt() to it.component2().toInt() }
                return a == 127 || a == 10 || a == 0 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
            }
            if (':' in h) return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h.startsWith("fe9") ||
                h.startsWith("fea") || h.startsWith("feb") || h.startsWith("::ffff:")
            return false
        }
    }
}
