package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Keeps the activity's screen; the desktop window shows it. */
fun ComponentActivity.setContent(parent: CompositionContext? = null, content: @Composable () -> Unit) {
    this.content.value = content
}

/** The pickers and permission asks the shared code launches, done the desktop way (see the contracts). */
@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ActivityResultLauncher<I> {
    val current = rememberUpdatedState(onResult)
    return remember(contract) {
        object : ActivityResultLauncher<I>() {
            override fun launch(input: I) = contract.runOnDesktop(input) { current.value(it) }
        }
    }
}

/**
 * The phone's back gesture, done with the Escape key: the shared screens register what back does
 * (closing the settings shown in place of the screen); the latest one still enabled answers.
 * The window passes Escape here (see the desktop's Main).
 */
object DesktopBack {
    private val handlers = mutableListOf<Pair<androidx.compose.runtime.State<Boolean>, androidx.compose.runtime.State<() -> Unit>>>()

    /** True when a screen took the back press. */
    fun press(): Boolean {
        val h = synchronized(handlers) { handlers.lastOrNull { it.first.value } } ?: return false
        h.second.value()
        return true
    }

    internal fun add(h: Pair<androidx.compose.runtime.State<Boolean>, androidx.compose.runtime.State<() -> Unit>>) = synchronized(handlers) { handlers.add(h) }
    internal fun remove(h: Pair<androidx.compose.runtime.State<Boolean>, androidx.compose.runtime.State<() -> Unit>>) = synchronized(handlers) { handlers.remove(h) }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val on = rememberUpdatedState(enabled)
    val action = rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(Unit) {
        val h = on to action
        DesktopBack.add(h)
        onDispose { DesktopBack.remove(h) }
    }
}
