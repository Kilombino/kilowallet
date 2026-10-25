package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.crypto.Base58
import com.kilombino.pyblockwatch.crypto.Bech32
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Hashes
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A JoinMarket (Jam) wallet watched read-only: its five jars and its fidelity bonds.
 *
 * Jam exports it (Settings › View in Kilowallet) as one line, small enough for one QR:
 *
 *     kilowallet-jm:v1:{"n":"mainnet","x":[zpub0,…,zpub4],"b":[[timenumber,locktime],…]}
 *
 * `x` are the BIP-84 account keys m/84'/0'/N' of mixdepths 0–4 (Apricot … Elderberry): each is
 * scanned like any account, receive branch 0 and change branch 1. `b` are the wallet's
 * fidelity bonds: the key of a bond is mixdepth 0's branch 2 at the bond's *timenumber*
 * (months since January 2020; JoinMarket ties one key to each locktime, the 1st of a month at
 * 00:00 UTC), and the coins sit in a P2WSH of
 *
 *     <locktime> OP_CHECKLOCKTIMEVERIFY OP_DROP <pubkey> OP_CHECKSIG
 *
 * (jmcore.btc_script.mk_freeze_script). No private key ever: this wallet cannot sign.
 */
object JoinMarket {
    const val PREFIX = "kilowallet-jm:v1:"
    const val MAINNET = "mainnet"

    /** JoinMarket's names for mixdepths 0–4. */
    val JARS = listOf("Apricot", "Blueberry", "Cherry", "Date", "Elderberry")

    /** The branch JoinMarket keeps fidelity-bond keys on (mixdepth 0 only). */
    const val BOND_BRANCH = 2

    /** One timenumber per month, January 2020 to December 2099. */
    const val TIMENUMBER_COUNT = 960

    data class Bond(val timenumber: Int, val locktime: Long)

    /**
     * [fingerprint]: the master key fingerprint (8 hex digits), when the export carries it in
     * the optional "f" field. A zpub cannot tell it, and signers need it to find their key in a
     * PSBT; without it the PSBT says 00000000, which SeedSigner fills in from its seed.
     */
    data class Export(val network: String, val xpubs: List<String>, val bonds: List<Bond>, val fingerprint: String? = null) {
        /** The same line Jam shows (compact JSON). */
        fun encode(): String = PREFIX + JSONObject()
            .put("n", network)
            .put("x", JSONArray(xpubs))
            .put("b", JSONArray(bonds.map { JSONArray().put(it.timenumber).put(it.locktime) }))
            .apply { fingerprint?.let { put("f", it) } }
            .toString()

        /** BIP-44 coin type of the keys: 0 on mainnet, 1 on JoinMarket's test networks. */
        val coinType: Int get() = if (network == MAINNET) 0 else 1

        /** The bech32 prefix of this network's addresses. */
        val hrp: String get() = when (network) { MAINNET -> "bc"; "regtest" -> "bcrt"; else -> "tb" }

        /** The account keys, ready for derivation (P2WPKH whatever their prefix). */
        fun accounts(): List<Bip32.ExtendedPubKey> = xpubs.map { parseKey(it) }

        /** A short id for this wallet (its jar keys), to name caches and compare imports. */
        val id: String get() = with(Hashes) { sha256(xpubs.joinToString(",").toByteArray()).toHex().take(16) }
    }

    /**
     * Reads an export (as scanned or pasted). Throws with a readable message on anything that
     * is not one. Only XBT mainnet is accepted unless [allowTestNetworks] (tests).
     */
    /**
     * Whether exports of a test network (regtest, testnet, signet) are accepted. Off in the
     * published app; a debuggable build turns it on (MainActivity) so a bond can be spent end to
     * end against a regtest node.
     */
    @Volatile var testNetworksAllowed = false

