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
