package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * `xbt-taker-vectors.json`, produced by JoinBlaket's own Python (`scripts/xbt/taker_vectors.py`,
 * v0.43.0-xbt4), copied verbatim into the test resources.
 */
object Vectors {
    val all: JSONObject by lazy { JSONObject(text("/joinblaket/xbt-taker-vectors.json")) }

    fun text(path: String): String = Vectors::class.java.getResourceAsStream(path)!!.bufferedReader().readText()

    fun section(name: String): JSONObject = all.getJSONObject(name)

    /** The generator's fixed 32 bytes for a label: `sha256("xbt-taker-vectors/<label>")`. */
    fun h(label: String): ByteArray = Hashes.sha256("xbt-taker-vectors/$label".toByteArray())

    fun key(label: String): BigInteger = BigInteger(1, h(label))

    fun hex(s: String): ByteArray = Hashes.hexToBytes(s)

    fun big(hex: String): BigInteger = BigInteger(hex, 16)

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    /** The inner `line` of a frame string. */
    fun line(frame: String): String = Wire.parse(frame).line
}
