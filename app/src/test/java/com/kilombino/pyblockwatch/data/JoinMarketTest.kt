package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JoinMarket watch-only wallet: Jam's export, the fidelity-bond scripts and the bond value.
 * The vectors come from JoinMarket-NG itself (jmwallet WalletService on "abandon … about",
 * mainnet) and from a bond made and spent from Jam on regtest (checked with bitcoind's
 * decodescript).
 */
class JoinMarketTest {
    private fun hex(b: ByteArray) = with(Hashes) { b.toHex() }

    // JoinMarket-NG's get_account_zpub(0..4) for "abandon ×11 about".
    private val zpubs = listOf(
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
        "zpub6rFR7y4Q2AijF6Gk1bofHLs1d66hKFamhXWdWBup1Em25wfabZqkDqvaieV63fDQFaYmaatCG7jVNUpUiM2hAMo6SAVHcrUpSnHDpNzucB7",
        "zpub6rFR7y4Q2AijHxf5H8YD9SZ1S1hrLi3PmbR9iJeVVZSJmK8R86EPCwBhyTaycoeXEVqLigViktQUy2tt3yLnvcZ7BcXz9QxHrLjaTeJn3xL",
        "zpub6rFR7y4Q2AijKtJ66XKz29oDCtvXLTHgJ71fjNCS5kWGi97AcTfHkPPxL9GNPzR2TaqfcJx2WrcfQEHCjx7LcJz3jwwvQm4D1fcW7aiGxfT",
        "zpub6rFR7y4Q2AijM8GBWicX3FmPEK8juiGC1TueN7qQzGFLTKQbFrQsgBwrco3DgKidS4DwYUC12UULUux5XvPtgzmy1HoDpDhGABnnEyBQzsL",
    )

    private fun line(n: String = "mainnet", x: List<String> = zpubs, b: String = "[[82,1793491200]]") =
        JoinMarket.PREFIX + "{\"n\":\"$n\",\"x\":[" + x.joinToString(",") { "\"$it\"" } + "],\"b\":$b}"

    @Test fun parsesJamsExportAndWritesItBack() {
        val e = JoinMarket.parse("  " + line() + "\n")
        assertEquals("mainnet", e.network)
        assertEquals(zpubs, e.xpubs)
        assertEquals(listOf(JoinMarket.Bond(82, 1793491200)), e.bonds)
        assertEquals(e, JoinMarket.parse(e.encode()))
        assertTrue(e.encode().startsWith("kilowallet-jm:v1:{"))
    }

