package com.kilombino.pyblockwatch.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** The hand-written HTTP/1.1 client behind the ROBLO tab's requests. */
class HttpLiteTest {
    /** A one-shot HTTP server: answers [reply] to the first request and returns what it received. */
    private fun serve(reply: String, block: (port: Int) -> Unit): String {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        var got = ""
        val t = Thread {
            server.accept().use { s ->
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                val sb = StringBuilder(); var len = 0
                while (true) { val l = r.readLine() ?: break; sb.append(l).append('\n'); if (l.startsWith("Content-Length:", true)) len = l.substringAfter(':').trim().toInt(); if (l.isEmpty()) break }
                if (len > 0) { val b = CharArray(len); var off = 0; while (off < len) { val n = r.read(b, off, len - off); if (n < 0) break; off += n }; sb.append(String(b)) }
                got = sb.toString()
                s.getOutputStream().write(reply.toByteArray()); s.getOutputStream().flush()
            }
        }
        t.start()
        try { block(server.localPort) } finally { t.join(5000); server.close() }
        return got
    }

    private val direct = HttpLite.Connector { host, port, _ -> Socket(host, port) }

    @Test fun contentLengthBodyAndRequestShape() {
        lateinit var r: HttpLite.Response
        val got = serve("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}") { port ->
            r = HttpLite.request("POST", "http://127.0.0.1:$port/api/order/?order_id=5", mapOf("Authorization" to "Token abc", "Content-Type" to "application/json"),
                "{\"a\":1}".toByteArray(), direct)
        }
        assertEquals(200, r.code); assertEquals("{\"ok\":true}", r.text()); assertEquals("application/json", r.headers["content-type"])
        assertTrue(got.startsWith("POST /api/order/?order_id=5 HTTP/1.1\n"))
        assertTrue(got.contains("Host: 127.0.0.1:")); assertTrue(got.contains("Authorization: Token abc"))
        assertTrue(got.contains("Connection: close")); assertTrue(got.endsWith("{\"a\":1}"))
    }

    @Test fun chunkedBody() {
        lateinit var r: HttpLite.Response
        serve("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;x=y\r\n world\r\n0\r\n\r\n") { port ->
            r = HttpLite.request("GET", "http://127.0.0.1:$port/", emptyMap(), null, direct)
        }
        assertEquals("hello world", r.text())
    }

    @Test fun bodyUntilCloseAndContinue() {
        lateinit var r: HttpLite.Response
        serve("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 404 Not Found\r\n\r\nnope") { port ->
            r = HttpLite.request("GET", "http://127.0.0.1:$port/x", emptyMap(), null, direct)
        }
        assertEquals(404, r.code); assertEquals("nope", r.text())
    }

    @Test fun aHeaderWithALineBreakIsRefused() {
        try {
            HttpLite.request("GET", "http://127.0.0.1:1/", mapOf("X" to "a\r\nEvil: 1"), null, direct)
            fail("accepted a header with a line break")
        } catch (e: IllegalArgumentException) { assertEquals("bad header", e.message) }
    }

    @Test fun onlyHttp() {
        try { HttpLite.request("GET", "file:///etc/passwd", emptyMap(), null, direct); fail() } catch (e: IllegalArgumentException) { }
    }

    /** Through a SOCKS5 proxy with a username: Arti's per-robot circuit. */
    @Test fun throughSocksWithIsolation() {
        FakeSocks().use { socks ->
            lateinit var r: HttpLite.Response
            serve("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok") { port ->
                r = HttpLite.request("GET", "http://abcdefghijklmnop.onion:$port/api/info/", emptyMap(), null,
                    HttpLite.Connector { host, p, _ -> Socks5.connect(socks.address, host, p, "roblo:1234") })
            }
            assertEquals("ok", r.text())
            assertEquals(FakeSocks.Conn("roblo:1234", "abcdefghijklmnop.onion", socks.seen[0].port), socks.seen[0])
        }
    }
}
