package android.net

/** A link or a file picked on the desktop. */
class Uri private constructor(private val s: String) {
    val scheme: String? get() = s.substringBefore(':', "").ifEmpty { null }
    val path: String? get() = if (s.startsWith("file:")) java.net.URI(s).path else if (s.startsWith("/")) s else null
    val lastPathSegment: String? get() = path?.substringAfterLast('/')
    override fun toString() = s
    companion object {
        fun parse(s: String) = Uri(s)
        fun fromFile(f: java.io.File) = Uri(f.toURI().toString())
        @JvmField val EMPTY = Uri("")
    }
}
