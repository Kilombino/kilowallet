package com.kilombino.pyblockwatch.roblo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.kilombino.pyblockwatch.BuildConfig
import com.kilombino.pyblockwatch.chain.Tor

/**
 * The ROBLO tab's WebView, kept alive while the user moves between HOT, WATCH, JM and ROBLO (an
 * order page keeps its state and its connections), and destroyed after [IDLE_MS] off screen or when
 * Android is short of memory. It shows only the Roblosats client bundled in this APK (assets/roblo,
 * pinned in roblo-client/CLIENT), served from a local https origin; it can't load anything from
 * the network itself: every request goes through [RobloBridge] and Kilowallet's Tor.
 */
@SuppressLint("StaticFieldLeak") // the WebView holds a MutableContextWrapper on the application, the activity only while attached
object RobloWeb {
    const val ORIGIN_HOST = "appassets.androidplatform.net"
    const val BASE = "https://$ORIGIN_HOST/roblo/"
    const val TOR_OWNER = "roblo"
    const val IDLE_MS = 10 * 60_000L

    private var web: WebView? = null
    private var ctxWrapper: MutableContextWrapper? = null
    private var bridge: RobloBridge? = null
    private var activity: Activity? = null
    private val main = Handler(Looper.getMainLooper())
    private val idle = Runnable { destroy("off screen for ${IDLE_MS / 60_000} min") }
    private val logs = ArrayDeque<String>()

    /** The photo picker for the chat's image button: set by the screen showing the page. */
    @Volatile var pickImage: ((ValueCallback<Array<Uri>>) -> Unit)? = null

