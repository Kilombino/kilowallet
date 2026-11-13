package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Secp256k1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger

/** One of our coins for the round: a confirmed P2WPKH output and the key that spends it. */
class TakerCoin(val txid: String, val vout: Long, val value: Long, val privateKey: BigInteger, val height: Int) {
    val pubkey: ByteArray = Secp256k1.compress(Secp256k1.publicPoint(privateKey))
    val scriptPubKey: ByteArray = TakerTx.p2wpkh(pubkey)
    val outpoint: Pair<String, Long> get() = txid.lowercase() to vout
    val utxo: TakerTx.Utxo get() = TakerTx.Utxo(txid.lowercase(), vout, value, scriptPubKey)

    /** Confirmations at tip [tip] (0 while unconfirmed). */
    fun confirmations(tip: Int): Int = if (height <= 0) 0 else tip - height + 1
}

/**
 * What the user asked for: [amount] sats to [recipient] as the CoinJoin output, change to
 * [changeAddress], from some of [coins], at about [feeRate] sat/vB (clamped to the taker's
 * minimum and cap, then randomized once per round as Python does).
 */
class TakerRequest(
    val recipient: String,
    val amount: Long,
    val feeRate: Double,
    val coins: List<TakerCoin>,
    val changeAddress: String,
    /** How many makers to ask for; fewer are fine down to [minimumMakers] (Jam asks for 8-10). */
    val makers: Int = 9,
    val minimumMakers: Int = 1,
)

/** How the signed CoinJoin is broadcast (`tx_broadcast`, `taker/coinjoin_session.py:2409-2575`). */
enum class BroadcastPolicy {
    /** `!push` to makers in random order, each followed by a check that the txid shows up; then our own backend. */
    RANDOM_PEER,
    /** Our own backend (node or Electrum server) only. */
    SELF,
}

/** The taker's knobs, defaults as `taker/config.py` (spec sections 4-7). */
data class TakerConfig(
    val network: JbNetwork = JbNetwork.MAINNET,
    val orderbook: Orderbook.Timing = Orderbook.Timing(),
    /** `maker_timeout_sec`: each phase (fill, auth, signatures) waits this long for the makers. */
    val makerTimeoutMs: Long = 60_000,
    val connectAttempts: Int = 3,
    val maxCjFee: Fees.MaxCjFee = Fees.MaxCjFee(),
    /** `max_maker_replacement_attempts`. */
    val maxReplacementAttempts: Int = 3,
    /** `taker_utxo_age`, `taker_utxo_amtpercent`, `taker_utxo_retries`: what makers accept for a PoDLE. */
    val utxoAge: Int = 5,
    val utxoAmountPercent: Int = 20,
    val utxoRetries: Int = 3,
    val maxMakerUtxos: Int = IoauthCheck.MAX_MAKER_UTXOS,
    /** `min_fee_rate_sat_vb`, `max_fee_rate_sat_vb`, `tx_fee_factor`. */
    val minFeeRate: Double = 1.0,
    val maxFeeRate: Double = 100.0,
    val txFeeFactor: Double = 0.2,
    /**
     * The mining fee the user confirms is capped as if each maker brought this many inputs (the
     * taker pays for every input). A CoinJoin that would cost more drops the makers with the
     * most inputs (down to the minimum) or is abandoned before `!tx`.
     */
    val ceilingInputsPerMaker: Int = 5,
    /** The default of the CLI/Jam path (`jmcore/settings.py:1265-1268`), also what the regtest lab logged. */
    val broadcast: BroadcastPolicy = BroadcastPolicy.RANDOM_PEER,
    /** At most this many makers are asked to `!push` before we broadcast ourselves. */
    val pushPeers: Int = 3,
    /** `broadcast_timeout_sec` and the 2 s poll for the txid after a push. */
    val broadcastTimeoutMs: Long = 30_000,
    val pollMs: Long = 2_000,
    /** How long the user has to confirm the plan (`initial_confirmation_timeout_sec`). */
    val confirmTimeoutMs: Long = 5 * 60_000,
)

