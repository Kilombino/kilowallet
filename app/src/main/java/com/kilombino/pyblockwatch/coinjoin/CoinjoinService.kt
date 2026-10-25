package com.kilombino.pyblockwatch.coinjoin

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps this phone in its coinjoin rounds while the app is closed: a round needs every
 * member online to answer votes and pass signatures, which a 5-minute poll cannot do. It
 * shows an ongoing notification with the round's state and stops itself once no pool needs it.
 */
class CoinjoinService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CoinjoinHub.ensureChannel(this)
        val n = ongoing(this)
        // specialUse from Android 14: dataSync is cut after ~6 hours a day on 15, mid-round.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        // The cards first: a round that ended while the app was away still gets its last one.
        runCatching { RoundNotifications.update(this, first()?.poolId) }
        if (!CoinjoinHub.hasActive(this)) { stopSelf(); return START_NOT_STICKY }
        if (!started) {
            started = true; CoinjoinHub.startAll(this)
            // Perfect coins waiting: looked at every minute, joined on their own once confirmed.
            val app = applicationContext
            Thread {
                while (started) {
                    runCatching { if (PerfectCoin.hasWaiting(app)) PerfectCoin.check(app) }
                    runCatching { refresh(app) }
                    Thread.sleep(60_000)
                }
            }.apply { isDaemon = true }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        started = false
        CoinjoinHub.stopAll()
        super.onDestroy()
    }

    companion object {
        private const val ID = 4242
        @Volatile private var started = false

        fun start(ctx: Context) {
            val i = Intent(ctx, CoinjoinService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
            }
            // Already running: start the new session straight away.
            if (started) CoinjoinHub.startAll(ctx)
        }

        /** The round the service's own notification shows: the first one going. */
        private fun first(): PoolSession.State? = CoinjoinHub.states.value
            .filter { it.phase !in setOf(PoolSession.Phase.CONFIRMED, PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED) }
            .minByOrNull { it.created }

        /** Redraw the round cards and the ongoing notification, or stop when no round needs us any more. */
        fun refresh(ctx: Context) {
            runCatching { RoundNotifications.update(ctx, if (started) first()?.poolId else null) }
            // Telegram alerts that did not get through yet go again (each after its own wait).
            if (CoinjoinAlerts.waiting(ctx) > 0) {
                val app = ctx.applicationContext
                Thread { runCatching { CoinjoinAlerts.flush(app) } }.apply { isDaemon = true; name = "cj-alerts" }.start()
            }
            if (!started) return
            if (!CoinjoinHub.hasActive(ctx)) { ctx.stopService(Intent(ctx, CoinjoinService::class.java)); return }
            // Unchanged, it is not posted again (it would come back on the phone every minute).
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val sig = first()?.let { RoundNotifications.signature(ctx, it) } ?: ""
            val showing = runCatching { nm.activeNotifications.any { it.id == ID } }.getOrDefault(false)
            if (showing && sig == lastSig) return
            nm.notify(ID, ongoing(ctx)); lastSig = sig
        }
        @Volatile private var lastSig: String? = null

        private fun ongoing(ctx: Context): Notification {
            // The first round going is this notification, drawn as its lock-screen card.
            first()?.let { st -> runCatching { return RoundNotifications.card(ctx, st) } }
            val live = CoinjoinHub.states.value.filter { it.phase !in setOf(PoolSession.Phase.CONFIRMED, PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED) }
            val text = live.firstOrNull()?.let { s ->
                when (s.phase) {
                    PoolSession.Phase.JOINING -> "Joining a pool…"
                    PoolSession.Phase.OPEN -> "Waiting for people · ${s.seats.size}/${s.terms.maxPeers}"
                    PoolSession.Phase.VOTING -> "Vote in progress: close now?"
                    PoolSession.Phase.CLOSING -> "Closing · collecting outputs"
                    PoolSession.Phase.SIGNING -> "Ready to sign — open the app"
                    PoolSession.Phase.BROADCAST -> "Sent · waiting for confirmation"
                    else -> ""
                }
            } ?: "Coinjoin"
            return Notification.Builder(ctx, CoinjoinHub.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Coinjoin").setContentText(com.kilombino.pyblockwatch.ui.i18n.I18n.tr(text))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(CoinjoinHub.openApp(ctx)).build()
        }
    }
}
