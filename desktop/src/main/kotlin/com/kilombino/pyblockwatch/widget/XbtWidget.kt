package com.kilombino.pyblockwatch.widget

/** The phone's home-screen widget: none on the desktop. */
class XbtWidget {
    companion object {
        fun hasWidgets(ctx: android.content.Context): Boolean = false
        fun renderAll(ctx: android.content.Context) {}
    }
}
