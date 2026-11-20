package com.kilombino.pyblockwatch.roblo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * What the Roblosats client keeps on the phone (robot tokens, the Garage Key, settings, the
 * on-chain rescue data): the bridge's getEncryptedStorage/setEncryptedStorage, in preferences of
 * their own ("roblo"), every value sealed with [com.kilombino.pyblockwatch.data.SecretBox] (an AES
 * key in the phone's keystore), as the Roblosats app keeps them in EncryptedSharedPreferences.
 * They travel in the .kab backup (its "roblo" section, the backup file is encrypted).
 */
class RobloStore(private val prefs: KeyValues, private val sealer: Sealer) {
    /** Where the values live: SharedPreferences on the phone, a map in tests. */
    interface KeyValues {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
        fun keys(): Set<String>
    }

    /** Seals a value at rest; null when it can't (then nothing is written). */
    interface Sealer {
        fun seal(plain: String): String?
        fun open(sealed: String): String?
    }

    private val cache = HashMap<String, String>()

    /** "" when there is nothing under [key] (what the client expects for a missing key). */
    @Synchronized fun get(key: String): String {
        require(validKey(key)) { "bad key" }
        cache[key]?.let { return it }
        val v = prefs.get(PREFIX + key)?.let { sealer.open(it) } ?: return ""
        cache[key] = v
        return v
    }

    @Synchronized fun set(key: String, value: String): Boolean {
        require(validKey(key)) { "bad key" }
        require(value.length <= MAX_VALUE) { "value too large" }
        val sealed = sealer.seal(value) ?: return false
        prefs.put(PREFIX + key, sealed)
        cache[key] = value
        return true
    }

    @Synchronized fun delete(key: String) {
        require(validKey(key)) { "bad key" }
        prefs.remove(PREFIX + key); cache.remove(key)
    }

    /** Clearnet chosen in Roblosats' own settings (its Tor switch off); Tor otherwise, the default. */
    fun clearnet(): Boolean = runCatching { get(USE_PROXY) == "false" }.getOrDefault(false)

    /** Any robot or Garage Key kept (the .kab's "include robots" box shows only then). */
    fun hasRobots(): Boolean = runCatching { get("garage_slots").let { it.isNotEmpty() && it != "{}" && it != "[]" } || get("garage_key").isNotEmpty() }.getOrDefault(false)

    /** Everything, opened, for the backup file (which is itself encrypted with its password). */
    @Synchronized fun export(): JSONObject {
        val items = JSONObject()
        for (k in prefs.keys().filter { it.startsWith(PREFIX) }.sorted()) {
            val key = k.removePrefix(PREFIX)
            if (!validKey(key)) continue
            val v = prefs.get(k)?.let { sealer.open(it) } ?: continue
            items.put(key, v)
        }
        return JSONObject().put("v", VERSION).put("items", items)
    }

    /**
     * Puts back what [export] saved: a restore brings the robots back as they were. Keys that are
     * not the client's (bad names) are skipped; values are sealed again with this phone's key.
     * Returns how many values were written.
     */
    @Synchronized fun import(o: JSONObject): Int {
        require(o.optInt("v", 0) in 1..VERSION) { "Roblosats data from a newer version" }
        val items = o.optJSONObject("items") ?: return 0
        var n = 0
        for (key in items.keys()) {
            if (!validKey(key)) continue
            val v = items.optString(key, "")
            if (v.length > MAX_VALUE) continue
            if (set(key, v)) n++
        }
        return n
    }

    companion object {
        const val VERSION = 1
        private const val PREFIX = "v:"
        const val USE_PROXY = "settings_use_proxy"
        const val MAX_VALUE = 4_000_000
        private val KEY = Regex("^[A-Za-z0-9_.,:;!?()\\[\\]{}\\-]{1,200}$")

        /** The client's storage keys: the characters the Roblosats app accepts, minus blanks and quotes. */
        fun validKey(key: String) = KEY.matches(key)

        @Volatile private var instance: RobloStore? = null

        /** The phone's store: preferences "roblo", sealed with SecretBox. */
        fun of(ctx: Context): RobloStore = instance ?: synchronized(this) {
            instance ?: RobloStore(Prefs(ctx.applicationContext.getSharedPreferences("roblo", Context.MODE_PRIVATE)), object : Sealer {
                override fun seal(plain: String) = com.kilombino.pyblockwatch.data.SecretBox.seal(plain)
                override fun open(sealed: String) = com.kilombino.pyblockwatch.data.SecretBox.open(sealed)
            }).also { instance = it }
        }
    }

    private class Prefs(private val p: SharedPreferences) : KeyValues {
        override fun get(key: String) = p.getString(key, null)
        override fun put(key: String, value: String) { p.edit().putString(key, value).commit() }
        override fun remove(key: String) { p.edit().remove(key).commit() }
        override fun keys(): Set<String> = p.all.keys
    }
}
