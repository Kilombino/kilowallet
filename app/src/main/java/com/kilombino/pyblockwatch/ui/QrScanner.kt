package com.kilombino.pyblockwatch.ui

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.kilombino.pyblockwatch.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * A full-screen QR scanner. Reads an extended public key off another screen or a paper
 * backup so the user doesn't have to type it. CameraX drives the preview; ZXing (pure
 * Java) decodes the frames. Nothing is stored — the decoded text is handed straight back.
 *
 * An xpub QR is a *dense* one (BIP-32 extended keys are ~110 base58 chars, so a version
 * 8–11 symbol with tiny modules). Two things make those readable that a naive scanner
 * gets wrong: capturing at a high enough resolution that each module survives, and letting
 * ZXing spend real effort per frame. We ask CameraX for ~1280×720 and turn on TRY_HARDER.
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) = QrScannerDialog(onResult, onDismiss, null, null)

/**
 * The scanner for an animated QR (a UR such as a signed PSBT from SeedSigner): every new frame
 * goes to [onFrame], which returns true once the message is whole; then [onResult] gets "".
 * [progress] is shown under the preview while frames come in.
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit, onFrame: ((String) -> Boolean)?, progress: String?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        // A dense xpub QR needs enough pixels that its small modules survive.
                        // The default analysis resolution (~640×480) blurs them together; ask
                        // for 1280×720 or the nearest the camera supports.
                        val resolution = ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resolution)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor, QrAnalyzer(continuous = onFrame != null) { text ->
                            // CameraX must be touched on the main thread; doing unbind + the
                            // callback here (not on the analyzer's worker thread) is what makes
                            // the dialog actually close and hand the result back.
                            ContextCompat.getMainExecutor(ctx).execute {
                                val whole = onFrame?.let { f -> runCatching { f(text) }.getOrDefault(false) } ?: true
                                if (whole) {
                                    runCatching { provider.unbindAll() }
                                    onResult(if (onFrame != null) "" else text)
                                }
                            }
                        })
                        provider.unbindAll()
                        runCatching {
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                            )
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Text("close", color = Color.White)
            }
            Text(
                progress ?: "Point the camera at a QR",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(40.dp),
            )
        }
    }
}

/** Decodes one QR per frame. Uses ImageProxy.toBitmap() so the YUV→RGB conversion is the
 *  platform's (correct on every device), rotates by the reported sensor rotation so the QR is
 *  upright, and reads with ZXing over an RGB luminance source. */
private class QrAnalyzer(private val continuous: Boolean = false, private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    @Volatile private var lastText: String? = null
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
    )
    @Volatile private var done = false

    override fun analyze(image: ImageProxy) {
        if (done) { image.close(); return }
        try {
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
            val text = decode(upright) ?: decode(raw)
            if (text != null) {
                if (!continuous) { done = true; onFound(text) }
                else if (text != lastText) { lastText = text; onFound(text) }   // each new frame once
            }
        } catch (_: Exception) {
            // no QR in this frame — keep looking
        } finally {
            image.close()
        }
    }

    private fun decode(bmp: android.graphics.Bitmap): String? {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val candidates = listOf(
            HybridBinarizer(source),
            com.google.zxing.common.GlobalHistogramBinarizer(source),
            HybridBinarizer(source.invert()),
        )
        for (binarizer in candidates) {
            try {
                return MultiFormatReader().apply { setHints(hints) }
                    .decodeWithState(BinaryBitmap(binarizer)).text
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }
}
