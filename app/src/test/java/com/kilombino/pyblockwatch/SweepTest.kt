package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.TxBuilder
import com.kilombino.pyblockwatch.crypto.Wif
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sweeping a private key: every single-key form the key can hold coins in, signed exactly as
 * Bitcoin Knots signs it. The expected transactions come from `signrawtransactionwithkey` with
 * the same keys and prevouts: on regtest (legacy SIGHASH_ALL) and on the BLAKE2b mainnet node
 * (unified sighash). Signing grinds low R like Core, so the bytes must match exactly.
 */
class SweepTest {

    private val keyA = Wif.decode("5JipF8XWSsGUtAFGBiTkKZVNhgfGBoro53vJmDjX7FJundLYbSC")   // uncompressed
    private val keyB = Wif.decode("L4xGdkvtuV41e3f5LNgHjiPFizsXxayoUw7J1njTxjYZCqLik7Nw")  // compressed

    private fun inputs(legacyOnly: Boolean): List<TxBuilder.Input> {
        val all = listOf(
            TxBuilder.Input("11".repeat(32), 0, 100_000, keyA.privateKey, keyA.pubkey, 0xfffffffdL, ScriptType.P2PKH),
            TxBuilder.Input("22".repeat(32), 1, 50_000, keyB.privateKey, keyB.pubkey, 0xfffffffdL, ScriptType.P2SH_P2WPKH),
            TxBuilder.Input("33".repeat(32), 2, 20_000, keyB.privateKey, keyB.pubkey, 0xfffffffdL, ScriptType.P2WPKH),
            TxBuilder.Input("44".repeat(32), 3, 10_000, keyB.privateKey, keyB.pubkey, 0xfffffffdL, ScriptType.P2PKH),
        )
        return if (legacyOnly) all.take(1) else all
    }

    private fun outputs(sats: Long) = listOf(TxBuilder.Output(Address.scriptPubKey(keyB.pubkey, ScriptType.P2WPKH), sats))

    @Test
    fun `wif keys decode with their compression flag`() {
        assertFalse(keyA.compressed)
        assertTrue(keyB.compressed)
        assertEquals("0473591f364b1a07f16ac54fb84e1463643eb536f7417bbe8719068bc1094d221381ceb2d49fd9ac655fadfe94783181598dcc98a542d560dd45eff816f416e02b", with(com.kilombino.pyblockwatch.crypto.Hashes) { keyA.pubkey.toHex() })
        assertEquals("03112479b15bec3821666713c4af0cf6118228cc28a76bf42e233531b3b03ff2f9", with(com.kilombino.pyblockwatch.crypto.Hashes) { keyB.pubkey.toHex() })
        assertEquals(listOf(ScriptType.P2PKH), keyA.sweepableTypes)
    }

    @Test
    fun `mixed legacy, nested and native inputs match Knots, legacy sighash`() {
        val tx = TxBuilder.build(inputs(false), outputs(170_000), unified = false, grindLowR = true)
        assertEquals(
            "020000000001041111111111111111111111111111111111111111111111111111111111111111000000008a47304402" +
            "203a0f7837b5b811da94c46cbc18e63d8a6651c8825c0dfefac1a98be03711a21002200c1ac6b65e7059cefd1f654fa3" +
            "7a2c1dce03aafce68493d148992406325a4c9801410473591f364b1a07f16ac54fb84e1463643eb536f7417bbe871906" +
            "8bc1094d221381ceb2d49fd9ac655fadfe94783181598dcc98a542d560dd45eff816f416e02bfdffffff222222222222" +
            "2222222222222222222222222222222222222222222222222222010000001716001457bce7381cfd73ad3dab8c10ad2d" +
            "e1d28a0f4603fdffffff33333333333333333333333333333333333333333333333333333333333333330200000000fd" +
            "ffffff4444444444444444444444444444444444444444444444444444444444444444030000006a47304402200e8bb3" +
            "252a2d878b231b034469efc40f597f80106684286585105771b509fcd902202f16091ae32d7323df1be9a90e50d8a3b8" +
            "1a9970c2baf5637b07f24cba65881d012103112479b15bec3821666713c4af0cf6118228cc28a76bf42e233531b3b03f" +
            "f2f9fdffffff01109802000000000016001457bce7381cfd73ad3dab8c10ad2de1d28a0f4603000247304402202629fe" +
            "087a89d31393642dcf5710aead4bf9579f8a42a4c192e1a739e80944f502203a30298168fb5ee6717de3c644f5c92937" +
            "c9343622f968c57e5d245ffdadf589012103112479b15bec3821666713c4af0cf6118228cc28a76bf42e233531b3b03f" +
            "f2f90247304402206fc96079da1eddcbc3c9243da56689304d46a109dec25b2d08cb12e84f137f53022078b99cdfb5a1" +
            "9dd90df0ff6a63755487c0829a3cd001e3f26d52fa29a6c33808012103112479b15bec3821666713c4af0cf6118228cc" +
            "28a76bf42e233531b3b03ff2f90000000000",
            tx.rawHex,
        )
    }

