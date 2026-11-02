package com.kilombino.pyblockwatch.coinjoin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The relay's onion over Tor, falling back to wss:// through a Tor exit. Offline checks always;
 * the live ones with KILOJOIN_E2E_TOR=host:port (a Tor SOCKS port, e.g. 127.0.0.1:9050).
 */
class RelayOnionTest {
    private val tor = System.getenv("KILOJOIN_E2E_TOR")

    @After fun reset() { RelayClient.socksOverride = null; RelayClient.onionFailedAt = 0L }

    @Test fun onlyOurRelayHasAnOnion() {
        assertEquals(Protocol.RELAY_ONION, RelayClient.onionFor(Protocol.RELAY))
        assertNull(RelayClient.onionFor("wss://relay.example.com"))
        assertTrue(RelayClient.isOnion(java.net.URI(Protocol.RELAY_ONION).host))
        // What pools and other wallets know stays the clearnet address.
        assertEquals("wss://relay.kilombino.com", Protocol.RELAY)
    }

    @Test fun plainWsNeverOffTor() {
        // Without Tor, ws:// to the onion is refused before any connection is tried.
        val c = RelayClient("ws://pf7d47aytdrrlmoa6f23gpsi7bjwvpeo27gnuz4vhh44rtn2r4bdy6qd.onion", { _, _ -> })
        val e = runCatching { c.connect() }.exceptionOrNull()
        assertTrue("$e", e is IllegalArgumentException)
    }

    private fun useTor() {
        assumeTrue("set KILOJOIN_E2E_TOR=host:port", tor != null)
        RelayClient.socksOverride = InetSocketAddress(tor!!.substringBefore(':'), tor.substringAfter(':').toInt())
        RelayClient.log = { println(it) }
    }

    /** Pool listings of the last three days, read over [c]: the subscription must reach EOSE. */
    private fun readPools(c: RelayClient, got: AtomicInteger) {
        c.subscribe("pools", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL))
            .put("#t", JSONArray().put(Protocol.TAG)).put("since", System.currentTimeMillis() / 1000 - 3 * 86400)), timeoutSec = 60)
        println("pool listings read: ${got.get()} via ${c.via}")
    }

    @Test fun onionFirstOverTor() {
        useTor()
        for (iso in listOf(null, "kj-seat-test", "kj-out-test")) {
            val got = AtomicInteger()
            val c = RelayClient(Protocol.RELAY, { _, ev -> if (ev.kind == Protocol.KIND_POOL) got.incrementAndGet() }, { println("closed: $it") }, iso)
            try {
                c.connect()
                assertEquals("isolation $iso", RelayClient.VIA_ONION, c.via)
                readPools(c, got)
                assertTrue(c.connected)
            } finally { c.close() }
        }
    }

    @Test fun unreachableOnionFallsBackToTheExit() {
        useTor()
        // A well-formed onion nobody runs: Tor finds no descriptor ("general failure" or a timeout).
        val dead = "ws://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaad.onion"
        val got = AtomicInteger()
        val c = RelayClient(Protocol.RELAY, { _, ev -> if (ev.kind == Protocol.KIND_POOL) got.incrementAndGet() }, {}, "kj-seat-fallback", dead)
        try {
            c.connect()
            assertEquals(RelayClient.VIA_TOR, c.via)
            readPools(c, got)
            assertTrue(c.connected)
        } finally { c.close() }
        // For the next while the onion is skipped: straight to the exit, no new wait.
        val t = System.currentTimeMillis()
        val c2 = RelayClient(Protocol.RELAY, { _, _ -> }, {}, null, dead)
        try { c2.connect(); assertEquals(RelayClient.VIA_TOR, c2.via) } finally { c2.close() }
        println("second connection, onion skipped: ${System.currentTimeMillis() - t} ms")
    }

    @Test fun bothDownSaysBoth() {
        useTor()
        val c = RelayClient("wss://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaad.onion", { _, _ -> }, {}, "kj-both",
            "ws://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaad.onion")
        val e = runCatching { c.connect() }.exceptionOrNull()
        println("both down: ${e?.message}")
        assertTrue("${e?.message}", e?.message.orEmpty().contains("its onion too"))
    }
}
