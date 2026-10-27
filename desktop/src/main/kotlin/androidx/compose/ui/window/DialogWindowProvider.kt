package androidx.compose.ui.window

/** A dialog's own window (Android). Desktop dialogs have none the shared code could brighten. */
interface DialogWindowProvider { val window: android.view.Window }
