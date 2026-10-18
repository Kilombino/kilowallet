package com.kilombino.pyblockwatch.ln

import com.kilombino.pyblockwatch.crypto.Bip39
import org.junit.Assert.assertEquals
import org.junit.Test

class LnSeedTest {
    /** Checked against LND 0.21.4-blake2b.20 given the same root by InitWallet's extended_master_key (getinfo identity_pubkey). */
    @Test fun nodePubkeyMatchesLnd() {
        val seed = Bip39.toSeed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "))
        assertEquals("02faeca8e7361ff87b57c7a049733d0264fd3f64e4f3e94d97926bb7eb5439f176", LnSeed.nodePubkey(seed))
    }
}
