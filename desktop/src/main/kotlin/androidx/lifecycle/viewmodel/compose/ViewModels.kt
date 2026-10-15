package androidx.lifecycle.viewmodel.compose

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel

/**
 * viewModel() as the shared screens call it: one instance per class for the whole app (there
 * is one window), built with the Application when its constructor takes one.
 */
@Composable
inline fun <reified VM : ViewModel> viewModel(): VM = DesktopViewModels.get(VM::class.java)

object DesktopViewModels {
    private val made = HashMap<Class<*>, ViewModel>()
    @Suppress("UNCHECKED_CAST")
    fun <VM : ViewModel> get(cls: Class<VM>): VM = synchronized(made) {
        made.getOrPut(cls) {
            val withApp = cls.constructors.firstOrNull { it.parameterTypes.size == 1 && Application::class.java.isAssignableFrom(it.parameterTypes[0]) }
            (withApp?.newInstance(com.kilombino.kilowallet.desktop.DesktopApp) ?: cls.getDeclaredConstructor().newInstance()) as ViewModel
        } as VM
    }
}
