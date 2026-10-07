package com.kilombino.pyblockwatch.ark

/**
 * The Ark engine: the Paperclip/bark wallet (Rust, MIT) compiled for Android as
 * libkilombino_ark.so. It runs the wallet daemon and its REST API inside this process,
 * on 127.0.0.1 only, behind a random bearer token generated at every start.
 */
internal object ArkNative {
    /** False when this build or this CPU has no Ark engine; the wallet then hides Ark. */
    val available: Boolean = runCatching { System.loadLibrary("kilombino_ark") }.isSuccess

    /**
     * Starts the engine; returns the bearer token, or "ERR:<message>". [mnemonic] is null
     * while there is no Ark wallet yet; [passphrase] is its BIP-39 passphrase, "" for none.
     * The engine keeps both in memory only.
     */
    external fun start(datadir: String, port: Int, mnemonic: String?, passphrase: String): String

    external fun stop()

    /** libsecp256k1 (constant time): 65-byte uncompressed public key of a 32-byte secret, or null. */
    external fun secpPubkey(secret: ByteArray): ByteArray?
    /** RFC 6979 low-S ECDSA, compact r‖s; [lowR] grinds like Bitcoin Core. Null if invalid. */
    external fun secpEcdsaSign(secret: ByteArray, hash: ByteArray, lowR: Boolean): ByteArray?
    /** BIP-340 Schnorr signature. Null if invalid. */
    external fun secpSchnorrSign(secret: ByteArray, msg: ByteArray, aux: ByteArray): ByteArray?
}