    @Test
    fun `mixed legacy, nested and native inputs match Knots, unified sighash`() {
        val tx = TxBuilder.build(inputs(false), outputs(170_000), unified = true, grindLowR = true)
        assertEquals(
            "020000000001041111111111111111111111111111111111111111111111111111111111111111000000008a47304402" +
            "205738913fcbadb2a556f49b78aab057166cb2a52e3e87f1f1af39526cd5315cee0220642ae8172b9da25c8ef37550aa" +
            "9d049f74829c8bb0396288b11483bee0289bbe21410473591f364b1a07f16ac54fb84e1463643eb536f7417bbe871906" +
            "8bc1094d221381ceb2d49fd9ac655fadfe94783181598dcc98a542d560dd45eff816f416e02bfdffffff222222222222" +
            "2222222222222222222222222222222222222222222222222222010000001716001457bce7381cfd73ad3dab8c10ad2d" +
            "e1d28a0f4603fdffffff33333333333333333333333333333333333333333333333333333333333333330200000000fd" +
            "ffffff4444444444444444444444444444444444444444444444444444444444444444030000006a47304402206ad0ad" +
            "1f3cd0935e6d81583bbb3b8715ab4e6457bcf9d4ff9a10927e937172fd02206cb996f82f457f65db31c0c577e3711b1c" +
            "b353c7b6c2c1f618ce1fe45e267437212103112479b15bec3821666713c4af0cf6118228cc28a76bf42e233531b3b03f" +
            "f2f9fdffffff01109802000000000016001457bce7381cfd73ad3dab8c10ad2de1d28a0f460300024730440220783857" +
            "3e21145bb8f910b560a691f822f523e0f4f34bd736ce8b078828fce8a502207f3f259c0c3a12e576279e1d4f94359e29" +
            "58edae5e17b5031324e4ed2912be61212103112479b15bec3821666713c4af0cf6118228cc28a76bf42e233531b3b03f" +
            "f2f90247304402206c315c2dc2d5104cb4c2d6f44e6d655501a48253c2ce389fb77d20fd84fa939802204e0ae995b995" +
            "3a9ddb9752da87d9cde5cbef9d5faa610ed6e1651d29f011acb7212103112479b15bec3821666713c4af0cf6118228cc" +
            "28a76bf42e233531b3b03ff2f90000000000",
            tx.rawHex,
        )
    }

    @Test
    fun `a legacy-only sweep has no witness and matches Knots`() {
        assertEquals(
            "02000000011111111111111111111111111111111111111111111111111111111111111111000000008a473044022075" +
            "440639f58b10dbd5386c7b69d5ba148b3b1b7d5bf7332cef48bed055b213a90220767567e58fa05c28229db43114abd6" +
            "ffd8c01045479dd562fce0db85b90b833901410473591f364b1a07f16ac54fb84e1463643eb536f7417bbe8719068bc1" +
            "094d221381ceb2d49fd9ac655fadfe94783181598dcc98a542d560dd45eff816f416e02bfdffffff01b8820100000000" +
            "0016001457bce7381cfd73ad3dab8c10ad2de1d28a0f460300000000",
            TxBuilder.build(inputs(true), outputs(99_000), unified = false, grindLowR = true).rawHex,
        )
        assertEquals(
            "02000000011111111111111111111111111111111111111111111111111111111111111111000000008a473044022051" +
            "3f31ab3f2215a85be08c55624b0c4f295d9bf850a448190ec681fce0c2410b022075a117b30f726d5b947f7345c40c64" +
            "99bf8bf9413f454b07193ab8d8099a1e7221410473591f364b1a07f16ac54fb84e1463643eb536f7417bbe8719068bc1" +
            "094d221381ceb2d49fd9ac655fadfe94783181598dcc98a542d560dd45eff816f416e02bfdffffff01b8820100000000" +
            "0016001457bce7381cfd73ad3dab8c10ad2de1d28a0f460300000000",
            TxBuilder.build(inputs(true), outputs(99_000), unified = true, grindLowR = true).rawHex,
        )
    }
}
