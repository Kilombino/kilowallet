package com.kilombino.pyblockwatch.chain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Electrum server through Tor: a .onion (plain TCP and TLS ports), and a clearnet server with
 * "through Tor" on; without Tor nothing goes out. The live part runs only with KW_TOR_SOCKS=host:port
 * (a Tor SOCKS port) and KW_ONION=<the server's .onion>, plain on 50001 and TLS on 50002.
 */
class ElectrumTorTest {
    @After fun reset() { ElectrumClient.torProxy = null; ElectrumClient.throughTor = false }

    @Test fun onionNames() {
        assertTrue(ElectrumClient.isOnion("abcdef.onion"))
        assertTrue(ElectrumClient.isOnion(" ABCDEF.ONION. "))
        assertFalse(ElectrumClient.isOnion("onion.example.com"))
        assertFalse(ElectrumClient.isOnion("192.168.1.10"))
    }

    @Test fun withoutTorNothingGoesOut() {
        ElectrumClient.torProxy = { null }
        val onion = ElectrumClient(NodeEndpoint("f7bxv2tgul264mcme5hj4vamu327edlt564qpfdnndvw7n6qgjnjxead.onion", 50002, true), null)
        try { onion.connect(); fail() } catch (e: ElectrumException) { assertTrue(e.message!!.contains("needs Tor")) }
        // A clearnet server with "through Tor" on is not reached directly instead.
        ElectrumClient.throughTor = true
        val clear = ElectrumClient(NodeEndpoint("127.0.0.1", 9, true), null)
        try { clear.connect(); fail() } catch (e: ElectrumException) { assertTrue(e.message!!.contains("nothing was sent without it")) }
    }

    private fun live(): Pair<java.net.InetSocketAddress, String>? {
        val socks = System.getenv("KW_TOR_SOCKS") ?: return null
        val onion = System.getenv("KW_ONION") ?: return null
        val addr = java.net.InetSocketAddress(socks.substringBefore(':'), socks.substringAfter(':').toInt())
        ElectrumClient.torProxy = { java.net.Proxy(java.net.Proxy.Type.SOCKS, addr) }
        return addr to onion
    }

    @Test fun onionPlainAndTls() {
        val (_, onion) = live() ?: return
        // Fulcrum's plain port behind the onion.
        val plain = ElectrumClient(NodeEndpoint(onion, 50001, true), null)
        try { plain.connect(); assertTrue(plain.blockHeight() > 0); assertNull(plain.serverFingerprint) } finally { plain.close() }
        // Its TLS port: TLS, with the certificate captured for pinning.
        val tls = ElectrumClient(NodeEndpoint(onion, 50002, true), null)
        val fp = try { tls.connect(); assertTrue(tls.blockHeight() > 0); tls.serverFingerprint } finally { tls.close() }
        assertNotNull(fp)
        // Pinned, and the pin holds over the onion too.
        val again = ElectrumClient(NodeEndpoint(onion, 50002, true), fp)
        try { again.connect(); assertEquals(fp, again.serverFingerprint) } finally { again.close() }
        println("onion: plain and TLS (${fp!!.take(16)}…) ok")
    }

    @Test fun clearnetThroughTor() {
        live() ?: return
        ElectrumClient.throughTor = true
        val c = ElectrumClient(NodeEndpoint.default(Chain.BLAKE2B), null)
        try { c.connect(); assertTrue(c.blockHeight() > 0); assertNotNull(c.serverFingerprint) } finally { c.close() }
        println("default server through Tor ok: ${c.serverVersion}")
    }
}
