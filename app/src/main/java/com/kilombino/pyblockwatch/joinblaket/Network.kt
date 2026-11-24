package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bech32

/**
 * The chain a JoinBlaket round runs on: the handshake's `network` and the address prefix both
 * the taker and its makers use. BTC BLAKE2b mainnet keeps Bitcoin's `bc1` addresses
 * (`jmcore/models.py:110-130`); the regtest lab uses `bcrt1`.
 */
enum class JbNetwork(val wire: String, val hrp: String) {
    MAINNET("xbt", "bc"),
    REGTEST("xbt-regtest", "bcrt");

    /**
     * The scriptPubKey of [address] if it is an address of this network, else throws
     * (`taker/coinjoin_session.py:976-983,1178-1184`: both maker addresses must decode for our
     * network). Mainnet takes every address type the wallet knows; regtest only bech32.
     */
    fun scriptPubKey(address: String): ByteArray {
        if (this == MAINNET) return Address.decodeToScriptPubKey(address)
        val sw = Bech32.decodeSegwit(hrp, address.trim()) ?: throw IllegalArgumentException("Not a valid ${hrp}1 address.")
        val op = if (sw.witnessVersion == 0) 0x00 else 0x50 + sw.witnessVersion
        return byteArrayOf(op.toByte(), sw.program.size.toByte()) + sw.program
    }

    /** The native SegWit v0 address of a P2WPKH [scriptPubKey]. */
    fun p2wpkhAddress(scriptPubKey: ByteArray): String {
        require(scriptPubKey.size == 22 && scriptPubKey[0] == 0.toByte() && scriptPubKey[1] == 0x14.toByte()) { "not P2WPKH" }
        return Bech32.encodeSegwit(hrp, 0, scriptPubKey.copyOfRange(2, 22))
    }
}

/** One JoinBlaket directory: an onion, with the nick-auth id it signs challenges with (its own lowercase `host:port`). */
data class DirectoryEndpoint(val host: String, val port: Int = 5222) {
    val id: String get() = "$host:$port"
}

/**
 * Where JoinBlaket lives on mainnet: start10's directory ([HOST]) and two run by trusted
 * operators, all onions with nick authentication. The taker uses them all ([DirectoryPool]):
 * a maker is found on whichever of them it announces, and one being down is no problem.
 */
object JoinBlaketDirectory {
    const val HOST = "f3tfzgk5ilwfapx427yx5ckeh7ptuvskfxnfnicfo747jyq5mgzdtvad.onion"
    const val PORT = 5222
    /** The nick-auth identity the directory signs challenges with: its own lowercase `host:port`. */
    const val ID = "$HOST:$PORT"
    val NETWORK = JbNetwork.MAINNET
    val ALL = listOf(
        DirectoryEndpoint(HOST, PORT),
        DirectoryEndpoint("xgn4ctdotf6w5tnnqg6revvfyzgxkthbzy25zqxyowhv2cmtuqegwuyd.onion"),
        DirectoryEndpoint("ftow7ou2tnxuiowxtva4elpuebzk2vq5cbvj4gqdnbzx6wy2x7awekid.onion"),
    )
}
