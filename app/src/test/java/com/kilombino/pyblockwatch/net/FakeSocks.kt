package com.kilombino.pyblockwatch.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A SOCKS5 proxy in the test's own process (as Arti's SOCKS port): no-auth or username/password,
 * CONNECT by host name. It records each connection's username and destination, and connects
 * anything to the loopback (a ".onion" name included), so isolation can be checked off-device.
 */
class FakeSocks(private val resolve: (String) -> String = { "127.0.0.1" }) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val address = InetSocketAddress("127.0.0.1", server.localPort)
    data class Conn(val user: String?, val host: String, val port: Int)
    val seen = CopyOnWriteArrayList<Conn>()

    init {
        Thread({
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ handle(s) }, "fake-socks").apply { isDaemon = true }.start()
            }
        }, "fake-socks-accept").apply { isDaemon = true }.start()
    }

    private fun handle(s: Socket) {
        runCatching {
            val i = s.getInputStream(); val o = s.getOutputStream()
            val ver = i.read(); val n = i.read(); val methods = ByteArray(n).also { Socks5.readFully(i, n).copyInto(it) }
            check(ver == 5)
            var user: String? = null
            if (methods.contains(2)) {
                o.write(byteArrayOf(5, 2)); o.flush()
                i.read(); val ul = i.read(); user = String(Socks5.readFully(i, ul)); val pl = i.read(); Socks5.readFully(i, pl)
                o.write(byteArrayOf(1, 0)); o.flush()
            } else { o.write(byteArrayOf(5, 0)); o.flush() }
            val req = Socks5.readFully(i, 4)
            check(req[1].toInt() == 1 && req[3].toInt() == 3)
            val hl = i.read(); val host = String(Socks5.readFully(i, hl))
            val pb = Socks5.readFully(i, 2); val port = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)
            seen.add(Conn(user, host, port))
            val t = Socket(resolve(host), port)
            o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); o.flush()
            val a = Thread { runCatching { t.getInputStream().copyTo(o) }; runCatching { s.close() } }.apply { isDaemon = true; start() }
            runCatching { i.copyTo(t.getOutputStream()) }; runCatching { t.close() }
            a.join(1000)
        }
        runCatching { s.close() }
    }

    override fun close() { runCatching { server.close() } }
}
