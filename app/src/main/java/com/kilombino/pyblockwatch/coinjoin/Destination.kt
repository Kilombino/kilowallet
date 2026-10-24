package com.kilombino.pyblockwatch.coinjoin

import android.content.Context
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.data.JoinMarket
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.data.Store

/**
 * Where a seat's mixed outputs go. By default the hot wallet's own fresh addresses; it can also
 * be unused receive addresses of the watch-only wallet, or of Jam's first jar (Apricot), when
 * that wallet is set up here and its addresses are of the pool's type (every output of a round
 * looks the same, so a bc1q pool takes only bc1q addresses and a Taproot pool only bc1p).
 *
 * Nothing about this leaves the phone: the round only ever sees an output script, the same as
 * any other, and the pool's announcements and Telegram alerts never carry addresses.
 *
 * [Kind.PAY] is a payment paid through a pool ([CoinjoinPayment]): the mixed outputs are the
 * recipients' own addresses, given by the user, never derived or reserved from any wallet here.
 */
object Destination {
    enum class Kind { HOT, WATCH, JAM, PAY }

    /** How each choice is named on screen. */
    fun label(kind: Kind): String = when (kind) {
        Kind.HOT -> "Hot wallet"
        Kind.WATCH -> "Watch-only wallet"
        Kind.JAM -> "JoinMarket · Apricot"
        Kind.PAY -> "Payment recipients"
    }

    /** The choices for a pool of [type]: the hot wallet always first (the default), then whichever fits. */
    fun options(ctx: Context, type: ScriptType): List<Kind> {
        val out = mutableListOf(Kind.HOT)
        val watch = Store(ctx, Store.WATCH)
        if (watch.xpub != null && watch.scriptType == type && typeKnown(watch)) out += Kind.WATCH
        if (type == ScriptType.P2WPKH && jamExport(ctx) != null) out += Kind.JAM
        return out
    }

    /** The watch-only wallet's address type is its own, not a plain xpub's default (see [Store.typeChosen]). */
    fun typeKnown(watch: Store): Boolean = watch.typeChosen ||
        watch.xpub?.let { com.kilombino.pyblockwatch.data.Scanner.scriptTypeOf(it) }.let { it == ScriptType.P2WPKH || it == ScriptType.P2SH_P2WPKH }

    private fun jamExport(ctx: Context): JoinMarket.Export? =
        Store(ctx, Store.JM).jmExport?.let { runCatching { JoinMarket.parse(it) }.getOrNull() }
            ?.takeIf { it.network == JoinMarket.MAINNET }

    /**
     * Scripts every seat of this phone still counts on: the mixed outputs of pools not over (or
     * over after this wallet posted its output), and those a perfect coin is waiting to use.
     */
    fun reserved(ctx: Context): Set<List<Byte>> {
        runCatching { CoinjoinHub.load(ctx) }
        runCatching { PerfectCoin.load(ctx) }
        val seats = CoinjoinHub.states.value.filter { st ->
            val ended = st.phase == PoolSession.Phase.ABORTED || st.phase == PoolSession.Phase.REJECTED
            !(ended && !st.postedOutput)
        }.flatMap { it.mixScripts }
        val perfect = PerfectCoin.waiting.value.flatMap { it.mixScripts }
        return (seats + perfect).map { it.toList() }.toSet()
    }

    /**
     * [n] receive addresses of [kind]'s account, of [type], that never received anything (asked to
     * the wallet's server) and no seat of this phone holds: the lowest ones past the last used, so a
     * cancelled round leaves no gap for the other wallet's scan to stumble on. Network: call it off
     * the main thread. Throws with a readable message when that wallet can't take them.
     */
    fun scripts(ctx: Context, kind: Kind, type: ScriptType, n: Int): List<ByteArray> =
        pick(ctx, kind, type, n).let { (account, idx) -> idx.map { i -> Address.scriptPubKey(Bip32.derivePath(account, 0, i).pubkey(), type) } }

    /** An address a round's mixed output goes to, with its place in that wallet, to check it against the wallet itself. */
    data class Shown(val address: String, val path: String)

