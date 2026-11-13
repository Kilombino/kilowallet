package com.kilombino.pyblockwatch.joinblaket

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The PoDLE commitments this wallet already showed to makers (`taker/podle_manager.py`). A
 * commitment is burned once a maker has seen it: makers broadcast `!hp2 <commitment>` and every
 * maker then refuses it, so one is never sent twice. It is marked used BEFORE it is sent, so a
 * crash in between wastes an index instead of reusing one.
 */
interface CommitmentStore {
    fun isUsed(commitmentHex: String): Boolean
    /** Record [commitmentHex] as used, durably, before returning. */
    fun markUsed(commitmentHex: String)
}

/**
 * [CommitmentStore] in Python's `cmtdata/commitments.json` format, `{"used": [hex…], "external": {}}`,
 * so the file reads the same in both. Written to a temporary file and renamed into place: a
 * crash leaves the old list or the new one, never half of one.
 */
class FileCommitmentStore(private val file: File) : CommitmentStore {
    private val lock = Any()

    private fun read(): MutableSet<String> {
        if (!file.exists()) return LinkedHashSet()
        val o = JSONObject(file.readText())
        val a = o.optJSONArray("used") ?: JSONArray()
        return (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it).lowercase() }
    }

    override fun isUsed(commitmentHex: String): Boolean = synchronized(lock) { commitmentHex.lowercase() in read() }

    override fun markUsed(commitmentHex: String) = synchronized(lock) {
        val used = read()
        if (!used.add(commitmentHex.lowercase())) return@synchronized
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("used", JSONArray(used.toList())).put("external", JSONObject()).toString())
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "could not save the used commitments" } }
    }

    /** How many commitments were used: for tests and the backup summary. */
    fun count(): Int = synchronized(lock) { read().size }
}
