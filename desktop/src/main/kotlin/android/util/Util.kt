package android.util

/** android.util.Base64 with the flags the shared code passes (NO_WRAP, URL_SAFE…), over java.util.Base64. */
object Base64 {
    const val DEFAULT = 0; const val NO_PADDING = 1; const val NO_WRAP = 2; const val CRLF = 4; const val URL_SAFE = 8
    fun encodeToString(b: ByteArray?, flags: Int): String {
        b ?: throw NullPointerException("null input")
        var e = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) e = e.withoutPadding()
        val s = e.encodeToString(b)
        return if (flags and NO_WRAP != 0) s else s.chunked(76).joinToString("\n") + "\n"
    }
    fun encode(b: ByteArray, flags: Int): ByteArray = encodeToString(b, flags).toByteArray()
    fun decode(s: String, flags: Int): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        return if (flags and URL_SAFE != 0 || clean.any { it == '-' || it == '_' }) java.util.Base64.getUrlDecoder().decode(clean)
        else java.util.Base64.getDecoder().decode(clean)
    }
    fun decode(b: ByteArray, flags: Int): ByteArray = decode(String(b), flags)
}

object Log {
    private fun out(level: String, tag: String, msg: String) = System.err.println("$level/$tag: $msg")
    fun d(tag: String, msg: String): Int { out("D", tag, msg); return 0 }
    fun i(tag: String, msg: String): Int { out("I", tag, msg); return 0 }
    fun w(tag: String, msg: String): Int { out("W", tag, msg); return 0 }
    fun w(tag: String, msg: String, t: Throwable?): Int { out("W", tag, "$msg ${t?.message ?: ""}"); return 0 }
    fun e(tag: String, msg: String): Int { out("E", tag, msg); return 0 }
    fun e(tag: String, msg: String, t: Throwable?): Int { out("E", tag, "$msg ${t?.message ?: ""}"); return 0 }
}

class Size(val width: Int, val height: Int)
class SizeF(val width: Float, val height: Float)
