package com.kilombino.pyblockwatch.joinblaket

import org.json.JSONObject

/**
 * The onion message channel framing (`jmcore/network.py`, `jmcore/directory_client.py`): one
 * JSON object `{"type": <int>, "line": <string>}` per `\r\n`-terminated line, plus the byte
 * helpers the rest of the package shares.
 */
object Wire {
    const val PRIVMSG = 685
    const val PUBMSG = 687
    const val PEERLIST = 789
    const val GETPEERLIST = 791
    const val HANDSHAKE = 793
    const val DN_HANDSHAKE = 795
    const val PING = 798
    const val PONG = 799
    const val NICK_AUTH_CHALLENGE = 803
    const val NICK_AUTH_PROOF = 805
    const val NICK_AUTH_RESULT = 807

    /** The fixed hostid every privmsg signature covers, whatever channel carries it. */
    const val ONION_HOSTID = "onion-network"

    /** One received frame. */
    data class Frame(val type: Int, val line: String)

    /**
     * A frame as Python's `json.dumps({"type": t, "line": l})` writes it (`", "` / `": "`
     * separators, non-ASCII escaped), without the trailing `\r\n`. Peers parse JSON, so only
     * the HANDSHAKE needs these exact bytes, but matching them keeps the vectors byte-equal.
     */
    fun envelope(type: Int, line: String): String = "{\"type\": $type, \"line\": ${quote(line)}}"

    /** Parse one received line (trailing `\r\n` allowed); throws on anything that is not a frame. */
    fun parse(text: String): Frame {
        val o = JSONObject(text.trimEnd('\r', '\n'))
        return Frame(o.getInt("type"), o.getString("line"))
    }

    /** A JSON string literal exactly as Python's `json.dumps` (ensure_ascii) writes it. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000c' -> sb.append("\\f")
            c < ' ' || c > '~' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    /** Bitcoin compactsize (varint) of [n]. */
    fun compactSize(n: Long): ByteArray = when {
        n < 0xfd -> byteArrayOf(n.toByte())
        n <= 0xffff -> byteArrayOf(0xfd.toByte(), n.toByte(), (n ushr 8).toByte())
        n <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + le(n, 4)
        else -> byteArrayOf(0xff.toByte()) + le(n, 8)
    }

    /** [n] as [bytes] little-endian bytes. */
    fun le(n: Long, bytes: Int): ByteArray = ByteArray(bytes) { (n ushr (8 * it)).toByte() }

    /** Little-endian unsigned integer from [len] bytes of [b] at [off]. */
    fun readLe(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private val HEX = Regex("^[0-9a-fA-F]*$")

    /** Hex to bytes, rejecting odd length and non-hex characters (Hashes.hexToBytes does not). */
    fun hex(s: String): ByteArray {
        require(s.length % 2 == 0 && HEX.matches(s)) { "not hex" }
        return com.kilombino.pyblockwatch.crypto.Hashes.hexToBytes(s)
    }
}
