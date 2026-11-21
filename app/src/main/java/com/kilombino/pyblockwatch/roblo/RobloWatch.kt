package com.kilombino.pyblockwatch.roblo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.kilombino.pyblockwatch.chain.Tor
import com.kilombino.pyblockwatch.net.WsClient
import com.kilombino.pyblockwatch.ui.i18n.I18n
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Roblosats' notices in Kilowallet's background watcher ([com.kilombino.pyblockwatch.data.WatchService]):
 * one watcher, one persistent notification, no service of Roblosats' own.
 *
 * - At rest, every cycle of the watcher: one look at the coordinators' relays (through Tor, as the
 *   tab), the robots' gift wraps since the last look, each new notice shown once.
 * - With an order that needs following (a live status): a subscription kept open in the same
 *   watcher, so a step of the trade arrives within seconds; the watcher's own notification says
 *   "Watching · Roblosats order #N" instead of a second one.
 * Off when Roblosats' own setting for notifications is off.
 */
object RobloWatch {
    const val TOR_OWNER = "roblo-watch"
    const val CHANNEL_ORDERS = "roblo_orders"
    const val CHANNEL_ACTION = "roblo_action"
    const val GROUP = "roblosats"
    const val SUMMARY_ID = 6999
    /**
     * Gift wraps' created_at is randomised up to two days back (NIP-59): looked for that far,
     * plus an hour for clocks (as the Roblosats app 0.9.13).
     */
    const val WRAP_SLACK = 2 * 24 * 3600L + 3600L
    /** Never further back than a week, nor more than [LIMIT] wraps per request (0.9.13). */
    const val MAX_WINDOW = 7 * 24 * 3600L
    const val FIRST_WINDOW = 3 * 24 * 3600L
    const val LIMIT = 300
    private const val LAST_LOOK = "kw_watch_last_look"

    /** The watcher's sources, from the client's storage and Kilowallet's Tor. */
    class Sources(val store: RobloStore, val net: RobloNet)

    fun sources(ctx: Context): Sources {
        val app = ctx.applicationContext
        val store = RobloStore.of(app)
        val net = RobloNet(
            torSocks = { Tor.acquire(app, TOR_OWNER); Tor.proxy()?.address() as? java.net.InetSocketAddress },
            clearnet = { store.clearnet() },
            clearnetHosts = { store.clearnetHosts() },
            allowLocal = com.kilombino.pyblockwatch.BuildConfig.DEBUG,
            labOnly = com.kilombino.pyblockwatch.BuildConfig.ROBLO_LAB,
        )
        return Sources(store, net)
    }

    /** Roblosats' notifications are on (the client's setting; on unless switched off) and there are robots. */
    fun enabled(store: RobloStore): Boolean =
        com.kilombino.pyblockwatch.BuildConfig.ROBLO && runCatching { store.get("settings_notifications") != "false" && store.hasRobots() }.getOrDefault(false)

    /** The order the watcher keeps a connection open for, or null. */
    fun liveOrder(store: RobloStore): RobloGarage.Order? =
        runCatching { RobloGarage.liveOrder(RobloGarage.robots(store.get("garage_slots"))) }.getOrNull()

    /**
     * One look at the relays for new notices (blocking, a few seconds; called from the watcher's
     * loop). Returns how many were shown.
     */
    fun lookOnce(ctx: Context, src: Sources = sources(ctx), timeoutMs: Long = 25_000): Int {
        val store = src.store
        if (!enabled(store)) return 0
        val robots = RobloGarage.robots(store.get("garage_slots"))
        val relays = RobloGarage.relays(store.get("federation_relays")).take(3)
        if (robots.isEmpty() || relays.isEmpty()) return 0
        val now = System.currentTimeMillis() / 1000
        val since = since(store.get(LAST_LOOK).toLongOrNull(), now)
        var shown = 0
        try {
            var reached = 0
            for (relay in relays) {
                val inbox = Inbox(store, robots, RobloGarage.coordinatorKeys(store.get("federation_pubkeys"))) { r, robot -> notify(ctx, r, robot); shown++ }
                if (runCatching { fetch(src.net, relay, robots.map { it.pubkey }, since, inbox, timeoutMs) }.isSuccess) reached++
            }
            // Moved on only when a relay answered: a look with Tor still starting loses nothing.
            if (reached > 0) store.set(LAST_LOOK, now.toString())
        } finally { if (liveSub == null) Tor.release(ctx.applicationContext, TOR_OWNER) }
        return shown
    }

