package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.HeaderChain
import com.kilombino.pyblockwatch.chain.NodeRpcBackend
import com.kilombino.pyblockwatch.chain.Tor
import com.kilombino.pyblockwatch.data.Scanner
import com.kilombino.pyblockwatch.data.Store
import com.kilombino.pyblockwatch.joinblaket.ChainLookup
import com.kilombino.pyblockwatch.joinblaket.CommitmentStore
import com.kilombino.pyblockwatch.joinblaket.DirectoryClient
import com.kilombino.pyblockwatch.joinblaket.DirectoryException
import com.kilombino.pyblockwatch.joinblaket.ElectrumChainLookup
import com.kilombino.pyblockwatch.joinblaket.FileCommitmentStore
import com.kilombino.pyblockwatch.joinblaket.IoauthCheck
import com.kilombino.pyblockwatch.joinblaket.JoinBlaketDirectory
import com.kilombino.pyblockwatch.joinblaket.NickIdentity
import com.kilombino.pyblockwatch.joinblaket.RpcChainLookup
import com.kilombino.pyblockwatch.joinblaket.TakerCoin
import com.kilombino.pyblockwatch.joinblaket.TakerEnv
import com.kilombino.pyblockwatch.joinblaket.TakerPlan
import com.kilombino.pyblockwatch.joinblaket.TakerRequest
import com.kilombino.pyblockwatch.joinblaket.TakerResult
import com.kilombino.pyblockwatch.joinblaket.TakerSession
import com.kilombino.pyblockwatch.joinblaket.TakerStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * PAY THROUGH JOINBLAKET, the app's side: SEND hands over a payment ([start]), [JoinBlaketService]
 * runs it as a JoinMarket taker round ([TakerSession]) in the foreground, and [state] tells the
 * sheet and the notification where it is. The user confirms the plan (makers' fees and mining
 * fee) on the sheet before anything about the coins leaves the phone ([answer]).
 *
 * One round at a time. While it runs its coins are kept out of other payments ([lockedOutpoints])
 * and its change address is held ([heldChangeScripts]); the coins' keys live only in the request
 * and are dropped when the round ends.
 */
object JoinBlaketPay {
    /** What SEND hands over: the payment, the coins it may use (with their keys) and a fresh change address. */
    class Request(
        val recipient: String, val amount: Long, val feeRate: Double,
        val coins: List<TakerCoin>, val changeAddress: String, val changeScript: ByteArray,
    )

    /** The round as the sheet shows it. */
    data class View(
        val amount: Long,
        val recipient: String,
        val step: TakerStep? = null,
        /** Set while the plan waits for the user's answer. */
        val plan: TakerPlan? = null,
        val result: TakerResult? = null,
    ) {
        val finished: Boolean get() = result != null
        /** Signed and on its way: cancelling no longer stops it. */
        val pastReturn: Boolean get() = step == TakerStep.Broadcasting
    }

    private val _state = MutableStateFlow<View?>(null)
    val state: StateFlow<View?> = _state.asStateFlow()
    /** The sheet shows while a round is going (or just ended) unless the user hid it. */
    val sheetVisible = MutableStateFlow(false)

    @Volatile private var request: Request? = null
    @Volatile private var job: Job? = null
    @Volatile private var answer: CompletableDeferred<Boolean>? = null

    val running: Boolean get() = request != null && _state.value?.finished == false

    fun showSheet() { if (_state.value != null) sheetVisible.value = true }

    /** Hand a payment to the service. False if a round is already going. */
    fun start(ctx: Context, r: Request): Boolean {
        synchronized(this) {
            if (running) return false
            request = r
            _state.value = View(r.amount, r.recipient)
        }
        sheetVisible.value = true
        // No Tor on this phone (Arti ships for arm64 only): the directory is an onion.
        if (!Tor.available) { finish(TakerResult.Failed(NO_TOR)); return true }
        JoinBlaketService.start(ctx)
        return true
    }

    /** The user's answer to the plan. */
    fun answer(yes: Boolean) { answer?.complete(yes) }

    /** Stop the round, unless it is already signed and on its way. */
    fun cancel() {
        val v = _state.value ?: return
        if (v.finished || v.pastReturn) return
        answer?.complete(false)
        job?.cancel()
        finish(TakerResult.Cancelled(com.kilombino.pyblockwatch.joinblaket.TakerText.CANCELLED))
    }

    /**
     * The service is going away with the round unfinished (Android stopped it): cancelled, or,
     * if it was already signed and being broadcast, reported as possibly sent.
     */
    fun serviceStopped() {
        val v = _state.value ?: return
        if (v.finished) return
        if (!v.pastReturn) return cancel()
        job?.cancel()
        finish(TakerResult.Failed(com.kilombino.pyblockwatch.joinblaket.TakerText.NOT_BROADCAST, "the app was stopped while broadcasting"))
    }

    /** Forget a finished round (its sheet is closed). */
    fun dismiss() {
        if (_state.value?.finished == true) { _state.value = null; sheetVisible.value = false }
    }

    /** Coins of the round going: kept out of other payments and pools until it ends. */
    fun lockedOutpoints(): Set<String> =
        if (running) request?.coins?.map { "${it.txid}:${it.vout}" }?.toSet().orEmpty() else emptySet()

    /** The change address of the round going (and of one just sent, until the wallet sees it used). */
    fun heldChangeScripts(): List<ByteArray> = listOfNotNull(request?.changeScript)

