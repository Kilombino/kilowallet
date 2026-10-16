package android.content.res

import java.io.FileNotFoundException
import java.io.InputStream

/** The app's assets (app/src/main/assets), copied into the desktop jar under assets/. */
object AssetManager {
    fun open(path: String): InputStream =
        AssetManager::class.java.getResourceAsStream("/assets/$path") ?: throw FileNotFoundException(path)
    fun list(path: String): Array<String> = emptyArray()
}