    /**
     * The addresses [scripts] would pick now for [kind], to compare with the other wallet before
     * joining (the same ones, unless something reaches them first). Network: off the main thread.
     */
    fun preview(ctx: Context, kind: Kind, type: ScriptType, n: Int): List<Shown> =
        pick(ctx, kind, type, n).let { (account, idx) ->
            idx.map { i -> Shown(Address.encode(Bip32.derivePath(account, 0, i).pubkey(), type), pathOf(kind, 0, i)) }
        }

    /**
     * Where a round's [scripts] are in [kind]'s account, found by deriving its receive and change
     * addresses (no network). One not found (e.g. the wallet was changed since) shows without a path.
     */
    fun describe(ctx: Context, kind: Kind, type: ScriptType, scripts: List<ByteArray>): List<Shown> {
        // A payment's recipients are not in any wallet here: their addresses, as typed in SEND.
        if (kind == Kind.PAY) return scripts.mapIndexed { i, sp ->
            Shown(runCatching { CoinjoinPayment.address(sp) }.getOrElse { sp.toHex() }, "recipient ${i + 1}")
        }
        val account = accountOf(ctx, kind, type)
        val want = scripts.map { it.toList() }.toMutableSet()
        val found = HashMap<List<Byte>, Shown>()
        if (account != null) for (branch in 0..1) for (i in 0 until SEARCH) {
            if (want.isEmpty()) break
            val pub = Bip32.derivePath(account, branch, i).pubkey()
            val sp = Address.scriptPubKey(pub, type).toList()
            if (want.remove(sp)) found[sp] = Shown(Address.encode(pub, type), pathOf(kind, branch, i))
        }
        return scripts.map { sp -> found[sp.toList()] ?: Shown(sp.toHex(), "?") }
    }

    /** [kind]'s account key for [type], if that wallet is here. */
    private fun accountOf(ctx: Context, kind: Kind, type: ScriptType): Bip32.ExtendedPubKey? = runCatching {
        when (kind) {
            Kind.HOT -> Store(ctx, Store.HOT).let { h -> if (type == ScriptType.P2TR) h.taprootXpub else h.xpub }?.let { Bip32.parseExtendedPubKey(it) }
            Kind.WATCH -> Store(ctx, Store.WATCH).xpub?.let { Bip32.parseExtendedPubKey(it) }
            Kind.JAM -> jamExport(ctx)?.let { JoinMarket.parseKey(it.xpubs[0]) }
            Kind.PAY -> null
        }
    }.getOrNull()

    /**
     * The path as that wallet shows it: Jam's Apricot is jar 0 (m/84'/0'/0'); the others from
     * their account key, which is how Sparrow-like wallets list them too.
     */
    private fun pathOf(kind: Kind, branch: Int, i: Int): String =
        if (kind == Kind.JAM) "m/84'/0'/0'/$branch/$i" else "…/$branch/$i"

    /** Checks [kind] can take [type]'s outputs and walks its receive branch for [n] unused indexes. */
    private fun pick(ctx: Context, kind: Kind, type: ScriptType, n: Int): Pair<Bip32.ExtendedPubKey, List<Int>> {
        require(kind != Kind.HOT) { "the hot wallet's addresses come from its own seed" }
        require(kind != Kind.PAY) { "a payment's addresses are the recipients' own" }
        val (account, from) = when (kind) {
            Kind.WATCH -> {
                val w = Store(ctx, Store.WATCH)
                val xpub = w.xpub ?: error("There is no watch-only wallet any more.")
                require(w.scriptType == type) { "The watch-only wallet's addresses are not of this pool's type." }
                require(typeKnown(w)) { "Choose the watch-only wallet's address type in its settings first." }
                Bip32.parseExtendedPubKey(xpub) to w.usedTop(xpub, 0, type)
            }
            Kind.JAM -> {
                val e = jamExport(ctx) ?: error("There is no JoinMarket wallet any more.")
                require(type == ScriptType.P2WPKH) { "JoinMarket's addresses are native SegWit (bc1q): they only fit a bc1q pool." }
                JoinMarket.parseKey(e.xpubs[0]) to 0
            }
            Kind.HOT, Kind.PAY -> error("unreachable")
        }
        val held = reserved(ctx)
        val store = Store(ctx)
        val endpoint = store.endpoint(Chain.BLAKE2B)
        val c = ElectrumClient(endpoint, store.pinnedFingerprint(endpoint))
        try {
            c.connect()
            val derive = { i: Int -> Address.scriptPubKey(Bip32.derivePath(account, 0, i).pubkey(), type) }
            return account to pickUnusedIndexes(n, from, held, derive,
                hasHistory = { sp -> c.history(Address.electrumScriptHash(sp)).isNotEmpty() })
        } finally { c.close() }
    }