    private fun finish(result: TakerResult) {
        synchronized(this) {
            val v = _state.value ?: return
            if (v.finished) return
            _state.value = v.copy(plan = null, result = result)
            // The keys go now; the change address stays held until the wallet has seen it.
            request = request?.let { r -> if (result is TakerResult.Sent) Request(r.recipient, r.amount, r.feeRate, emptyList(), r.changeAddress, r.changeScript) else null }
            answer = null; job = null
        }
        sheetVisible.value = true
    }

    /** Run the round handed over by [start] in [scope] (the service's); [onEnd] once it is over. */
    fun run(ctx: Context, scope: CoroutineScope, onEnd: () -> Unit): Job? {
        val r = request ?: return null
        if (job != null || _state.value?.finished != false) return null
        val app = ctx.applicationContext
        val j = scope.launch {
            val chain = AppChain(app)
            val result = try {
                val env = Env(app, chain)
                TakerSession(env).run(TakerRequest(r.recipient, r.amount, r.feeRate, r.coins, r.changeAddress))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TakerResult.Failed(CHAIN, e.message)
            } finally {
                chain.close()
            }
            finish(result)
        }
        j.invokeOnCompletion { onEnd() }
        job = j
        return j
    }

    const val NO_TOR = "Tor can't run on this phone, and the JoinBlaket directory is reached only through Tor."
    const val CHAIN = "Your BTC server could not be reached or its data did not verify. Nothing was sent."

    /** Where the used PoDLE commitments are kept (Python's `cmtdata/commitments.json`), in the app's backed-up files. */
    fun commitmentsFile(ctx: Context) = File(ctx.applicationContext.filesDir, "joinblaket/commitments.json")

    private class Env(private val ctx: Context, private val appChain: AppChain) : TakerEnv {
        override fun directory(identity: NickIdentity): DirectoryClient {
            Tor.start(ctx)
            val proxy = Tor.proxy(120_000) ?: throw DirectoryException("Tor did not come up: ${Tor.lastLine}")
            return DirectoryClient(JoinBlaketDirectory.HOST, JoinBlaketDirectory.PORT, identity, JoinBlaketDirectory.NETWORK.wire,
                JoinBlaketDirectory.ID, requireVerified = true, proxy = proxy)
        }
        override val chain: ChainLookup = appChain
        override fun broadcast(rawHex: String): String = appChain.broadcast(rawHex)
        override val commitments: CommitmentStore = FileCommitmentStore(commitmentsFile(ctx))

        override suspend fun confirm(plan: TakerPlan): Boolean {
            val d = CompletableDeferred<Boolean>()
            answer = d
            _state.value = _state.value?.copy(plan = plan)
            sheetVisible.value = true
            JoinBlaketService.refresh(ctx)
            return try { d.await() } finally { _state.value = _state.value?.copy(plan = null) }
        }

        override fun progress(step: TakerStep) {
            _state.value = _state.value?.copy(step = step)
            JoinBlaketService.refresh(ctx)
        }
    }

    /**
     * Our chain view for the round: the wallet's own node by RPC ([RpcChainLookup]) or its Electrum
     * server with every coin's block proven against the verified header chain (SPV,
     * [HeaderChain.proves]). Opened on first use; a call that fails on a dropped connection opens
     * it again once. Blocking, one call at a time.
     */
    private class AppChain(private val ctx: Context) : ChainLookup {
        private var electrum: ElectrumClient? = null
        private var lookup: ChainLookup? = null
        private var broadcaster: ((String) -> String)? = null

        @Synchronized private fun open() {
            if (lookup != null) return
            val store = Store(ctx)
            val endpoint = store.endpoint(Chain.BLAKE2B)
            val rpc = endpoint.rpc
            if (rpc != null) {
                val node = NodeRpcBackend(rpc).also { it.connectChain() }
                lookup = RpcChainLookup(node::rpc)
                broadcaster = node::broadcast
            } else {
                val client = ElectrumClient(endpoint, store.pinnedFingerprint(endpoint)).also { it.connect() }
                // The headers must verify first: every height the server gives is then proven in its block.
                requireNotNull(runCatching { HeaderChain.sync(client) }.getOrNull()) { "the server's block headers don't verify" }
                electrum = client
                lookup = ElectrumChainLookup(client) { txid, height -> HeaderChain.proves(client, txid, height, Scanner.SPV_FLOOR_BITS) }
                broadcaster = client::broadcast
            }
        }

        @Synchronized private fun <T> call(f: () -> T): T {
            open()
            return try { f() } catch (e: java.io.IOException) {
                close(); open(); f()
            } catch (e: com.kilombino.pyblockwatch.chain.ElectrumException) {
                if (e is com.kilombino.pyblockwatch.chain.CertificateChangedException) throw e
                close(); open(); f()
            }
        }

        override fun height(): Int = call { lookup!!.height() }
        override fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray?): IoauthCheck.ChainUtxo? = call { lookup!!.utxo(txid, vout, scriptPubKey) }
        override fun blockTime(height: Int): Long = call { lookup!!.blockTime(height) }
        fun broadcast(rawHex: String): String = call { broadcaster!!(rawHex) }

        @Synchronized fun close() {
            runCatching { electrum?.close() }
            electrum = null; lookup = null; broadcaster = null
        }
    }
}
