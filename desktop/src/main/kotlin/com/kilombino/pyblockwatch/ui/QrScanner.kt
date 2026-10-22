package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.kilombino.kilowallet.desktop.Tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Reading a QR on the desktop (the phone points its camera): from the webcam when there is one
 * (frames through ffmpeg from /dev/video*), from what is on the screen, from an image file or
 * from an image copied to the clipboard. Same signature as the phone's dialog.
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) = QrScannerDialog(onResult, onDismiss, null, null)

/**
 * [onFrame]: for a QR of several parts (an animated ur:… QR), each one read is handed to it and
 * reading goes on until it says the whole was read; [progress] is shown meanwhile. From the
 * screen, an animated QR is read again and again until it is whole.
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit, onFrame: ((String) -> Boolean)?, progress: String?) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val camera = remember { QrDesktop.cameraDevice() }
    var useCamera by remember { mutableStateOf(camera != null && QrDesktop.hasFfmpeg()) }
    val done = remember { mutableStateOf(false) }
    var lastPart by remember { mutableStateOf<String?>(null) }
    fun found(text: String) {
        if (done.value) return
        if (onFrame != null) {
            if (text == lastPart) return
            lastPart = text
            if (!onFrame(text)) return
        }
        done.value = true; onResult(text)
    }

    if (useCamera && camera != null) LaunchedEffect(camera) {
        withContext(Dispatchers.IO) {
            QrDesktop.camera(camera, isActive = { isActive && !done.value }, onFrame = { img ->
                frame = img.toComposeImageBitmap()
                QrDesktop.decode(img)?.let { t -> scope.launch { found(t) } }
            }, onError = { useCamera = false; status = Tr.t("The camera could not be opened.", "No se ha podido abrir la cámara.") })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Tr.t("Read a QR", "Leer un QR")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                frame?.takeIf { useCamera }?.let { Image(it, null, Modifier.size(320.dp, 240.dp)) }
                if (useCamera) Text(Tr.t("Hold the QR in front of the camera.", "Pon el QR delante de la cámara."), textAlign = TextAlign.Center)
                OutlinedButton(onClick = {
                    scope.launch {
                        val t = withContext(Dispatchers.IO) { QrDesktop.fromScreen() }
                        if (t == null) { status = Tr.t("No QR found on the screen. Make it bigger, or save it as an image.",
                            "No hay ningún QR en la pantalla. Hazlo más grande o guárdalo como imagen."); return@launch }
                        found(t)
                        // An animated QR: keep reading the screen until every part is in (two minutes at most).
                        if (onFrame != null) {
                            val until = System.currentTimeMillis() + 120_000
                            while (!done.value && System.currentTimeMillis() < until) {
                                kotlinx.coroutines.delay(150)
                                withContext(Dispatchers.IO) { QrDesktop.fromScreen() }?.let { found(it) }
                            }
                        }
                    }
                }, Modifier.fillMaxWidth()) { Text(Tr.t("Read from the screen", "Leer de la pantalla")) }
                OutlinedButton(onClick = {
                    androidx.activity.result.contract.DesktopFiles.pick(save = false, suggested = null) { uri ->
                        val f = uri?.path?.let { File(it) } ?: return@pick
                        val t = runCatching { ImageIO.read(f)?.let { QrDesktop.decode(it) } }.getOrNull()
                        if (t != null) found(t) else status = Tr.t("No QR found in that image.", "No hay ningún QR en esa imagen.")
                    }
                }, Modifier.fillMaxWidth()) { Text(Tr.t("Open an image", "Abrir una imagen")) }
                OutlinedButton(onClick = {
                    val t = QrDesktop.fromClipboard()
                    if (t != null) found(t) else status = Tr.t("The clipboard holds no image with a QR.", "En el portapapeles no hay ninguna imagen con un QR.")
                }, Modifier.fillMaxWidth()) { Text(Tr.t("Paste an image", "Pegar una imagen")) }
                progress?.let { Text(it, textAlign = TextAlign.Center) }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(Tr.t("Close", "Cerrar")) } },
    )
}

internal object QrDesktop {
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
    )

    fun decode(img: BufferedImage): String? {
        val source = BufferedImageLuminanceSource(img)
        for (b in listOf(HybridBinarizer(source), com.google.zxing.common.GlobalHistogramBinarizer(source), HybridBinarizer(source.invert()))) {
            runCatching { return MultiFormatReader().apply { setHints(hints) }.decodeWithState(BinaryBitmap(b)).text }
        }
        return null
    }

    /** Every screen, captured (needs X11 or XWayland; under pure Wayland it may come back blank). */
    fun fromScreen(): String? = runCatching {
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        ge.screenDevices.firstNotNullOfOrNull { d ->
            val r = d.defaultConfiguration.bounds
            decode(java.awt.Robot(d).createScreenCapture(r))
        }
    }.getOrNull()

    fun fromClipboard(): String? = runCatching {
        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        val img = cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image ?: return null
        val b = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB)
        b.graphics.drawImage(img, 0, 0, null)
        decode(b)
    }.getOrNull()

    fun cameraDevice(): String? = (0..3).map { "/dev/video$it" }.firstOrNull { File(it).exists() && File(it).canRead() }

    fun hasFfmpeg(): Boolean = com.kilombino.kilowallet.desktop.Platform.onPath("ffmpeg") != null

    /** Frames from the webcam as JPEGs, five a second, until [isActive] says stop. */
    fun camera(device: String, isActive: () -> Boolean, onFrame: (BufferedImage) -> Unit, onError: () -> Unit) {
        val p = runCatching {
            ProcessBuilder("ffmpeg", "-loglevel", "error", "-f", "v4l2", "-i", device, "-vf", "fps=5,scale=640:-2",
                "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "5", "-").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrElse { onError(); return }
        try {
            val input = java.io.BufferedInputStream(p.inputStream)
            var frames = 0
            while (isActive()) {
                val jpeg = nextJpeg(input) ?: break
                frames++
                ImageIO.read(jpeg.inputStream())?.let(onFrame)
            }
            if (frames == 0) onError()
        } finally { p.destroy() }
    }

    /** One JPEG from an MJPEG stream: from FFD8 to FFD9. */
    private fun nextJpeg(s: java.io.InputStream): ByteArray? {
        var prev = -1
        while (true) { val b = s.read(); if (b < 0) return null; if (prev == 0xFF && b == 0xD8) break; prev = b }
        val out = java.io.ByteArrayOutputStream(); out.write(0xFF); out.write(0xD8); prev = -1
        while (true) { val b = s.read(); if (b < 0) return null; out.write(b); if (prev == 0xFF && b == 0xD9) return out.toByteArray(); prev = b }
    }
}
