package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Words with a BIP-39 passphrase (with accents, so NFKD matters) restore the same Taproot and
 * native SegWit addresses as Bitcoin Core/Knots' `deriveaddresses` on the same master key.
 */
class PassphraseTaprootTest {
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    private val master = Bip32Priv.fromSeed(Bip39.toSeed(words, "Contraseña ñ"))

    private fun first(purpose: Int, type: ScriptType): String {
        val account = Bip32.parseExtendedPubKey(Bip32Priv.accountXpub(master, purpose = purpose, account = 0))
        return Address.encode(Bip32.deriveChild(Bip32.deriveChild(account, 0), 0).pubkey(), type)
    }

    @Test
    fun `passphrase taproot account matches the node`() =
        assertEquals("bc1px37z6es7uu6r3qzc3qwd9hneyx3sfnprszmkeenvhs0l79356wlq0g3kp2", first(86, ScriptType.P2TR))

    @Test
    fun `passphrase segwit account matches the node`() =
        assertEquals("bc1qsxn90a28a29mxeqrrcy5upd3ua6fz7d9w8lxly", first(84, ScriptType.P2WPKH))
}
