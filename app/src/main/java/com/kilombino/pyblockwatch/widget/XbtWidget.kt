package com.kilombino.pyblockwatch.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.kilombino.pyblockwatch.R
import com.kilombino.pyblockwatch.data.MarketData
import com.kilombino.pyblockwatch.data.MarketFeed
import com.kilombino.pyblockwatch.data.Store
import com.kilombino.pyblockwatch.ui.MainActivity
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The XBT price widget (formerly the separate "XBT Widget" app), now part of the wallet.
 *
 * No WorkManager on purpose: the wallet keeps its dependency list minimal for the
 * reproducible build. Refreshes come from an alarm every [EVERY_MS] (shown as a countdown
 * next to the refresh button), Android's own 30-minute update, the wallet's background
 * watcher and the refresh button — all through [MarketFeed], which decides whether the
 * server may be asked.
 */
class XbtWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        scheduleNext(ctx)
        renderAll(ctx)
        refreshAsync(ctx, force = false)
    }

    override fun onDisabled(ctx: Context) {
        alarm(ctx)?.cancel(tickIntent(ctx))
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        render(ctx, mgr, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) refreshAsync(ctx, force = true)
        if (intent.action == ACTION_TICK) { scheduleNext(ctx); refreshAsync(ctx, force = false) }
    }

    private fun refreshAsync(ctx: Context, force: Boolean) {
        val app = ctx.applicationContext
        // Button press: the arrow starts spinning at once, so the tap visibly registered.
        if (force) { spinning = true; renderAll(app) }
        val pending = goAsync()
        Thread {
            val t0 = System.currentTimeMillis()
            try {
                MarketFeed.refresh(app, force)
                // Keep it spinning for a moment even when the answer is instant (or the
                // one-minute limit says "not yet"), otherwise it only flickers.
                if (force) Thread.sleep(maxOf(0L, MIN_SPIN_MS - (System.currentTimeMillis() - t0)))
            } finally {
                if (force) spinning = false
                renderAll(app)
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "com.kilombino.pyblockwatch.widget.REFRESH"
        private const val ACTION_TICK = "com.kilombino.pyblockwatch.widget.TICK"
        private const val EVERY_MS = 5 * 60_000L

        /** When the next automatic refresh is due (elapsed-realtime clock), for the countdown. */
        @Volatile private var nextTickElapsed = 0L

        private fun alarm(ctx: Context) = ctx.getSystemService(android.app.AlarmManager::class.java)

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 7, Intent(ctx, XbtWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** The next automatic refresh in [EVERY_MS]; allowed while the phone dozes (Android may delay it). */
        fun scheduleNext(ctx: Context) {
            if (!hasWidgets(ctx)) return
            val at = android.os.SystemClock.elapsedRealtime() + EVERY_MS
            nextTickElapsed = at
            alarm(ctx)?.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME, at, tickIntent(ctx))
        }

        private const val MIN_SPIN_MS = 1500L

        /** True while a refresh asked for with the button is running. */
        @Volatile private var spinning = false

        private const val FULL_MIN_WIDTH_DP = 180
        private const val FULL_MIN_HEIGHT_DP = 100
        private const val LARGE_MIN_HEIGHT_DP = 250

        private enum class Size { SMALL, FULL, LARGE }

        fun hasWidgets(ctx: Context): Boolean =
            AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(ComponentName(ctx, XbtWidget::class.java)).isNotEmpty()

        fun renderAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, XbtWidget::class.java))) render(ctx, mgr, id)
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val m = MarketFeed.cached(ctx)
            val fiat = Store(ctx).fiat
            val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: the launcher picks the layout from the widget's real size.
                RemoteViews(mapOf(
                    SizeF(110f, 40f) to build(ctx, m, fiat, Size.SMALL),
                    SizeF(FULL_MIN_WIDTH_DP.toFloat(), FULL_MIN_HEIGHT_DP.toFloat()) to build(ctx, m, fiat, Size.FULL),
                    SizeF(FULL_MIN_WIDTH_DP.toFloat(), LARGE_MIN_HEIGHT_DP.toFloat()) to build(ctx, m, fiat, Size.LARGE),
                ))
            } else {
                val o = mgr.getAppWidgetOptions(id)
                val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                val size = when {
                    w < FULL_MIN_WIDTH_DP || h < FULL_MIN_HEIGHT_DP -> Size.SMALL
                    h >= LARGE_MIN_HEIGHT_DP -> Size.LARGE
                    else -> Size.FULL
                }
                build(ctx, m, fiat, size)
            }
            mgr.updateAppWidget(id, views)
        }

        private fun build(ctx: Context, m: MarketData?, fiat: String, size: Size): RemoteViews {
            val v = RemoteViews(ctx.packageName, when (size) {
                Size.SMALL -> R.layout.widget_small
                Size.FULL -> R.layout.widget_full
                Size.LARGE -> R.layout.widget_large
            })
            // Tapping the widget opens the wallet; the round arrow asks for fresh data.
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            if (size == Size.LARGE) v.setOnClickPendingIntent(R.id.convert, PendingIntent.getActivity(
                ctx, 2, Intent(ctx, com.kilombino.pyblockwatch.ui.ConverterActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            if (nextTickElapsed > android.os.SystemClock.elapsedRealtime()) {
                v.setChronometer(R.id.countdown, nextTickElapsed, null, true)
                v.setChronometerCountDown(R.id.countdown, true)
                v.setViewVisibility(R.id.countdown, View.VISIBLE)
            } else v.setViewVisibility(R.id.countdown, View.GONE)
            v.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, XbtWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            v.setViewVisibility(R.id.refresh_icon, if (spinning) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.refresh_spin, if (spinning) View.VISIBLE else View.GONE)

            val sym = if (fiat == "EUR") "€" else "$"
            val px = if (fiat == "EUR") m?.xbtEur else m?.xbtUsd
            // High/low come in USD; convert with the same rate as the price.
            val rate = if (fiat == "EUR" && m?.xbtUsd != null && m.xbtEur != null && m.xbtUsd > 0) m.xbtEur / m.xbtUsd else 1.0
            v.setTextViewText(R.id.price, money(sym, px))
            val ch = m?.changePct
            v.setTextViewText(R.id.change, if (ch == null) (if (m == null) "Loading…" else "")
                else changeText(ch) + " 24h")
            v.setTextColor(R.id.change, ctx.getColor(changeColor(ch)))
            if (size == Size.SMALL) return v

            v.setTextViewText(R.id.poolsats, m?.xbtPoolsats?.let { String.format(Locale.US, "%,d Poolsats", it) } ?: "–")
            val pch = m?.poolsatsChangePct
            v.setTextViewText(R.id.poolsats_change, if (pch == null) "" else changeText(pch) + " 24h")
            v.setTextColor(R.id.poolsats_change, ctx.getColor(changeColor(pch)))
            v.setTextViewText(R.id.hashrate, m?.networkHashps?.let { hashrate(it) } ?: "–")
            difficultyRows(ctx, v, m, compact = size == Size.FULL)
            v.setTextViewText(R.id.range,
                if (m?.low24Usd == null || m.high24Usd == null) "–"
                else "${money(sym, m.low24Usd * rate)} – ${money(sym, m.high24Usd * rate)}")
            v.setTextViewText(R.id.block, m?.height?.let { String.format(Locale.US, "#%,d", it) } ?: "–")
            v.setTextViewText(R.id.updated, if (m == null) MarketFeed.SOURCE else {
                val t = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.US).format(Date(m.fetchedMs))
                MarketFeed.SOURCE + " · " + (if (MarketFeed.isStale(m)) "⚠ last data $t" else "updated $t")
            })
            if (size == Size.LARGE) {
                v.setTextViewText(R.id.earns, m?.thsXbtDay?.let {
                    String.format(Locale.US, "%.4f BTC/d", it) + (m.thsUsdDay?.let { u -> " · " + money(sym, u * rate) } ?: "")
                } ?: "–")
                v.setTextViewText(R.id.rent, m?.rentPoolsatsPerThDay?.let {
                    String.format(Locale.US, "%,d Poolsats/d", it.roundToLong()) +
                        (m.rentUsdPerThDay?.let { u -> " · " + money(sym, u * rate) } ?: "")
                } ?: "–")
                v.setTextViewText(R.id.kwh, m?.kwhPerXbt?.let { String.format(Locale.US, "%,d kWh", it.roundToLong()) } ?: "–")
                v.setTextViewText(R.id.ysh, m?.yshValue?.let { String.format(Locale.US, "%.2f %s", it, m.yshUnit ?: "") } ?: "–")
                v.setTextViewText(R.id.chain, m?.chainSizeGB?.let { String.format(Locale.US, "%,.2f GB", it) } ?: "–")
            }
            return v
        }

        /**
         * Large widget: "Difficulty   5.09 G ▲10.0%  prev 4.63 G" and "Next in 66 blocks   ~5.93 G ▲16.6%".
         * Medium widget ([compact], no room for two rows): "Difficulty   5.09 G ▲10.0% → ~5.93 G ▲16.6%",
         * the last retarget's change standing for the previous period.
         * Changes are colored like the price's; nothing shows with an older server.
         */
        private fun difficultyRows(ctx: Context, v: RemoteViews, m: MarketData?, compact: Boolean) {
            val d = m?.difficulty
            v.setViewVisibility(R.id.diff_row, if (d == null) View.GONE else View.VISIBLE)
            if (d == null) {
                if (!compact) v.setViewVisibility(R.id.diff_next_row, View.GONE)
                return
            }
            val muted = ctx.getColor(R.color.widget_muted)
            val t = SpannableStringBuilder(WidgetFormat.difficultyNow(d))
            m.difficultyPrevChangePct?.let { c ->
                t.append(" ")
                colored(t, WidgetFormat.pct(c), ctx.getColor(changeColor(c)))
            }
            val next = m.difficultyNext
            if (compact) {
                if (next != null) {
                    colored(t, " → ", muted)
                    t.append(WidgetFormat.difficultyNext(next))
                    m.difficultyChangePct?.let { c ->
                        t.append(" ")
                        colored(t, WidgetFormat.pct(c), ctx.getColor(changeColor(c)))
                    }
                }
                v.setTextViewText(R.id.difficulty, t)
                return
            }
            m.difficultyPrev?.let { p ->
                t.append("  ")
                colored(t, WidgetFormat.difficultyPrev(p), muted)
            }
            v.setTextViewText(R.id.difficulty, t)
            v.setViewVisibility(R.id.diff_next_row, if (next == null) View.GONE else View.VISIBLE)
            if (next != null) {
                v.setTextViewText(R.id.diff_next_label, WidgetFormat.nextLabel(m.difficultyRemainingBlocks))
                val n = SpannableStringBuilder(WidgetFormat.difficultyNext(next))
                m.difficultyChangePct?.let { c ->
                    n.append(" ")
                    colored(n, WidgetFormat.pct(c), ctx.getColor(changeColor(c)))
                }
                v.setTextViewText(R.id.diff_next, n)
            }
        }

        private fun colored(b: SpannableStringBuilder, text: String, color: Int) {
            val start = b.length
            b.append(text)
            b.setSpan(ForegroundColorSpan(color), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun changeText(pct: Double): String =
            (if (pct >= 0) "▲ " else "▼ ") + String.format(Locale.US, "%.2f%%", abs(pct))

        private fun changeColor(pct: Double?): Int = when {
            pct == null -> R.color.widget_muted
            pct >= 0 -> R.color.widget_up
            else -> R.color.widget_down
        }

        private fun hashrate(hps: Double): String {
            val units = listOf(1e18 to "EH/s", 1e15 to "PH/s", 1e12 to "TH/s", 1e9 to "GH/s")
            val (f, u) = units.firstOrNull { hps >= it.first } ?: (1.0 to "H/s")
            return String.format(Locale.US, "%.2f %s", hps / f, u)
        }

        private fun money(sym: String, v: Double?): String =
            if (v == null) "–" else sym + String.format(Locale.US, "%,.2f", v)
    }
}
