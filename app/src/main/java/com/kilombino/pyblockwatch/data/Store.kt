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

    /** Per-wallet keys: the hot wallet keeps the original names, the watch-only one gets "w_". */
    private fun k(key: String) = if (profile == WATCH) "w_$key" else key

    private val prefs = context.applicationContext
        .getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE)

    var xpub: String?
        get() = prefs.getString(k(KEY_XPUB), null)
        set(v) = prefs.edit().apply { if (v == null) remove(k(KEY_XPUB)) else putString(k(KEY_XPUB), v) }.apply()

    var label: String
        get() = prefs.getString(k(KEY_LABEL), "") ?: ""
        set(v) = prefs.edit().putString(k(KEY_LABEL), v).apply()

    /** Which chain the user last looked at, so the app reopens where they left it. */
    var lastChain: Chain
        get() = Chain.entries.firstOrNull { it.id == prefs.getString(KEY_CHAIN, null) } ?: Chain.BLAKE2B
        set(v) = prefs.edit().putString(KEY_CHAIN, v.id).apply()

    fun endpoint(chain: Chain): NodeEndpoint {
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
        prefs.getString(keyPin(endpoint), null)

    fun pinFingerprint(endpoint: NodeEndpoint, fingerprint: String) {
        prefs.edit().putString(keyPin(endpoint), fingerprint).apply()
    }

    fun forgetPin(endpoint: NodeEndpoint) {
        prefs.edit().remove(keyPin(endpoint)).apply()
    }

    /**
     * One past the highest used index of a branch (0 receive, 1 change) on either chain, for
     * this xpub. Only grows: an address once used stays used.
     */
    fun usedTop(xpub: String, chainIndex: Int): Int = prefs.getInt(keyUsedTop(xpub, chainIndex), 0)

    fun noteUsedTop(xpub: String, chainIndex: Int, top: Int) {
        if (top > usedTop(xpub, chainIndex)) prefs.edit().putInt(keyUsedTop(xpub, chainIndex), top).apply()
    }

    private fun keyUsedTop(xpub: String, chainIndex: Int) =
        "used_top_${chainIndex}_" + com.kilombino.pyblockwatch.crypto.Hashes.sha256(xpub.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }

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
     * Which address type to derive from the xpub. Defaults to native SegWit (BIP-84,
     * m/84'/0'/0', bc1q); the user can switch to Nested (BIP-49), Legacy (BIP-44) or
     * Taproot (BIP-86). Independent of the xpub prefix, so a plain `xpub` still works.
     */
    var scriptType: ScriptType
        get() = prefs.getString(k(KEY_SCRIPT), null)
            ?.let { runCatching { ScriptType.valueOf(it) }.getOrNull() } ?: ScriptType.P2WPKH
        set(v) = prefs.edit().putString(k(KEY_SCRIPT), v.name).apply()

    // ---------------------------------------------------------------- optional features
    // Ark and Coinjoin are opt-in: a new user sees what they are and accepts before using them.

    /** Which wallet is on screen: [HOT] (the seed's) or [WATCH] (a separate xpub). Global. */
    var activeWallet: String
        get() = prefs.getString("active_wallet", HOT) ?: HOT
        set(v) = prefs.edit().putString("active_wallet", v).apply()

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
            remove(k(KEY_XPUB)); remove(k(KEY_LABEL))
            Chain.entries.forEach {
                remove(k(keyTotal(it))); remove(k(keyConf(it))); remove(k(keyUnconf(it)))
                remove(k(keyNotConf(it))); remove(k(keyNotUnconf(it))); remove(k(keyPending(it)))
            }
        }.apply()
    }

    companion object {
        const val HOT = "hot"
        const val WATCH = "watch"

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