    @Test fun refusesWhatIsNotAnExport() {
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse(zpubs[0]) }
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse("kilowallet-jm:v2:{}") }
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse(JoinMarket.PREFIX + "{nope") }
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse(line(x = zpubs.take(4))) }
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse(line(x = zpubs.take(4) + zpubs[0])) }
        // A locktime that is not its timenumber's (82 = 2026-11-01).
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse(line(b = "[[82,1793491201]]")) }
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse(line(b = "[[960,4102444800]]")) }
        // A test network's export is only read when asked to (tests).
        val regtest = line(n = "regtest", b = "[]")
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse(regtest) }
        assertEquals("regtest", JoinMarket.parse(regtest, allowTestNetworks = true).network)
    }

    @Test fun timenumbersAreMonthsSinceJanuary2020() {
        assertEquals(1577836800L, JoinMarket.lockTime(0))
        assertEquals(1609459200L, JoinMarket.lockTime(12))
        assertEquals(1793491200L, JoinMarket.lockTime(82))      // 2026-11-01
        assertEquals(4099766400L, JoinMarket.lockTime(959))     // 2099-12-01
        assertEquals(82, JoinMarket.timenumber(1793491200L))
        assertNull(JoinMarket.timenumber(1793491201L))
        assertEquals("2026-11-01", JoinMarket.date(1793491200L))
    }

    @Test fun locktimePushIsAMinimalScriptNumber() {
        assertEquals("040081e66a", hex(JoinMarket.pushNumber(1793491200L)))
        assertEquals("0400e10b5e", hex(JoinMarket.pushNumber(1577836800L)))
        // 2099-12-01 sets the top bit of its 4th byte: a 5th byte keeps it positive.
        assertEquals("0580785df400", hex(JoinMarket.pushNumber(4099766400L)))
        assertEquals("51", hex(JoinMarket.pushNumber(1)))
    }

    /** JoinMarket-NG's get_fidelity_bond_script / _address for the same wallet. */
    @Test fun bondScriptsMatchJoinMarket() {
        val e = JoinMarket.parse(line(b = "[[0,1577836800],[82,1793491200],[959,4099766400]]"))
        val s = JoinMarket.bondScripts(e)
        assertEquals(listOf(0, 82, 959), s.map { it.bond.timenumber })
        assertEquals("bc1qhhhf29f4nlyalyfrrpfrknxj9uwqk4qsyvkujsa7w0ulfur78xkspsqn84", s[0].address)
        assertEquals("eeee53c3cdebfa601ab3580080c7031cbafc3234fdcb9ba41317fe1cbcc26b46", s[0].scriptHash)
        assertEquals("bc1qqsn877uv55enswjax29ykr6wznxqq7qxhr6trpceshj49dk0cz5qp0n3g6", s[1].address)
        assertEquals("122a5f6985bdba8ddb05e886ad3d08e86e635fb219cf39083d34d5c32848eaa1", s[1].scriptHash)
        assertEquals("m/84'/0'/0'/2/82", s[1].path)
        assertEquals("bc1qsqex3czzqzrn0n6rjayvhddygj0rz8df4fj2uwk9dkzdqkt9f7zs5c493u", s[2].address)
        assertEquals("528353e3f8720460e630978e5c792e02e7537fb38c7d997ede46875f4ac323b2", s[2].scriptHash)

        val md0 = JoinMarket.parseKey(zpubs[0])
        assertEquals(
            "040081e66ab17521020632204d17baa1a7cbd28971f17ca8a53ffbfcf67d86986dd0281862cd91ec8cac",
            hex(JoinMarket.bondScript(JoinMarket.bondPubkey(md0, 82), 1793491200L)),
        )
    }

    /** The jars are BIP-84 accounts: JoinMarket's get_address(md, branch, 0). */
    @Test fun jarAddressesMatchJoinMarket() {
        val want = listOf(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu" to "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el",
            "bc1qku0qh0mc00y8tk0n65x2tqw4trlspak0fnjmfz" to "bc1qt0x83f5vmnapgl2gjj9r3d67rcghvjaqrvgpck",
            "bc1qkljqd65ax6mdpyzm9c2r8scg8euymkfqauukae" to "bc1q8nfzpmhhsuchpjdcuv7tyy5f95kwp0vudgmgwx",
            "bc1qzycgs5zetsgk7y664m5y9u2ak7zfxykxmr628r" to "bc1qegauukq63waqctr3fym2l5th0tq9md443qj4pl",
            "bc1q8nxmfxqj5wkyssjd430twsh44sgg3chnzrey92" to "bc1q4weukw88mch53uvvh8k0vghjue4acar406gg6t",
        )
        JoinMarket.parse(line()).accounts().forEachIndexed { md, k ->
            assertEquals(want[md].first, Address.encode(Bip32.derivePath(k, 0, 0).pubkey(), ScriptType.P2WPKH))
            assertEquals(want[md].second, Address.encode(Bip32.derivePath(k, 1, 0).pubkey(), ScriptType.P2WPKH))
        }
    }

    /**
     * The bond made from Jam on regtest (2026-10-20): its witness script, as it appeared in the
     * spend and as bitcoind's decodescript reads it, comes from the export's mixdepth-0 vpub.
     */
    @Test fun regtestBondFromJam() {
        val vpub0 = "vpub5YimRAm5MY7jnzVfd7rocTTLPA6gtAogNZq8JwX5A3DNxsnCeXhNQ31D2yPno4AcTcNbeJr26CobRbG8TgxMhfGJqCFky5UHbqy5DDNyefJ"
        val ws = JoinMarket.bondScript(JoinMarket.bondPubkey(JoinMarket.parseKey(vpub0), 82), JoinMarket.lockTime(82))
        assertEquals("040081e66ab1752103808f8a4d55173b9a3f9a259835f7ce7069321700ebcf2a0fc5892b2649fe134aac", hex(ws))
        assertEquals("00200981dcf3b36f7610d26a17c896aa195e818394f83fc565a2d9dd796f0ebd6f09", hex(JoinMarket.p2wsh(ws)))
        assertEquals("bcrt1qpxqaeuandampp5n2zlyfd2set6qc898c8lzktgkem4uk7r4aduysaeda8s", JoinMarket.bondAddress(ws, "bcrt"))
    }

    /** Network and the optional master fingerprint: what a bond PSBT's key origin is made of. */
    @Test fun networkCoinTypeAndFingerprint() {
        val main = JoinMarket.parse(line())
        assertEquals(0, main.coinType); assertEquals("bc", main.hrp); assertEquals(null, main.fingerprint)
        assertEquals("[00000000/84'/0'/0'/2/82]".let { com.kilombino.pyblockwatch.crypto.Psbt.Origin.parse(it)!!.bytes().toList() },
            JoinMarket.bondOrigin(main, 82).bytes().toList())
        assertEquals("m/84'/0'/0'/2/82", JoinMarket.bondScripts(main)[0].path)
        val withFp = JoinMarket.parse(line().removeSuffix("}") + ",\"f\":\"C47272BB\"}")
        assertEquals("c47272bb", withFp.fingerprint)
        assertEquals(withFp, JoinMarket.parse(withFp.encode()))
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.parse(line().removeSuffix("}") + ",\"f\":\"xyz\"}") }
        val reg = JoinMarket.parse(line(n = "regtest").removeSuffix("}") + ",\"f\":\"c47272bb\"}", allowTestNetworks = true)
        assertEquals(1, reg.coinType); assertEquals("bcrt", reg.hrp)
        assertEquals(com.kilombino.pyblockwatch.crypto.Psbt.Origin.parse("[c47272bb/84'/1'/0'/2/82]")!!.bytes().toList(),
            JoinMarket.bondOrigin(reg, 82).bytes().toList())
        assertTrue(JoinMarket.bondScripts(reg)[0].address.startsWith("bcrt1q"))
        assertEquals("00143a0b72fbc9d6cc43acec4ababfa90fce58426901",
            hex(JoinMarket.scriptPubKey(reg, "bcrt1q8g9h977f6mxy8t8vf2atl2g0eevyy6gpne8l0k")))
        assertThrows(IllegalArgumentException::class.java) { JoinMarket.scriptPubKey(reg, "bc1q8g9h977f6mxy8t8vf2atl2g0eevyy6gpne8l0k") }
    }

    /** jmcore.bond_calc.calculate_timelocked_fidelity_bond_value for the same inputs. */
    @Test fun bondValueMatchesJoinMarket() {
        val conf = 1792515000L; val lt = 1793491200L
        assertEquals(473464L, JoinMarket.bondValue(50_000_000, conf, lt, 1792600000))
        assertEquals(473464L, JoinMarket.bondValue(50_000_000, conf, lt, lt))
        assertEquals(238608L, JoinMarket.bondValue(50_000_000, conf, lt, lt + 400_000))
        assertEquals(0L, JoinMarket.bondValue(50_000_000, conf, lt, lt + (lt - conf)))
        assertEquals(0L, JoinMarket.bondValue(50_000_000, conf, lt, lt + 100_000_000))
        val conf2 = 1735689600L; val lt2 = 1798761600L
        assertEquals(268143281L, JoinMarket.bondValue(100_000_000, conf2, lt2, 1760000000))
        assertEquals(111302953L, JoinMarket.bondValue(100_000_000, conf2, lt2, 1830000000))
        assertEquals(0L, JoinMarket.bondValue(100_000_000, conf2, lt2, 1861833600))
        assertEquals(lt + (lt - conf), JoinMarket.valueZeroAt(conf, lt))
    }

    @Test fun bondStatusAndAlerts() {
        val e = JoinMarket.parse(line())
        val script = JoinMarket.bondScripts(e)[0]
        val lt = script.bond.locktime
        val conf = lt - 100 * 86_400L
        val funded = JoinMarket.BondState(script, value = 50_000_000, txid = "ab", height = 100, confirmationTime = conf, everUsed = true)

        assertEquals(JoinMarket.Status.UNFUNDED, JoinMarket.BondState(script).status(lt))
        assertEquals(JoinMarket.Status.SPENT, JoinMarket.BondState(script, everUsed = true).status(lt))
        assertEquals(JoinMarket.Status.PENDING, funded.copy(height = 0, confirmationTime = null).status(lt - 1000))
        assertEquals(JoinMarket.Status.LOCKED, funded.status(lt - 1))
        assertEquals(JoinMarket.Status.UNLOCKED, funded.status(lt))
        assertEquals(JoinMarket.Status.UNLOCKED, funded.status(lt + 100 * 86_400L - 1))
        assertEquals(JoinMarket.Status.VALUE_ZERO, funded.status(lt + 100 * 86_400L))
        assertEquals(lt + 100 * 86_400L, funded.zeroAt)

        assertNull(JoinMarket.alertFor(funded, lt - 8 * 86_400L))
        assertEquals(JoinMarket.Alert.UNLOCKS_SOON, JoinMarket.alertFor(funded, lt - 7 * 86_400L))
        assertEquals(JoinMarket.Alert.UNLOCKED, JoinMarket.alertFor(funded, lt + 1))
        assertEquals(JoinMarket.Alert.VALUE_ZERO, JoinMarket.alertFor(funded, lt + 200 * 86_400L))
        assertNull(JoinMarket.alertFor(JoinMarket.BondState(script, everUsed = true), lt + 1))
        assertTrue(funded.currentValue(lt - 1)!! > 0)
        assertEquals(0L, funded.currentValue(lt + 100 * 86_400L))
    }

    /** A bond's confirmation time is its block's: header bytes 68–71, 80- or 164-byte headers. */
    @Test fun blockTimeFromHeader() {
        val t = 1792515000L
        val le = (0 until 4).joinToString("") { "%02x".format((t ushr (8 * it)) and 0xff) }
        assertEquals(t, JamWatch.blockTime("00".repeat(68) + le + "00".repeat(8)))
        assertEquals(t, JamWatch.blockTime("00".repeat(68) + le + "00".repeat(92)))
    }
}
