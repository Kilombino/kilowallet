package com.kilombino.pyblockwatch.roblo

import android.content.Context
import com.kilombino.pyblockwatch.ln.Lightning
import com.kilombino.pyblockwatch.ln.Lnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Roblosats' hold invoices paid with Kilowallet's Lightning node (bonds, the seller's trade escrow).
 *
 * A held payment stays IN_FLIGHT for as long as the trade lasts (hours). While one is, this phone's
 * node has an HTLC out: it must come back up whenever the app is killed (the background watcher
 * brings it up and follows the payment again, [resume]), or the peer may have to close the channel
 * on chain when the HTLC times out. Each one is kept here (payment hash → order) until it ends:
 * settled (the coordinator charged it), or cancelled (the bond came back).
 */
object RobloLn {
    private const val PREFS = "roblo_ln"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val following = ConcurrentHashMap.newKeySet<String>()

    data class Held(val hash: String, val order: String, val sats: Long, val since: Long, val locked: Boolean)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun held(ctx: Context): List<Held> = prefs(ctx).all.mapNotNull { (k, v) ->
        runCatching { JSONObject(v as String).let { Held(k, it.getString("order"), it.getLong("sats"), it.getLong("since"), it.optBoolean("locked")) } }.getOrNull()
    }

    /** One of our hold payments: Lightning's own follow-up leaves it to us. */
    fun owns(ctx: Context, hash: String): Boolean = prefs(ctx).contains(hash)

    /** An HTLC of a Roblosats payment may be out: the node must stay up. */
    fun live(ctx: Context): Boolean = prefs(ctx).all.isNotEmpty()

    private fun keep(ctx: Context, h: Held) {
        prefs(ctx).edit().putString(h.hash, JSONObject().put("order", h.order).put("sats", h.sats).put("since", h.since).put("locked", h.locked).toString()).commit()
    }

    private fun drop(ctx: Context, hash: String) { prefs(ctx).edit().remove(hash).commit(); following.remove(hash) }

    /**
     * The node up for a sheet that needs it: started if Lightning is on but the node is not running
     * yet (the app was just opened on ROBLO, the LIGHTNING tab never shown), then waited for. Null
     * when it is ready, else why not. (21 nov, desktop lab: the sheet said "closed" instead.)
     */
    suspend fun awaitReady(ctx: Context, timeoutMs: Long = 180_000): String? {
        val app = ctx.applicationContext
        if (!Lightning.enabled(app)) return notReady(app)
        if (!Lnd.running || Lightning.status.value !is Lightning.Status.Running) runCatching { Lightning.resume(app) }
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (Lnd.running && Lightning.status.value is Lightning.Status.Running) return null
            // the status moves on only when refreshed (the LIGHTNING tab does it while shown)
            if (Lnd.running) runCatching { Lightning.refresh() }
            kotlinx.coroutines.delay(2000)
        }
        return notReady(app) ?: "Lightning is still starting: try again in a moment."
    }

    /** Why the node can't pay now, or null when it can. */
    fun notReady(ctx: Context): String? = when {
        !Lightning.enabled(ctx) -> "Lightning is off in this wallet: turn it on in the LIGHTNING tab first."
        Lightning.status.value !is Lightning.Status.Running -> "Lightning is still starting: try again in a moment."
        else -> null
    }

    /**
     * Pays [bolt11] for [order]: [onLocked] once the HTLC is out (the coordinator sees the bond or
     * escrow locked), [onFailed] if it never gets there. The payment is then followed to its end in
     * the background.
     */
    fun pay(ctx: Context, bolt11: String, order: String, onLocked: () -> Unit, onFailed: (String) -> Unit) {
        val app = ctx.applicationContext
        notReady(app)?.let { onFailed(it); return }
        scope.launch {
            val req = runCatching { Lnd.decode(bolt11) }.getOrElse { onFailed("Not a Lightning invoice: ${it.message}"); return@launch }
            if (!req.blake2b) { onFailed("This invoice is not for Bitcoin (BLAKE2b)."); return@launch }
            if (owns(app, req.hash) || following.contains(req.hash)) { onLocked(); return@launch }
            keep(app, Held(req.hash, order, req.sats, System.currentTimeMillis() / 1000, false))
            following.add(req.hash)
            // the watcher keeps the process (and the node) up while it is held
            runCatching { RobloWatch.kick(app) }
            var answered = false
            val r = runCatching {
                Lnd.payHold(bolt11, Lightning.feeLimit(app, req.sats), onLocked = {
                    keep(app, Held(req.hash, order, req.sats, System.currentTimeMillis() / 1000, true))
                    if (!answered) { answered = true; onLocked() }
                })
            }
            r.onFailure { e -> if (!answered) { answered = true; onFailed(Lnd.payError(e)) } }
            ended(app, req.hash, r.exceptionOrNull())
        }
    }

    /**
     * After a restart: the node is up again, follow each held payment to its end (LND kept it in
     * flight across the restart). Called by the watcher and when the app opens.
     */
    fun resume(ctx: Context) {
        val app = ctx.applicationContext
        if (Lightning.status.value !is Lightning.Status.Running) return
        for (h in held(app)) {
            if (!following.add(h.hash)) continue
            scope.launch { ended(app, h.hash, runCatching { Lnd.trackHold(h.hash, onLocked = {}) }.exceptionOrNull()) }
        }
    }

    /**
     * How a held payment's follow-up ended: [outcome] null (settled) or a final failure (cancelled:
     * the bond came back; never left the node) closes it; an unknown end (the node stopped, the
     * stream broke) keeps it, to be followed again ([resume]).
     */
    internal fun ended(ctx: Context, hash: String, outcome: Throwable?) {
        if (keepFollowing(outcome)) { following.remove(hash); return }
        drop(ctx, hash)
        scope.launch { runCatching { Lightning.refresh() } }
    }

    /** True when the payment's outcome is still unknown. */
    internal fun keepFollowing(outcome: Throwable?): Boolean = outcome is com.kilombino.pyblockwatch.ln.HoldUnknown &&
        outcome.message?.let { it.contains("not found", true) || it.contains("unknown payment", true) } != true

    /** An invoice from the node for the Roblosats payout (it needs inbound liquidity to be paid). */
    suspend fun invoice(ctx: Context, sats: Long, memo: String): String {
        notReady(ctx)?.let { throw IllegalStateException(it) }
        return Lnd.addInvoice(sats, memo.ifBlank { "Roblosats payout" }, expirySeconds = 24 * 3600)
    }
}
