package com.kilombino.pyblockwatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import com.kilombino.pyblockwatch.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A bordered panel, the app's one container shape. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color = Purple,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelBg)
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, color: Color = Purple) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

/** A slowly breathing dot — "this is live", without a spinner's false urgency. */
@Composable
fun PulseDot(color: Color, size: Int = 8) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a = t.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    // The alpha is read while drawing, not while composing: each frame of the pulse only redraws
    // the dot, instead of composing it again (it is on screen the whole time the wallet is open).
    Box(Modifier.size(size.dp).drawBehind { drawCircle(color.copy(alpha = a.value)) })
}

/**
 * The same dot, still: "all is well" with nothing going on (a finished scan, a coin waiting for
 * its block). An idle screen must not animate: a dot breathing forever keeps the GPU drawing a
 * frame every 16 ms for as long as the wallet is open.
 */
@Composable
fun StillDot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).drawBehind { drawCircle(color) })
}

/**
 * Something being prepared: the breathing dot, what is being done and, under it, what it waits
 * for now ([step], when known). Shown the moment a button is pressed, so the wait happens here
 * and not on the button.
 */
@Composable
fun WorkingLine(accent: Color, title: String, step: String? = null, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseDot(accent); androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Column {
            Text(title, color = accent, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, color = TextSoft, style = MaterialTheme.typography.bodySmall) }
            step?.let { Text(it, color = TextFaint, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * The gap-limit ring.
 *
 * This is the one piece of wallet machinery most people never see, so it gets to be
 * the visual centrepiece of a scan: the ring fills as consecutive empty addresses
 * accumulate, and the scan stops when it closes. Watching it fill and reset explains
 * the gap limit better than a paragraph could.
 */
@Composable
fun GapRing(used: Int, limit: Int, color: Color, modifier: Modifier = Modifier) {
    val frac = (used.toFloat() / limit.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.size(46.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(46.dp)
                .drawBehind {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    drawArc(
                        color = color.copy(alpha = 0.18f), startAngle = 0f, sweepAngle = 360f,
                        useCenter = false, topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke),
                    )
                    drawArc(
                        color = color, startAngle = -90f, sweepAngle = 360f * frac,
                        useCenter = false, topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke),
                    )
                },
        )
        Text("$used", style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** A derivation path sliding in — the scan's heartbeat made visible. */
@Composable
fun DerivationTicker(path: String, color: Color) {
    AnimatedVisibility(
        visible = path.isNotEmpty(),
        enter = fadeIn(tween(150)) + slideInHorizontally(tween(150)) { it / 3 },
        exit = fadeOut(tween(100)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseDot(color, 6)
            Text(path, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

/** An explanatory aside. The app is meant to teach, so these are first-class. */
@Composable
fun Explain(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = TextFaint,
        textAlign = TextAlign.Start,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(120)),
    ) { content() }
}

/** Format satoshis the way a person reads them, with the whole-coin part emphasised. */
fun formatSats(sats: Long): Pair<String, String> {
    val coins = sats / 100_000_000L
    val rest = (sats % 100_000_000L).toString().padStart(8, '0')
    return "$coins" to rest
}

/**
 * Group a sats amount with thin spaces (12 345 678) so the primary figure reads as an
 * exact integer count of sats, never rounded to whole bitcoin. A narrow no-break space
 * keeps the digits from wrapping mid-number.
 */
fun groupSats(sats: Long): String {
    val digits = kotlin.math.abs(sats).toString()
    val sb = StringBuilder()
    for ((i, c) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append('\u202F')
        sb.append(c)
    }
    return (if (sats < 0) "-" else "") + sb
}

fun shortAddress(a: String): String =
    if (a.length <= 20) a else "${a.take(10)}…${a.takeLast(8)}"

/** The app's own icon, round, for the top of the screen. */
@Composable
fun AppLogo(size: Int = 40) {
    // Tapping the logo shows who makes the wallet.
    var about by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.kilombino.pyblockwatch.R.mipmap.ic_launcher_full),
        contentDescription = "Kilowallet",
        modifier = Modifier.size(size.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .clickable { about = true },
    )
    if (about) AboutDialog { about = false }
}

const val KILOMBINO_WEB = "https://kilombino.com"

@Composable
fun AboutDialog(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Kilowallet $version") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("A wallet for Bitcoin BLAKE2b, made by Kilombino.", style = MaterialTheme.typography.bodySmall)
                Text("Open source and reproducible: anyone can rebuild this app from its code and check it " +
                    "is byte for byte the one published. No account, no tracking.", style = MaterialTheme.typography.bodySmall)
                Text("kilombino.com", style = MaterialTheme.typography.bodyMedium, color = Purple,
                    modifier = Modifier.clickable { runCatching { uri.openUri(KILOMBINO_WEB) } })
                Text("github.com/Kilombino/kilowallet", style = MaterialTheme.typography.bodySmall, color = TextSoft,
                    modifier = Modifier.clickable { runCatching { uri.openUri("https://github.com/Kilombino/kilowallet") } })
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("CLOSE", color = TextSoft) } },
        containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
    )
}

/** The power symbol, drawn: the fonts on many phones have no ⏻. */
@Composable
private fun PowerGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
        val w = size.width; val stroke = w * 0.13f
        drawArc(color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2 + w * 0.06f), size = Size(w - stroke, w - stroke),
            style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawLine(color, Offset(w / 2, 0f), Offset(w / 2, w * 0.45f), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** The balance card's countdown: "↻ 4:59", or a hint to pull down when automatic refresh is off. */
fun refreshCountdown(nextRefreshAt: Long, seconds: Int): String =
    if (nextRefreshAt <= 0L) "↓ pull to refresh"
    else if (seconds >= 60) "↻ %d:%02d".format(seconds / 60, seconds % 60) else "↻ ${seconds}s"

/**
 * Next to the logo: the power button quits the app for real. The balance watcher and any
 * coinjoin round stop, the Ark engine goes with the process, and nothing runs until the app
 * is opened again (then the background checks come back as set in settings).
 */
@Composable
fun QuitButton(accent: Color) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity ?: return
    var ask by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable { ask = true },
        contentAlignment = Alignment.Center) { PowerGlyph(TextSoft) }
    if (ask) {
        val rounds = com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.hasActive(activity)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Close Kilowallet?") },
            text = {
                Text((if (rounds) "A coinjoin round is still running: it waits on this phone until you open the app " +
                    "again, and if it ends without you your coins stay where they are. " else "") +
                    "Everything stops until you open the app again: no balance checks, no alerts for incoming " +
                    "payments, no persistent notification and no battery used. If you use Ark, open the app at " +
                    "least once a month so your Ark coins are renewed.", style = MaterialTheme.typography.bodySmall)
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { ask = false; quitApp(activity) }) { Text("CLOSE", color = accent) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { ask = false }) { Text("CANCEL", color = TextSoft) } },
            containerColor = PanelBg, titleContentColor = TextMain, textContentColor = TextSoft,
        )
    }
}

