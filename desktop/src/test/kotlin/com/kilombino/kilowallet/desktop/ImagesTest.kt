package com.kilombino.kilowallet.desktop

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every image the screens draw is in the jar and decodes (WebP since Android 1.1.7). */
class ImagesTest {
    @Test fun everyDrawableDecodes() {
        for ((id, path) in com.kilombino.pyblockwatch.R.paths) {
            val bytes = javaClass.classLoader.getResourceAsStream(path)?.readBytes()
            assertTrue("$path ($id) is not in the jar", bytes != null)
            val img = org.jetbrains.skia.Image.makeFromEncoded(bytes!!)
            assertTrue("$path does not decode", img.width > 0 && img.height > 0)
        }
    }
}
