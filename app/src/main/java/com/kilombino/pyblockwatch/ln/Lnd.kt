package com.kilombino.pyblockwatch.ln

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import lndmobile.Callback
import lndmobile.Lndmobile
import lndmobile.RecvStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Lightning node inside the app: LND (the Bitcoin BLAKE2b fork, with the neutrino light
 * client) run as a library in this process. It follows the chain itself through compact block
 * filters from peers that serve them, checks BLAKE2b proof of work and the chain's activation
 * block, and keeps its channels and keys on this phone.
 *
 * Calls go in and out as serialized protobuf ([Pb], [PbMsg]); each is wrapped as a suspend
 * function. The node runs once per process: [start] is idempotent, and it stops with the app.
 */
object Lnd {
    /** Peers that serve the chain's compact block filters, tried in order. Ours first. */
    val DEFAULT_PEERS = listOf(
        "ln.kilombino.com:11010",         // Kilombino's Knots node (our name on AirVPN's current IP)
        "nodoblake2b.airdns.org:11010",   // the same through AirDNS, which can lag behind an IP change
        "179.27.118.130:8343",            // PyBLOCK
        // PyBLOCK's 173.249.217.35:4142 (Knots 29.4.3rc1) answered every getheaders with no headers
        // on 18 Oct 2026, and neutrino kept it as its sync peer for good: left out until it serves them.
        "82.67.102.15:8333",              // mempool.guide
        "home.skyhawk.ca:8333",
    )

    /** BLAKE2b fee estimates in LND's fee.url format, from Kilombino's node. */
    const val FEE_URL = "https://nodex.kilombino.com/data/lnd-fees.json"

    fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "lnd")

    @Volatile var running = false
        private set

    /**
     * The configuration file LND reads: mainnet, neutrino with only [peers] (the DNS seeds are
     * Bitcoin's and return nodes on the SHA256d chain), no listening port (a phone cannot be
     * reached), and Tor through [torSocks] when given.
     */
    fun writeConfig(ctx: Context, peers: List<String>, torSocks: Int?, alias: String = "Kilowallet") {
        val d = dir(ctx); d.mkdirs()
        val conf = buildString {
            appendLine("[Application Options]")
            appendLine("debuglevel=info")
            appendLine("nolisten=true")
            appendLine("norest=true")
            // The TLS certificate would list every interface's address, and Android does not let
            // apps read them (netlink): LND then stops at start. Only localhost talks to it here.
            appendLine("tlsdisableautofill=true")
            appendLine("alias=$alias")
            appendLine("maxpendingchannels=3")
            // Lightning DNS seeds list nodes of the SHA256d chain; peers here are chosen by hand.
            appendLine("nobootstrap=true")
            // Channels up to 0.5 BTC can be opened to this wallet.
            appendLine("maxchansize=50000000")
            appendLine("[Bitcoin]")
            appendLine("bitcoin.mainnet=true")
            appendLine("bitcoin.node=neutrino")
            appendLine("[neutrino]")
            // Go's resolver finds no DNS server on Android (there is no /etc/resolv.conf), so a
            // peer given by name never connects: the app resolves names here, keeping the name too.
            resolved(peers).forEach { appendLine("neutrino.connect=$it") }
            // Room for every peer given: with fewer slots it drops and redials one every few seconds.
            appendLine("neutrino.maxpeers=${maxOf(4, peers.size + 2)}")
            appendLine("[fee]")
            appendLine("fee.url=$FEE_URL")
            appendLine("[protocol]")
            appendLine("protocol.wumbo-channels=true")
            // Channels can take more funds or give some back without closing (BOLT 2 splicing).
            // From the first start with it the channel database can't go back to a build without it.
            appendLine("protocol.splice=true")
            appendLine("[wtclient]")
            appendLine("wtclient.active=true")
            if (torSocks != null) {
                appendLine("[tor]")
                appendLine("tor.active=true")
                appendLine("tor.socks=127.0.0.1:$torSocks")
                appendLine("tor.skip-proxy-for-clearnet-targets=true")
            }
        }
        File(d, "lnd.conf").writeText(conf)
    }

    /** [peers] with each host name also given as the addresses Android resolves it to. */
    fun resolved(peers: List<String>): List<String> = peers.flatMap { p ->
        val host = p.substringBeforeLast(':'); val port = p.substringAfterLast(':')
        if (host.any { it.isLetter() } && !host.endsWith(".onion")) {
            val ips = runCatching { java.net.InetAddress.getAllByName(host).map { it.hostAddress!! } }.getOrDefault(emptyList())
            ips.map { if (':' in it) "[$it]:$port" else "$it:$port" } + p
        } else listOf(p)
    }.distinct()

    /** The end of LND's log, to share when something fails. It names peers and channels, never keys. */
    fun logTail(ctx: Context, maxBytes: Long = 80_000): String? = runCatching {
        val f = File(dir(ctx), "logs/bitcoin/mainnet/lnd.log")
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(r.length(), maxBytes); r.seek(r.length() - n)
            val b = ByteArray(n.toInt()); r.readFully(b); String(b, Charsets.UTF_8).substringAfter('\n')
        }
    }.getOrNull()

    /** Starts the node (once per process); returns when its RPC answers. */
    suspend fun start(ctx: Context) {
        if (running) return
        val d = dir(ctx)
        suspendCancellableCoroutine<Unit> { cont ->
            Lndmobile.start("--lnddir=${d.absolutePath} --configfile=${File(d, "lnd.conf").absolutePath}", object : Callback {
                override fun onError(e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(b: ByteArray?) { running = true; if (cont.isActive) cont.resume(Unit) }
            })
        }
    }

    /** The node stopped on its own (it logs why): the next [start] starts it again. */
    fun markStopped() { running = false }

    /**
     * How far the chain sync has got while LND does not answer yet (its RPC only opens once
     * neutrino has caught up): the highest block height in the end of its log, or null.
     */
    fun syncHeight(ctx: Context): Int? = runCatching {
        val f = File(dir(ctx), "logs/bitcoin/mainnet/lnd.log")
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(r.length(), 48_000L)
            r.seek(r.length() - n)
            val b = ByteArray(n.toInt()); r.readFully(b)
            Regex("""height[= ](\d{3,7})""").findAll(String(b, Charsets.UTF_8)).maxOfOrNull { it.groupValues[1].toInt() }
        }
    }.getOrNull()

    /** The last error LND logged, to show when it does not come up. */
    fun lastError(ctx: Context): String? = runCatching {
        File(dir(ctx), "logs/bitcoin/mainnet/lnd.log").readLines().lastOrNull { "[ERR]" in it }
            ?.substringAfter("[ERR] ")?.substringAfter(": ")
    }.getOrNull()

    /** LND was stopped in this process: it cannot be started again until the app restarts. */
    @Volatile var stoppedThisRun = false
        private set

    suspend fun stop() {
        if (!running) return
        runCatching { call { Lndmobile.stopDaemon(ByteArray(0), it) } }
        running = false; stoppedThisRun = true
    }

    /** One unary call: [f] gets the callback to hand to Lndmobile. */
    // A call LND never answers (it happens while it is still starting) must not hang the caller
    // for good: the LN tab's refresh loop stopped there until the tab was opened again.
    suspend fun call(timeoutMs: Long = 60_000, f: (Callback) -> Unit): PbMsg =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<PbMsg> { cont ->
                f(object : Callback {
                    override fun onError(e: Exception) { if (cont.isActive) cont.resumeWithException(LndError(e.message ?: "LND error")) }
                    override fun onResponse(b: ByteArray?) { if (cont.isActive) cont.resume(PbMsg.parse(b ?: ByteArray(0))) }
                })
            }
        } ?: throw LndError("the node did not answer")

    /** A server stream: [onMsg] for each message, [onEnd] when it ends or fails. */
    fun stream(f: (RecvStream) -> Unit, onMsg: (PbMsg) -> Unit, onEnd: (Exception?) -> Unit) {
        f(object : RecvStream {
            override fun onError(e: Exception) { onEnd(if (e.message?.contains("EOF") == true) null else e) }
            override fun onResponse(b: ByteArray?) { onMsg(PbMsg.parse(b ?: ByteArray(0))) }
        })
    }

    // ---------------------------------------------------------------- wallet lifecycle

    /** lnrpc.WalletState: what the node needs before its main RPC works. */
    enum class WalletState { NON_EXISTING, LOCKED, UNLOCKED, RPC_ACTIVE, SERVER_ACTIVE, WAITING_TO_START, UNKNOWN }

    suspend fun state(): WalletState = runCatching {
        when (call { Lndmobile.getState(ByteArray(0), it) }.int(1)) {
            0 -> WalletState.NON_EXISTING; 1 -> WalletState.LOCKED; 2 -> WalletState.UNLOCKED
            3 -> WalletState.RPC_ACTIVE; 4 -> WalletState.SERVER_ACTIVE; 255 -> WalletState.WAITING_TO_START
            else -> WalletState.UNKNOWN
        }
    }.getOrDefault(WalletState.UNKNOWN)

    /**
     * Creates the node's wallet from [xprv], the Lightning master key worked out from the
     * recovery words ([LnSeed.masterXprv]), encrypted on disk with [password].
     * [birthday] (unix seconds) lets the first scan start late instead of at genesis.
     */
    suspend fun initWallet(password: ByteArray, xprv: String, birthday: Long, channelBackup: ByteArray? = null) {
        val req = Pb().bytes(1, password).string(7, xprv).int(8, birthday).int(4, 2500)
        // channel_backups (5): ChanBackupSnapshot.multi_chan_backup (2).multi_chan_backup (2).
        if (channelBackup != null) req.msg(5, Pb().msg(2, Pb().bytes(2, channelBackup)))
        call { Lndmobile.initWallet(req.build(), it) }
    }

    suspend fun unlock(password: ByteArray) {
        call { Lndmobile.unlockWallet(Pb().bytes(1, password).build(), it) }
    }

    // ---------------------------------------------------------------- reads

    data class Info(
        val pubkey: String, val alias: String, val height: Int, val syncedChain: Boolean,
        val syncedGraph: Boolean, val peers: Int, val active: Int, val pending: Int, val headerTime: Long,
    )

    suspend fun info(): Info {
        val m = call { Lndmobile.getInfo(ByteArray(0), it) }
        return Info(m.string(1), m.string(2), m.int(6), m.bool(9), m.bool(18), m.int(5), m.int(4), m.int(3), m.long(13))
    }

    data class Balances(val onchainConfirmed: Long, val onchainUnconfirmed: Long, val channels: Long, val pendingOpen: Long)

    suspend fun balances(): Balances {
        val w = call { Lndmobile.walletBalance(ByteArray(0), it) }
        val c = call { Lndmobile.channelBalance(ByteArray(0), it) }
        return Balances(w.long(2), w.long(3), c.msg(3).long(1), c.msg(7).long(1))
    }

    suspend fun newAddress(): String =
        call { Lndmobile.newAddress(Pb().int(1, 4).build(), it) }.string(1)   // 4 = taproot (p2tr)

    data class Channel(
        val active: Boolean, val peer: String, val point: String, val capacity: Long,
        val local: Long, val remote: Long, val peerAlias: String, val chanId: Long = 0,
        /** Whether a splice can start now: "ok", or why not (lnrpc Channel.splice_status). */
        val spliceStatus: String = "",
        /** The splice in progress, if any. */
        val splice: Splice? = null,
    )

    /** A splice in progress (lnrpc.PendingSplice). */
    data class Splice(
        val state: String, val localDelta: Long, val txid: String, val confirmations: Int, val lockDepth: Int,
        val dead: Boolean, val relayStatus: String, val newCapacity: Long, val fee: Long = 0,
    )

    suspend fun channels(): List<Channel> =
        call { Lndmobile.listChannels(Pb().bool(6, true).build(), it) }.msgs(11).map {
            val sp = if (it.has(1003)) it.msg(1003).let { p ->
                Splice(p.string(2), p.long(3), p.string(8), p.int(10), p.int(11), p.bool(14), p.string(15), p.long(18), p.long(5))
            } else null
            Channel(it.bool(1), it.string(2), it.string(3), it.long(5), it.long(6), it.long(7), it.string(34), it.long(4),
                it.string(1002), sp)
        }

    /** What a splice would do (lnrpc.EstimateSpliceResponse); [refusal] is empty when it can go ahead. */
    data class SpliceEstimate(
        val fee: Long, val satPerVbyte: Long, val amount: Long, val minSats: Long, val maxIn: Long, val maxOut: Long,
        val minSatPerVbyte: Long, val newCapacity: Long, val newLocal: Long, val lockDepth: Int, val refusal: String,
    )

    private fun spliceReq(point: String, out: Boolean, sats: Long?, satPerVbyte: Long, addr: String?): Pb {
        val b = Pb().msg(1, Pb().string(2, point.substringBefore(':')).int(3, point.substringAfter(':').toInt()))
            .int(2, if (out) 1 else 0)
        if (sats == null) b.bool(4, true) else b.int(3, sats)
        b.int(5, satPerVbyte)
        if (out && !addr.isNullOrBlank()) b.string(7, addr.trim())
        return b
    }

    /** [sats] into the channel from the node's wallet, or out of it ([out]); null = as much as possible. */
    suspend fun estimateSplice(point: String, out: Boolean, sats: Long?, satPerVbyte: Long, addr: String? = null): SpliceEstimate {
        val r = call { Lndmobile.estimateSplice(spliceReq(point, out, sats, satPerVbyte, addr).build(), it) }
        return SpliceEstimate(r.long(1), r.long(3), r.long(4), r.long(5), r.long(6), r.long(7), r.long(8),
            r.long(11), r.long(12), r.int(13), r.string(15))
    }

    /** Starts the splice; returns its transaction id once both nodes have signed it, or throws with why not. */
    suspend fun splice(point: String, out: Boolean, sats: Long?, satPerVbyte: Long, addr: String? = null): String =
        suspendCancellableCoroutine { cont ->
            val req = spliceReq(point, out, sats, satPerVbyte, addr).build()
            stream({ Lndmobile.spliceChannel(req, it) }, onMsg = { u ->
                when (u.string(1)) {
                    "signed", "broadcast", "confirmed", "locked" -> if (u.string(2).isNotEmpty() && cont.isActive) cont.resume(u.string(2))
                    "aborted" -> if (cont.isActive) cont.resumeWithException(LndError(
                        (if (u.bool(6)) "the other node said: " else "") + u.string(5).ifEmpty { "the splice was aborted" }))
                }
            }, onEnd = { e -> if (cont.isActive) cont.resumeWithException(e ?: LndError("the splice ended without an answer")) })
        }

    data class Pending(val peer: String, val point: String, val capacity: Long, val local: Long, val confirmationsLeft: Int)

    suspend fun pendingOpen(): List<Pending> =
        call { Lndmobile.pendingChannels(ByteArray(0), it) }.msgs(2).map { p ->
            val ch = p.msg(1)
            Pending(ch.string(1), ch.string(2), ch.long(3), ch.long(4), p.int(7))
        }

    /** A channel being closed: [note] says how far (waiting for the close, or locked until a block). */
    data class Closing(val peer: String, val capacity: Long, val limbo: Long, val note: String, val txid: String,
                       /** A cooperative close waiting for its transaction: one that can be sped up. */
                       val waiting: Boolean = false)

    suspend fun pendingClose(): List<Closing> {
        val m = call { Lndmobile.pendingChannels(ByteArray(0), it) }
        val waiting = m.msgs(5).map { w ->
            val ch = w.msg(1)
            Closing(ch.string(1), ch.long(3), w.long(2), "closing · waiting for the closing transaction to confirm", w.string(4), waiting = true)
        }
        val forced = m.msgs(4).map { f ->
            val ch = f.msg(1)
            val left = f.int(5)
            Closing(ch.string(1), ch.long(3), f.long(3),
                if (left > 0) "force closed · your sats unlock in $left blocks" else "force closed · sweeping your sats", f.string(2))
        }
        return waiting + forced
    }

    /** A coin of the node paid by [txid] and still unconfirmed: what a speed-up (CPFP) spends. */
    data class CloseCoin(val txid: String, val index: Int, val sats: Long)

    suspend fun unconfirmedCoinOf(txid: String): CloseCoin? =
        call { Lndmobile.walletKitListUnspent(Pb().int(1, 0).int(2, 0).build(), it) }.msgs(1)
            .map { u -> u to u.msg(5) }
            .firstOrNull { (_, o) -> o.string(2) == txid }
            ?.let { (u, o) -> CloseCoin(txid, o.int(3), u.long(3)) }

    /**
     * Speeds up a cooperative close that pays the node, by spending the node's output of it with
     * a child (CPFP) whose fee lifts the two together to [targetRate] sat/vB. The close spends
     * only the funding output, so its fee is [capacity] minus its outputs. The sweeper's budget
     * is the child's fee, so it never pays more than this. Returns the child's fee rate.
     */
    suspend fun speedUpClose(txid: String, capacity: Long, targetRate: Long): Long {
        val coin = unconfirmedCoinOf(txid) ?: throw LndError("the node has no unconfirmed coin from this close (it may pay another wallet, or be confirmed)")
        val raw = call { Lndmobile.getTransactions(Pb().int(1, 0).int(2, -1).build(), it) }.msgs(1)
            .firstOrNull { it.string(1) == txid }?.string(9) ?: throw LndError("the node does not know this transaction")
        val parent = TxSize.of(raw)
        val parentFee = capacity - parent.outputs
        if (parentFee <= 0 || parentFee > capacity / 2) throw LndError("could not work out the close's fee")
        val childRate = cpfpChildRate(targetRate, parent.vsize, parentFee, CPFP_CHILD_VSIZE)
        val budget = childRate * CPFP_CHILD_VSIZE + 20
        if (budget >= coin.sats / 2) throw LndError("that fee would take too much of the coin")
        val out = Pb().bytes(1, txid.chunked(2).map { it.toInt(16).toByte() }.toByteArray().reversedArray()).int(3, coin.index)
        call { Lndmobile.walletKitBumpFee(Pb().msg(1, out).int(5, childRate).bool(6, true).int(7, budget).build(), it) }
        return childRate
    }

    /**
     * Speeds up any unconfirmed transaction of the node that pays it change (a channel opening,
     * an on-chain payment, a resize), the same way: a child spending the node's output of it whose
     * fee lifts the two to [targetRate] sat/vB. [knownFee] is the parent's fee when the wallet
     * can't tell it (a resize spends the channel's output too); otherwise the wallet's record.
     */
    suspend fun speedUpTx(txid: String, targetRate: Long, knownFee: Long? = null): Long {
        val coin = unconfirmedCoinOf(txid) ?: throw LndError("the node has no unconfirmed coin from this transaction (no change, or already confirmed)")
        val tx = call { Lndmobile.getTransactions(Pb().int(1, 0).int(2, -1).build(), it) }.msgs(1)
            .firstOrNull { it.string(1) == txid } ?: throw LndError("the node does not know this transaction")
        val parent = TxSize.of(tx.string(9))
        val parentFee = knownFee ?: tx.long(7)
        if (parentFee <= 0) throw LndError("could not work out the transaction's fee")
        val childRate = cpfpChildRate(targetRate, parent.vsize, parentFee, CPFP_CHILD_VSIZE)
        val budget = childRate * CPFP_CHILD_VSIZE + 20
        if (budget >= coin.sats / 2) throw LndError("that fee would take too much of the coin")
        val out = Pb().bytes(1, txid.chunked(2).map { it.toInt(16).toByte() }.toByteArray().reversedArray()).int(3, coin.index)
        call { Lndmobile.walletKitBumpFee(Pb().msg(1, out).int(5, childRate).bool(6, true).int(7, budget).build(), it) }
        return childRate
    }

    data class PayReq(val destination: String, val hash: String, val sats: Long, val description: String, val expiry: Long, val timestamp: Long,
                      /** Sets option_blake2b (feature 512 or 513): an invoice of this chain, not of the SHA-256 one. */
                      val blake2b: Boolean = true,
                      /** The exact amount, in millisatoshis (num_msat). */
                      val msat: Long = sats * 1000,
                      /** Blocks the payee asks for its own timelock (cltv_expiry): how long a held payment can stay held. */
                      val cltv: Long = 18,
                      /** A BOLT 12 offer (lno1…) rather than an invoice: paid with [payOffer]. */
                      val offer: String? = null)

    suspend fun decode(invoice: String): PayReq {
        val m = call { Lndmobile.decodePayReq(Pb().string(1, invoice.trim()).build(), it) }
        // features (13) is a map<uint32, Feature>: each entry carries its bit as key (1).
        val bits = m.msgs(13).map { it.long(1) }.toSet()
        // num_msat (12): the bridge's hold invoices are not whole sats.
        return PayReq(m.string(1), m.string(2), m.long(3), m.string(6), m.long(5), m.long(4),
            blake2b = 512L in bits || 513L in bits, msat = m.long(12), cltv = m.long(9))
    }

    /**
     * Reads a BOLT 12 offer (offersrpc.DecodeBolt12) as something to pay. An offer without an
     * amount comes back with sats 0, like an invoice without one.
     */
    suspend fun decodeOffer(offer: String): PayReq {
        val m = call { Lndmobile.offersDecodeBolt12(Pb().string(1, offer.trim()).build(), it) }
        // DecodeBolt12Response: type=1, for_this_chain=2, offer=5, valid=8, validation_error=9, ours=10.
        if (m.string(1) != "offer") throw LndError("this is not a BOLT 12 offer (lno1…)")
        if (!m.bool(2)) throw LndError("this offer is for another chain, not for Bitcoin (BLAKE2b)")
        if (!m.bool(8)) throw LndError("this offer can't be paid: " + m.string(9).ifBlank { "it is not valid" })
        if (m.bool(10)) throw LndError("this offer is your own node's")
        // Offer: description=3, amount_msat=4, absolute_expiry=5, issuer=13.
        val o = m.msg(5)
        val msat = o.long(4)
        return PayReq(o.string(13).ifBlank { "BOLT 12 offer" }, "", (msat + 999) / 1000, o.string(3), o.long(5), 0,
            msat = msat, offer = offer.trim())
    }

    /**
     * Pays a BOLT 12 offer (offersrpc.PayOffer): the node asks the issuer for an invoice over
     * onion messages and pays it. [amountSats] only for an offer without an amount. Returns the
     * preimage, or throws with why it failed.
     */
    suspend fun payOffer(offer: String, feeLimitSats: Long, timeoutSeconds: Int = 60, amountSats: Long? = null): String {
        // PayOfferRequest: offer=1, amount_msat=3, timeout_seconds=6 (each for the invoice and the
        // payment), fee_limit_msat=7.
        val b = Pb().string(1, offer.trim()).int(6, timeoutSeconds).int(7, feeLimitSats * 1000)
        amountSats?.let { b.int(3, it * 1000) }
        val first = runCatching { payOfferOnce(b.build(), timeoutSeconds) }
        val e = first.exceptionOrNull() ?: return first.getOrThrow()
        // No route right after the phone reconnects: the channel isn't active yet on this side. The
        // invoice was already fetched; pay that one again once, a little later (LND names it in
        // the reason: "retry with this invoice, not the offer: lni1…").
        val invoice = Regex("lni1[0-9a-z]+").find(e.message ?: "")?.value
        if (invoice == null || "no_route" !in (e.message ?: "")) throw e
        kotlinx.coroutines.delay(15_000)
        return payOfferOnce(Pb().string(2, invoice).int(6, timeoutSeconds).int(7, feeLimitSats * 1000).build(), timeoutSeconds)
    }

    private suspend fun payOfferOnce(req: ByteArray, timeoutSeconds: Int): String {
        val m = call(timeoutMs = (timeoutSeconds * 2 + 30) * 1000L) { Lndmobile.offersPayOffer(req, it) }
        // PayOfferResponse: payment_preimage=3.
        return m.bytes(3).joinToString("") { "%02x".format(it) }
    }

    /**
     * This node's reusable BOLT 12 offer, without an amount: each payer chooses how much
     * (offersrpc.CreateOffer). The same fields always mint the same offer, so asking again
     * returns the one already made. Blinded paths are asked for: this node is not announced, and
     * payers reach it through its peer.
     */
    suspend fun reusableOffer(description: String): String {
        // CreateOfferRequest: description=1, label=6, with_paths=7. CreateOfferResponse: offer=1 (Offer: bolt12=2).
        val m = call { Lndmobile.offersCreateOffer(Pb().string(1, description).string(6, "kilowallet: reusable").bool(7, true).build(), it) }
        return m.msg(1).string(2).ifBlank { throw LndError("the node gave no offer") }
    }

    /** Follows a payment already under way (routerrpc.TrackPaymentV2) to its end, like [pay]. */
    suspend fun track(hashHex: String): String =
        suspendCancellableCoroutine { cont ->
            val hash = hashHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            // routerrpc.TrackPaymentRequest: payment_hash=1.
            stream({ Lndmobile.routerTrackPaymentV2(Pb().bytes(1, hash).build(), it) }, onMsg = { p ->
                when (p.int(10)) {
                    2 -> if (cont.isActive) cont.resume(p.string(6))
                    3 -> if (cont.isActive) cont.resumeWithException(LndError(failureText(p.int(16))))
                }
            }, onEnd = { e -> if (cont.isActive) cont.resumeWithException(e ?: LndError("the payment ended without an answer")) })
        }

    /** What a payment error means to someone paying, when the node's own words would not say. */
    fun payError(e: Throwable): String {
        val raw = e.message ?: "failed"
        return when {
            "option_blake2b" in raw -> SHA256_INVOICE
            // LND keeps a payment it could not end yet (a part of it is held somewhere on the way).
            "in transition" in raw || "AlreadyExists" in raw ->
                "This invoice is already being paid: the payment is still on its way. Wait for it to end; " +
                    "if it fails, the sats come back to your channel."
            "already paid" in raw || "AlreadySucceeded" in raw -> "This invoice was already paid."
            // An offer's payment that failed: LND adds the whole fetched invoice (lni1…) to its
            // reason, a wall of text nobody can read.
            "no_route" in raw || "NO_ROUTE" in raw -> "Not paid: " + failureText(2)
            "insufficient_balance" in raw || "INSUFFICIENT_BALANCE" in raw -> "Not paid: " + failureText(5)
            "timeout" in raw && "payment failed" in raw -> "Not paid: " + failureText(1)
            else -> "Not paid: " + raw.substringBefore("; retry with this invoice").let { if (it.length > 200) it.take(200) + "…" else it }
        }
    }

    const val SHA256_INVOICE = "This invoice is for Lightning on the SHA-256 chain, which this node does not pay " +
        "directly. Paste it in PAY and read it: Kilombino's bridge can pay it with BTC. Nothing was sent."

    // ---------------------------------------------------------------- actions

    /** Renames the running node now (peersrpc UpdateNodeAnnouncement): GetInfo and the next node announcement use [alias]. */
    suspend fun rename(alias: String) {
        call { Lndmobile.peersUpdateNodeAnnouncement(Pb().string(3, alias).build(), it) }
    }

    suspend fun connect(pubkey: String, host: String) {
        runCatching {
            call { Lndmobile.connectPeer(Pb().msg(1, Pb().string(1, pubkey).string(2, host)).int(3, 30).build(), it) }
        }.onFailure { if (it.message?.contains("already connected") != true) throw it }
    }

    /** Opens a public channel of [sats] to [pubkey] from the node's on-chain coins; returns the funding txid. */
    /**
     * Channels from a phone are private (not announced): nobody routes through it, so a phone
     * that goes off mid-payment never leaves someone else's payment hanging on it (which ends
     * in a force close). Invoices then carry route hints so the channel can still receive.
     */
    suspend fun openChannel(pubkey: String, sats: Long, satPerVbyte: Long, private: Boolean = true,
                            coins: List<String> = emptyList()): String {
        // Coin control (outpoints, 28): only those coins fund it. With coins and no amount, all
        // of them go in (fund_max, 26), minus the fee.
        val all = coins.isNotEmpty() && sats <= 0
        val req = Pb().string(3, pubkey).int(4, if (all) 0 else sats).int(1, satPerVbyte).bool(8, private).bool(26, all)
        coins.forEach { c -> req.msg(28, Pb().string(2, c.substringBefore(':')).int(3, c.substringAfter(':').toInt())) }
        val m = call { Lndmobile.openChannelSync(req.build(), it) }
        // ChannelPoint: funding_txid_bytes (1, little-endian) or funding_txid_str (2).
        val str = m.string(2)
        return if (str.isNotEmpty()) str else m.bytes(1).reversedArray().joinToString("") { "%02x".format(it) }
    }

    /**
     * Opens a channel of [sats] spending ALL of [coins] ("txid:vout"), the change back as one
     * coin: LND's own selection would take only what it needs from a list, so the funding
     * transaction is built here (PSBT funding: LND gives the channel address, FundPsbt spends
     * exactly these coins, LND checks it, the wallet signs, LND publishes). On any failure the
     * pending channel is cancelled and the coins released. Returns the funding txid.
     */
    suspend fun openChannelAllCoins(pubkey: String, sats: Long, satPerVbyte: Long, coins: List<String>): String {
        val pendingId = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val leases = java.util.Collections.synchronizedList(ArrayList<PbMsg>())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        suspend fun cleanUp() {
            runCatching { call { Lndmobile.fundingStateStep(Pb().msg(2, Pb().bytes(1, pendingId)).build(), it) } }
            leases.forEach { l -> runCatching { call { Lndmobile.walletKitReleaseOutput(Pb().bytes(1, l.bytes(1)).bytes(2, l.bytes(2)).build(), it) } } }
        }
        return try {
            suspendCancellableCoroutine { cont ->
                // The streaming OpenChannel wants the key as bytes (node_pubkey, 2); only the
                // synchronous one still reads the old hex field.
                val pk = pubkey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val req = Pb().bytes(2, pk).string(3, pubkey).int(4, sats).bool(8, true)
                    .msg(14, Pb().msg(2, Pb().bytes(1, pendingId)))
                var funding = false
                stream({ Lndmobile.openChannel(req.build(), it) }, onMsg = { u ->
                    if (u.has(5) && !funding) {
                        funding = true
                        val f = u.msg(5)
                        scope.launch {
                            runCatching {
                                val tmpl = Pb()
                                coins.forEach { c -> tmpl.msg(1, Pb().string(2, c.substringBefore(':')).int(3, c.substringAfter(':').toInt())) }
                                tmpl.mapEntry(2, f.string(1), f.long(2))
                                val funded = call {
                                    Lndmobile.walletKitFundPsbt(Pb().msg(2, tmpl).int(4, satPerVbyte).int(6, 1).int(8, 1).build(), it)
                                }
                                leases.addAll(funded.msgs(3))
                                val psbt = funded.bytes(1)
                                call { Lndmobile.fundingStateStep(Pb().msg(3, Pb().bytes(1, psbt).bytes(2, pendingId)).build(), it) }
                                val signed = call { Lndmobile.walletKitFinalizePsbt(Pb().bytes(1, psbt).build(), it) }.bytes(1)
                                call { Lndmobile.fundingStateStep(Pb().msg(4, Pb().bytes(1, signed).bytes(2, pendingId)).build(), it) }
                            }.onFailure { e -> if (cont.isActive) cont.resumeWithException(e) }
                        }
                    } else if (u.has(1)) {
                        val txid = u.msg(1).bytes(1).reversedArray().joinToString("") { "%02x".format(it) }
                        if (cont.isActive) cont.resume(txid)
                    }
                }, onEnd = { e -> if (cont.isActive) cont.resumeWithException(e ?: LndError("the channel open ended without an answer")) })
            }
        } catch (e: Exception) { cleanUp(); throw e }
    }

    /** Sends the node's on-chain coins to [address]; [all] sweeps everything. Returns the txid. */
    /** An on-chain transaction of the node: [sats] its effect (+ in, − out), [label] LND's note. */
    data class ChainTx(val txid: String, val sats: Long, val confirmations: Int, val time: Long, val fee: Long, val label: String)

    suspend fun transactions(): List<ChainTx> =
        call { Lndmobile.getTransactions(Pb().int(1, 0).int(2, -1).build(), it) }.msgs(1).map {
            ChainTx(it.string(1), it.long(2), it.int(3), it.long(6), it.long(7), it.string(10))
        }.sortedByDescending { it.time }

    /**
     * Confirmations of [txids] from the chain explorer. A light wallet only follows transactions
     * that pay its own addresses, so one that pays elsewhere (a channel closed to the BTC
     * wallet) stays "unconfirmed" in LND for good; the explorer knows better.
     */
    fun explorerConfirmations(txids: List<String>): Map<String, Int> {
        if (txids.isEmpty()) return emptyMap()
        fun get(path: String): String {
            val c = (java.net.URL("https://mempool.kilombino.com/api$path").openConnection() as java.net.HttpURLConnection)
                .apply { connectTimeout = 10_000; readTimeout = 10_000 }
            return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        }
        val tip = runCatching { get("/blocks/tip/height").trim().toInt() }.getOrNull() ?: return emptyMap()
        return txids.associateWith { id ->
            runCatching {
                val o = org.json.JSONObject(get("/tx/$id/status"))
                if (o.optBoolean("confirmed")) tip - o.optInt("block_height") + 1 else 0
            }.getOrDefault(0)
        }
    }

    /** Closed channels by closing txid: what the node got out of each (its settled balance). */
    suspend fun closedSettled(): Map<String, Long> =
        call { Lndmobile.closedChannels(ByteArray(0), it) }.msgs(1).associate { it.string(4) to it.long(8) }

    /** One of the node's on-chain coins. [outpoint] is "txid:vout". */
    data class Utxo(val outpoint: String, val address: String, val sats: Long, val confirmations: Long)

    suspend fun utxos(): List<Utxo> =
        call { Lndmobile.listUnspent(Pb().int(1, 0).int(2, Int.MAX_VALUE).build(), it) }.msgs(1).map { u ->
            val op = u.msg(5)
            Utxo("${op.string(2)}:${op.int(3)}", u.string(2), u.long(3), u.long(6))
        }.sortedByDescending { it.sats }

    /**
     * Sends the node's on-chain coins to [address]: [sats], or everything chosen with [all].
     * [coins] ("txid:vout") limits it to those coins (coin control); empty lets LND choose.
     * LND keeps back what anchor channels need for fee bumping. Returns the txid.
     */
    suspend fun sendCoins(address: String, sats: Long, satPerVbyte: Long, all: Boolean = false, coins: List<String> = emptyList()): String =
        call {
            val req = Pb().string(1, address).int(2, if (all) 0 else sats).int(4, satPerVbyte).bool(6, all)
                .string(7, "Kilowallet")
            if (coins.isNotEmpty()) req.bool(9, true)
            coins.forEach { c ->
                req.msg(11, Pb().string(2, c.substringBefore(':')).int(3, c.substringAfter(':').toInt()))
            }
            Lndmobile.sendCoins(req.build(), it)
        }.string(1)

    /** An invoice and its payment hash (hex), to follow it until it is paid. */
    suspend fun addInvoiceWithHash(sats: Long, memo: String, expirySeconds: Long = 3600): Pair<String, String> {
        val m = call {
            Lndmobile.addInvoice(Pb().string(1, memo).int(5, sats).int(11, expirySeconds).bool(15, true).build(), it)
        }
        return m.string(2) to m.bytes(1).joinToString("") { "%02x".format(it) }
    }

    /** lnrpc.Invoice.state: 0 open, 1 settled, 2 canceled, 3 accepted. */
    suspend fun invoiceState(rHashHex: String): Int {
        val h = rHashHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return call { Lndmobile.lookupInvoice(Pb().bytes(2, h).build(), it) }.int(21)
    }

    data class Invoice(val memo: String, val sats: Long, val paid: Long, val created: Long, val settled: Long, val state: Int)

    suspend fun invoices(max: Int = 30): List<Invoice> =
        call { Lndmobile.listInvoices(Pb().int(5, max).bool(6, true).build(), it) }.msgs(1).map {
            Invoice(it.string(1), it.long(5), it.long(19), it.long(7), it.long(8), it.int(21))
        }.sortedByDescending { it.created }

    /**
     * Invoices as they get paid (lnrpc.SubscribeInvoices), for as long as the node runs: those
     * settled after [settleIndex] first, then each new one. BOLT 12 offers' invoices too.
     */
    fun subscribeSettled(settleIndex: Long, onSettled: (memo: String, sats: Long, settleIndex: Long, hashHex: String) -> Unit,
                         onEnd: (Exception?) -> Unit) =
        // InvoiceSubscription: settle_index=2. Invoice: memo=1, r_hash=4, settle_index=17,
        // amt_paid_sat=19, state=21 (1 settled).
        stream({ Lndmobile.subscribeInvoices(Pb().int(2, settleIndex).build(), it) }, onMsg = { inv ->
            if (inv.int(21) == 1) onSettled(inv.string(1), inv.long(19), inv.long(17), inv.bytes(4).joinToString("") { "%02x".format(it) })
        }, onEnd = onEnd)

    data class Payment(val sats: Long, val fee: Long, val timeSec: Long, val status: Int, val hash: String)

    suspend fun payments(max: Int = 30): List<Payment> =
        call { Lndmobile.listPayments(Pb().int(3, max).bool(4, true).build(), it) }.msgs(1).map {
            Payment(it.long(7), it.long(11), it.long(13) / 1_000_000_000, it.int(10), it.string(1))
        }.sortedByDescending { it.timeSec }

    /**
     * Closes a channel ("txid:index"): cooperatively unless [force] (only for a peer that is
     * gone: the money is then locked for the channel's delay). [delivery] sends a cooperative
     * close straight to that address; blank, it goes to the node's on-chain wallet. Returns the
     * closing transaction's id once it is published.
     */
    suspend fun closeChannel(point: String, force: Boolean, delivery: String?, satPerVbyte: Long): String =
        suspendCancellableCoroutine { cont ->
            val req = Pb().msg(1, Pb().string(2, point.substringBefore(':')).int(3, point.substringAfter(':').toInt()))
                .bool(2, force)
            if (!force) {
                req.int(6, satPerVbyte)
                if (!delivery.isNullOrBlank()) req.string(5, delivery.trim())
            }
            stream({ Lndmobile.closeChannel(req.build(), it) }, onMsg = { u ->
                if (u.has(1)) {
                    val txid = u.msg(1).bytes(1).reversedArray().joinToString("") { "%02x".format(it) }
                    if (cont.isActive) cont.resume(txid)
                }
            }, onEnd = { e -> if (cont.isActive) cont.resumeWithException(e ?: LndError("the close ended without an answer")) })
        }

    suspend fun addInvoice(sats: Long, memo: String, expirySeconds: Long = 3600): String =
        call {
            // private (15): route hints for the private channels, or nobody finds a way here.
            Lndmobile.addInvoice(Pb().string(1, memo).int(5, sats).int(11, expirySeconds).bool(15, true).build(), it)
        }.string(2)

    /**
     * Pays [invoice] (routerrpc.SendPaymentV2); returns the preimage, or throws with why it failed.
     * [amountSats]: only for an invoice without an amount (LND refuses it on one that has its own).
     */
    suspend fun pay(invoice: String, feeLimitSats: Long, timeoutSeconds: Int = 60, outgoingChanId: Long? = null,
                    amountSats: Long? = null): String =
        suspendCancellableCoroutine { cont ->
            // routerrpc.SendPaymentRequest: amt=2, payment_request=5, timeout_seconds=6, fee_limit_sat=7,
            // outgoing_chan_ids=19 (only through that channel).
            val b = Pb().string(5, invoice.trim()).int(6, timeoutSeconds).int(7, feeLimitSats)
            amountSats?.let { b.int(2, it) }
            outgoingChanId?.let { b.int(19, it) }
            val req = b.build()
            stream({ Lndmobile.routerSendPaymentV2(req, it) }, onMsg = { p ->
                // lnrpc.Payment: status=10 (2 SUCCEEDED, 3 FAILED), payment_preimage=6, failure_reason=16.
                when (p.int(10)) {
                    2 -> if (cont.isActive) cont.resume(p.string(6))
                    3 -> if (cont.isActive) cont.resumeWithException(LndError(failureText(p.int(16))))
                }
            }, onEnd = { e -> if (cont.isActive) cont.resumeWithException(e ?: LndError("the payment ended without an answer")) })
        }

    private fun failureText(code: Int) = when (code) {
        1 -> "timed out without finding a route"
        2 -> "no route to the destination with enough liquidity"
        3 -> "an unexpected error"
        4 -> "the invoice was refused by the destination (wrong amount, expired or already paid)"
        5 -> "not enough balance in your channels"
        6 -> "the payment was cancelled"
        else -> "the payment failed"
    }

    /** Adds a watchtower ([pubkeyHex]@[address]); already added is fine. */
    suspend fun addTower(pubkeyHex: String, address: String) {
        val pk = pubkeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        runCatching { call { Lndmobile.watchtowerClientAddTower(Pb().bytes(1, pk).string(2, address).build(), it) } }
            .onFailure { if (it.message?.contains("already", true) != true) throw it }
    }

    /**
     * Hands a channel backup (a channel.backup file) to the running node: for each channel in
     * it, the node asks the peer to force close and the money comes back on-chain.
     */
    suspend fun restoreChannelBackups(multi: ByteArray) {
        call(120_000) { Lndmobile.restoreChannelBackups(Pb().bytes(2, multi).build(), it) }
    }

    /** Signs [msg] with the node's key (lnrpc.SignMessage): zbase32, checkable with the node pubkey. */
    suspend fun signMessage(msg: String): String =
        call { Lndmobile.signMessage(Pb().bytes(1, msg.toByteArray(Charsets.UTF_8)).build(), it) }.string(1)

    /** The Static Channel Backup of every channel, to keep with the backup file. */
    suspend fun channelBackup(): ByteArray =
        call { Lndmobile.exportAllChannelBackups(ByteArray(0), it) }.msg(2).bytes(2)
}

class LndError(msg: String) : Exception(msg)
