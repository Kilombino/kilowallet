package android.webkit

/**
 * Marks the ROBLO bridge's methods the page may call (Android's WebView reads it). On the desktop
 * nothing reads it: the JCEF host (roblo/RobloWeb) calls those methods itself, from a closed list.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class JavascriptInterface
