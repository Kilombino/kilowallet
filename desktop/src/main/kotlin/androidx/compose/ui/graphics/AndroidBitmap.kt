package androidx.compose.ui.graphics

/** android.graphics.Bitmap (the QR codes the shared code draws) as a Compose image. */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap {
    val img = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, width, height, pixels, 0, width)
    return img.toComposeImageBitmap()
}
