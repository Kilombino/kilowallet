package com.kilombino.pyblockwatch.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.kilombino.pyblockwatch.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kilombino.pyblockwatch.data.MarketData
import com.kilombino.pyblockwatch.data.MarketFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small converter that opens over the home screen from the widget: type in any box —
 * XBT, sats, Poolcoins (the SHA-256 Spamchain), EUR or USD — and the others follow, at
 * the price the widget shows (mempool.kilombino.com). A widget cannot hold a text field,
 * so the widget's Convert button opens this dialog instead.
 */
class ConverterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PyBlockWatchTheme { Converter(onClose = { finish() }) } }
    }
}

/** A box of the converter: XBT, sats, Poolcoins, or a fiat currency ([fiat] its code). */
private data class Denom(val label: String, val decimals: Int, val fiat: String? = null) {
    companion object {
        val XBT = Denom("BTC", 8); val SATS = Denom("sats", 0); val POOLCOINS = Denom("Poolcoins", 8)
        /** EUR and USD always, and the currency chosen in the wallet when it is another one. */
        fun all(chosen: String): List<Denom> = listOf(XBT, SATS, POOLCOINS) +
            (listOf("EUR", "USD") + listOfNotNull(chosen.takeIf { it != "EUR" && it != "USD" }))
                .map { Denom(it, com.kilombino.pyblockwatch.data.Fiat.of(it).decimals, it) }
    }
}

@Composable
private fun Converter(onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var market by remember { mutableStateOf(MarketFeed.cached(ctx)) }
    val chosen = remember { com.kilombino.pyblockwatch.data.Store(ctx).fiat }
    LaunchedEffect(true) {
        withContext(Dispatchers.IO) { runCatching { MarketFeed.refresh(ctx) }.getOrNull() }?.let { market = it }
    }
    // Everything is kept as an amount of XBT; the box being typed in keeps its own text.
    var xbt by remember { mutableStateOf<BigDecimal?>(BigDecimal.ONE) }
    var editing by remember { mutableStateOf<Denom?>(null) }
    var editText by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PanelBg).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BTC converter", style = MaterialTheme.typography.titleMedium, color = Purple,
                 modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("✕", color = TextSoft, style = MaterialTheme.typography.titleLarge) }
        }
        val m = market
        if (m == null || m.xbtUsd == null) {
            Text("No price yet. Check the connection and open the converter again.",
                 style = MaterialTheme.typography.bodySmall, color = Warn)
        }
        for (u in Denom.all(chosen)) {
            val shown = if (editing == u) editText else xbt?.let { format(fromXbt(it, u, m), u) } ?: ""
            OutlinedTextField(
                value = shown,
                onValueChange = { t ->
                    editing = u
                    editText = t.filter { it.isDigit() || it == '.' || it == ',' }
                    xbt = parse(editText)?.let { toXbt(it, u, m) }
                },
                label = { Text(u.label) },
                singleLine = true,
                enabled = m != null || u == Denom.XBT || u == Denom.SATS,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        m?.let {
            Spacer(Modifier.height(2.dp))
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.fetchedMs))
            Text("1 BTC = $" + String.format(Locale.US, "%,.2f", it.xbtUsd ?: 0.0) +
                " · €" + String.format(Locale.US, "%,.2f", it.xbtEur ?: 0.0) +
                (if (chosen != "USD" && chosen != "EUR") it.xbtIn(chosen)?.let { p -> " · " + com.kilombino.pyblockwatch.data.Fiat.format(p, chosen) } ?: "" else "") +
                (it.xbtPoolsats?.let { p -> " · " + String.format(Locale.US, "%,d", p) + " Poolsats" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = TextFaint)
            Text("Prices from ${MarketFeed.SOURCE}, updated $time. Poolcoins are the units of the " +
                "SHA-256 Spamchain.", style = MaterialTheme.typography.bodySmall, color = TextFaint)
        }
        Spacer(Modifier.width(1.dp))
    }
}

private fun parse(t: String): BigDecimal? =
    t.replace(',', '.').takeIf { it.isNotBlank() && it != "." }?.let { runCatching { BigDecimal(it) }.getOrNull() }

/** How many of [u] one XBT is worth; null without a price. */
private fun perXbt(u: Denom, m: MarketData?): BigDecimal? = when (u) {
    Denom.XBT -> BigDecimal.ONE
    Denom.SATS -> BigDecimal(100_000_000)
    Denom.POOLCOINS -> m?.xbtPoolsats?.let { BigDecimal(it).movePointLeft(8) }
    else -> u.fiat?.let { m?.xbtIn(it) }?.let { BigDecimal(it) }
}

private fun toXbt(v: BigDecimal, u: Denom, m: MarketData?): BigDecimal? =
    perXbt(u, m)?.takeIf { it.signum() > 0 }?.let { v.divide(it, 12, RoundingMode.HALF_UP) }

private fun fromXbt(xbt: BigDecimal, u: Denom, m: MarketData?): BigDecimal? = perXbt(u, m)?.let { xbt.multiply(it) }

private fun format(v: BigDecimal?, u: Denom): String =
    v?.setScale(u.decimals, RoundingMode.HALF_UP)?.stripTrailingZeros()
        ?.let { if (it.scale() < 0) it.setScale(0) else it }?.toPlainString() ?: "–"
