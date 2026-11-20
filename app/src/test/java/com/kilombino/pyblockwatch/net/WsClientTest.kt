package com.kilombino.pyblockwatch.net

import com.kilombino.pyblockwatch.coinjoin.TestRelay
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The WebSocket client behind the ROBLO tab's relays and chat, against an in-process relay. */
class WsClientTest {
    private val direct = HttpLite.Connector { host, port, _ -> Socket(host, port) }

    @Test fun messagesBothWaysAndANormalClose() {
        TestRelay().use { relay ->
            val got = LinkedBlockingQueue<String>(); val closed = CompletableFuture<Throwable?>()
            val ws = WsClient(relay.url, direct, onText = { got.add(it) }, onClosed = { closed.complete(it) })
            ws.connect()
            assertTrue(ws.send(JSONArray().put("REQ").put("s1").put(org.json.JSONObject().put("kinds", JSONArray().put(1))).toString()))
            val eose = got.poll(5, TimeUnit.SECONDS)
            assertEquals("[\"EOSE\",\"s1\"]", eose?.replace(" ", ""))
            ws.close()
            assertNull(closed.get(5, TimeUnit.SECONDS))
            assertFalse(ws.send("x"))
        }
    }

    /** The relay goes away (a dropped Tor circuit): the page is told with an error, once. */
    @Test fun aDroppedConnectionEndsWithAnError() {
        TestRelay().use { relay ->
            val closed = CompletableFuture<Throwable?>(); var calls = 0
            val ws = WsClient(relay.url, direct, onText = {}, onClosed = { calls++; closed.complete(it) })
            ws.connect()
            Thread.sleep(200)
            relay.dropConnections()
            assertNotNull(closed.get(5, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertEquals(1, calls)
            assertFalse(ws.open)
        }
    }

    /** No answer at all for too long (a connection the phone lost silently): closed with an error. */
    @Test fun silenceIsNoticedByThePing() {
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
        Thread {
            val s = server.accept()
            val r = s.getInputStream().bufferedReader()
            var key = ""
            while (true) { val l = r.readLine() ?: break; if (l.startsWith("Sec-WebSocket-Key:")) key = l.substringAfter(':').trim(); if (l.isEmpty()) break }
            val accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            s.getOutputStream().write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            Thread.sleep(5000) // never answers the pings
            s.close()
        }.apply { isDaemon = true }.start()
        val closed = CompletableFuture<Throwable?>()
        val ws = WsClient("ws://127.0.0.1:${server.localPort}/relay", direct, onText = {}, onClosed = { closed.complete(it) }, pingEveryMs = 200, deadAfterMs = 600)
        ws.connect()
        val err = closed.get(5, TimeUnit.SECONDS)
        assertTrue(err?.message.orEmpty().contains("no answer"))
        server.close()
    }

    @Test fun aServerThatIsNotAWebSocketIsRefused() {
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
        Thread { server.accept().use { it.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray()) } }.apply { isDaemon = true }.start()
        val ws = WsClient("ws://127.0.0.1:${server.localPort}/", direct, onText = {})
        assertTrue(runCatching { ws.connect() }.exceptionOrNull()?.message.orEmpty().contains("refused"))
        server.close()
    }
}