    /** One relay: REQ for the robots' gift wraps since [since], until EOSE (or the timeout), then closed. */
    internal fun fetch(net: RobloNet, relay: String, pubkeys: List<String>, since: Long, inbox: Inbox, timeoutMs: Long) {
        val route = net.route(relay, "anon")
        if (route is RobloNet.Route.Refused) return
        val eose = CountDownLatch(1)
        val ws = WsClient(relay, net.connector(route), onText = { m ->
            val a = runCatching { JSONArray(m) }.getOrNull() ?: return@WsClient
            when (a.optString(0)) {
                "EVENT" -> a.optJSONObject(2)?.let { inbox.handle(it) }
                "EOSE", "CLOSED" -> eose.countDown()
            }
        }, onClosed = { eose.countDown() }, pingEveryMs = 0)
        ws.connect()
        try {
            ws.send(JSONArray().put("REQ").put("kw-roblo").put(filter(pubkeys, since)).toString())
            eose.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally { ws.close() }
    }

    /** Where a look starts: the last look minus [WRAP_SLACK], never more than [MAX_WINDOW] back. */
    internal fun since(lastLook: Long?, now: Long): Long =
        maxOf(lastLook?.let { it - WRAP_SLACK } ?: (now - FIRST_WINDOW - WRAP_SLACK), now - MAX_WINDOW, 0L)

    internal fun filter(pubkeys: List<String>, since: Long): JSONObject =
        JSONObject().put("kinds", JSONArray().put(1059)).put("#p", JSONArray(pubkeys)).put("since", maxOf(0, since)).put("limit", LIMIT)

    /**
     * What arrives from the relays: each gift wrap for one of the [robots], unwrapped; a notice from
     * a coordinator of the federation ([coordinators], when known), shown once ([RobloDedup]).
     */
    class Inbox(
        private val store: RobloStore,
        private val robots: List<RobloGarage.Robot>,
        private val coordinators: Set<String>,
        private val onNotice: (GiftWrap.Rumor, RobloGarage.Robot) -> Unit,
    ) {
        private val seenWraps = HashSet<String>()
        private val dedup = object : RobloDedup.Store {
            override fun get(key: String) = runCatching { store.get(key) }.getOrDefault("")
            override fun set(key: String, value: String) { runCatching { store.set(key, value) } }
        }

        @Synchronized fun handle(wrap: JSONObject) {
            if (wrap.optInt("kind") != 1059) return
            val id = wrap.optString("id"); if (!seenWraps.add(id)) return
            val p = (0 until (wrap.optJSONArray("tags")?.length() ?: 0)).mapNotNull { i ->
                wrap.getJSONArray("tags").optJSONArray(i)?.takeIf { it.optString(0) == "p" }?.optString(1)
            }
            val robot = robots.firstOrNull { it.pubkey in p } ?: return
            val rumor = GiftWrap.unwrap(wrap, robot.secret) ?: return
            if (rumor.kind != 14) return
            if (coordinators.isNotEmpty() && rumor.pubkey !in coordinators) return
            if (!RobloDedup.shouldNotify(dedup, rumor.id, rumor.createdAt)) return
            onNotice(rumor, robot)
        }
    }

    // ---------------------------------------------------------------- live order

    @Volatile private var liveSub: Thread? = null
    @Volatile private var liveStop = false

    /** The watcher's text while an order is followed live, or null. */
    @Volatile var liveText: String? = null
        private set

    /**
     * Called by the watcher every few seconds: opens the live subscription when an order needs
     * following, closes it when none does. Returns the text for the watcher's own notification.
     */
    @Synchronized fun keepLive(ctx: Context): String? {
        val app = ctx.applicationContext
        val src = sources(app)
        val order = if (enabled(src.store)) liveOrder(src.store) else null
        if (order == null) {
            if (liveSub != null) { liveStop = true; liveSub?.interrupt(); liveSub = null; Tor.release(app, TOR_OWNER) }
            liveText = null
            return null
        }
        liveText = I18n.tr("Watching · Roblosats order #{0}").replace("{0}", order.id.toString())
        if (liveSub?.isAlive == true) return liveText
        liveStop = false
        liveSub = Thread({ liveLoop(app, src) }, "roblo-live").apply { isDaemon = true; start() }
        return liveText
    }

    /** The watcher stops: the live subscription with it. */
    @Synchronized fun stopLive(ctx: Context) {
        if (liveSub != null) { liveStop = true; liveSub?.interrupt(); liveSub = null; Tor.release(ctx.applicationContext, TOR_OWNER) }
        liveText = null
    }

    /** The client saved its garage: a new robot or a new order. Starts the watcher if it is meant to run. */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        val st = com.kilombino.pyblockwatch.data.Store(app)
        if (!st.notificationsEnabled || st.refreshBackgroundMinutes <= 0) return
        if (liveOrder(RobloStore.of(app)) == null && !RobloLn.live(app)) return
        runCatching { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, com.kilombino.pyblockwatch.data.WatchService::class.java)) }
    }

    /** The garage has a robot the open subscription does not ask for. */
    internal fun gainedRobots(subscribed: Set<String>, now: List<RobloGarage.Robot>): Boolean = now.any { it.pubkey !in subscribed }

    private fun liveLoop(app: Context, src: Sources) {
        var backoff = 5_000L
        while (!liveStop) {
            val store = src.store
            val robots = RobloGarage.robots(store.get("garage_slots"))
            val relay = RobloGarage.relays(store.get("federation_relays")).firstOrNull()
            if (robots.isEmpty() || relay == null || RobloGarage.liveOrder(robots) == null) break
            val closed = CountDownLatch(1)
            val keys = robots.map { it.pubkey }.toSet()
            val inbox = Inbox(store, robots, RobloGarage.coordinatorKeys(store.get("federation_pubkeys"))) { r, robot -> notify(app, r, robot) }
            runCatching {
                val route = src.net.route(relay, "anon")
                if (route is RobloNet.Route.Refused) error(route.why)
                val ws = WsClient(relay, src.net.connector(route), onText = { m ->
                    val a = runCatching { JSONArray(m) }.getOrNull()
                    if (a?.optString(0) == "EVENT") a.optJSONObject(2)?.let { inbox.handle(it) }
                }, onClosed = { closed.countDown() }, pingEveryMs = 25_000, deadAfterMs = 70_000)
                ws.connect()
                backoff = 5_000L
                ws.send(JSONArray().put("REQ").put("kw-roblo-live").put(filter(keys.toList(), System.currentTimeMillis() / 1000 - WRAP_SLACK)).toString())
                // Open until it drops, a robot is added, the order finishes or the watcher stops it.
                // A garage with fewer robots (one deleted, a save half done) keeps it as it is (0.9.13).
                while (!liveStop && !closed.await(15, TimeUnit.SECONDS)) {
                    val now = RobloGarage.robots(store.get("garage_slots"))
                    if (gainedRobots(keys, now) || RobloGarage.liveOrder(now) == null) break
                }
                ws.close()
            }
            if (liveStop) break
            runCatching { Thread.sleep(backoff) }; backoff = minOf(backoff * 2, 60_000L)
        }
    }

    // ---------------------------------------------------------------- notifications

    fun ensureChannels(ctx: Context) {
        val m = ctx.getSystemService(NotificationManager::class.java) ?: return
        m.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "Roblosats"))
        m.createNotificationChannel(NotificationChannel(CHANNEL_ORDERS, I18n.tr("Roblosats orders"), NotificationManager.IMPORTANCE_DEFAULT).apply { group = GROUP })
        m.createNotificationChannel(NotificationChannel(CHANNEL_ACTION, I18n.tr("Roblosats: your order needs you"), NotificationManager.IMPORTANCE_HIGH).apply {
            group = GROUP; enableVibration(true); vibrationPattern = longArrayOf(0, 300, 150, 300)
        })
    }

    /** The notice id: 7000 and up, one per rumor (a second look updates it, never stacks it). */
    fun notificationId(rumorId: String) = 7000 + Math.floorMod(rumorId.hashCode(), 100_000)

    /** "kilobot/24" → "Kilobot #24". */
    fun title(orderPath: String?): String {
        val p = orderPath ?: return "Roblosats"
        val (alias, id) = p.split('/').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        return alias.replaceFirstChar { it.uppercase() } + if (id.isNotEmpty()) " #$id" else ""
    }

    fun notify(ctx: Context, rumor: GiftWrap.Rumor, robot: RobloGarage.Robot) {
        ensureChannels(ctx)
        val path = rumor.tag("order_id")
        val needsYou = rumor.tag("action") == "1"
        val intent = Intent(ctx, com.kilombino.pyblockwatch.ui.MainActivity::class.java)
            .setAction(RobloLinks.ACTION)
            .putExtra(RobloLinks.EXTRA_PATH, path)
            .putExtra(RobloLinks.EXTRA_ROBOT, robot.pubkey)
            .setPackage(ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val id = notificationId(rumor.id)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, if (needsYou) CHANNEL_ACTION else CHANNEL_ORDERS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("🤖 " + title(path))
            .setContentText(rumor.content)
            .setStyle(Notification.BigTextStyle().bigText(rumor.content))
            .setCategory(if (needsYou) Notification.CATEGORY_REMINDER else Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setGroup(GROUP)
            .build()
        val m = ctx.getSystemService(NotificationManager::class.java) ?: return
        m.notify(id, n)
        // Roblosats' notices in a bundle of their own, apart from the wallet's payments.
        m.notify(SUMMARY_ID, Notification.Builder(ctx, CHANNEL_ORDERS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("🤖 Roblosats")
            .setGroup(GROUP).setGroupSummary(true).setAutoCancel(true)
            .build())
    }
}
