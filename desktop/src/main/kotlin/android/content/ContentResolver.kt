package android.content

import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Files picked on the desktop come as file: Uris; this opens them. */
object ContentResolver {
    fun openInputStream(uri: Uri): InputStream? = uri.file()?.inputStream()
    fun openOutputStream(uri: Uri): OutputStream? = uri.file()?.let { f -> f.parentFile?.mkdirs(); f.outputStream() }
    fun openOutputStream(uri: Uri, mode: String): OutputStream? = openOutputStream(uri)
    private fun Uri.file(): File? = path?.let { File(it) }
}