/** What the user confirms before any commitment, input or signature leaves the phone. */
data class TakerPlan(
    val amount: Long,
    val recipient: String,
    /** Each chosen maker's fee and bond value, in the order they were chosen. */
    val makers: List<PlanMaker>,
    /** Our inputs and their total. */
    val inputs: Int,
    val inputTotal: Long,
    /** The randomized rate this round pays, sat/vB. */
    val feeRate: Double,
    /** The mining fee if every maker brings two inputs (Python's estimate), and the most we accept. */
    val miningFeeEstimate: Long,
    val miningFeeMax: Long,
) {
    data class PlanMaker(val fee: Long, val bondValue: Long)
    val makerFees: Long get() = makers.sumOf { it.fee }
    val totalEstimate: Long get() = makerFees + miningFeeEstimate
    val totalMax: Long get() = makerFees + miningFeeMax
}

/** Where a round is, for the notification and the sheet. */
sealed class TakerStep {
    data class Connecting(val attempt: Int) : TakerStep()
    object ReadingOffers : TakerStep()
    data class CheckingBonds(val offers: Int) : TakerStep()
    data class Confirm(val plan: TakerPlan) : TakerStep()
    data class WaitingForMakers(val ready: Int, val asked: Int) : TakerStep()
    data class CollectingSignatures(val makers: Int) : TakerStep()
    /** From here on the CoinJoin is signed: cancelling no longer stops it. */
    object Broadcasting : TakerStep()
}

sealed class TakerResult {
    /**
     * The CoinJoin went out. [minerFee] is the whole mining fee (inputs minus outputs), of which
     * [txFee] is ours and the rest the makers' `txfee` contributions plus any dust change.
     * [seen]: our backend showed the txid (false only if it never did within the wait).
     */
    data class Sent(
        val txid: String, val rawHex: String, val makers: Int, val makerFees: Long, val txFee: Long,
        val minerFee: Long, val change: Long, val viaMaker: Boolean, val seen: Boolean,
    ) : TakerResult()
    /** Nothing was broadcast. [reason] is for the user (English, translated by the app); [detail] for logs. */
    data class Failed(val reason: String, val detail: String? = null) : TakerResult()
    data class Cancelled(val reason: String) : TakerResult()
}

/** What the session needs from the app: the directory, the chain, persistence and the user. */
interface TakerEnv {
    /**
     * A new, not yet connected client for [identity] (Tor proxy, directory id and network set).
     * Blocking (it may wait for Tor); throws [DirectoryException] when none can be made.
     */
    fun directory(identity: NickIdentity): DirectoryClient
    /** Lookups on our own chain view; blocking, called from `Dispatchers.IO`. */
    val chain: ChainLookup
    /** Broadcast [rawHex] through our own backend; returns the txid or throws. Blocking. */
    fun broadcast(rawHex: String): String
    val commitments: CommitmentStore
    /** Show [plan] and wait for the user's answer: true to go on. */
    suspend fun confirm(plan: TakerPlan): Boolean
    fun progress(step: TakerStep) {}
    /** Diagnostics (nicks never go to the app's logs; tests print them). */
    fun log(line: String) {}
}

/** User-facing reasons, in English as the code shows them (the app's tables translate them). */
object TakerText {
    const val DIRECTORY = "Could not reach the JoinBlaket directory. Nothing was sent; try again in a moment."
    const val CONNECTION_LOST = "The connection to the JoinBlaket directory was lost. Nothing was sent."
    const val NO_MAKERS = "No maker with a fidelity bond takes {0} sats right now. Nothing was sent."
    const val NO_PODLE_COIN = "None of your coins can open this CoinJoin: one needs at least {0} confirmations and at least {1} sats (20% of the amount)."
    const val NO_FRESH_COMMITMENT = "Your coins that can open a CoinJoin have each been used for three already. Move one to a fresh address of yours and try again."
    const val NOT_ENOUGH_COINS = "Not enough coins: this CoinJoin needs up to {0} sats and your coins add up to {1} sats."
    const val FEE_CAP = "The mining fee with these makers would be above {0} sat/vB. Nothing was sent."
    const val CANCELLED = "Cancelled. Nothing was sent."
    const val NOT_CONFIRMED = "Not confirmed in time. Nothing was sent."
    const val TOO_FEW = "Too few makers answered ({0} of the {1} needed). Nothing was sent."
    const val ABOVE_CEILING = "The makers brought more coins than the mining fee you confirmed covers. Nothing was sent."
    const val BAD_TX = "The CoinJoin did not add up, so it was not signed. Nothing was sent."
    const val NO_SIGNATURE = "A maker did not sign in time. Nothing was sent; try again."
    const val BAD_SIGNATURE = "A maker's signature was not valid. Nothing was sent; try again."
    const val INPUT_SPENT = "A coin of the CoinJoin was spent meanwhile. Nothing was sent."
    const val NOT_BROADCAST = "The CoinJoin was signed but could not be broadcast. It may still go out: wait before spending these coins."
    const val INVALID_REQUEST = "This payment can't go through JoinBlaket: {0}"

