package com.kilombino.pyblockwatch.data

import android.content.Context
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.crypto.ScriptType

/**
 * Everything this app remembers.
 *
 * Plain app-private SharedPreferences on purpose. A watch-only wallet holds NO
 * secrets — an xpub cannot spend, and this app has no signing code to misuse one
 * with — so wrapping it in EncryptedSharedPreferences would buy nothing except a
 * dependency on `androidx.security:security-crypto`, which is both an alpha and
 * deprecated by Google. An xpub IS privacy-sensitive (it reveals every address you
 * will ever use), so `allowBackup=false` keeps it off cloud backups, and Android's
 * per-app sandbox does the rest.
 */
class Store(context: Context, private val profile: String = HOT) {

    /** Per-wallet keys: the hot wallet keeps the original names, the watch-only one gets "w_", JoinMarket "jm_". */
    private fun k(key: String) = when (profile) { WATCH -> "w_$key"; JM -> "jm_$key"; else -> key }

    private val prefs = context.applicationContext
        .getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE)

    var xpub: String?
        get() = prefs.getString(k(KEY_XPUB), null)
        set(v) = prefs.edit().apply { if (v == null) remove(k(KEY_XPUB)) else putString(k(KEY_XPUB), v) }.apply()

    /** Watch-only: the account key's origin, like `[d34db33f/84'/0'/0']`, for PSBT signers ("" = unknown). */
    var keyOrigin: String
        get() = prefs.getString(k("key_origin"), "") ?: ""
        set(v) = prefs.edit().putString(k("key_origin"), v).apply()

    var label: String
        get() = prefs.getString(k(KEY_LABEL), "") ?: ""
        set(v) = prefs.edit().putString(k(KEY_LABEL), v).apply()

    /** Which chain the user last looked at, so the app reopens where they left it. */
    var lastChain: Chain
        get() = Chain.entries.firstOrNull { it.id == prefs.getString(KEY_CHAIN, null) } ?: Chain.BLAKE2B
        set(v) = prefs.edit().putString(KEY_CHAIN, v.id).apply()

    /** The user's own node by RPC (BTC only), in the order to try them; shared by both wallets. */
    // Kept sealed with [SecretBox] (they carry the node's RPC password). A plain list, from an
    // older version or a restored backup, is sealed the first time it is read.
    var rpcConns: List<com.kilombino.pyblockwatch.chain.RpcConn>
        get() = runCatching {
            val raw = prefs.getString("rpc_conns", null)
            val json = when {
                raw == null -> "[]"
                SecretBox.isSealed(raw) -> SecretBox.open(raw) ?: "[]"
                else -> raw.also { plain -> SecretBox.seal(plain)?.let { prefs.edit().putString("rpc_conns", it).apply() } }
            }
            val a = org.json.JSONArray(json)
            (0 until a.length()).map { com.kilombino.pyblockwatch.chain.RpcConn.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(v) {
            val plain = org.json.JSONArray(v.map { it.toJson() }).toString()
            prefs.edit().putString("rpc_conns", SecretBox.seal(plain) ?: plain).apply()
        }

    /** Read BTC from the node by RPC (true) or from an Electrum server (false). */
    var useRpc: Boolean
        get() = prefs.getBoolean("use_rpc", false)
        set(v) = prefs.edit().putBoolean("use_rpc", v).apply()

    fun endpoint(chain: Chain): NodeEndpoint {
        if (chain == Chain.BLAKE2B && useRpc) {
            val conns = rpcConns
            val key = xpub
            if (conns.isNotEmpty() && key != null)
                return NodeEndpoint("node", 0, isCustom = true, rpc = com.kilombino.pyblockwatch.chain.RpcNode(conns, key, scriptType))
        }
        if (!chain.allowsCustomNode) return NodeEndpoint.default(chain)
        val host = prefs.getString(keyHost(chain), null) ?: return NodeEndpoint.default(chain)
        val port = prefs.getInt(keyPort(chain), chain.defaultPort)
        return NodeEndpoint(host, port, isCustom = true)
    }

    fun setCustomEndpoint(chain: Chain, host: String?, port: Int) {
        prefs.edit().apply {
            if (host.isNullOrBlank()) { remove(keyHost(chain)); remove(keyPort(chain)) }
            else { putString(keyHost(chain), host.trim()); putInt(keyPort(chain), port) }
        }.apply()
    }

    /**
     * Trust-on-first-use certificate pinning.
     *
     * Electrum servers use self-signed certificates — Frigate's literally says
     * `CN=localhost` — so CA validation says nothing. Remembering the fingerprint we
     * first saw, and shouting when it changes, is the security property that is
     * actually available here.
     */
    fun pinnedFingerprint(endpoint: NodeEndpoint): String? =
        prefs.getString(keyPin(endpoint), null) ?: BUILT_IN_PINS["${endpoint.host}:${endpoint.port}"]

    fun pinFingerprint(endpoint: NodeEndpoint, fingerprint: String) {
        prefs.edit().putString(keyPin(endpoint), fingerprint).apply()
    }

    fun forgetPin(endpoint: NodeEndpoint) {
        prefs.edit().remove(keyPin(endpoint)).apply()
    }

    /**
     * One past the highest used index of a branch (0 receive, 1 change) on either chain, for
     * this xpub and address [type]. Only grows: an address once used stays used.
     *
     * Kept per type because one watch-only key can be read as bc1q or as Taproot: its bc1q
     * addresses being used says nothing about its Taproot ones. Before 1.2.4 it was kept per
     * key only; that old number is still read for every type but Taproot (a Taproot account
     * used to be its own key, and a watch-only key switched to Taproot is what this fixes).
     */
    fun usedTop(xpub: String, chainIndex: Int, type: ScriptType): Int =
        prefs.getInt(keyUsedTop(xpub, chainIndex, type),
            if (type == ScriptType.P2TR) 0 else prefs.getInt(keyUsedTop(xpub, chainIndex, null), 0))

    fun noteUsedTop(xpub: String, chainIndex: Int, top: Int, type: ScriptType) {
        if (top > usedTop(xpub, chainIndex, type)) prefs.edit().putInt(keyUsedTop(xpub, chainIndex, type), top).apply()
    }

    private fun keyUsedTop(xpub: String, chainIndex: Int, type: ScriptType?) =
        "used_top_${chainIndex}_" + (type?.let { "${it.name}_" } ?: "") +
            com.kilombino.pyblockwatch.crypto.Hashes.sha256(xpub.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** Last known total per chain, so the service can tell "changed" from "first run". */
    fun lastTotal(chain: Chain): Long = prefs.getLong(k(keyTotal(chain)), -1L)
    fun setLastTotal(chain: Chain, sats: Long) {
        prefs.edit().putLong(k(keyTotal(chain)), sats).apply()
    }

    // Confirmed and unconfirmed tracked apart so the watcher can tell "arrived in the
    // mempool" from "just confirmed" from "sent", and word the notification accordingly.
    fun lastConfirmed(chain: Chain): Long = prefs.getLong(k(keyConf(chain)), -1L)
    fun lastUnconfirmed(chain: Chain): Long = prefs.getLong(k(keyUnconf(chain)), 0L)
    fun setLastBalance(chain: Chain, confirmed: Long, unconfirmed: Long) {
        prefs.edit().putLong(k(keyConf(chain)), confirmed).putLong(k(keyUnconf(chain)), unconfirmed).apply()
    }

    // The watcher keeps its OWN baseline of what it has already notified about, separate
    // from the figures the foreground app records — otherwise every open would reset the
    // baseline and the background notification could never fire.
    fun lastNotifiedConf(chain: Chain): Long = prefs.getLong(k(keyNotConf(chain)), -1L)
    fun lastNotifiedUnconf(chain: Chain): Long = prefs.getLong(k(keyNotUnconf(chain)), 0L)
    fun setLastNotified(chain: Chain, confirmed: Long, unconfirmed: Long) {
        prefs.edit().putLong(k(keyNotConf(chain)), confirmed).putLong(k(keyNotUnconf(chain)), unconfirmed).apply()
    }

    /**
     * The set of wallet transactions currently sitting in the mempool, each mapped to the
     * sats it moved (signed: + received, − sent). Tracked so the watcher can say "new
     * outgoing payment in the mempool: N sats" the moment it appears, and later "first
     * confirmation of the outgoing payment of N sats" when that txid first confirms — the amount is
     * remembered from when it entered the mempool, because a confirmation is balance-neutral
     * (it only moves sats from unconfirmed to confirmed) and carries no delta of its own.
     */
    fun pendingMap(chain: Chain): MutableMap<String, Long> {
        val raw = prefs.getString(k(keyPending(chain)), null) ?: return mutableMapOf()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            val out = mutableMapOf<String, Long>()
            obj.keys().forEach { out[it] = obj.getLong(it) }
            out
        }.getOrElse { mutableMapOf() }
    }

    fun setPendingMap(chain: Chain, map: Map<String, Long>) {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(k(keyPending(chain)), obj.toString()).apply()
    }

    /**
     * Which home screen to show: "simple" (balance, Send, Receive, fiat) or "advanced"
     * (everything else). Null until the user has chosen once.
     */
    var uiMode: String?
        get() = prefs.getString("ui_mode", null)
        set(v) = prefs.edit().putString("ui_mode", v).apply()

    /**
     * Block explorer (a mempool.space-style site) for [chain], used to open a movement.
     * Defaults to Kilombino's: mempool.kilombino.com for BLAKE2b and its SHA-256 twin.
     */
    fun explorer(chain: Chain): String =
        prefs.getString("explorer_${chain.id}", null) ?: defaultExplorer(chain)

    fun setExplorer(chain: Chain, url: String?) {
        val clean = url?.trim()?.trimEnd('/')
        if (clean.isNullOrBlank()) prefs.edit().remove("explorer_${chain.id}").apply()
        else prefs.edit().putString("explorer_${chain.id}", clean).apply()
        setExplorerChosen(chain)
    }

    fun defaultExplorer(chain: Chain): String = when (chain) {
        Chain.BLAKE2B -> "https://mempool.kilombino.com"
        Chain.SHA256 -> "https://nobip110mempool.kilombino.com"
    }

    /** Fiat currency for conversions: "USD" or "EUR". */
    var fiat: String
        get() = prefs.getString("fiat", "USD") ?: "USD"
        set(v) = prefs.edit().putString("fiat", v).apply()

    /** Ask mempool.kilombino.com for prices and mining figures (off: no fiat values, no request). */
    var showPrices: Boolean
        get() = prefs.getBoolean("show_prices", true)
        set(v) = prefs.edit().putBoolean("show_prices", v).apply()

    /** How many consecutive empty addresses end a branch scan. Configurable; sane bounds. */
    var gapLimit: Int
        get() = prefs.getInt(KEY_GAP, 20).coerceIn(5, 100)
        set(v) = prefs.edit().putInt(KEY_GAP, v.coerceIn(5, 100)).apply()

    // On by default: the whole point is to be told when coins arrive without opening the app.
    /**
     * Whether the user has ever accepted connecting to the public SHA-256 (spamchain) servers.
     * Until then the app never contacts them: not at start-up, not in the background.
     */
    var spamchainAccepted: Boolean
        get() = prefs.getBoolean("spamchain_accepted", false)
        set(v) = prefs.edit().putBoolean("spamchain_accepted", v).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY, v).apply()

    /**
     * When every address of [chain] was last asked about (ms). Looks in between leave out the
     * addresses that are done with (see Scanner.lookup); a day later the next look is full.
     */
    fun lastFullLook(chain: Chain): Long = prefs.getLong(k("full_look_" + chain.name), 0)
    fun setLastFullLook(chain: Chain, at: Long = System.currentTimeMillis()) =
        prefs.edit().putLong(k("full_look_" + chain.name), at).apply()
    fun fullLookDue(chain: Chain): Boolean = System.currentTimeMillis() - lastFullLook(chain) > FULL_LOOK_EVERY_MS

    /** Seconds between refreshes while the app is on screen; 0 = only by hand. */
    var refreshOpenSeconds: Int
        get() = prefs.getInt("refresh_open_s", 300)
        set(v) = prefs.edit().putInt("refresh_open_s", v).apply()

    /**
     * Minutes between checks with the app closed; 0 = off: no watcher, no persistent
     * notification, no battery used while closed.
     */
    var refreshBackgroundMinutes: Int
        get() = prefs.getInt("refresh_bg_min", 15)
        set(v) = prefs.edit().putInt("refresh_bg_min", v).apply()

    /**
     * Which address type to derive from the xpub. Defaults to native SegWit (BIP-84,
     * m/84'/0'/0', bc1q); the user can switch to Nested (BIP-49), Legacy (BIP-44) or
     * Taproot (BIP-86). Independent of the xpub prefix, so a plain `xpub` still works.
     */
    var scriptType: ScriptType
        get() = prefs.getString(k(KEY_SCRIPT), null)
            ?.let { runCatching { ScriptType.valueOf(it) }.getOrNull() } ?: ScriptType.P2WPKH
        set(v) = prefs.edit().putString(k(KEY_SCRIPT), v.name).apply()

    /**
     * Watch-only: whether [scriptType] is known to be the wallet's own and not just the default a
     * plain `xpub` gets: a ypub/zpub says it, and so does picking it on import or in settings.
     * Coinjoin outputs go to a watch-only wallet only then, or they could land on addresses of a
     * type the other wallet never looks at.
     */
    var typeChosen: Boolean
        get() = prefs.getBoolean(k("type_chosen"), false)
        set(v) = prefs.edit().putBoolean(k("type_chosen"), v).apply()

    /**
     * Hot wallet only: the seed's Taproot account (BIP-86, m/86'/0'/0'), next to the BIP-84 one in
     * [xpub]. Once set it is always scanned, so its coins stay visible and spendable whichever
     * kind of address [receiveTaproot] hands out. Null until Taproot is turned on.
     */
    var taprootXpub: String?
        get() = prefs.getString(k("taproot_xpub"), null)
        set(v) = prefs.edit().apply { if (v == null) remove(k("taproot_xpub")) else putString(k("taproot_xpub"), v) }.apply()

    /** Hot wallet: new receive and change addresses are Taproot (bc1p) instead of native SegWit. */
    var receiveTaproot: Boolean
        get() = prefs.getBoolean(k("receive_taproot"), false)
        set(v) = prefs.edit().putBoolean(k("receive_taproot"), v).apply()

    // ---------------------------------------------------------------- optional features
    // Ark and Coinjoin are opt-in: a new user sees what they are and accepts before using them.

    /** Which wallet is on screen: [HOT] (the seed's) or [WATCH] (a separate xpub). Global. */
    var activeWallet: String
        get() = prefs.getString("active_wallet", HOT) ?: HOT
        set(v) = prefs.edit().putString("active_wallet", v).apply()

    /**
     * The JoinMarket wallet (read-only, from Jam's export) is on screen instead of [activeWallet].
     * Global; [activeWallet] keeps the hot/watch-only one to go back to.
     */
    var jamView: Boolean
        get() = prefs.getBoolean("jam_view", false)
        set(v) = prefs.edit().putBoolean("jam_view", v).apply()

    /** JoinMarket profile: Jam's export line (account keys and bonds), null when none is imported. */
    var jmExport: String?
        get() = prefs.getString(k("export"), null)
        set(v) = prefs.edit().apply { if (v == null) remove(k("export")) else putString(k("export"), v) }.apply()

    /** JoinMarket profile: a bond alert already given (key = bond utxo + kind), so it is given once. */
    fun alerted(key: String): Boolean = prefs.getBoolean(k("alert_$key"), false)
    fun setAlerted(key: String) = prefs.edit().putBoolean(k("alert_$key"), true).apply()

    /** JoinMarket profile: block time of a bond's confirmation, by txid (it never changes). */
    fun confTime(txid: String): Long? = prefs.getLong(k("conft_$txid"), -1L).takeIf { it > 0 }
    fun setConfTime(txid: String, time: Long) = prefs.edit().putLong(k("conft_$txid"), time).apply()

    /** Simple/advanced was chosen for the hot wallet (watch-only never asks). */
    var hotModeChosen: Boolean
        get() = prefs.getBoolean("hot_mode_chosen", prefs.getString("ui_mode", null) != null && prefs.getString("xpub", null) != null)
        set(v) = prefs.edit().putBoolean("hot_mode_chosen", v).apply()

    /** Look on GitHub for a newer release when the app opens. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean("check_updates", true)
        set(v) = prefs.edit().putBoolean("check_updates", v).apply()

    /** The user picked their own block explorer (or kept the default on purpose): stop asking. */
    fun explorerChosen(chain: Chain): Boolean = prefs.getBoolean("explorer_chosen_${chain.id}", false)
    fun setExplorerChosen(chain: Chain) = prefs.edit().putBoolean("explorer_chosen_${chain.id}", true).apply()

    /** The user went through the Coinjoin explainer and accepted it. */
    var coinjoinEnabled: Boolean
        get() = prefs.getBoolean("coinjoin_enabled", false)
        set(v) = prefs.edit().putBoolean("coinjoin_enabled", v).apply()

    /** Notify new coinjoin pools (from the background watcher). Remembered as the user left it. */
    var coinjoinNotify: Boolean
        get() = prefs.getBoolean("coinjoin_notify", false)
        set(v) = prefs.edit().putBoolean("coinjoin_notify", v).apply()

    /** The one-time "interested in coinjoins?" question was answered (yes or no). */
    var coinjoinAsked: Boolean
        get() = prefs.getBoolean("coinjoin_asked", false)
        set(v) = prefs.edit().putBoolean("coinjoin_asked", v).apply()

    /** Every preference of this file, for the full backup (no secrets live here). */
    fun exportAll(): Map<String, *> = prefs.all

    /** Puts back what [exportAll] saved, over whatever is here. */
    fun importAll(values: Map<String, *>) = importPrefs(prefs, values)

    fun clearWallet() {
        prefs.edit().apply {
            remove(k(KEY_XPUB)); remove(k(KEY_LABEL)); remove(k("taproot_xpub")); remove(k("receive_taproot")); remove(k("type_chosen"))
            if (profile == JM) {
                remove(k("export"))
                prefs.all.keys.filter { it.startsWith(k("alert_")) || it.startsWith(k("conft_")) || it.startsWith(k("full_look_")) }.forEach { remove(it) }
            }
            Chain.entries.forEach {
                remove(k(keyTotal(it))); remove(k(keyConf(it))); remove(k(keyUnconf(it)))
                remove(k(keyNotConf(it))); remove(k(keyNotUnconf(it))); remove(k(keyPending(it)))
            }
        }.apply()
    }

    companion object {
        /** A full look of every address at least this often. */
        const val FULL_LOOK_EVERY_MS = 24 * 3600 * 1000L

        /**
         * Certificates shipped with the app, so even the first connection to the default server
         * is checked (not trust-on-first-use). fulcrum.kilombino.com's own certificate,
         * self-signed, valid until 2036-08-31.
         */
        val BUILT_IN_PINS = mapOf(
            "fulcrum.kilombino.com:17717" to "506dadc710c5abaeb13191056c5aaf47035d30e08bd869f7b4fbe6e13745d5a7",
        )

        const val HOT = "hot"
        const val WATCH = "watch"
        /** The JoinMarket wallet, watched from Jam's export (never signs). */
        const val JM = "joinmarket"

        /** Writes a typed key→value map into [p] (Boolean, Int, Long, Float, String, Set<String>). */
        fun importPrefs(p: android.content.SharedPreferences, values: Map<String, *>) {
            val e = p.edit()
            for ((k, v) in values) when (v) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            e.apply()
        }

        private const val KEY_XPUB = "xpub"
        private const val KEY_LABEL = "label"
        private const val KEY_CHAIN = "chain"
        private const val KEY_NOTIFY = "notify"
        private const val KEY_SCRIPT = "scripttype"
        private const val KEY_GAP = "gaplimit"
        private fun keyHost(c: Chain) = "host_${c.id}"
        private fun keyPort(c: Chain) = "port_${c.id}"
        private fun keyTotal(c: Chain) = "total_${c.id}"
        private fun keyConf(c: Chain) = "conf_${c.id}"
        private fun keyUnconf(c: Chain) = "unconf_${c.id}"
        private fun keyNotConf(c: Chain) = "notconf_${c.id}"
        private fun keyNotUnconf(c: Chain) = "notunconf_${c.id}"
        private fun keyPending(c: Chain) = "pending_${c.id}"
        private fun keyPin(e: NodeEndpoint) = "pin_${e.host}_${e.port}"
    }
}