    fun parse(text: String, allowTestNetworks: Boolean = testNetworksAllowed): Export {
        val t = text.trim()
        require(t.startsWith(PREFIX)) {
            if (t.startsWith("kilowallet-jm:")) "This export comes from a newer Jam: update Kilowallet."
            else "Not a JoinMarket export. In Jam: Settings › View in Kilowallet (read-only)."
        }
        val o = runCatching { JSONObject(t.substring(PREFIX.length)) }
            .getOrElse { throw IllegalArgumentException("The JoinMarket export is damaged: copy it again.") }
        val network = o.optString("n", "")
        require(network == MAINNET || allowTestNetworks) { "This export is for $network, not BTC mainnet." }
        val xa = o.optJSONArray("x") ?: throw IllegalArgumentException("The JoinMarket export is damaged: copy it again.")
        val xpubs = (0 until xa.length()).map { xa.getString(it).trim() }
        require(xpubs.size == JARS.size) { "A JoinMarket export has 5 jar keys, this one has ${xpubs.size}." }
        require(xpubs.toSet().size == xpubs.size) { "The JoinMarket export is damaged: copy it again." }
        xpubs.forEach { x ->
            val k = runCatching { parseKey(x) }.getOrElse { throw IllegalArgumentException("A jar key is not valid: ${it.message}") }
            require(k.depth == 3) { "A jar key is not an account key (m/84'/0'/N')." }
        }
        val ba = o.optJSONArray("b") ?: JSONArray()
        val bonds = (0 until ba.length()).map { i ->
            val e = ba.getJSONArray(i)
            val tn = e.getInt(0); val lt = e.getLong(1)
            require(tn in 0 until TIMENUMBER_COUNT && lockTime(tn) == lt) { "A fidelity bond in the export is not valid." }
            Bond(tn, lt)
        }.distinctBy { it.timenumber }.sortedBy { it.timenumber }
        val fp = o.optString("f", "").trim().lowercase().takeIf { it.isNotEmpty() }
        require(fp == null || (fp.length == 8 && fp.all { it in "0123456789abcdef" })) { "The JoinMarket export is damaged: copy it again." }
        return Export(network, xpubs, bonds, fp)
    }