    fun fill(t: String, vararg v: Any): String = v.foldIndexed(t) { i, s, x -> s.replace("{$i}", x.toString()) }
}

/**
 * A JoinMarket taker round on JoinBlaket (`taker/taker.py`, `taker/coinjoin_session.py`): one
 * payment sent as the CoinJoin output of a CoinJoin with bonded makers, change back to us.
 *
 * 1. Connect to the directory with a fresh nick (a few attempts: onion circuits fail now and then).
 * 2. `!orderbook`, keep offers with a valid bond proof, value the bonds on our chain view,
 *    choose up to [TakerRequest.makers] bonded makers (fewer are fine down to the minimum).
 * 3. Choose our coins, one of which can carry the PoDLE (>= [TakerConfig.utxoAge] confirmations,
 *    >= [TakerConfig.utxoAmountPercent] % of the amount, an unused commitment index), check the
 *    fee headroom, and ask the user to confirm the plan. Nothing has left the phone yet.
 * 4. `!fill` -> `!pubkey` -> `!auth` -> `!ioauth` with every maker ([IoauthCheck], values from our
 *    lookups). A maker that drops before `!ioauth` is ignored and replaced from the remaining
 *    offers (at most [TakerConfig.maxReplacementAttempts] times, within the confirmed maker fees,
 *    with a fresh commitment once one was revealed); the round goes on with fewer makers down to
 *    the minimum (spec 7).
 * 5. Build the transaction ([TakerTx], shuffled), check the miner fee, send `!tx`, collect and
 *    verify every maker signature (0x21 only). Any failure from here aborts the round: nothing
 *    is broadcast.
 * 6. Sign ours, re-check every input unspent and confirmed, broadcast ([BroadcastPolicy]) and
 *    wait until our backend shows the txid.
 *
 * [tamperSignature] exists for the negative end-to-end test only: it alters a maker's
 * signature as received, to prove nothing is broadcast then.
 */
