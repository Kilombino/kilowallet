package androidx.lifecycle

import android.app.Application

/** A ViewModel holding the application, as Android's. */
open class AndroidViewModel(private val app: Application) : ViewModel() {
    @Suppress("UNCHECKED_CAST")
    open fun <T : Application> getApplication(): T = app as T
}
