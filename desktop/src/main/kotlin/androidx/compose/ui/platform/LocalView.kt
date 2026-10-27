package androidx.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** The view a screen is drawn in. On the desktop it sits in no dialog window: shared code asking for one gets none. */
val LocalView = staticCompositionLocalOf { android.view.View() }
