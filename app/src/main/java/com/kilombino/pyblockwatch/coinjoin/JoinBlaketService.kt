package com.kilombino.pyblockwatch.coinjoin

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.kilombino.pyblockwatch.joinblaket.TakerResult
import com.kilombino.pyblockwatch.joinblaket.TakerStep
import com.kilombino.pyblockwatch.ui.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps a PAY THROUGH JOINBLAKET round going while the app is in the background: the round holds
 * a Tor connection to the directory for a few minutes (offers, then three exchanges with the
 * makers), which Android would cut without a foreground service. Its notification follows the
 * round ([JoinBlaketPay.state]); at the end it leaves the outcome and stops.
 */
class JoinBlaketService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CoinjoinHub.ensureChannel(this)
        val n = notification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        val app = applicationContext
        val job = JoinBlaketPay.run(app, scope) {
            // The outcome stays as an ordinary notification once the service is gone.
            refresh(app)
            stopSelf()
        }
        if (job == null && !JoinBlaketPay.running) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // A round still going when Android stops us is cancelled before it signs (or reported as possibly sent after).
        JoinBlaketPay.serviceStopped()
        scope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        refresh(applicationContext)
        super.onDestroy()
    }

    companion object {
        private const val ID = 4250

        fun start(ctx: Context) {
            val i = Intent(ctx, JoinBlaketService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i) }
        }

        /** Redraw the notification from the round's state. */
        fun refresh(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (JoinBlaketPay.state.value == null) { nm.cancel(ID); return }
            runCatching { nm.notify(ID, notification(ctx)) }
        }

        /** One line for the round's state, in English (the caller translates). */
        fun line(v: JoinBlaketPay.View): String {
            v.result?.let { r ->
                return when (r) {
                    is TakerResult.Sent -> if (r.seen) "Done: the CoinJoin was broadcast." else "Sent to the makers; your server doesn't show it yet."
                    is TakerResult.Failed -> r.reason
                    is TakerResult.Cancelled -> r.reason
                }
            }
            if (v.plan != null) return "Confirm the CoinJoin: open the app."
            return when (val s = v.step) {
                null, is TakerStep.Connecting -> "Connecting to JoinBlaket through Tor…"
                TakerStep.ReadingOffers -> "Reading the makers' offers…"
                is TakerStep.CheckingBonds -> "Checking the makers' fidelity bonds…"
                is TakerStep.Confirm -> "Confirm the CoinJoin: open the app."
                is TakerStep.WaitingForMakers -> "Waiting for the makers: {0} of {1} ready".replace("{0}", s.ready.toString()).replace("{1}", s.asked.toString())
                is TakerStep.CollectingSignatures -> "Collecting the makers' signatures…"
                TakerStep.Broadcasting -> "Signing and broadcasting…"
            }
        }

        private fun notification(ctx: Context): Notification {
            val v = JoinBlaketPay.state.value
            val text = I18n.tr(v?.let(::line) ?: "Connecting to JoinBlaket through Tor…")
            return Notification.Builder(ctx, CoinjoinHub.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(I18n.tr("🔀 Pay through JoinBlaket"))
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setOngoing(v != null && !v.finished)
                // Asking for the user's confirmation alerts; the other steps update in silence.
                .setOnlyAlertOnce(v?.plan == null && v?.finished != true)
                .setAutoCancel(v?.finished == true)
                .setContentIntent(com.kilombino.pyblockwatch.data.OpenTab.pending(ctx, com.kilombino.pyblockwatch.data.OpenTab.JOINBLAKET))
                .build()
        }
    }
}