    /** The WebView, created (and the client loaded) the first time; attached to [act] now. */
    fun obtain(act: Activity): WebView {
        main.removeCallbacks(idle)
        activity = act
        web?.let { w -> ctxWrapper?.baseContext = act; (w.parent as? ViewGroup)?.removeView(w); return w }
        val app = act.applicationContext
        val store = RobloStore.of(app)
        // Debug builds against the regtest lab only (adb: run-as … touch files/roblo-lab-regtest).
        RobloWallet.labRegtest = BuildConfig.DEBUG && java.io.File(app.filesDir, "roblo-lab-regtest").exists()
        runCatching { RobloLn.resume(app) }
        if (!store.clearnet()) Tor.acquire(app, TOR_OWNER)
        val wrapper = MutableContextWrapper(act).also { ctxWrapper = it }
        val w = WebView(wrapper)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        configure(w)
        val net = RobloNet(
            torSocks = {
                Tor.acquire(app, TOR_OWNER) // started again if it stopped
                (Tor.proxy()?.address() as? java.net.InetSocketAddress)
            },
            clearnet = { store.clearnet() },
            allowLocal = BuildConfig.DEBUG,
        )
        val b = RobloBridge(HostImpl(app), store, net)
        bridge = b
        w.addJavascriptInterface(b, "AndroidAppRobosats")
        w.loadUrl(BASE + "index.html")
        web = w
        watchNetwork(app)
        log("client loaded")
        return w
    }

    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** A lost network ends the page's WebSockets at once (see [RobloBridge.dropConnections]). */
    private fun watchNetwork(app: Context) {
        if (netCallback != null) return
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                // only when no other network took over (wifi → mobile data keeps going)
                if (cm.activeNetwork == null || cm.activeNetwork == network) { log("network lost"); bridge?.dropConnections() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    /** The tab is showing the page (set by the screen). */
    @Volatile private var shown = false

    /** The page left the screen (another tab): ended after [IDLE_MS] unless it comes back. */
    fun offScreen() { shown = false; pause() }

    /** The tab shows the page again. */
    fun onScreen() { shown = true; main.removeCallbacks(idle); web?.onResume() }

    /** The app went to the background (the tab may still be the one open). */
    fun appHidden() = pause()

    /** The app is visible again: the page resumes if its tab is the one open. */
    fun appShown() { if (shown) onScreen() }

    private fun pause() {
        if (web == null) return
        main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS)
        web?.onPause()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The activity is going away (not the page: it is kept for the next one). */
    fun detach(act: Activity) {
        if (activity === act) { activity = null; ctxWrapper?.baseContext = act.applicationContext }
    }

    /** Android is short of memory and the page is not on screen. */
    fun onTrimMemory(level: Int, visible: Boolean) {
        if (!visible && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) destroy("low memory")
    }

    val alive: Boolean get() = web != null

    /** Ends the page: its connections close, Tor is released, cache and cookies are wiped. */
    fun destroy(why: String) {
        val w = web ?: return
        log("closed: $why")
        main.removeCallbacks(idle)
        bridge?.close(); bridge = null
        (w.parent as? ViewGroup)?.removeView(w)
        runCatching { w.clearCache(true); CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
        w.destroy()
        web = null
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ctxWrapper?.baseContext?.applicationContext?.let { Tor.release(it, TOR_OWNER) }
        ctxWrapper = null
    }

    /**
     * A notification asked to open an order ([RobloLinks.pending]) while the page is up: the page
     * takes it now (a page still loading takes it itself once its garage is read).
     */
    fun navigateNow() = main.post {
        web?.evaluateJavascript("window.AndroidRobosats && window.AndroidRobosats.onNavigationIntent && window.AndroidRobosats.onNavigationIntent()", null)
    }

    /** Reload from the start (after Tor/clearnet changes in the client's settings). */
    fun reload() = main.post {
        // In place, in the same WebView: the client asks for it after its Tor/clearnet switch. A new
        // WebView put under the screen's AndroidView was never laid out (beta2: a black tab until
        // the app was closed). The bridge reads the setting on every request, so its connections
        // are closed, Tor is held or let go for the new choice, and the page loads again.
        val w = web ?: return@post
        val app = w.context.applicationContext
        bridge?.dropConnections()
        if (RobloStore.of(app).clearnet()) Tor.release(app, TOR_OWNER) else Tor.acquire(app, TOR_OWNER)
        log("reload (" + (if (RobloStore.of(app).clearnet()) "clearnet" else "Tor") + ")")
        w.loadUrl(BASE + "index.html")
    }

    @Synchronized private fun log(m: String) {
        Log.i(RobloBridge.TAG, m)
        logs.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $m")
        while (logs.size > 600) logs.removeFirst()
    }

    @Synchronized private fun logText() = logs.joinToString("\n")

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = false
        @Suppress("DEPRECATION") s.saveFormData = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.setGeolocationEnabled(false)
        // Only the bundle served below: no file:// and no content:// pages.
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = false // the order's sounds
        s.setSupportZoom(true); s.builtInZoomControls = true; s.displayZoomControls = false
        s.loadWithOverviewMode = true; s.useWideViewPort = true; s.textZoom = 100
        // The client recognises the Android bridge by "AndroidRobosats", and Kilowallet by its marker.
        s.userAgentString = "AndroidRobosats Kilowallet/${BuildConfig.VERSION_NAME}"
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        w.setBackgroundColor(0xFF0B0B10.toInt())
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                RobloAssets.serve(view.context.assets, request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == ORIGIN_HOST) return false
                openOutside(u)
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                log("renderer gone (crash=${detail.didCrash()})")
                main.post { destroy("renderer gone") }
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
                val pick = pickImage ?: run { filePathCallback.onReceiveValue(null); return true }
                pick(filePathCallback)
                return true
            }
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING || BuildConfig.DEBUG)
                    log("console ${m.messageLevel().name.lowercase()}: ${m.message().take(500)}")
                return true
            }
        }
    }

    /** A link out of the client (a explorer, the docs): the browser, after asking. */
    private fun openOutside(u: Uri) {
        val act = activity ?: return
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && scheme != "http" && scheme != "bitcoin" && scheme != "lightning") return
        android.app.AlertDialog.Builder(act)
            .setTitle(com.kilombino.pyblockwatch.ui.i18n.I18n.tr("Open outside Kilowallet?"))
            .setMessage(com.kilombino.pyblockwatch.ui.i18n.I18n.tr("This link leaves Roblosats and opens in another app (your browser does not go through Kilowallet's Tor):") + "\n\n" + u.toString().take(300))
            .setPositiveButton(com.kilombino.pyblockwatch.ui.i18n.I18n.tr("OPEN")) { _, _ ->
                runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, u)) }
            }
            .setNegativeButton(com.kilombino.pyblockwatch.ui.i18n.I18n.tr("CANCEL"), null)
            .show()
    }

    private class HostImpl(private val app: Context) : RobloBridge.Host {
        override fun evaluate(js: String) {
            val clean = js.replace("\u0000", "")
            main.post { runCatching { web?.evaluateJavascript(clean, null) } }
        }
        override fun copyToClipboard(text: String) = main.post {
            runCatching {
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Roblosats", text))
                if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(app, com.kilombino.pyblockwatch.ui.i18n.I18n.tr("Copied"), Toast.LENGTH_SHORT).show()
            }
        }.let { }
        override fun share(text: String) = main.post {
            runCatching {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                (activity ?: app).startActivity(Intent.createChooser(send, "Roblosats").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }.let { }
        override fun keepScreenOn(on: Boolean) = main.post {
            activity?.window?.let { if (on) it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }.let { }
        override fun reload() { RobloWeb.reload() }
        override fun torStatus(): String = when {
            Tor.running -> "ON"
            Tor.available -> { if (!Tor.alive) Tor.acquire(app, TOR_OWNER); "STARTING" } // Arti stopped: start it again
            else -> "OFF"
        }
        override fun appVersion(): String = BuildConfig.VERSION_NAME
        override fun recentLogs(): String = logText()
        override fun garageChanged() { RobloWatch.kick(app) }
        override fun takeNavigation(): String = RobloLinks.take()
        override fun explorer(): String = runCatching { com.kilombino.pyblockwatch.data.Store(app).explorer(com.kilombino.pyblockwatch.chain.Chain.BLAKE2B) }.getOrDefault("")
    }
}
