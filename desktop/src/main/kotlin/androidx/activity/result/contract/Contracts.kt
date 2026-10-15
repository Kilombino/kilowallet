package androidx.activity.result.contract

import android.net.Uri
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** A contract the desktop carries out itself: a file dialog for documents, yes for permissions. */
abstract class ActivityResultContract<I, O> {
    abstract fun runOnDesktop(input: I, done: (O) -> Unit)
}

object ActivityResultContracts {
    /** Save a file: the system's save dialog, the suggested name filled in. */
    class CreateDocument(private val mime: String) : ActivityResultContract<String, Uri?>() {
        override fun runOnDesktop(input: String, done: (Uri?) -> Unit) = DesktopFiles.pick(save = true, suggested = input, done = done)
    }
    /** Open a file: the system's open dialog. */
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun runOnDesktop(input: Array<String>, done: (Uri?) -> Unit) = DesktopFiles.pick(save = false, suggested = null, done = done)
    }
    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun runOnDesktop(input: String, done: (Uri?) -> Unit) = DesktopFiles.pick(save = false, suggested = null, done = done)
    }
    /** A permission is the user's own on the desktop: granted. */
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun runOnDesktop(input: String, done: (Boolean) -> Unit) = done(true)
    }
    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun runOnDesktop(input: Array<String>, done: (Map<String, Boolean>) -> Unit) = done(input.associateWith { true })
    }
}

object DesktopFiles {
    fun pick(save: Boolean, suggested: String?, done: (Uri?) -> Unit) {
        javax.swing.SwingUtilities.invokeLater {
            val d = FileDialog(null as Frame?, if (save) "Save" else "Open", if (save) FileDialog.SAVE else FileDialog.LOAD)
            d.directory = System.getProperty("user.home")
            if (suggested != null) d.file = suggested
            d.isVisible = true
            val f = d.file?.let { File(d.directory, it) }
            done(f?.let { Uri.fromFile(it) })
        }
    }
}
