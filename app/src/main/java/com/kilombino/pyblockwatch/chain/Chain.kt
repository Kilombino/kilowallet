package com.kilombino.pyblockwatch.chain

/**
 * The two chains this wallet can watch.
 *
 * They share a genesis block and Bitcoin's whole address scheme — the BLAKE2b fork
 * changed the proof-of-work, not key derivation — so ONE xpub is meaningful on both
 * sides, and the interesting question the app answers is "what does this key hold on
 * each side of the fork?".
 *
 * A wallet is watch-only unless it holds a seed; a seed-backed (hot) wallet can sign and
 * spend. Spends go to the BLAKE2b chain, where the fork validates the unified opt-in sighash
 * that protects them from replay; SHA-256 is a read-only lookup, reached over its own server.
 * The distinction the user cares about is which chain answers, and which one their keys can move.
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
        blurb = "The classic SHA-256 chain. Read-only: find your coins with the xpub.",
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
