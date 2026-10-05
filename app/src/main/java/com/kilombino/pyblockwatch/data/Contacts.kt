package com.kilombino.pyblockwatch.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved payment destinations with a name: Ark addresses, XBT addresses, reusable Lightning
 * offers (BOLT12) and user@domain handles (BIP-353). One-off Lightning invoices are never
 * saved: they can only be paid once.
 *
 * Kept on the phone and copied into the Ark backup file, so a restore brings them back.
 * The recovery words alone do not.
 */
object Contacts {
    data class Contact(val name: String, val dest: String, val added: Long) {
        val kind: Kind get() = kindOf(dest) ?: Kind.XBT
    }

    enum class Kind(val label: String) { ARK("Ark"), XBT("XBT"), OFFER("Lightning offer"), HANDLE("user@domain") }

    @Volatile private var app: Context? = null

    /** Remembers the application context so [digest] can run without one. */
    fun attach(ctx: Context) { if (app == null) app = ctx.applicationContext }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("kilombino_contacts", Context.MODE_PRIVATE)

    /** What [dest] is, or null when it is not worth saving (an invoice, or not a destination). */
    fun kindOf(dest: String): Kind? {
        val d = dest.trim()
        val l = d.lowercase()
        return when {
            l.startsWith("ark1") || l.startsWith("tark1") -> Kind.ARK
            l.startsWith("lno1") -> Kind.OFFER
            l.startsWith("lnbc") || l.startsWith("lntb") || l.startsWith("lnbcrt") || l.startsWith("lnurl") -> null
            Regex("^[^@\\s]+@[a-z0-9.-]+\\.[a-z]{2,}$").matches(l) -> Kind.HANDLE
            l.startsWith("bc1") || l.startsWith("sp1") -> Kind.XBT
            Regex("^[13][1-9A-HJ-NP-Za-km-z]{25,34}$").matches(d) -> Kind.XBT
            else -> null
        }
    }

    fun savable(dest: String): Boolean = kindOf(dest) != null

    fun all(ctx: Context): List<Contact> {
        attach(ctx)
        return parse(prefs(ctx).getString("list", null)).sortedBy { it.name.lowercase() }
    }

    fun find(ctx: Context, dest: String): Contact? = all(ctx).firstOrNull { same(it.dest, dest) }

    /** Adds [dest] under [name], or renames it if it is already saved. */
    fun save(ctx: Context, name: String, dest: String) {
        val n = name.trim().take(60)
        require(n.isNotEmpty()) { "Give the contact a name." }
        require(savable(dest)) { "This can't be saved as a contact." }
        val rest = all(ctx).filterNot { same(it.dest, dest) }
        write(ctx, rest + Contact(n, dest.trim(), System.currentTimeMillis()))
    }

    fun remove(ctx: Context, dest: String) = write(ctx, all(ctx).filterNot { same(it.dest, dest) })

    /** The list as stored in the backup file; null when there is nothing to store. */
    fun export(ctx: Context): String? = all(ctx).takeIf { it.isNotEmpty() }?.let { toJson(it) }

    /** Adds the contacts of a backup file to the ones already here; a name here wins. */
    fun merge(ctx: Context, json: String?) {
        if (json.isNullOrBlank()) return
        val here = all(ctx)
        val extra = parse(json).filter { c -> savable(c.dest) && here.none { same(it.dest, c.dest) } }
        if (extra.isNotEmpty()) write(ctx, here + extra)
    }

    /** Changes whenever the list does; empty when there are no contacts. */
    fun digest(): String {
        val ctx = app ?: return ""
        val list = all(ctx)
        if (list.isEmpty()) return ""
        return list.joinToString(";") { it.name + "=" + it.dest }
    }

    // Ark and Lightning codes are case-insensitive; legacy XBT addresses are not.
    private fun same(a: String, b: String): Boolean {
        val x = a.trim(); val y = b.trim()
        return if (kindOf(x) == Kind.XBT && !x.lowercase().startsWith("bc1")) x == y else x.equals(y, ignoreCase = true)
    }

    private fun write(ctx: Context, list: List<Contact>) {
        prefs(ctx).edit().putString("list", toJson(list)).apply()
    }

    private fun toJson(list: List<Contact>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("name", it.name).put("dest", it.dest).put("added", it.added)) }
    }.toString()

    private fun parse(json: String?): List<Contact> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").trim()
                val dest = o.optString("dest").trim()
                if (name.isEmpty() || dest.isEmpty()) null else Contact(name, dest, o.optLong("added"))
            }
        }.getOrDefault(emptyList())
    }
}
