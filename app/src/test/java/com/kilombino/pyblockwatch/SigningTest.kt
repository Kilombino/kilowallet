package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The signing half is new and the whole point is that it never produces a wrong signature,
 * so these are the published vectors: BIP-32 Test Vector 1, a BIP-39 vector with the TREZOR
 * passphrase, and internal ECDSA round-trips. The unforgiving end-to-end anchor (BIP-143) is
 * exercised by the transaction test.
 */
class SigningTest {

    @Test
    fun `bip32 master and hardened child match test vector 1`() {
        val seed = Hashes.hexToBytes("000102030405060708090a0b0c0d0e0f")
        val m = Bip32Priv.fromSeed(seed)
        assertEquals(
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35",
            m.keyBytes().toHex(),
        )
        assertEquals(
            "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
            m.chainCode.toHex(),
        )
        val m0h = Bip32Priv.derivePath(m, "m/0'")
        assertEquals(
            "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea",
            m0h.keyBytes().toHex(),
        )
        assertEquals(
            "47fdacbd0f1097043b78c63c20c34ef4ed9a111d980047ad16282c7ae6236141",
            m0h.chainCode.toHex(),
        )
    }

    @Test
    fun `bip39 all-zero entropy yields the abandon mnemonic and TREZOR seed`() {
        val entropy = ByteArray(16) // 128 bits of zero
        val words = Bip39.fromEntropy(entropy)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about",
            words.joinToString(" "),
        )
        assertTrue(Bip39.isValid(words))
        val seed = Bip39.toSeed(words, "TREZOR")
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e534955" +
                "31f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            seed.toHex(),
        )
    }

    @Test
    fun `bip39 rejects a tampered checksum`() {
        val good = "abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about"
        assertTrue(Bip39.isValid(good.split(" ")))
        val bad = good.replace("about", "abandon")
        assertFalse(Bip39.isValid(bad.split(" ")))
    }

    @Test
    fun `dice rolls need the right count and produce SeedSigner entropy`() {
        val fifty = "1".repeat(50)
        val e = Bip39.entropyFromDiceRolls(fifty, 128)
        assertEquals(16, e.size)
        // SeedSigner: SHA-256 of the roll string, truncated. Pin the known digest.
        assertEquals(Hashes.sha256(fifty.toByteArray()).copyOf(16).toHex(), e.toHex())
    }

    @Test
    fun `ecdsa signs deterministically, verifies, and rejects a tampered message`() {
        val d = BigInteger("cca9fbcc1b41e5a95d369eaa6ddcff73b61a4efaa279cfc6567e8daa39cbaf50", 16)
        val pub = Secp256k1.multiply(d, Secp256k1.G)
        val hash = Hashes.sha256("Kilombino Bitcoin-Blake2b".toByteArray())

        val a = Ecdsa.sign(d, hash)
        val b = Ecdsa.sign(d, hash)
        assertEquals("RFC 6979 must be deterministic", a, b)          // same (key, msg) → same sig
        assertTrue(a.s <= Secp256k1.N.shiftRight(1))                  // low-S
        assertTrue(Ecdsa.verify(pub, hash, a))                       // verifies
        val other = Hashes.sha256("different".toByteArray())
        assertFalse(Ecdsa.verify(pub, other, a))                     // not under a different message
    }

    /**
     * The widely published secp256k1 RFC 6979 (HMAC-SHA256, low-S) vectors. libsecp256k1
     * produces these exact signatures, as does embit, the library inside SeedSigner — the
     * same check https://newtonick.github.io/deterministic-nonce-check/ runs on a signer:
     * a deterministic nonce leaves no room to hide anything in the signature.
     */
    @Test
    fun `ecdsa nonces and signatures match the RFC 6979 secp256k1 vectors`() {
        val n1 = Secp256k1.N.subtract(BigInteger.ONE)
        listOf(
            Triple(BigInteger.ONE, "Satoshi Nakamoto",
                "3045022100934b1ea10a4b3c1757e2b0c017d0b6143ce3c9a7e6a4a49860d7a6ab210ee3d8" +
                    "02202442ce9d2b916064108014783e923ec36b49743e2ffa1c4496f01a512aafd9e5"),
            Triple(BigInteger.ONE, "All those moments will be lost in time, like tears in rain. Time to die...",
                "30450221008600dbd41e348fe5c9465ab92d23e3db8b98b873beecd930736488696438cb6b" +
                    "0220547fe64427496db33bf66019dacbf0039c04199abb0122918601db38a72cfc21"),
            Triple(n1, "Satoshi Nakamoto",
                "3045022100fd567d121db66e382991534ada77a6bd3106f0a1098c231e47993447cd6af2d0" +
                    "02206b39cd0eb1bc8603e159ef5c20a5c8ad685a45b06ce9bebed3f153d10d93bed5"),
        ).forEach { (d, msg, expected) ->
            val sig = Ecdsa.sign(d, Hashes.sha256(msg.toByteArray()))
            assertEquals(msg, expected, Ecdsa.der(sig).joinToString("") { "%02x".format(it) })
        }
    }

    @Test
    fun `bip84 account zpub and first address match the published vector`() {
        val mnemonic = ("abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about").split(" ")
        val master = Bip32Priv.fromSeed(Bip39.toSeed(mnemonic))
        val zpub = Bip32Priv.accountXpub(master, purpose = 84, account = 0)
        assertEquals(
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtf" +
                "SdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            zpub,
        )
        val first = Bip32Priv.derivePath(master, "m/84'/0'/0'/0/0")
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            com.kilombino.pyblockwatch.crypto.Address.encode(
                first.publicKey(), com.kilombino.pyblockwatch.crypto.ScriptType.P2WPKH),
        )
    }

    @Test
    fun `private key one has G as its public point`() {
        val pub = Secp256k1.multiply(BigInteger.ONE, Secp256k1.G)
        assertEquals(Secp256k1.compress(Secp256k1.G).toHex(), Secp256k1.compress(pub).toHex())
    }
}
