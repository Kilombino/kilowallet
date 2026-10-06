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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        if (!CoinjoinHub.hasActive(this)) { stopSelf(); return START_NOT_STICKY }
        if (!started) { started = true; CoinjoinHub.startAll(this) }
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

        /** Redraw the ongoing notification, or stop when no round needs us any more. */
        fun refresh(ctx: Context) {
            if (!started) return
            if (!CoinjoinHub.hasActive(ctx)) { ctx.stopService(Intent(ctx, CoinjoinService::class.java)); return }
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, ongoing(ctx))
        }

        private fun ongoing(ctx: Context): Notification {
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
                .setContentTitle("Coinjoin").setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(CoinjoinHub.openApp(ctx)).build()
        }
    }
}
