package com.kilombino.pyblockwatch

/**
 * The Android resources the shared code names, on the desktop: the images it draws come from
 * the app's own res/ folder (copied into the jar by the build), the rest are only numbers.
 */
object R {
    object drawable {
        const val nodex_logo = 1; const val widget_preview = 2; const val xbt_coin = 3
        const val cj_dot_done = 10; const val cj_dot_empty = 11; const val cj_dot_now = 12
    }
    object mipmap { const val ic_launcher_full = 20; const val ic_launcher = 21 }
    object color { const val widget_down = 30; const val widget_muted = 31; const val widget_up = 32 }
    object layout { const val cj_round_big = 40; const val cj_round_small = 41; const val widget_full = 42; const val widget_large = 43; const val widget_small = 44 }
    object id {
        const val block = 100; const val chain = 101; const val change = 102; const val cj_bar1 = 103; const val cj_bar2 = 104
        const val cj_bar3 = 105; const val cj_bar4 = 106; const val cj_dot1 = 107; const val cj_dot2 = 108; const val cj_dot3 = 109
        const val cj_dot4 = 110; const val cj_dot5 = 111; const val cj_status = 112; const val cj_timer = 113; const val cj_timer_box = 114
        const val cj_title = 115; const val cj_where = 116; const val convert = 117; const val countdown = 118; const val earns = 119
        const val hashrate = 120; const val kwh = 121; const val poolsats = 122; const val poolsats_change = 123; const val price = 124
        const val range = 125; const val refresh = 126; const val refresh_icon = 127; const val refresh_spin = 128; const val rent = 129
        const val root = 130; const val updated = 131; const val ysh = 132
    }

    /** Where each drawable is in the jar. */
    internal val paths = mapOf(
        drawable.nodex_logo to "res/drawable-nodpi/nodex_logo.png",
        drawable.widget_preview to "res/drawable-nodpi/widget_preview.jpg",
        drawable.xbt_coin to "res/drawable-nodpi/xbt_coin.png",
        mipmap.ic_launcher_full to "res/mipmap-xxxhdpi/ic_launcher_full.png",
        mipmap.ic_launcher to "res/mipmap-xxxhdpi/ic_launcher.png",
    )
}
