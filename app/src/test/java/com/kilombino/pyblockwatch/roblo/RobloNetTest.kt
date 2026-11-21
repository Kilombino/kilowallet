package com.kilombino.pyblockwatch.roblo

import com.kilombino.pyblockwatch.net.FakeSocks
import com.kilombino.pyblockwatch.net.HttpLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Where the Roblosats client's requests go: Tor by default, a circuit per robot, no local addresses. */
class RobloNetTest {
    private fun net(clearnet: Boolean = false, local: Boolean = false, socks: java.net.InetSocketAddress? = null,
                    hosts: Set<String> = setOf("kilobot.kilombino.com")) =
        RobloNet(torSocks = { socks }, clearnet = { clearnet }, allowLocal = local, clearnetHosts = { hosts })

    @Test fun torByDefaultAndAlwaysForAnOnion() {
        val n = net()
        assertTrue(n.route("https://kilobot.kilombino.com/api/info/", "anon") is RobloNet.Route.Tor)
        assertTrue(n.route("http://kiloblo3ibbey3wridofauizwb467y5osfaycxoecgsf66mggsrw6ryd.onion/api/book/", "anon") is RobloNet.Route.Tor)
        assertTrue(n.route("ws://abc.onion/relay", "anon") is RobloNet.Route.Tor)
    }

    @Test fun clearnetOnlyWhenChosenAndAnOnionStillThroughTor() {
        val n = net(clearnet = true)
        assertEquals(RobloNet.Route.Direct, n.route("https://kilobot.kilombino.com/api/info/", "anon"))
        assertEquals(RobloNet.Route.Direct, n.route("wss://kilobot.kilombino.com/relay/", "anon"))
        assertTrue(n.route("http://abc.onion/api/info/", "anon") is RobloNet.Route.Tor)
        // 0.9.11: anything that is not a coordinator's clearnet host keeps going through Tor
        assertTrue(n.route("https://mempool.kilombino.com/api/tx/x", "anon") is RobloNet.Route.Tor)
    }

    @Test fun localAddressesAreRefusedExceptInTheLabOrForTheUsersOwnExplorer() {
        for (u in listOf("http://127.0.0.1:28800/api/", "http://localhost/", "http://192.168.0.14:3000/", "http://10.0.2.2/",
                "http://172.20.1.1/", "http://169.254.1.1/", "http://[::1]/", "http://[fd00::1]/", "http://[fe80::1]/", "ws://127.0.0.1:27778/relay")) {
            assertTrue(u, net().route(u, "anon") is RobloNet.Route.Refused)
            assertTrue(u, net(clearnet = true).route(u, "anon") is RobloNet.Route.Refused)
            assertEquals(u, RobloNet.Route.Direct, net(local = true).route(u, "anon"))
            assertEquals(u, RobloNet.Route.Direct, net().route(u, "anon", userChosen = true))
        }
        assertFalse(RobloNet.isLocal("8.8.8.8")); assertFalse(RobloNet.isLocal("172.32.0.1")); assertFalse(RobloNet.isLocal("kilobot.kilombino.com"))
    }

    @Test fun aLabBuildReachesOnlyTheLab() {
        val lab = RobloNet(torSocks = { null }, clearnet = { true }, allowLocal = true, clearnetHosts = { setOf("kilobot.kilombino.com") }, labOnly = true)
        assertEquals(RobloNet.Route.Direct, lab.route("http://127.0.0.1:28800/api/book/", "anon"))
        assertEquals(RobloNet.Route.Direct, lab.route("ws://127.0.0.1:27778/relay", "anon"))
        // a real coordinator, its onion, an explorer: never, whatever the federation or the mode says
        for (u in listOf("https://kilobot.kilombino.com/api/info/", "http://kiloblo3ibbey3wridofauizwb467y5osfaycxoecgsf66mggsrw6ryd.onion/api/book/",
                "wss://nostr.kilombino.com/", "https://mempool.kilombino.com/api/tx/x"))
            assertTrue(u, lab.route(u, "anon") is RobloNet.Route.Refused)
        assertTrue(lab.route("https://kilobot.kilombino.com/", "anon", userChosen = true) is RobloNet.Route.Refused)
    }

    @Test fun otherSchemesAreRefused() {
        for (u in listOf("file:///data/data/x", "javascript:alert(1)", "content://x/y", "ftp://a.b/", "not a url"))
            assertTrue(u, net().route(u, "anon") is RobloNet.Route.Refused)
    }

    @Test fun eachRobotItsOwnCircuitAndTheSameRobotAlwaysTheSame() {
        val n = net()
        val a = n.route("http://x.onion/api/robot/", RobloNet.isolationKey("http://x.onion/api/robot/", mapOf("Authorization" to "Token aaa111 | Public x | Private y | Nostr z")))
        val a2 = n.route("http://x.onion/api/order/", RobloNet.isolationKey("http://x.onion/api/order/", mapOf("authorization" to "Token aaa111")))
        val b = n.route("http://x.onion/api/robot/", RobloNet.isolationKey("http://x.onion/api/robot/", mapOf("Authorization" to "Token bbb222")))
        val chat = n.route("ws://x.onion/ws/chat/5/?token_sha256_hex=aaa111", RobloNet.isolationKey("ws://x.onion/ws/chat/5/?token_sha256_hex=aaa111", emptyMap()))
        val anon = n.route("http://x.onion/api/book/", RobloNet.isolationKey("http://x.onion/api/book/", emptyMap()))
        assertEquals(a, a2); assertEquals(a, chat)
        assertNotEquals(a, b); assertNotEquals(a, anon)
        assertTrue((a as RobloNet.Route.Tor).isolation.matches(Regex("roblo:[0-9a-f]{16}")))
        // The token itself never reaches Tor, only a hash of it.
        assertFalse(a.isolation.contains("aaa111"))
    }

    /** End to end through a SOCKS proxy: two robots really use two SOCKS usernames. */
    @Test fun requestsOfTwoRobotsLeaveWithTwoUsernames() {
        FakeSocks().use { socks ->
            val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            Thread { repeat(2) { server.accept().use { s ->
                val r = s.getInputStream().bufferedReader(); while (r.readLine()?.isNotEmpty() == true) { }
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray()) } } }.apply { isDaemon = true }.start()
            val n = net(socks = socks.address)
            for (token in listOf("Token aaa", "Token bbb")) {
                val url = "http://coordinator.onion:${server.localPort}/api/robot/"
                val route = n.route(url, RobloNet.isolationKey(url, mapOf("Authorization" to token)))
                assertEquals("{}", HttpLite.request("GET", url, mapOf("Authorization" to token), null, n.connector(route)).text())
            }
            server.close()
            assertEquals(2, socks.seen.size)
            assertNotEquals(socks.seen[0].user, socks.seen[1].user)
            assertTrue(socks.seen.all { it.host == "coordinator.onion" })
        }
    }

    @Test fun withoutTorNothingGoesOutDirectly() {
        val n = net(socks = null)
        val route = n.route("https://kilobot.kilombino.com/api/info/", "anon")
        assertTrue(runCatching { n.connector(route).open("kilobot.kilombino.com", 443, true) }.exceptionOrNull()?.message.orEmpty().contains("Tor"))
    }
}