class TakerSession(
    private val env: TakerEnv,
    private val config: TakerConfig = TakerConfig(),
    internal val tamperSignature: ((ByteArray) -> ByteArray)? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Abort(val result: TakerResult) : Exception()

    private fun fail(reason: String, detail: String? = null): Nothing = throw Abort(TakerResult.Failed(reason, detail))

    /** One maker in the round. */
    private class Maker(val offer: ValuedOffer, val fee: Long) {
        val nick: String get() = offer.offer.counterparty
        var box: Nacl.Box? = null
        var naclPublic: ByteArray? = null
        var features: Set<String> = emptySet()
        var part: TakerTx.MakerPart? = null
    }

    /** The commitment in use and the coin it opens. */
    private class Opening(val coin: TakerCoin, val commitment: Podle.Commitment)

    suspend fun run(request: TakerRequest): TakerResult = withContext(Dispatchers.IO) {
        var client: DirectoryClient? = null
        try {
            validate(request)
            client = connect()
            negotiate(client, request)
        } catch (a: Abort) {
            a.result
        } catch (e: DirectoryException) {
            TakerResult.Failed(TakerText.CONNECTION_LOST, e.message)
        } finally {
            client?.close()
        }
    }

    private fun validate(r: TakerRequest) {
        fun bad(why: String): Nothing = fail(TakerText.fill(TakerText.INVALID_REQUEST, why))
        if (r.amount <= 0) bad("the amount must be above zero")
        if (r.coins.isEmpty()) bad("no coins to pay from")
        if (r.minimumMakers < 1 || r.makers < r.minimumMakers) bad("the number of makers is wrong")
        val to = runCatching { config.network.scriptPubKey(r.recipient) }.getOrNull() ?: bad("the address is not valid")
        if (to.size != 22 || to[0] != 0.toByte()) bad("only native SegWit (bc1q) addresses look like the makers' outputs")
        runCatching { config.network.scriptPubKey(r.changeAddress) }.getOrNull() ?: bad("the change address is not valid")
        if (r.coins.map { it.outpoint }.toSet().size != r.coins.size) bad("the same coin twice")
    }

    private suspend fun connect(): DirectoryClient {
        var last: Exception? = null
        for (attempt in 1..config.connectAttempts) {
            env.progress(TakerStep.Connecting(attempt))
            // A fresh nick for every attempt and every round: nothing links two rounds.
            var c: DirectoryClient? = null
            try {
                c = env.directory(NickIdentity.random())
                c.connect()
                env.log("connected as ${c.nick} (nick auth ${c.authenticated})")
                return c
            } catch (e: DirectoryException) {
                last = e; c?.close()
                env.log("connect attempt $attempt failed: ${e.message}")
            }
        }
        fail(TakerText.DIRECTORY, last?.message)
    }

    private suspend fun negotiate(client: DirectoryClient, r: TakerRequest): TakerResult {
        env.progress(TakerStep.ReadingOffers)
        val book = Orderbook.fetch(client, config.orderbook)
        env.progress(TakerStep.CheckingBonds(book.offers.size))
        val valued = BondValues.value(book.offers, env.chain)
        val tip = env.chain.height()
        env.log("offers: ${book.offers.size} bonded of ${book.offerMessages}, valued > 0: ${valued.count { it.value > 0 }}, tip $tip")

        val chosen = when (val pick = MakerSelection.choose(valued, r.amount, r.makers, r.minimumMakers, config.maxCjFee)) {
            is MakerSelection.Result.Chosen -> pick
            is MakerSelection.Result.Failed -> fail(TakerText.fill(TakerText.NO_MAKERS, r.amount), pick.reason)
        }
        val n = chosen.offers.size
        val rate = Fees.randomizedRate(r.feeRate.coerceIn(config.minFeeRate, config.maxFeeRate), config.txFeeFactor, config.maxFeeRate)

        // Our coins: one that can carry a fresh PoDLE, then the largest others until even the
        // most expensive CoinJoin we accept is covered (so the change can never go negative).
        val podleCoins = podleCandidates(r, tip)
        if (podleCoins.isEmpty()) fail(TakerText.fill(TakerText.NO_PODLE_COIN, config.utxoAge, minPodleValue(r.amount)))
        val first = podleCoins.firstOrNull { c -> freshIndex(c) != null } ?: fail(TakerText.NO_FRESH_COMMITMENT)
        val others = r.coins.filter { it.outpoint != first.outpoint && it.confirmations(tip) >= 1 }.sortedByDescending { it.value }
        val ours = arrayListOf(first)
        fun ceilingFee(inputs: Int, makers: Int) = Fees.txFee(inputs + makers * config.ceilingInputsPerMaker, Fees.outputCount(makers), rate)
        fun need() = r.amount + chosen.totalFee + ceilingFee(ours.size, n)
        for (c in others) { if (ours.sumOf { it.value } >= need()) break; ours.add(c) }
        if (ours.sumOf { it.value } < need()) fail(TakerText.fill(TakerText.NOT_ENOUGH_COINS, need(), (listOf(first) + others).sumOf { it.value }))
        preflightFeeHeadroom(ours.size, chosen.offers.map { it.offer }, rate)

        val plan = TakerPlan(
            amount = r.amount, recipient = r.recipient,
            makers = chosen.offers.map { TakerPlan.PlanMaker(chosen.fees.getValue(it.offer.counterparty), it.value) },
            inputs = ours.size, inputTotal = ours.sumOf { it.value }, feeRate = rate,
            miningFeeEstimate = Fees.txFee(ours.size + 2 * n, Fees.outputCount(n), rate),
            miningFeeMax = ceilingFee(ours.size, n),
        )
        env.progress(TakerStep.Confirm(plan))
        val yes = withTimeoutOrNull(config.confirmTimeoutMs) { env.confirm(plan) }
            ?: return TakerResult.Cancelled(TakerText.NOT_CONFIRMED)
        if (!yes) return TakerResult.Cancelled(TakerText.CANCELLED)

        // Only now does anything about our coins leave the phone.
        val makers = runMakers(client, r, valued, chosen, ours)
        return buildSignBroadcast(client, r, ours, makers, rate, plan, tip)
    }

    private fun minPodleValue(amount: Long): Long = amount * config.utxoAmountPercent / 100

    /** `taker/podle.py:116-148`: enough confirmations and value, best (confirmations, value) first. */
    private fun podleCandidates(r: TakerRequest, tip: Int): List<TakerCoin> =
        r.coins.filter { it.confirmations(tip) >= config.utxoAge && it.value >= minPodleValue(r.amount) }
            .sortedWith(compareByDescending<TakerCoin> { it.confirmations(tip) }.thenByDescending { it.value })

    /** The first commitment index of [coin] never shown to a maker, or null. */
    private fun freshIndex(coin: TakerCoin): Int? = (0 until config.utxoRetries).firstOrNull { i ->
        !env.commitments.isUsed(Podle.generate(coin.privateKey, "${coin.txid.lowercase()}:${coin.vout}", i).commitment.toHex())
    }

    /**
     * A fresh commitment from one of [ours] that qualifies ([podleCandidates] order), marked used
     * before it is returned (`taker/podle_manager.py:183-239`). Null when none is left.
     */
    private fun newOpening(ours: List<TakerCoin>, r: TakerRequest, tip: Int): Opening? {
        val eligible = podleCandidates(TakerRequest(r.recipient, r.amount, r.feeRate, ours, r.changeAddress), tip)
        for (coin in eligible) {
            val i = freshIndex(coin) ?: continue
            val c = Podle.generate(coin.privateKey, "${coin.txid.lowercase()}:${coin.vout}", i)
            env.commitments.markUsed(c.commitment.toHex())
            return Opening(coin, c)
        }
        return null
    }

    /**
     * `_preflight_fee_headroom` (`taker/taker.py:963-1026`): with one input per maker, the complete
     * mining fee (ours, the makers' contributions, a possible unreturned change and 1 sat of slack)
     * must stay within the rate cap before any commitment is exposed.
     */
    private fun preflightFeeHeadroom(ourInputs: Int, offers: List<Offer>, rate: Double) {
        val inputs = ourInputs + offers.size
        val outputs = 1 + 2 * offers.size
        val complete = Fees.txFee(inputs, outputs + 1, rate) + offers.sumOf { it.txfee } + Fees.TAKER_DUST + 1
        if (complete > config.maxFeeRate * Fees.vsizeEstimate(inputs, outputs)) fail(TakerText.fill(TakerText.FEE_CAP, config.maxFeeRate.toInt()))
    }

    /** Receive verified privmsgs from the makers in [waiting] for [timeoutMs]; [handle] removes a nick once done. */
    private suspend fun collect(client: DirectoryClient, waiting: MutableSet<String>, timeoutMs: Long, handle: (Session.Incoming) -> Unit) {
        val deadline = now() + timeoutMs
        while (waiting.isNotEmpty()) {
            val left = deadline - now()
            if (left <= 0) break
            val f = client.receive(minOf(left, 1_000L)) ?: continue
            val m = Session.parsePrivmsg(f) ?: continue
            if (!Session.accepts(m, client.nick, waiting)) continue
            handle(m)
        }
    }

    /**
     * Steps 4 of the class comment: fill, auth and the replacements, until enough makers passed
     * `!ioauth`. Returns them in the order they passed.
     */
    private suspend fun runMakers(
        client: DirectoryClient, r: TakerRequest, valued: List<ValuedOffer>, chosen: MakerSelection.Result.Chosen,
        ours: List<TakerCoin>,
    ): List<Maker> {
        val tip = env.chain.height()
        val wanted = chosen.offers.size
        val feeBudget = chosen.totalFee
        val naclSecret = Nacl.generateSecret()
        val naclPublic = Nacl.publicKey(naclSecret)
        val ignored = HashSet<String>()
        val done = ArrayList<Maker>()
        var opening = newOpening(ours, r, tip) ?: fail(TakerText.NO_FRESH_COMMITMENT)
        var revealed = false
        var pending = chosen.offers.map { Maker(it, chosen.fees.getValue(it.offer.counterparty)) }
        var attempts = 0
        var blacklistRetries = 0

        while (true) {
            env.progress(TakerStep.WaitingForMakers(done.size, done.size + pending.size))
            // !fill -> !pubkey
            for (m in pending) client.sendPrivate(m.nick, "fill", Session.fillData(m.offer.offer.oid, r.amount, naclPublic, opening.commitment.wire))
            val waiting = pending.map { it.nick }.toMutableSet()
            var blacklisted = 0
            collect(client, waiting, config.makerTimeoutMs) { msg ->
                val m = pending.first { it.nick == msg.from }
                when (msg.command) {
                    "pubkey" -> runCatching { Session.parsePubkey(msg.data) }.onSuccess { pk ->
                        m.naclPublic = pk.naclPublic; m.features = pk.features; m.box = Nacl.Box(naclSecret, pk.naclPublic)
                    }.onFailure { env.log("${m.nick}: unreadable !pubkey") }
                    "error" -> { if ("blacklist" in msg.data.lowercase()) blacklisted++; env.log("${m.nick}: !error in fill: ${msg.data.take(120)}") }
                    else -> return@collect
                }
                waiting.remove(m.nick)
            }
            // A majority calling the commitment blacklisted: burn it, take the next and fill the same makers again (spec 7).
            if (blacklisted > 0 && blacklisted * 2 >= pending.size && blacklistRetries < config.utxoRetries && !revealed) {
                blacklistRetries++
                opening = newOpening(ours, r, tip) ?: fail(TakerText.NO_FRESH_COMMITMENT)
                pending.forEach { it.box = null }
                continue
            }
            val answered = pending.filter { it.box != null }
            pending.filter { it.box == null }.forEach { ignored += it.nick }

            // !auth -> !ioauth
            val revelation = { m: Maker ->
                if ("neutrino_compat" in m.features) opening.commitment.revelation(opening.coin.scriptPubKey.toHex(), opening.coin.height)
                else opening.commitment.revelation()
            }
            for (m in answered) client.sendPrivate(m.nick, "auth", Session.authData(m.box!!, revelation(m)))
            if (answered.isNotEmpty()) revealed = true
            val waitingIo = answered.map { it.nick }.toMutableSet()
            val taken = HashSet<Pair<String, Long>>().apply { ours.forEach { add(it.outpoint) }; done.forEach { d -> d.part!!.utxos.forEach { add(it.outpoint) } } }
            collect(client, waitingIo, config.makerTimeoutMs) { msg ->
                val m = answered.first { it.nick == msg.from }
                when (msg.command) {
                    "ioauth" -> {
                        val io = runCatching { Session.decodeIoauth(m.box!!, msg.data) }.getOrNull()
                        if (io == null) env.log("${m.nick}: unreadable !ioauth") else {
                            val hints = io.utxos.associate { (it.txid.lowercase() to it.vout) to it.scriptPubKeyHex?.let(Wire::hex) }
                            val res = IoauthCheck.check(io, m.naclPublic!!, m.nick, r.amount, m.offer.offer.txfee, m.fee, taken,
                                { txid, vout -> env.chain.utxo(txid, vout, hints[txid to vout]) }, config.maxMakerUtxos, config.network)
                            when (res) {
                                is IoauthCheck.Result.Ok -> { m.part = res.part; res.part.utxos.forEach { taken.add(it.outpoint) } }
                                is IoauthCheck.Result.Fail -> env.log("${m.nick}: ioauth refused: ${res.reason}")
                            }
                        }
                    }
                    "error" -> env.log("${m.nick}: !error in auth: ${msg.data.take(120)}")
                    else -> return@collect
                }
                waitingIo.remove(m.nick)
            }
            answered.forEach { if (it.part != null) done += it else ignored += it.nick }
            env.progress(TakerStep.WaitingForMakers(done.size, wanted))

            val short = wanted - done.size
            if (short <= 0 || attempts >= config.maxReplacementAttempts) break
            // Replacements: never a maker that failed, one already in, or a sibling of one in (same bond).
            val inBonds = done.map { it.offer.bond.txid to it.offer.bond.vout }.toSet()
            val pool = valued.filter { (it.bond.txid to it.bond.vout) !in inBonds }
            val replacement = (MakerSelection.choose(pool, r.amount, short, 1, config.maxCjFee,
                ignored = ignored + done.map { it.nick }) as? MakerSelection.Result.Chosen) ?: break
            // Within the maker fees the user confirmed: the most expensive replacements go first.
            val spent = done.sumOf { it.fee }
            val affordable = ArrayList<Maker>()
            for (o in replacement.offers.sortedBy { replacement.fees.getValue(it.offer.counterparty) }) {
                val fee = replacement.fees.getValue(o.offer.counterparty)
                if (spent + affordable.sumOf { it.fee } + fee <= feeBudget) affordable += Maker(o, fee)
            }
            if (affordable.isEmpty()) break
            attempts++
            env.log("replacing $short maker(s) with ${affordable.size} (attempt $attempts)")
            // Once revealed in !auth the commitment is burned for everyone (`!hp2`): a fresh one for the newcomers.
            if (revealed) {
                opening = newOpening(ours, r, tip) ?: break
                revealed = false
            }
            pending = affordable
        }
        if (done.size < r.minimumMakers || done.isEmpty()) {
            fail(TakerText.fill(TakerText.TOO_FEW, done.size, maxOf(1, r.minimumMakers)))
        }
        return done
    }

    /** Steps 5 and 6: the transaction, `!tx`, the makers' signatures, ours, the last checks and the broadcast. */
    private suspend fun buildSignBroadcast(
        client: DirectoryClient, r: TakerRequest, ours: List<TakerCoin>, ready: List<Maker>, rate: Double,
        plan: TakerPlan, tip: Int,
    ): TakerResult {
        val makers = ready.toMutableList()
        fun txFeeFor(ms: List<Maker>) = Fees.txFee(ours.size + ms.sumOf { it.part!!.utxos.size }, Fees.outputCount(ms.size), rate)
        // Above the confirmed ceiling: leave out the makers with the most inputs (they never get !tx), down to the minimum.
        while (txFeeFor(makers) > plan.miningFeeMax && makers.size > r.minimumMakers) {
            val heavy = makers.maxByOrNull { it.part!!.utxos.size }!!
            makers.remove(heavy)
            env.log("left out ${heavy.nick}: ${heavy.part!!.utxos.size} inputs")
        }
        val txFee = txFeeFor(makers)
        if (txFee > plan.miningFeeMax) fail(TakerText.ABOVE_CEILING)
        val makerFees = makers.sumOf { it.fee }
        if (makerFees > plan.makerFees) fail(TakerText.BAD_TX, "maker fees above the plan")
        val ourUtxos = ours.map { it.utxo }
        val change = Fees.takerChange(ourUtxos.sumOf { it.value }, r.amount, makers.map { it.fee }, txFee)
        if (change < 0) fail(TakerText.fill(TakerText.NOT_ENOUGH_COINS, r.amount + makerFees + txFee, ourUtxos.sumOf { it.value }))

        val unsigned = runCatching {
            TakerTx.build(ourUtxos, r.recipient, r.changeAddress, makers.map { it.part!! }, r.amount, txFee,
                TakerTx.locktime(tip.toLong()), Shuffler.Secure, config.network)
        }.getOrElse { fail(TakerText.BAD_TX, it.message) }
        checkOurPart(unsigned, r, ours, change)
        val totalIn = unsigned.inputs.sumOf { it.value }
        val totalOut = unsigned.outputs.sumOf { it.value }
        Fees.checkMinerFee(totalIn, totalOut, unsigned.inputs.size, unsigned.outputs.size, config.maxFeeRate, config.minFeeRate)
            ?.let { fail(TakerText.BAD_TX, it) }

        // !tx -> !sig. From here a missing or bad signature aborts the round: the makers' inputs are in the tx.
        env.progress(TakerStep.CollectingSignatures(makers.size))
        val bytes = unsigned.serialize()
        for (m in makers) client.sendPrivate(m.nick, "tx", Session.txData(m.box!!, bytes))
        val got = makers.associate { it.nick to ArrayList<Pair<ByteArray, ByteArray>>() }
        val waiting = makers.map { it.nick }.toMutableSet()
        var badReason: String? = null
        collect(client, waiting, config.makerTimeoutMs) { msg ->
            val m = makers.first { it.nick == msg.from }
            when (msg.command) {
                "sig" -> {
                    val sig = runCatching { Session.decodeSig(m.box!!, msg.data) }.getOrNull()
                    if (sig == null) { badReason = "${m.nick}: unreadable !sig"; waiting.remove(m.nick); return@collect }
                    got.getValue(m.nick) += (tamperSignature?.invoke(sig.first) ?: sig.first) to sig.second
                    if (got.getValue(m.nick).size >= m.part!!.utxos.size) waiting.remove(m.nick)
                }
                "error" -> { badReason = "${m.nick}: !error in signing: ${msg.data.take(120)}"; waiting.remove(m.nick) }
            }
        }
        if (waiting.isNotEmpty()) fail(TakerText.NO_SIGNATURE, "no signature from ${waiting.size} maker(s)")
        badReason?.let { fail(TakerText.NO_SIGNATURE, it) }
        val witnesses = HashMap<Int, Pair<ByteArray, ByteArray>>()
        for (m in makers) {
            val matched = TakerTx.matchMakerSignatures(unsigned, m.nick, got.getValue(m.nick))
                ?: fail(TakerText.BAD_SIGNATURE, "${m.nick}: a signature did not verify under 0x21")
            witnesses.putAll(matched)
        }

        // Our own signatures, then every witness checked once more before anything is broadcast.
        env.progress(TakerStep.Broadcasting)
        for ((i, owner) in unsigned.inputOwners.withIndex()) {
            if (owner != "taker") continue
            val coin = ours.first { it.outpoint == unsigned.inputs[i].outpoint }
            witnesses[i] = TakerTx.signInput(unsigned, i, coin.privateKey) to coin.pubkey
        }
        if (unsigned.inputs.indices.any { i -> witnesses[i]?.let { (s, p) -> !TakerTx.verifyInput(unsigned, i, s, p) } != false }) {
            fail(TakerText.BAD_SIGNATURE, "a witness did not verify")
        }
        val signed = TakerTx.assemble(unsigned, witnesses)
        // `taker/coinjoin_session.py:2369-2407`: every input still unspent, confirmed and worth what we signed for.
        for (u in unsigned.inputs) {
            val c = runCatching { env.chain.utxo(u.txid, u.vout, u.scriptPubKey) }.getOrElse { fail(TakerText.INPUT_SPENT, "lookup failed: ${it.message}") }
            if (c == null || c.confirmations <= 0 || c.value != u.value || !c.scriptPubKey.contentEquals(u.scriptPubKey)) {
                fail(TakerText.INPUT_SPENT, "${u.txid}:${u.vout}")
            }
        }
        val txid = unsigned.txid()
        val hex = signed.toHex()
        val (viaMaker, seen) = broadcast(client, makers, signed, hex, txid, unsigned.outputs[0].scriptPubKey)
        if (!seen && !viaMaker) fail(TakerText.NOT_BROADCAST)
        return TakerResult.Sent(txid, hex, makers.size, makerFees, txFee, totalIn - totalOut, if (change > Fees.TAKER_DUST) change else 0,
            viaMaker, seen)
    }

    /** Our part of [tx], checked on its own: the payment exactly once, our change exactly right, our inputs all there. */
    private fun checkOurPart(tx: TakerTx.Unsigned, r: TakerRequest, ours: List<TakerCoin>, change: Long) {
        val pay = config.network.scriptPubKey(r.recipient)
        val back = config.network.scriptPubKey(r.changeAddress)
        val payOuts = tx.outputs.filter { it.scriptPubKey.contentEquals(pay) }
        val changeOuts = tx.outputs.filter { it.scriptPubKey.contentEquals(back) }
        val ok = payOuts.size == 1 && payOuts[0].value == r.amount &&
            (if (change > Fees.TAKER_DUST) changeOuts.size == 1 && changeOuts[0].value == change else changeOuts.isEmpty()) &&
            tx.inputs.indices.filter { tx.inputOwners[it] == "taker" }.map { tx.inputs[it].outpoint }.toSet() == ours.map { it.outpoint }.toSet()
        if (!ok) fail(TakerText.BAD_TX, "our outputs are not as planned")
    }

    /** Whether our backend shows [txid] (its first output, mempool included). */
    private fun seen(txid: String, firstOutput: ByteArray): Boolean =
        runCatching { env.chain.utxo(txid, 0, firstOutput) != null }.getOrDefault(false)

    private suspend fun waitSeen(txid: String, firstOutput: ByteArray): Boolean {
        val until = now() + config.broadcastTimeoutMs
        while (now() < until) {
            if (seen(txid, firstOutput)) return true
            kotlinx.coroutines.delay(config.pollMs)
        }
        return seen(txid, firstOutput)
    }

    /**
     * [BroadcastPolicy.RANDOM_PEER]: `!push` (plaintext) to up to [TakerConfig.pushPeers] makers in
     * random order, each followed by a wait for the txid; then our own backend. Returns
     * (pushed to a maker, seen by our backend).
     */
    private suspend fun broadcast(client: DirectoryClient, makers: List<Maker>, signed: ByteArray, hex: String, txid: String,
                                  firstOutput: ByteArray): Pair<Boolean, Boolean> {
        var pushed = false
        if (config.broadcast == BroadcastPolicy.RANDOM_PEER) {
            for (m in makers.shuffled(java.security.SecureRandom()).take(config.pushPeers)) {
                runCatching { client.sendPrivate(m.nick, "push", Session.pushData(signed)) }.onSuccess { pushed = true }
                if (waitSeen(txid, firstOutput)) return true to true
                env.log("${m.nick}: pushed, txid not seen")
            }
        }
        val selfError = runCatching { env.broadcast(hex) }.exceptionOrNull()
        selfError?.let { env.log("own broadcast: ${it.message}") }
        return pushed to waitSeen(txid, firstOutput)
    }
}