/** Stop every service, leave the task list, and end the process. */
fun quitApp(activity: android.app.Activity) {
    runCatching { com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.stopAll() }
    activity.stopService(android.content.Intent(activity, com.kilombino.pyblockwatch.coinjoin.CoinjoinService::class.java))
    activity.stopService(android.content.Intent(activity, com.kilombino.pyblockwatch.data.WatchService::class.java))
    activity.finishAndRemoveTask()
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
}

/** Fiat per whole coin: BTC from the market feed, Spamcoin through its XBT/Poolsats rate. */
fun fiatPerCoin(chain: com.kilombino.pyblockwatch.chain.Chain, market: com.kilombino.pyblockwatch.data.MarketData?, fiat: String): Double? {
    val xbtPx = market?.xbtIn(fiat)
    return if (chain == com.kilombino.pyblockwatch.chain.Chain.BLAKE2B) xbtPx
    else market?.xbtPoolsats?.takeIf { it > 0 }?.let { ps -> xbtPx?.let { it * 1e8 / ps } }
}


private var secureHolders = 0

/**
 * While on screen, the window is FLAG_SECURE: no screenshots, no screen recording, and a blank
 * thumbnail in recent apps. Used wherever recovery words are shown or typed; the rest of the app
 * stays recordable.
 */
@Composable
fun SecureWhileShown() {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity ?: return
    androidx.compose.runtime.DisposableEffect(Unit) {
        if (secureHolders++ == 0) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (--secureHolders == 0) activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** How many money flows (send, speed-up, Ark send…) are on screen; prompts wait while any is. */
val openFlows = androidx.compose.runtime.mutableIntStateOf(0)

/** Marks a send-like flow as open while it is on screen, so the update prompt does not cover it. */
@Composable
fun FlowOpenWhileShown() {
    androidx.compose.runtime.DisposableEffect(Unit) {
        openFlows.intValue++
        onDispose { openFlows.intValue-- }
    }
}

/**
 * Switch colours that tell on from off at a glance: on, a white knob on the accent; off, a
 * grey knob on a dark track with a grey edge. (A knob in the accent on an accent track, as
 * before, hid whether it was on.)
 */
@Composable
fun switchColors(accent: Color) = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = Color.White, checkedTrackColor = accent, checkedBorderColor = accent,
    uncheckedThumbColor = TextFaint, uncheckedTrackColor = PanelSoft, uncheckedBorderColor = TextFaint,
)

/**
 * Copying with a word back: [copy] puts the text on the clipboard and [copied] stays true for
 * two seconds, so a COPY button (or a tapped address) can say "COPIED ✓".
 */
class Copier(val copied: Boolean, val copy: (String) -> Unit)

@Composable
fun rememberCopier(): Copier {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(copied) { if (copied > 0) { kotlinx.coroutines.delay(2_000); copied = 0 } }
    return Copier(copied > 0) { t -> clip.setText(androidx.compose.ui.text.AnnotatedString(t)); copied = System.currentTimeMillis() }
}
