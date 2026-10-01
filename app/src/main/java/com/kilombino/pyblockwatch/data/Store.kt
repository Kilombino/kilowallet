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
class Store(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pyblockwatch", Context.MODE_PRIVATE)

    var xpub: String?
        get() = prefs.getString(KEY_XPUB, null)
        set(v) = prefs.edit().apply { if (v == null) remove(KEY_XPUB) else putString(KEY_XPUB, v) }.apply()

    var label: String
        get() = prefs.getString(KEY_LABEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LABEL, v).apply()

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

    /** Last known total per chain, so the service can tell "changed" from "first run". */
    fun lastTotal(chain: Chain): Long = prefs.getLong(keyTotal(chain), -1L)
    fun setLastTotal(chain: Chain, sats: Long) {
        prefs.edit().putLong(keyTotal(chain), sats).apply()
    }

    // Confirmed and unconfirmed tracked apart so the watcher can tell "arrived in the
    // mempool" from "just confirmed" from "sent", and word the notification accordingly.
    fun lastConfirmed(chain: Chain): Long = prefs.getLong(keyConf(chain), -1L)
    fun lastUnconfirmed(chain: Chain): Long = prefs.getLong(keyUnconf(chain), 0L)
    fun setLastBalance(chain: Chain, confirmed: Long, unconfirmed: Long) {
        prefs.edit().putLong(keyConf(chain), confirmed).putLong(keyUnconf(chain), unconfirmed).apply()
    }

    // The watcher keeps its OWN baseline of what it has already notified about, separate
    // from the figures the foreground app records — otherwise every open would reset the
    // baseline and the background notification could never fire.
    fun lastNotifiedConf(chain: Chain): Long = prefs.getLong(keyNotConf(chain), -1L)
    fun lastNotifiedUnconf(chain: Chain): Long = prefs.getLong(keyNotUnconf(chain), 0L)
    fun setLastNotified(chain: Chain, confirmed: Long, unconfirmed: Long) {
        prefs.edit().putLong(keyNotConf(chain), confirmed).putLong(keyNotUnconf(chain), unconfirmed).apply()
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
        val raw = prefs.getString(keyPending(chain), null) ?: return mutableMapOf()
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
        prefs.edit().putString(keyPending(chain), obj.toString()).apply()
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
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY, v).apply()

    /**
     * Which address type to derive from the xpub. Defaults to native SegWit (BIP-84,
     * m/84'/0'/0', bc1q); the user can switch to Nested (BIP-49), Legacy (BIP-44) or
     * Taproot (BIP-86). Independent of the xpub prefix, so a plain `xpub` still works.
     */
    var scriptType: ScriptType
        get() = prefs.getString(KEY_SCRIPT, null)
            ?.let { runCatching { ScriptType.valueOf(it) }.getOrNull() } ?: ScriptType.P2WPKH
        set(v) = prefs.edit().putString(KEY_SCRIPT, v.name).apply()

    fun clearWallet() {
        prefs.edit().apply {
            remove(KEY_XPUB); remove(KEY_LABEL)
            Chain.entries.forEach {
                remove(keyTotal(it)); remove(keyConf(it)); remove(keyUnconf(it))
                remove(keyNotConf(it)); remove(keyNotUnconf(it)); remove(keyPending(it))
            }
        }.apply()
    }

    private companion object {
        const val KEY_XPUB = "xpub"
        const val KEY_LABEL = "label"
        const val KEY_CHAIN = "chain"
        const val KEY_NOTIFY = "notify"
        const val KEY_SCRIPT = "scripttype"
        const val KEY_GAP = "gaplimit"
        fun keyHost(c: Chain) = "host_${c.id}"
        fun keyPort(c: Chain) = "port_${c.id}"
        fun keyTotal(c: Chain) = "total_${c.id}"
        fun keyConf(c: Chain) = "conf_${c.id}"
        fun keyUnconf(c: Chain) = "unconf_${c.id}"
        fun keyNotConf(c: Chain) = "notconf_${c.id}"
        fun keyNotUnconf(c: Chain) = "notunconf_${c.id}"
        fun keyPending(c: Chain) = "pending_${c.id}"
        fun keyPin(e: NodeEndpoint) = "pin_${e.host}_${e.port}"
    }
}
