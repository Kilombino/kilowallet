package com.kilombino.pyblockwatch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own state for the backup file, next to the words and the Ark wallet: every
 * setting (nodes, explorers, mode, fiat, derivation, gap limit, notifications, used
 * addresses, Ark/Coinjoin opt-ins…), the coinjoin rounds kept on this phone and what the
 * wallet remembers from its last looks (so a restored phone does not start its scan blind). No secret
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
            // Never a crash report (kept apart since 1.1.3; an older one may still sit here).
            // The node connections go in as text: sealed, they only open on this device. The
            // backup file itself is encrypted with its password.
            .put("store", dump(app.getSharedPreferences(STORE, Context.MODE_PRIVATE).all.filterKeys { !it.startsWith("crash_") }
                .mapValues { (k, v) -> if (k == "rpc_conns" && v is String && SecretBox.isSealed(v)) SecretBox.open(v) ?: "[]" else v }))
            .put("coinjoin", dump(app.getSharedPreferences(COINJOIN, Context.MODE_PRIVATE).all))
            .put("scan", scanMemory(app))
            .apply { com.kilombino.pyblockwatch.ln.Lightning.exportBackup(app)?.let { put("lightning", it) } }
            .toString()
    }

    /**
     * What the wallet remembers from its last looks: the balances and movements it opens on,
     * and each address's status with its history. A restored phone then opens on them and its
     * first check reuses whatever did not change. Only a shortcut: every address is still
     * asked about (see [Scanner]). The raw transactions are left out: they are fetched again.
     */
    private fun scanMemory(app: Context): JSONObject {
        val o = JSONObject()
        val snaps = JSONObject()
        java.io.File(app.noBackupFilesDir, "snapshots").listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.forEach { f ->
            runCatching { snaps.put(f.name, f.readText()) }
        }
        o.put("snapshots", snaps)
        runCatching { o.put("status", java.io.File(java.io.File(app.noBackupFilesDir, "scan-cache"), "status.json").readText()) }
        return o
    }

    private fun restoreScanMemory(app: Context, o: JSONObject) {
        val dir = java.io.File(app.noBackupFilesDir, "snapshots"); dir.mkdirs()
        o.optJSONObject("snapshots")?.let { s ->
            for (name in s.keys()) {
                // Our own file names only: hex, no path.
                if (!name.matches(Regex("[0-9a-f]{8,64}"))) continue
                runCatching { java.io.File(dir, name).writeText(s.getString(name)) }
            }
        }
        val cache = java.io.File(app.noBackupFilesDir, "scan-cache")
        o.optString("status").takeIf { it.isNotEmpty() }?.let { st ->
            runCatching { cache.mkdirs(); java.io.File(cache, "status.json").writeText(st) }
        }
        Scanner.reloadDisk(cache)
    }

    /** Settings that name a server, its pinned certificate, an explorer or your node's RPC. */
    internal fun isServerKey(k: String): Boolean {
        val key = k.removePrefix("w_")
        return key.startsWith("host_") || key.startsWith("port_") || key.startsWith("tls_") || key.startsWith("pin_") ||
            key.startsWith("explorer_") || key == "use_rpc" || key == "rpc_conns" || key.startsWith("crash_")
    }

    /** Puts back what [export] saved. Run before the wallet screen reads its settings. */
    fun import(ctx: Context, json: String) {
        val app = ctx.applicationContext
        val o = JSONObject(json)
        // Never the servers: a backup file one did not make could point the wallet at a server of
        // someone else's, with its certificate already pinned, or at their node by RPC. Those are
        // left as they are here (the defaults on a fresh install) and set again by hand.
        o.optJSONObject("store")?.let { Store.importPrefs(app.getSharedPreferences(STORE, Context.MODE_PRIVATE), load(it).filterKeys { k -> !isServerKey(k) }) }
        o.optJSONObject("coinjoin")?.let { Store.importPrefs(app.getSharedPreferences(COINJOIN, Context.MODE_PRIVATE), load(it)) }
        o.optJSONObject("scan")?.let { runCatching { restoreScanMemory(app, it) } }
        o.optJSONObject("lightning")?.let { runCatching { com.kilombino.pyblockwatch.ln.Lightning.stageRestore(app, it) } }
        com.kilombino.pyblockwatch.coinjoin.CoinjoinHub.reload(app)
    }
}
