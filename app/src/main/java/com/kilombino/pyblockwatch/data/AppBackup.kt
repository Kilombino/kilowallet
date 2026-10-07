package com.kilombino.pyblockwatch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own state for the backup file, next to the words and the Ark wallet: every
 * setting (nodes, explorers, mode, fiat, derivation, gap limit, notifications, used
 * addresses, Ark/Coinjoin opt-ins…) and the coinjoin rounds kept on this phone. No secret
 * lives in these preferences: the seed stays in the Keystore and travels as the words.
 *
 * Values are tagged with their type, because JSON cannot tell an Int from a Long and
 * SharedPreferences refuses a value read back as the wrong one.
 */
object AppBackup {
    private const val STORE = "pyblockwatch"
    private const val COINJOIN = "coinjoin"

    private fun dump(values: Map<String, *>): JSONObject {
        val o = JSONObject()
        for ((k, v) in values) {
            val (t, x) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is String -> "s" to v
                is Set<*> -> "ss" to JSONArray(v.filterIsInstance<String>())
                else -> continue
            }
            o.put(k, JSONObject().put("t", t).put("v", x))
        }
        return o
    }

    private fun load(o: JSONObject): Map<String, Any> {
        val m = HashMap<String, Any>()
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            m[k] = when (e.getString("t")) {
                "b" -> e.getBoolean("v")
                "i" -> e.getInt("v")
                "l" -> e.getLong("v")
                "f" -> e.getDouble("v").toFloat()
                "s" -> e.getString("v")
                "ss" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> continue
            }
        }
        return m
    }

    fun export(ctx: Context): String {
        val app = ctx.applicationContext
        return JSONObject()
            .put("store", dump(app.getSharedPreferences(STORE, Context.MODE_PRIVATE).all))
            .put("coinjoin", dump(app.getSharedPreferences(COINJOIN, Context.MODE_PRIVATE).all))
            .toString()
    }

    /** Puts back what [export] saved. Run before the wallet screen reads its settings. */
    fun import(ctx: Context, json: String) {
        val app = ctx.applicationContext
        val o = JSONObject(json)
        o.optJSONObject("store")?.let { Store.importPrefs(app.getSharedPreferences(STORE, Context.MODE_PRIVATE), load(it)) }
        o.optJSONObject("coinjoin")?.let { Store.importPrefs(app.getSharedPreferences(COINJOIN, Context.MODE_PRIVATE), load(it)) }
        com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.reload(app)
    }
}
