package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** The activity showing the screen (the main window's), as on Android. */
val LocalContext = staticCompositionLocalOf<Context> { android.content.DesktopContext }
