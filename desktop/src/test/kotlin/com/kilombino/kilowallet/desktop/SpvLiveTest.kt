package com.kilombino.kilowallet.desktop

import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.HeaderChain
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The phone's SPV proofs on the desktop JVM, against the default server; only with KW_LIVE=1. */
class SpvLiveTest {
    @Test fun provesBothSidesOfTheCheckpoint() {
        assumeTrue(System.getenv("KW_LIVE") == "1")
        HeaderChain.resetForTest()
        val c = ElectrumClient(NodeEndpoint.default(Chain.BLAKE2B), null)
        c.connect()
        try {
            HeaderChain.sync(c)
            assertEquals(true, HeaderChain.proves(c, "1e0315c8575c431b38e4422e1d7c55f652065d04e3d64b4a4694bd078e48b7d6", 976_000, 0x191b0540L))
            // Before the checkpoint (and the fork): a SHA256d block of the shared history.
            val pre = runCatching { HeaderChain.proves(c, "58b417953ab212585c33b47a894ecc246e23cd6d217e67d4793b09822a522e03", 960_900, 0x191b0540L) }
            println("pre-checkpoint: $pre")
            assertEquals(true, pre.getOrNull())
        } finally { c.close() }
    }
}
