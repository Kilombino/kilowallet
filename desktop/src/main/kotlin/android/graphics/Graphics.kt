package android.graphics

/** A bitmap of ARGB pixels, enough for the QR codes the shared code draws. */
class Bitmap private constructor(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)
    fun setPixel(x: Int, y: Int, c: Int) { pixels[y * width + x] = c }
    fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]
    fun setPixels(src: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (r in 0 until h) for (c in 0 until w) pixels[(y + r) * width + x + c] = src[offset + r * stride + c]
    }
    fun getPixels(dst: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (r in 0 until h) for (c in 0 until w) dst[offset + r * stride + c] = pixels[(y + r) * width + x + c]
    }
    enum class Config { ARGB_8888, RGB_565 }
    companion object {
        fun createBitmap(w: Int, h: Int, cfg: Config): Bitmap = Bitmap(w, h)
        fun createBitmap(src: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix?, filter: Boolean): Bitmap = src
    }
}
class Matrix { fun postRotate(d: Float): Boolean = true }
object Color { const val BLACK = -0x1000000; const val WHITE = -0x1; const val TRANSPARENT = 0
    fun parseColor(s: String): Int = java.lang.Long.decode(s.replace("#", "0x")).toInt().let { if (s.length <= 7) it or -0x1000000 else it } }
