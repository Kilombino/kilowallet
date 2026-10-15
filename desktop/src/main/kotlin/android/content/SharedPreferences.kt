package android.content

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Android's key-value preferences, as the shared code uses them. */
interface SharedPreferences {
    fun getString(key: String, def: String?): String?
    fun getStringSet(key: String, def: Set<String>?): Set<String>?
    fun getInt(key: String, def: Int): Int
    fun getLong(key: String, def: Long): Long
    fun getFloat(key: String, def: Float): Float
    fun getBoolean(key: String, def: Boolean): Boolean
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, value: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/**
 * Preferences kept as one JSON file per name in the app's private folder, written whole and
 * atomically (a temporary file renamed over the old one), owner-only.
 */
internal class DesktopPrefs private constructor(private val file: File) : SharedPreferences {
    private val map = LinkedHashMap<String, Any>()

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    map[k] = when (e.getString("t")) {
                        "s" -> e.getString("v")
                        "i" -> e.getInt("v")
                        "l" -> e.getLong("v")
                        "f" -> e.getDouble("v").toFloat()
                        "b" -> e.getBoolean("v")
                        "set" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                        else -> continue
                    }
                }
            }
        }
    }

    @Synchronized override fun getString(key: String, def: String?) = map[key] as? String ?: def
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String, def: Set<String>?) = (map[key] as? Set<String>)?.toSet() ?: def
    @Synchronized override fun getInt(key: String, def: Int) = (map[key] as? Number)?.toInt() ?: def
    @Synchronized override fun getLong(key: String, def: Long) = (map[key] as? Number)?.toLong() ?: def
    @Synchronized override fun getFloat(key: String, def: Float) = (map[key] as? Number)?.toFloat() ?: def
    @Synchronized override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    @Synchronized override fun contains(key: String) = key in map
    override val all: Map<String, *> @Synchronized get() = LinkedHashMap(map)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = LinkedHashMap<String, Any?>()
        private var wipe = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, value: Set<String>?) = apply { puts[key] = value?.toSet() }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { wipe = true }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(this@DesktopPrefs) {
            if (wipe) map.clear()
            for ((k, v) in puts) if (v == null) map.remove(k) else map[k] = v
            save()
        }
    }

    private fun save(): Boolean = runCatching {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, when (v) {
            is String -> JSONObject().put("t", "s").put("v", v)
            is Int -> JSONObject().put("t", "i").put("v", v)
            is Long -> JSONObject().put("t", "l").put("v", v)
            is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
            is Boolean -> JSONObject().put("t", "b").put("v", v)
            is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.toList()))
            else -> continue
        })
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        DesktopContext.private(tmp)
        check(tmp.renameTo(file))
        true
    }.getOrDefault(false)

    companion object {
        private val open = HashMap<String, DesktopPrefs>()
        fun of(name: String): DesktopPrefs = synchronized(open) {
            open.getOrPut(name) { DesktopPrefs(File(DesktopContext.root("shared_prefs"), "$name.json")) }
        }
    }
}
