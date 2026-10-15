package android.content

/** The system clipboard (AWT's), as Android's ClipboardManager. */
class ClipboardManager {
    val primaryClip: ClipData?
        get() = runCatching {
            (java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String)
                ?.let { ClipData.newPlainText("", it) }
        }.getOrNull()
    fun setPrimaryClip(c: ClipData) { DesktopIntents.copy(c.getItemAt(0).text.toString()) }
    fun hasPrimaryClip(): Boolean = primaryClip != null
}

class ClipData private constructor(private val item: Item) {
    class Item(val text: CharSequence)
    fun getItemAt(i: Int): Item = item
    val itemCount: Int get() = 1
    companion object { fun newPlainText(label: CharSequence?, text: CharSequence): ClipData = ClipData(Item(text)) }
}
