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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
    val a by t.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = a)),
    )
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

/** Fiat per whole coin: BTC from the market feed, Spamcoin through its XBT/Poolsats rate. */
fun fiatPerCoin(chain: com.kilombino.pyblockwatch.chain.Chain, market: com.kilombino.pyblockwatch.data.MarketData?, fiat: String): Double? {
    val xbtPx = if (fiat == "EUR") market?.xbtEur else market?.xbtUsd
    return if (chain == com.kilombino.pyblockwatch.chain.Chain.BLAKE2B) xbtPx
    else market?.xbtPoolsats?.takeIf { it > 0 }?.let { ps -> xbtPx?.let { it * 1e8 / ps } }
}

