package com.kilombino.pyblockwatch.joinblaket

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * An in-process directory on 127.0.0.1 that plays a [script] against the first connection: it
 * reads what the client sends ([expect]) and writes frames back ([send]). A failure inside the
 * script (an assertion on what the client sent) is rethrown by [finish].
 */
class FakeDirectory(private val script: FakeDirectory.() -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private lateinit var sock: Socket
    private lateinit var reader: BufferedReader
    @Volatile private var failure: Throwable? = null

    private val thread = Thread {
        try {
            sock = server.accept()
            sock.soTimeout = 15_000
            reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            script()
        } catch (t: Throwable) {
            failure = t
        }
    }.apply { isDaemon = true; start() }

    /** The next line the client sent, without `\r\n` (which it must carry). */
    fun expect(): String = reader.readLine() ?: error("client closed the connection")

    fun send(frame: String) { sock.getOutputStream().apply { write((frame + "\r\n").toByteArray()); flush() } }

    /** True once the client closed its side. */
    fun clientClosed(): Boolean = runCatching { reader.readLine() == null }.getOrDefault(true)

    /** Wait for the script and rethrow what failed in it. */
    fun finish(timeoutSec: Long = 20) {
        thread.join(TimeUnit.SECONDS.toMillis(timeoutSec))
        failure?.let { throw it }
        check(!thread.isAlive) { "directory script did not finish" }
    }

    override fun close() { runCatching { sock.close() }; server.close() }

    companion object {
        val hs = Vectors.section("handshake")
        val offerVec = Vectors.section("offer")
        /** The vectors' taker key: its nick is the one the handshake and offer vectors are bound to. */
        val takerKey = Vectors.big(Vectors.section("nick").getString("private_key"))

        /** The vectors' bond proof re-signed for [makerNick] (the cert key is in the vectors), [txid] optionally replaced. */
        fun proofFor(makerNick: String, takerNick: String, txid: String? = null): String {
            val raw = Base64.getDecoder().decode(offerVec.getString("proof_b64"))
            val sig = JmSig.signHash(Vectors.big(offerVec.getString("cert_private_key")), JmSig.messageHash("$takerNick|$makerNick"))
            val padded = ByteArray(72 - sig.size) { 0xff.toByte() } + sig
            System.arraycopy(padded, 0, raw, 0, 72)
            txid?.let { System.arraycopy(Vectors.hex(it), 0, raw, 212, 32) }
            return Base64.getEncoder().encodeToString(raw)
        }

        /** A signed offer privmsg frame from [maker] to [to]. */
        fun offerFrame(maker: NickIdentity, to: String, offer: String): String =
            Wire.envelope(Wire.PRIVMSG, "${maker.nick}!$to!${offer.substringBefore(' ')} ${maker.signData(offer.substringAfter(' '))}")
    }
}
