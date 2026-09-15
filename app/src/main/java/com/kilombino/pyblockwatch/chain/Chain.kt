package com.kilombino.pyblockwatch.chain

/**
 * The two chains this wallet can watch.
 *
 * They share a genesis block and Bitcoin's whole address scheme — the BLAKE2b fork
 * changed the proof-of-work, not key derivation — so ONE xpub is meaningful on both
 * sides, and the interesting question the app answers is "what does this key hold on
 * each side of the fork?".
 *
 * Watching is read-only, but the app also carries an opt-in hot wallet that can create keys
 * on the device and sign spends here. Spends on BLAKE2B sign with the unified opt-in sighash
 * so they cannot be replayed onto the SHA256d chain; spends on SHA256D use the legacy sighash
 * that chain requires.
 */
enum class Chain(
    val id: String,
    val display: String,
    val ticker: String,
    val defaultHost: String,
    val defaultPort: Int,
    val accent: Long,
    val blurb: String,
) {
    BLAKE2B(
        id = "blake2b",
        display = "BLAKE2b",
        ticker = "₿",
        defaultHost = "fulcrum.kilombino.com",
        defaultPort = 17717,
        accent = 0xFFB96BFF,
        blurb = "The BLAKE2b proof-of-work fork. 164-byte headers.",
    ),
    SHA256(
        id = "sha256",
        display = "SHA-256",
        ticker = "₿",
        defaultHost = "nobip110fulcrum.kilombino.com",
        defaultPort = 50002,
        accent = 0xFFF7931A,
        blurb = "The classic SHA-256 chain. Spends here sign legacy, so they are not replay-protected.",
    );

    /** Only BLAKE2b offers pointing at your own node; SHA-256 is a lookup service. */
    val allowsCustomNode: Boolean get() = this == BLAKE2B
}

/** Where to reach a chain: the bundled default, or a node the user typed in. */
data class NodeEndpoint(val host: String, val port: Int, val isCustom: Boolean = false) {
    override fun toString() = "$host:$port"
    companion object {
        fun default(chain: Chain) = NodeEndpoint(chain.defaultHost, chain.defaultPort, false)
    }
}
