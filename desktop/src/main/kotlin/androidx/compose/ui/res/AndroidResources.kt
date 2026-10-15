package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap

/** painterResource(R.drawable.x) as on Android, from the images copied into the desktop jar. */
@Composable
fun painterResource(id: Int): Painter = remember(id) {
    val path = com.kilombino.pyblockwatch.R.paths[id]
    val bytes = path?.let { Thread.currentThread().contextClassLoader.getResourceAsStream(it)?.use { s -> s.readBytes() } }
    if (bytes == null) ColorPainter(androidx.compose.ui.graphics.Color.Transparent)
    else BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
