package androidx.compose.ui.platform

import androidx.compose.runtime.Composable

/** What shared code reads of Android's Configuration: the window's size in dp. */
class Configuration(val screenWidthDp: Int, val screenHeightDp: Int)

/** As Android's LocalConfiguration: the size of the window the screen is in. */
object LocalConfiguration {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    val current: Configuration
        @Composable get() {
            val size = LocalWindowInfo.current.containerSize
            val d = LocalDensity.current.density
            return Configuration((size.width / d).toInt(), (size.height / d).toInt())
        }
}
