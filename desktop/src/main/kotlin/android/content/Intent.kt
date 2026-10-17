package android.content

import android.net.Uri
import android.os.Bundle

/** An Android Intent, as far as the shared code builds them: actions, data, extras, flags. */
open class Intent(var action: String? = null, var data: Uri? = null) {
    constructor(ctx: Context, cls: Class<*>) : this() { component = ComponentName(ctx, cls) }
    constructor(action: String, uri: Uri?, ctx: Context, cls: Class<*>) : this(action, uri) { component = ComponentName(ctx, cls) }

    var component: ComponentName? = null
    var type: String? = null
    var flags: Int = 0
    val extras = Bundle()
    var chosen: Intent? = null

    fun setPackage(p: String?): Intent = this
    fun addFlags(f: Int): Intent = apply { flags = flags or f }
    fun setFlags(f: Int): Intent = apply { flags = f }
    fun setData(u: Uri?): Intent = apply { data = u }
    fun setAction(a: String?): Intent = apply { action = a }
    fun setType(t: String?): Intent = apply { type = t }
    fun addCategory(c: String): Intent = this

    fun putExtra(k: String, v: String?): Intent = apply { extras.putString(k, v) }
    fun putExtra(k: String, v: Int): Intent = apply { extras.putInt(k, v) }
    fun putExtra(k: String, v: Long): Intent = apply { extras.putLong(k, v) }
    fun putExtra(k: String, v: Boolean): Intent = apply { extras.putBoolean(k, v) }
    fun putExtra(k: String, v: CharSequence?): Intent = apply { extras.putString(k, v?.toString()) }
    fun putExtra(k: String, v: android.os.Parcelable?): Intent = apply { extras.putObject(k, v) }
    fun putExtra(k: String, v: Array<String>?): Intent = apply { extras.putObject(k, v) }
    fun getStringExtra(k: String): String? = extras.getString(k)
    fun getIntExtra(k: String, d: Int): Int = extras.getInt(k, d)
    fun getLongExtra(k: String, d: Long): Long = extras.getLong(k, d)
    fun getBooleanExtra(k: String, d: Boolean): Boolean = extras.getBoolean(k, d)
    fun hasExtra(k: String): Boolean = extras.containsKey(k)

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_CHOOSER = "android.intent.action.CHOOSER"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_ACTIVITY_SINGLE_TOP = 0x20000000
        const val FLAG_ACTIVITY_CLEAR_TASK = 0x00008000
        const val FLAG_GRANT_READ_URI_PERMISSION = 1

        fun createChooser(target: Intent, title: CharSequence?): Intent =
            Intent(ACTION_CHOOSER).also { it.chosen = target; it.putExtra("title", title?.toString()) }
    }
}

/**
 * What starting an Intent means on the desktop: a link opens in the browser, a share puts the
 * text on the clipboard (and says so), anything else (Android settings screens) is skipped.
 */
object DesktopIntents {
    /** Shown by the window: a share's text was copied, a screen does not exist here… */
    @Volatile var onMessage: (String) -> Unit = {}

    fun start(intent: Intent) {
        val i = intent.chosen ?: intent
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.toString()?.let { open(it) }
            Intent.ACTION_SEND -> {
                val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n\n")
                if (text.isNotBlank()) {
                    copy(text)
                    onMessage("Copied to the clipboard, to paste where you want to share it.")
                }
            }
        }
    }

    /**
     * Opens a web page. Only http(s) addresses: what reaches here can come from a server's answer
     * (an update's page, an explorer link), and a file: or other scheme would hand the system
     * whatever it pointed at.
     */
    fun open(url: String) {
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "https" && scheme != "http") return
        runCatching {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            else com.kilombino.kilowallet.desktop.Platform.open(url)
        }
    }

    /**
     * Copies [text], and takes it off the clipboard again a minute later if it is still there
     * (as Android clears a copied secret): words, keys and addresses do not stay in the
     * session's clipboard history for good. A clipboard manager may still have kept a copy.
     */
    fun copy(text: String) {
        runCatching {
            val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            cb.setContents(java.awt.datatransfer.StringSelection(text), null)
            val t = javax.swing.Timer(60_000) {
                runCatching {
                    if (cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor) == text) cb.setContents(java.awt.datatransfer.StringSelection(""), null)
                }
            }
            t.isRepeats = false
            t.start()
        }
    }

    /** Background services run inside the desktop app (see DesktopServiceHost). */
    @Suppress("UNCHECKED_CAST")
    fun service(intent: Intent, start: Boolean): ComponentName? {
        val name = intent.component?.className ?: return null
        val cls = runCatching { Class.forName(name) }.getOrNull() ?: return null
        if (!android.app.Service::class.java.isAssignableFrom(cls)) return null
        if (start) android.app.DesktopServiceHost.start(cls as Class<out android.app.Service>, intent)
        else android.app.DesktopServiceHost.stop(cls)
        return intent.component
    }
}