    /**
     * [n] scripts of a receive branch ([derive] by index) past the last one with history: from
     * [from], it walks until [GAP] empty addresses in a row (the end of the used ones, as the
     * wallets' own scans see it), then takes the first unused ones not in [held]. Throws if that
     * would go further than [GAP] past the last used address: the wallet's scan would not find them.
     */
    internal fun pickUnused(n: Int, from: Int, held: Set<List<Byte>>, derive: (Int) -> ByteArray,
                            hasHistory: (ByteArray) -> Boolean): List<ByteArray> =
        pickUnusedIndexes(n, from, held, derive, hasHistory).map(derive)

    /** As [pickUnused], the indexes of the addresses picked. */
    internal fun pickUnusedIndexes(n: Int, from: Int, held: Set<List<Byte>>, derive: (Int) -> ByteArray,
                                   hasHistory: (ByteArray) -> Boolean): List<Int> {
        var i = from
        var lastUsed = from - 1
        var empty = 0
        while (empty < GAP) {
            if (hasHistory(derive(i))) { lastUsed = i; empty = 0 } else empty++
            i++
        }
        val out = mutableListOf<Int>()
        var j = lastUsed + 1
        while (out.size < n) {
            require(j - lastUsed <= GAP) {
                "That wallet has too many addresses waiting for rounds: finish or cancel some first, " +
                    "or its own scan would not see the next ones."
            }
            if (derive(j).toList() !in held) out += j
            j++
        }
        return out
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("coinjoin_dest", Context.MODE_PRIVATE)

    /**
     * Which wallet of that kind it is, as the card shows it: the watch-only wallet's name, else the
     * start of its key; for Jam, the start of Apricot's key. Changing the watch-only wallet later
     * leaves a round already going on the old one, so the card must say which.
     */
    fun which(ctx: Context, kind: Kind): String? = when (kind) {
        Kind.HOT, Kind.PAY -> null
        Kind.WATCH -> Store(ctx, Store.WATCH).let { w -> w.label.trim().ifEmpty { null } ?: w.xpub?.take(12)?.plus("…") }
        Kind.JAM -> jamExport(ctx)?.xpubs?.get(0)?.take(12)?.plus("…")
    }

    /** Notes on this phone (only) where a pool's mixed outputs go, for its card. The hot wallet is not noted. */
    fun remember(ctx: Context, poolId: String, kind: Kind) {
        if (kind != Kind.HOT) prefs(ctx).edit().putString(poolId, kind.name + "|" + (which(ctx, kind) ?: "")).apply()
    }

    /** Where [poolId]'s mixed outputs go: [Kind.HOT] unless noted otherwise. */
    fun of(ctx: Context, poolId: String): Kind =
        prefs(ctx).getString(poolId, null)?.substringBefore('|')?.let { n -> Kind.entries.firstOrNull { it.name == n } } ?: Kind.HOT

    /** That wallet's name or key start, as noted when the round began (older notes have none). */
    fun whichOf(ctx: Context, poolId: String): String? =
        prefs(ctx).getString(poolId, null)?.substringAfter('|', "")?.ifEmpty { null }

    /** The gap limit of Kilowallet's own scan and of JoinMarket-NG (both 20). */
    private const val GAP = 20

    /** How far [describe] looks for a round's address on each branch. */
    private const val SEARCH = 1000

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
