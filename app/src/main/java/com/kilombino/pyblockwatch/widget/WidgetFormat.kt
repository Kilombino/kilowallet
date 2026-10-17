package com.kilombino.pyblockwatch.widget

import java.util.Locale
import kotlin.math.abs

/** Plain-text pieces of the widget, kept apart from RemoteViews so they can be unit tested. */
internal object WidgetFormat {

    private val SI = listOf(1e18 to "E", 1e15 to "P", 1e12 to "T", 1e9 to "G", 1e6 to "M", 1e3 to "k")

    private fun unit(d: Double): Pair<Double, String> = SI.firstOrNull { d >= it.first } ?: (1.0 to "")

    private fun number(d: Double, f: Double): String {
        val x = d / f
        return when {
            x >= 100 -> String.format(Locale.US, "%.0f", x)
            x >= 10 -> String.format(Locale.US, "%.1f", x)
            else -> String.format(Locale.US, "%.2f", x)
        }
    }

    /** A difficulty with a metric prefix: 5086468190 -> "5.09 G". */
    fun difficulty(d: Double): String {
        val (f, u) = unit(d)
        val num = number(d, f)
        return if (u.isEmpty()) num else "$num $u"
    }

    /** A change in percent with an arrow, one decimal: 16.592 -> "▲16.6%". */
    fun pct(p: Double): String =
        (if (p >= 0) "▲" else "▼") + String.format(Locale.US, "%.1f%%", abs(p))

    /** The "now" part of the difficulty row. */
    fun difficultyNow(d: Double): String = difficulty(d)

    /** The previous period's difficulty, shown after the current one. */
    fun difficultyPrev(d: Double): String = "prev " + difficulty(d)

    /** The estimate after the next retarget, without its change: "~5.93 G". */
    fun difficultyNext(d: Double): String = "~" + difficulty(d)

    /** Label of the estimate row: "Next in 66 blocks". */
    fun nextLabel(blocks: Long?): String = when {
        blocks == null || blocks <= 0 -> "Next (est.)"
        blocks == 1L -> "Next in 1 block"
        else -> String.format(Locale.US, "Next in %,d blocks", blocks)
    }
}
