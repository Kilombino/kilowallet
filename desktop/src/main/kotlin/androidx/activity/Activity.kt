package androidx.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf

/**
 * An activity on the desktop: [androidx.activity.compose.setContent] keeps its screen, which
 * the window shows. Its lifecycle is the window's (created, resumed while shown).
 */
open class ComponentActivity : android.app.Activity(), androidx.lifecycle.LifecycleOwner {
    internal val content = mutableStateOf<(@Composable () -> Unit)?>(null)
    private val registry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

    open fun onCreate(savedInstanceState: android.os.Bundle?) { registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
    open fun onStart() { registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
    open fun onResume() { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
    open fun onPause() { registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
    open fun onStop() { registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
    open fun onDestroy() {}
    open fun onNewIntent(intent: android.content.Intent) {}
    open fun onWindowFocusChanged(hasFocus: Boolean) {}

    fun <I, O> registerForActivityResult(contract: androidx.activity.result.contract.ActivityResultContract<I, O>, callback: (O) -> Unit) =
        object : androidx.activity.result.ActivityResultLauncher<I>() {
            override fun launch(input: I) = contract.runOnDesktop(input, callback)
        }

    /** The screen set by setContent, for the window. */
    @Composable fun Screen() { content.value?.invoke() }
}

fun ComponentActivity.enableEdgeToEdge() {}
