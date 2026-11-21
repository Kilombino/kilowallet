package com.kilombino.pyblockwatch.roblo

import android.content.Context
import android.content.DesktopContext
import android.content.DesktopIntents
import androidx.compose.runtime.mutableStateOf
import com.jetbrains.cef.JCefAppConfig
import com.kilombino.pyblockwatch.BuildConfig
import com.kilombino.pyblockwatch.chain.Tor
import org.cef.CefApp
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRendering
import org.cef.callback.CefCallback
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefFileDialogCallback
import org.cef.callback.CefMenuModel
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefAppHandlerAdapter
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDialogHandler
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.Executors
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The ROBLO tab's page on the desktop: the Roblosats client in Chromium (JCEF, from the JetBrains
 * Runtime the app runs on), in place of Android's WebView, behind the same [RobloBridge].
 *
 * - Chromium starts the first time the tab opens (not with the app), with nothing on disk: no
 *   cache folder, cookies and storage in memory only.
 * - The page is the pinned client served from memory at the phone's own origin
 *   (https://appassets.androidplatform.net/roblo/, [RobloAssets]); every other address is refused
 *   (scheme handler, request handler and the page's CSP). It has no network of its own: every
 *   request goes through the bridge and Kilowallet's Tor.
 * - window.AndroidAppRobosats is a small script put first in index.html: the values the client
 *   reads synchronously are written into it, every other call goes to the bridge through JCEF's
 *   message router ([dispatch], a closed list of methods), and the bridge answers through
 *   executeJavaScript as on Android.
 * - Kept alive while the user moves between HOT, WATCH, JM and ROBLO; ended after [IDLE_MS] off
 *   screen, when the wallet locks, or when the app quits.
 */
object RobloWeb {
    const val ORIGIN_HOST = RobloAssets.ORIGIN_HOST
    const val BASE = RobloAssets.BASE
    const val TOR_OWNER = "roblo"
    const val IDLE_MS = 10 * 60_000L
    /** The message router's function in the page (not JCEF's default name). */
    private const val QUERY = "kwRobloQuery"

    /** The remote debugging port of a test build (-PkwDebug=true): the lab drives the page with CDP. */
    val debugPort: Int = if (BuildConfig.DEBUG) (System.getProperty("kilowallet.roblo.cdp")?.toIntOrNull() ?: 9229) else 0

    private var app: CefApp? = null
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var router: CefMessageRouter? = null
    private var bridge: RobloBridge? = null
    @Volatile private var store: RobloStore? = null
    private val holder = JPanel(BorderLayout()).apply { background = java.awt.Color(0x0B, 0x0B, 0x10) }
    private val calls = Executors.newSingleThreadExecutor { r -> Thread(r, "roblo-bridge").apply { isDaemon = true } }
    private val logs = ArrayDeque<String>()
    private var idle: javax.swing.Timer? = null

    /** A link out of the client waiting for the user's yes (the screen shows the question). */
    val outsideLink = mutableStateOf<String?>(null)

    /** Chromium could not start here (shown on the tab instead of the page). */
    val failure = mutableStateOf<String?>(null)

    /** The panel the tab shows: the page, created (and the client loaded) the first time. */
    fun obtain(): Component {
        onEdt()
        cancelIdle()
        if (browser == null) runCatching { create() }.onFailure { e ->
            log("Chromium did not start: ${e.message}")
            failure.value = e.message ?: e.javaClass.simpleName
        }
        return holder
    }

    val alive: Boolean get() = browser != null

    private fun create() {
        val ctx = DesktopContext
        val st = RobloStore.of(ctx).also { store = it }
        RobloWallet.labRegtest = BuildConfig.ROBLO_LAB
        runCatching { RobloLn.resume(ctx) }
        st.migrateClearnet()
        Tor.acquire(ctx, TOR_OWNER)
        val cef = cefApp()
        val c = cef.createClient()
        val r = CefMessageRouter.create(CefMessageRouter.CefMessageRouterConfig(QUERY, QUERY + "Cancel"))
        r.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(b: CefBrowser?, frame: CefFrame?, queryId: Long, request: String?, persistent: Boolean, callback: CefQueryCallback?): Boolean {
                // Only the client's own page may call the bridge.
                if (frame == null || RobloAssets.assetPath(frame.url ?: "") == null || request == null || request.length > RobloBridge.MAX_TEXT + 4096) {
                    callback?.failure(403, "refused"); return true
                }
                calls.execute { bridge?.let { b -> runCatching { RobloShim.dispatch(request, b, HostImpl.features()) }.onFailure { log("bridge: ${it.message}") } } }
                callback?.success("")
                return true
            }
        }, true)
        c.addMessageRouter(r)
        c.addRequestHandler(Requests)
        c.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            // window.open / target=_blank: never a second window; outside links after asking.
            override fun onBeforePopup(b: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
                targetUrl?.let { askOutside(it) }
                return true
            }
        })
        c.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(b: CefBrowser?, level: CefSettings.LogSeverity?, message: String?, source: String?, line: Int): Boolean {
                if (level == CefSettings.LogSeverity.LOGSEVERITY_ERROR || level == CefSettings.LogSeverity.LOGSEVERITY_WARNING || BuildConfig.DEBUG)
                    log("console ${level?.name?.removePrefix("LOGSEVERITY_")?.lowercase()}: ${message?.take(500)}")
                return true
            }
        })
        c.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadError(b: CefBrowser?, frame: CefFrame?, errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?) {
                if (errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) log("load error $errorCode ${failedUrl?.take(120)}")
            }
        })
        // The chat's image button: a file chooser for one picture.
        c.addDialogHandler(object : CefDialogHandler {
            override fun onFileDialog(b: CefBrowser?, mode: CefDialogHandler.FileDialogMode?, title: String?, defaultFilePath: String?,
                                      acceptFilters: java.util.Vector<String>?, acceptExtensions: java.util.Vector<String>?,
                                      acceptDescriptions: java.util.Vector<String>?, callback: CefFileDialogCallback?): Boolean {
                SwingUtilities.invokeLater {
                    val f = pickImage()
                    if (f != null) callback?.Continue(java.util.Vector(listOf(f))) else callback?.Cancel()
                }
                return true
            }
        })
        // No "inspect", "view source" or "save as" in a handed-out build.
        if (!BuildConfig.DEBUG) c.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
            override fun onBeforeContextMenu(b: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams?, model: CefMenuModel?) { model?.clear() }
        })
        // Downloads, camera and microphone are never granted (the handlers' defaults refuse them).
        val br = c.createBrowser(BASE + "index.html", CefRendering.DEFAULT, false)
        client = c; router = r; browser = br
        val net = RobloNet(
            torSocks = {
                Tor.acquire(ctx, TOR_OWNER) // started again if it stopped
                (Tor.proxy()?.address() as? java.net.InetSocketAddress)
            },
            clearnet = { st.clearnet() },
            clearnetHosts = { st.clearnetHosts() },
            allowLocal = BuildConfig.DEBUG,
            labOnly = BuildConfig.ROBLO_LAB,
        )
        bridge = RobloBridge(HostImpl, st, net)
        holder.removeAll()
        holder.add(br.uiComponent, BorderLayout.CENTER)
        holder.revalidate()
        failure.value = null
        log("client loaded")
    }

    /** Chromium, started once per run of the app (it can't be started twice in one process). */
    private fun cefApp(): CefApp {
        app?.let { return it }
        // Chromium aborts the whole process when it can't start its helper: refuse here instead,
        // and the tab says why (a runtime whose helper lost its execute bit, a missing framework).
        val home = java.io.File(System.getProperty("java.home"))
        if (com.kilombino.kilowallet.desktop.Platform.os == com.kilombino.kilowallet.desktop.Platform.Os.LINUX) {
            val helper = home.resolve("lib/jcef_helper")
            check(helper.isFile && helper.canExecute()) { "Chromium's helper is missing or not executable ($helper)" }
        }
        if (com.kilombino.kilowallet.desktop.Platform.os == com.kilombino.kilowallet.desktop.Platform.Os.WINDOWS) {
            val helper = home.resolve("bin/jcef_helper.exe")
            check(helper.isFile) { "Chromium's helper is missing ($helper)" }
        }
        if (com.kilombino.kilowallet.desktop.Platform.os == com.kilombino.kilowallet.desktop.Platform.Os.MAC) {
            val fw = home.parentFile.resolve("Frameworks/Chromium Embedded Framework.framework")
            check(fw.isDirectory) { "Chromium's framework is missing ($fw)" }
        }
        val cfg = JCefAppConfig.getInstance()
        val args = cfg.appArgsAsList.toMutableList()
        // Nothing of the page on disk; no GPU process (the page is light, and it is one less thing to crash).
        args += listOf("--disable-gpu", "--disable-gpu-compositing", "--incognito", "--disable-extensions",
            "--disable-background-networking", "--disable-component-update", "--disable-sync", "--no-first-run",
            "--disable-features=MediaRouter,Translate,OptimizationHints,AutofillServerCommunication",
            // Chromium itself never needs the network (the bridge makes every request, through Tor):
            // no name is ever resolved and nothing could leave even if a request slipped through.
            "--host-resolver-rules=MAP * ~NOTFOUND", "--proxy-server=socks5://127.0.0.1:9", "--proxy-bypass-list=<-loopback>")
        val s = cfg.cefSettings
        s.windowless_rendering_enabled = false
        s.cache_path = null
        s.persist_session_cookies = false
        s.user_agent = "AndroidRobosats Kilowallet/${BuildConfig.VERSION_NAME} (desktop)"
        s.remote_debugging_port = debugPort
        s.log_severity = if (BuildConfig.DEBUG) CefSettings.LogSeverity.LOGSEVERITY_WARNING else CefSettings.LogSeverity.LOGSEVERITY_DISABLE
        s.background_color = s.ColorType(255, 0x0B, 0x0B, 0x10)
        val array = args.toTypedArray()
        CefApp.addAppHandler(object : CefAppHandlerAdapter(array) {
            // The page's origin for requests that come without a browser (its WebAssembly worker):
            // served from the bundle too, never from the network.
            override fun onContextInitialized() {
                CefApp.getInstance().registerSchemeHandlerFactory("https", ORIGIN_HOST) { _, _, _, req -> serve(req?.url) }
            }
        })
        check(CefApp.startup(array)) { "JCEF did not start (is this the JetBrains Runtime with JCEF?)" }
        return CefApp.getInstance(array, s).also { app = it }
    }

    /**
     * What the page may load: its own origin, served from the bundle; data: and blob: URLs it makes
     * itself. Anything else is cancelled before it leaves (the CSP already forbids it); a navigation
     * to an outside address asks the user first and opens in the system's browser.
     */
    private object Requests : CefRequestHandlerAdapter() {
        override fun onBeforeBrowse(b: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean): Boolean {
            val url = request?.url ?: return true
            if (RobloAssets.assetPath(url) != null) return false
            askOutside(url)
            return true
        }

        override fun onOpenURLFromTab(b: CefBrowser?, frame: CefFrame?, targetUrl: String?, userGesture: Boolean): Boolean {
            targetUrl?.let { askOutside(it) }
            return true
        }

        override fun getResourceRequestHandler(b: CefBrowser?, frame: CefFrame?, request: CefRequest?, isNavigation: Boolean, isDownload: Boolean,
                                               requestInitiator: String?, disableDefaultHandling: BoolRef?): CefResourceRequestHandler = Resources

        override fun onRenderProcessTerminated(b: CefBrowser?, status: CefRequestHandler.TerminationStatus?, errorCode: Int, errorString: String?) {
            log("renderer gone ($status)")
            SwingUtilities.invokeLater { destroy("renderer gone") }
        }
    }

    private object Resources : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(b: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
            val url = request?.url ?: return true
            if (url.startsWith("data:") || url.startsWith("blob:")) return false
            return RobloAssets.assetPath(url) == null // true = cancelled
        }

        override fun getResourceHandler(b: CefBrowser?, frame: CefFrame?, request: CefRequest?): CefResourceHandler? = serve(request?.url)
    }

    /**
     * The answer for one of our URLs, never the network: a bundle that can't be read (or isn't the
     * pinned one) answers 500 and says why in the log. Null for anything else (already cancelled).
     */
    private fun serve(url: String?): CefResourceHandler? {
        if (url == null || RobloAssets.assetPath(url) == null) return null
        return Served(runCatching { RobloAssets.answer(url, ::shim) }.getOrElse { e ->
            log("bundle: ${e.message}")
            RobloAssets.Answer(500, "text/plain", mapOf("Cache-Control" to "no-store"), (e.message ?: "error").toByteArray())
        })
    }

    /** One file of the bundle, handed to Chromium. */
    private class Served(private val a: RobloAssets.Answer) : CefResourceHandler {
        private var pos = 0
        override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean { callback?.Continue(); return true }
        override fun getResponseHeaders(response: CefResponse?, length: IntRef?, redirect: StringRef?) {
            response?.status = a.status
            response?.mimeType = a.mime
            response?.setHeaderMap(a.headers)
            length?.set(a.body.size)
        }
        override fun readResponse(out: ByteArray?, bytesToRead: Int, bytesRead: IntRef?, callback: CefCallback?): Boolean {
            if (out == null || pos >= a.body.size) { bytesRead?.set(0); return false }
            val n = minOf(bytesToRead, a.body.size - pos)
            System.arraycopy(a.body, pos, out, 0, n); pos += n
            bytesRead?.set(n)
            return true
        }
        override fun cancel() {}
    }

    /**
     * window.AndroidAppRobosats for this load of the page: what the client reads synchronously
     * (hostInfo, isClearnet, appVersion, hostExplorer, takePendingNavigation) written in, every
     * other method a message to the bridge. Wallet methods only for the functions offered.
     */
    internal fun shim(): String {
        val st = store ?: RobloStore.of(DesktopContext)
        val features = HostImpl.features()
        val info = JSONObject().put("host", "kilowallet").put("version", HostImpl.appVersion()).put("clearnet", st.clearnet())
            .put("platform", HostImpl.platform()).put("features", JSONArray(features)).toString()
        return RobloShim.script(info, HostImpl.explorer(), RobloLinks.take(), features, QUERY)
    }

    // ---------------------------------------------------------------- lifecycle

    /** The tab shows the page. */
    fun onScreen() { onEdt(); cancelIdle() }

    /** The page left the screen (another tab): ended after [IDLE_MS] unless it comes back. */
    fun offScreen() { onEdt(); scheduleIdle() }

    /** The window was hidden (to the tray) or shown again. */
    fun appHidden() = SwingUtilities.invokeLater { scheduleIdle() }
    fun appShown() {}

    /** Android's memory pressure: never on the desktop. */
    fun onTrimMemory(level: Int, visible: Boolean) {}

    /** The activity is going away: nothing to do here (the page is the app's). */
    fun detach(act: android.app.Activity) {}

    private fun scheduleIdle() {
        if (browser == null) return
        cancelIdle()
        idle = javax.swing.Timer(IDLE_MS.toInt()) { destroy("off screen for ${IDLE_MS / 60_000} min") }.apply { isRepeats = false; start() }
    }

    private fun cancelIdle() { idle?.stop(); idle = null }

    /**
     * Ends the page: its connections close, Tor is released, nothing of it is kept (its storage was
     * only in memory). Chromium itself stays up until the app quits (it can't start twice).
     */
    fun destroy(why: String) {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater { destroy(why) }; return }
        val br = browser ?: return
        log("closed: $why")
        cancelIdle()
        bridge?.close(); bridge = null
        // Storage of the page's origin (in memory) goes with it, and no cookie is left either.
        runCatching { br.executeJavaScript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", BASE, 0) }
        runCatching { org.cef.network.CefCookieManager.getGlobalManager()?.deleteCookies("", "") }
        holder.removeAll(); holder.revalidate(); holder.repaint()
        runCatching { br.close(true) }
        runCatching { router?.let { client?.removeMessageRouter(it); it.dispose() } }
        runCatching { client?.dispose() }
        browser = null; client = null; router = null
        outsideLink.value = null
        Tor.release(DesktopContext, TOR_OWNER)
    }

    /** The app quits: the page and then Chromium end (a JCEF left running keeps the process alive). */
    fun shutdown() {
        val done = java.util.concurrent.CountDownLatch(1)
        val work = Runnable {
            runCatching { destroy("quit") }
            runCatching { app?.dispose() }
            app = null
            done.countDown()
        }
        if (SwingUtilities.isEventDispatchThread()) work.run() else { SwingUtilities.invokeLater(work); done.await(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /**
     * A notification asked to open an order ([RobloLinks.pending]) while the page is up: the page
     * takes it now (a page still loading takes it itself from its shim).
     */
    fun navigateNow() = SwingUtilities.invokeLater {
        val br = browser ?: return@invokeLater
        val nav = RobloLinks.take()
        if (nav.isEmpty()) return@invokeLater
        br.executeJavaScript("window.__kwNavigate && window.__kwNavigate('${RobloBridge.encodeForJavaScript(nav)}'); " +
            "window.AndroidRobosats && window.AndroidRobosats.onNavigationIntent && window.AndroidRobosats.onNavigationIntent()", BASE, 0)
    }

    /** Reload from the start (after Tor/clearnet changes in the client's settings), in place. */
    fun reload() = SwingUtilities.invokeLater {
        val br = browser ?: return@invokeLater
        bridge?.dropConnections()
        Tor.acquire(DesktopContext, TOR_OWNER)
        log("reload (" + (if (store?.clearnet() == true) "clearnet" else "Tor") + ")")
        br.loadURL(BASE + "index.html")
    }

    /**
     * The computer woke up from sleep (or the network came back): every WebSocket ends now with an
     * error, so the page shows "disconnected" at once and reconnects (see [RobloBridge.dropConnections]).
     */
    fun networkChanged(why: String) {
        log("connections dropped: $why")
        bridge?.dropConnections()
    }

    // ---------------------------------------------------------------- outside links, pictures

    /** A link out of the client: the system's browser, after the user says yes ([outsideLink]). */
    private fun askOutside(url: String) {
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "https" && scheme != "http") { log("link refused: ${scheme ?: "?"}:"); return }
        SwingUtilities.invokeLater { outsideLink.value = url.take(2000) }
    }

    /** The user's answer to [outsideLink]. */
    fun answerOutside(open: Boolean) {
        val url = outsideLink.value ?: return
        outsideLink.value = null
        if (open) DesktopIntents.open(url)
    }

    /** One picture for the chat, picked in the system's file dialog; null when cancelled. */
    private fun pickImage(): String? {
        val owner = SwingUtilities.getWindowAncestor(holder) as? java.awt.Frame
        val d = java.awt.FileDialog(owner, com.kilombino.kilowallet.desktop.i18n.I18n.tr("Choose a picture"), java.awt.FileDialog.LOAD)
        d.isMultipleMode = false
        d.setFilenameFilter { _, name -> name.lowercase().substringAfterLast('.') in setOf("png", "jpg", "jpeg", "webp", "gif") }
        d.isVisible = true
        val f = d.files?.firstOrNull() ?: return null
        return if (f.isFile && f.length() in 1..20_000_000L) f.absolutePath else null
    }

    // ---------------------------------------------------------------- logs, host

    @Synchronized private fun log(m: String) {
        logs.addLast("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $m")
        while (logs.size > 600) logs.removeFirst()
        if (BuildConfig.DEBUG) System.err.println("[roblo] $m")
    }

    @Synchronized private fun logText() = logs.joinToString("\n")

    private fun onEdt() = check(SwingUtilities.isEventDispatchThread()) { "RobloWeb: on the window's thread only" }

    internal object HostImpl : RobloBridge.Host {
        private val app: Context get() = DesktopContext
        override fun evaluate(js: String) {
            val clean = js.replace("\u0000", "")
            SwingUtilities.invokeLater { runCatching { browser?.executeJavaScript(clean, BASE, 0) } }
        }
        override fun copyToClipboard(text: String) { DesktopIntents.copy(text); android.widget.DesktopToast.show("Copied") }
        override fun share(text: String) { DesktopIntents.copy(text); DesktopIntents.onMessage("Copied to the clipboard, to paste where you want to share it.") }
        /** The client asks while an order waits for you; a computer's screen is left as the user set it. */
        override fun keepScreenOn(on: Boolean) {}
        override fun reload() { RobloWeb.reload() }
        override fun torStatus(): String = when {
            Tor.running -> "ON"
            Tor.available -> { if (!Tor.alive) Tor.acquire(app, TOR_OWNER); "STARTING" }
            else -> "OFF"
        }
        override fun appVersion(): String = BuildConfig.VERSION_NAME
        override fun recentLogs(): String = logText()
        override fun garageChanged() { RobloWatch.kick(app) }
        override fun takeNavigation(): String = RobloLinks.take()
        override fun explorer(): String = runCatching { com.kilombino.pyblockwatch.data.Store(app).explorer(com.kilombino.pyblockwatch.chain.Chain.BLAKE2B) }.getOrDefault("")
        override fun platform(): String = "desktop"
        /** The desktop has the hot wallet and its own Lightning node (LND): all four. */
        override fun features(): List<String> = RobloBridge.WALLET_FEATURES
    }
}
