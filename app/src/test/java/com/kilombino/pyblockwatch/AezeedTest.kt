package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Aez
import com.kilombino.pyblockwatch.crypto.Aezeed
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Blake2b
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Scrypt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Rescuing XBT from a pre-fork LND wallet starts from its aezeed. LND's own unit-test vectors
 * (aezeed/cipherseed_test.go, made with scrypt N = 16) pin the AEZ path; two seeds made by LND's
 * aezeed package with the real parameters, and their addresses derived with btcutil/hdkeychain,
 * pin the whole chain words → entropy → wallet and channel addresses.
 */
class AezeedTest {

    @Test
    fun `blake2b and scrypt reference vectors`() {
        assertEquals("6f56a82c8e7ef526dfe182eb5212f7db9df1317e57815dbda46083fc30f54ee6c66ba83be64b302d7cba6ce15bb556f4",
            Blake2b.digest("abc".toByteArray(), 48).toHex())
        assertEquals("7ad1025cf65da196577fc64beaede793b7b31618755c43b9122036b8341077dd92f3bc9bd6cc3167b428b13f0428ef01",
            Blake2b.digest(ByteArray(300) { 'a'.code.toByte() }, 48).toHex())
        assertEquals("fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            Scrypt.derive("password".toByteArray(), "NaCl".toByteArray(), 1024, 8, 16, 64).toHex())
        assertEquals("404b5ab55404c3c34d3b130b07262de7bed1841f22193bd7fc0c383f072676fb",
            Scrypt.derive("aezeed".toByteArray(), "salt1".toByteArray(), 32768, 8, 1, 32).toHex())
        assertEquals(0xc66363a5.toInt(), Aez.TE0[0])
    }

    private val noPass = ("ability liquid travel stem barely drastic pact cupboard apple thrive morning oak " +
        "feature tissue couch old math inform success suggest drink motion know royal").split(" ")
    private val withPass = ("able tree stool crush transfer cloud cross three profit outside hen citizen " +
        "plate ride require leg siren drum success suggest drink require fiscal upgrade").split(" ")

    // Made by LND's aezeed with the production scrypt parameters (N = 32768).
    private val realNoPass = ("about luggage aunt phone relief reunion crack pig cigar travel spin veteran " +
        "dinner art wealth thrive zone venture valve setup phone chief artist high").split(" ")
    private val realWithPass = ("above nominee ball chief neutral drive hover myth stomach news elder seed " +
        "found rather shrimp hint defy gloom magic monster youth affair imitate such").split(" ")

    @Test
    fun `lnd aezeed unit-test vectors decipher to their entropy and birthday`() {
        val a = Aezeed.decode(noPass, "", scryptN = 16)
        assertEquals("81b637d86359e6960de795e41e0b4cfd", a.entropy.toHex())
        assertEquals(0, a.birthdayDays)
        val b = Aezeed.decode(withPass, "!very_safe_55345_password*", scryptN = 16)
        assertEquals("81b637d86359e6960de795e41e0b4cfd", b.entropy.toHex())
        assertEquals(3365, b.birthdayDays)
        assertThrows(Aezeed.WrongPassphrase::class.java) { Aezeed.decode(withPass, "wrong", scryptN = 16) }
        assertThrows(IllegalArgumentException::class.java) { Aezeed.decode(noPass.reversed(), "", scryptN = 16) }
    }

    @Test
    fun `real-parameter lnd seeds decipher, with and without passphrase`() {
        assertEquals("81b637d86359e6960de795e41e0b4cfd", Aezeed.decode(realNoPass, "").entropy.toHex())
        val b = Aezeed.decode(realWithPass, "!very_safe_55345_password*")
        assertEquals("81b637d86359e6960de795e41e0b4cfd", b.entropy.toHex())
        assertEquals(3365, b.birthdayDays)
        assertThrows(Aezeed.WrongPassphrase::class.java) { Aezeed.decode(realWithPass, "") }
    }

    @Test
    fun `lnd wallet addresses from the seed match lnd`() {
        val root = Bip32Priv.fromSeed(Aezeed.decode(realNoPass, "").entropy)
        fun addr(path: String, t: ScriptType) = Address.encode(Bip32Priv.derivePath(root, path).publicKey(), t)
        assertEquals("bc1qkhdemh9jxcn2xez07vlgtkkx7gnc09xja4rs5q", addr("m/84'/0'/0'/0/0", ScriptType.P2WPKH))
        assertEquals("3JFuTHhqFphnQsZnfYfA1tLRkQwdH5eGgd", addr("m/49'/0'/0'/1/0", ScriptType.P2SH_P2WPKH))
        assertEquals("bc1pxyyd8talnncyl2wj07cy86pdtuf2tt6d4f7rc0dvhvhkzq544azqv5s4lz", addr("m/86'/0'/0'/0/0", ScriptType.P2TR))
        // Channel payment basepoint (key family 3): where a peer's force close pays us.
        assertEquals("bc1qyxxekdjv8g9xypcxqz4zwqgp4eg9yv642pramf", addr("m/1017'/0'/3'/0/0", ScriptType.P2WPKH))
    }
}