    /**
     * Parses an account key for derivation. A test network's vpub/tpub is read as the same key
     * (only the four version bytes differ); addresses are always derived as P2WPKH.
     */
    fun parseKey(encoded: String): Bip32.ExtendedPubKey {
        val raw = runCatching { Base58.decodeChecked(encoded.trim()) }.getOrNull()
        val version = raw?.takeIf { it.size == 78 }?.let {
            ((it[0].toInt() and 0xff) shl 24) or ((it[1].toInt() and 0xff) shl 16) or ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff)
        }
        val asZpub = if (raw != null && (version == VPUB || version == TPUB))
            Base58.encodeChecked(ZPUB_BYTES + raw.copyOfRange(4, 78)) else encoded
        return Bip32.parseExtendedPubKey(asZpub).copy(scriptType = com.kilombino.pyblockwatch.crypto.ScriptType.P2WPKH)
    }

    private const val VPUB = 0x045f1cf6
    private const val TPUB = 0x043587cf
    private val ZPUB_BYTES = byteArrayOf(0x04, 0xb2.toByte(), 0x47, 0x46)

    // ------------------------------------------------------------------ bonds: keys and scripts

    /** The locktime of [timenumber]: the 1st of its month, 00:00 UTC (jmcore.timenumber). */
    fun lockTime(timenumber: Int): Long {
        require(timenumber in 0 until TIMENUMBER_COUNT) { "timenumber out of range" }
        return ZonedDateTime.of(2020 + timenumber / 12, 1 + timenumber % 12, 1, 0, 0, 0, 0, ZoneOffset.UTC).toEpochSecond()
    }

    /** The timenumber of a locktime, or null when it is not the 1st of a month at 00:00 UTC. */
    fun timenumber(locktime: Long): Int? {
        val d = java.time.Instant.ofEpochSecond(locktime).atZone(ZoneOffset.UTC)
        val tn = (d.year - 2020) * 12 + (d.monthValue - 1)
        return tn.takeIf { it in 0 until TIMENUMBER_COUNT && lockTime(it) == locktime }
    }

    /** The bond's public key: mixdepth 0, branch 2, index = timenumber. */
    fun bondPubkey(mixdepth0: Bip32.ExtendedPubKey, timenumber: Int): ByteArray =
        Bip32.derivePath(mixdepth0, BOND_BRANCH, timenumber).pubkey()

    /** `<locktime> OP_CHECKLOCKTIMEVERIFY OP_DROP <pubkey> OP_CHECKSIG`, as python-bitcointx builds it. */
    fun bondScript(pubkey: ByteArray, locktime: Long): ByteArray {
        require(pubkey.size == 33) { "a compressed public key is 33 bytes" }
        return pushNumber(locktime) + byteArrayOf(OP_CLTV, OP_DROP, 33) + pubkey + byteArrayOf(OP_CHECKSIG)
    }

    /** OP_0 <sha256(witness script)>. */
    fun p2wsh(witnessScript: ByteArray): ByteArray = byteArrayOf(0x00, 0x20) + Hashes.sha256(witnessScript)

    fun bondAddress(witnessScript: ByteArray, hrp: String = "bc"): String =
        Bech32.encodeSegwit(hrp, 0, Hashes.sha256(witnessScript))

    /** A bond, worked out from the export: what to ask the server about. */
    data class BondScript(val bond: Bond, val address: String, val scriptHash: String, val path: String)

    fun bondScripts(export: Export): List<BondScript> {
        val md0 = parseKey(export.xpubs[0])
        return export.bonds.map { b ->
            val ws = bondScript(bondPubkey(md0, b.timenumber), b.locktime)
            BondScript(b, bondAddress(ws, export.hrp), com.kilombino.pyblockwatch.crypto.Address.electrumScriptHash(p2wsh(ws)),
                "m/84'/${export.coinType}'/0'/$BOND_BRANCH/${b.timenumber}")
        }
    }

    /** Where a bond's key sits: m/84'/coin'/0'/2/timenumber under the master (fingerprint 00000000 when unknown). */
    fun bondOrigin(export: Export, timenumber: Int): com.kilombino.pyblockwatch.crypto.Psbt.Origin =
        com.kilombino.pyblockwatch.crypto.Psbt.Origin(
            Hashes.hexToBytes(export.fingerprint ?: "00000000"),
            listOf(0x80000054L, 0x80000000L + export.coinType, 0x80000000L, BOND_BRANCH.toLong(), timenumber.toLong()))

    /** An address of this export's network as a scriptPubKey (SegWit/Taproot on test networks). */
    fun scriptPubKey(export: Export, address: String): ByteArray {
        val a = address.trim()
        if (export.network == MAINNET) return com.kilombino.pyblockwatch.crypto.Address.decodeToScriptPubKey(a)
        val sw = Bech32.decodeSegwit(export.hrp, a) ?: throw IllegalArgumentException("Not a valid ${export.hrp}1 address.")
        val op = if (sw.witnessVersion == 0) 0x00 else (0x50 + sw.witnessVersion)
        return byteArrayOf(op.toByte(), sw.program.size.toByte()) + sw.program
    }

    /** A script number push (CScriptNum, minimal, little-endian with a sign bit). */
    internal fun pushNumber(v: Long): ByteArray {
        if (v == 0L) return byteArrayOf(0x00)
        if (v in 1..16) return byteArrayOf((0x50 + v).toByte())
        if (v == -1L) return byteArrayOf(0x4f)
        val out = ArrayList<Byte>()
        var a = kotlin.math.abs(v)
        while (a > 0) { out += (a and 0xff).toByte(); a = a ushr 8 }
        if (out.last().toInt() and 0x80 != 0) out += (if (v < 0) 0x80 else 0x00).toByte()
        else if (v < 0) out[out.size - 1] = (out.last().toInt() or 0x80).toByte()
        require(out.size <= 75)
        return byteArrayOf(out.size.toByte()) + out.toByteArray()
    }

    private const val OP_CLTV: Byte = 0xb1.toByte()
    private const val OP_DROP: Byte = 0x75
    private const val OP_CHECKSIG: Byte = 0xac.toByte()

    // ------------------------------------------------------------------ bonds: value and status

    private const val YEAR = 60.0 * 60 * 24 * 365.2425
    const val INTEREST_RATE = 0.015
    const val VALUE_EXPONENT = 1.3

    /**
     * A timelocked bond's value, as jmcore.bond_calc computes it: it grows with the amount and
     * with how long the coins were locked (confirmation → locktime), and after the locktime it
     * falls, reaching zero once the time since the locktime equals the time it was locked.
     */
    fun bondValue(utxoValue: Long, confirmationTime: Long, locktime: Long, now: Long,
                  r: Double = INTEREST_RATE, exponent: Double = VALUE_EXPONENT): Long {
        val toMaturity = (locktime - confirmationTime) / YEAR
        val a = max(0.0, min(1.0, exp(r * toMaturity) - 1) - min(1.0, exp(r * max(0.0, now / YEAR - locktime / YEAR)) - 1))
        return (utxoValue * a).pow(exponent).toLong()
    }

    /** When the bond's value reaches zero: as long after the locktime as it was locked before it. */
    fun valueZeroAt(confirmationTime: Long, locktime: Long): Long = locktime + max(0L, locktime - confirmationTime)

    enum class Status {
        /** No coins at the bond's address (yet). */
        UNFUNDED,
        /** Sent, waiting for its first confirmation. */
        PENDING,
        /** Before the locktime: the coins cannot move. */
        LOCKED,
        /** Past the locktime, still worth something: spend or renew it from Jam. */
        UNLOCKED,
        /** Past the locktime for as long as it was locked: worth nothing to takers. */
        VALUE_ZERO,
        /** Its coins left the address. */
        SPENT,
    }

    /** What the server says about a bond's address. */
    data class BondState(
        val script: BondScript,
        /** The bond UTXO (the largest one, as JoinMarket counts it); null when there is none. */
        val value: Long? = null,
        val txid: String? = null,
        val height: Int = 0,
        /** Block time of the bond's confirmation (seconds); null while unconfirmed or unknown. */
        val confirmationTime: Long? = null,
        /** Other coins sent to the same address: locked too, but adding nothing to the bond. */
        val extraValue: Long = 0,
        val everUsed: Boolean = false,
    ) {
        val bond: Bond get() = script.bond
        val locktime: Long get() = script.bond.locktime

        fun status(now: Long): Status = when {
            value == null -> if (everUsed) Status.SPENT else Status.UNFUNDED
            height <= 0 || confirmationTime == null -> Status.PENDING
            now < locktime -> Status.LOCKED
            now < valueZeroAt(confirmationTime, locktime) -> Status.UNLOCKED
            else -> Status.VALUE_ZERO
        }

        fun currentValue(now: Long): Long? =
            if (value == null || confirmationTime == null) null else bondValue(value, confirmationTime, locktime, now)

        val zeroAt: Long? get() = confirmationTime?.let { valueZeroAt(it, locktime) }
    }

    /** Alerts are given this long before a bond unlocks. */
    const val UNLOCK_WARNING_S = 7 * 24 * 3600L

    enum class Alert { UNLOCKS_SOON, UNLOCKED, VALUE_ZERO }

    /** The alert a bond deserves now, if any (each is given once per bond, see [JamWatch]). */
    fun alertFor(s: BondState, now: Long): Alert? = when (s.status(now)) {
        Status.LOCKED -> if (s.locktime - now <= UNLOCK_WARNING_S) Alert.UNLOCKS_SOON else null
        Status.UNLOCKED -> Alert.UNLOCKED
        Status.VALUE_ZERO -> Alert.VALUE_ZERO
        else -> null
    }

    /** "2026-11-01" (UTC, as Jam shows a locktime). */
    fun date(epochSeconds: Long): String = java.time.Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate().toString()
}
