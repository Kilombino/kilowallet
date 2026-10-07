package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.crypto.BlockHeader
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The BLAKE2b block hash (= its proof of work) and merkle proofs, against real mainnet blocks. */
class BlockHeaderTest {
    private val headers = JSONArray(javaClass.classLoader!!.getResource("headers.json")!!.readText())

    @Test fun blockHashMatchesTheNodeForBlake2bAndPreForkHeaders() {
        for (i in 0 until headers.length()) {
            val o = headers.getJSONObject(i)
            val h = BlockHeader.parse(o.getString("header"))
            assertEquals("block ${o.getInt("height")}", o.getString("hash"), BlockHeader.blockHash(h))
            assertTrue("block ${o.getInt("height")} meets its target", BlockHeader.meetsItsTarget(h))
        }
    }

    @Test fun aTamperedHeaderFailsItsProofOfWork() {
        val o = headers.getJSONObject(0)
        val hex = o.getString("header")
        val bad = hex.substring(0, 80) + (if (hex[80] == '0') '1' else '0') + hex.substring(81) // one merkle-root nibble
        assertFalse(BlockHeader.meetsItsTarget(BlockHeader.parse(bad)))
    }

    @Test fun merkleProofFromTheServerChecksOut() {
        val c = ElectrumClient(NodeEndpoint.default(Chain.BLAKE2B), null)
        assumeTrue("needs the network", runCatching { c.connect() }.isSuccess)
        try {
            val txid = "f643e28c0a9a039634d9868c7346f9059e453b92530f2e669440d3d9370e93ad"
            val height = 976048
            val (branch, pos) = c.merkle(txid, height)
            val header = BlockHeader.parse(c.blockHeader(height))
            assertTrue(BlockHeader.inBlock(txid, branch, pos, header))
            assertFalse(BlockHeader.inBlock(txid.replaceRange(0, 1, if (txid[0] == 'f') "e" else "f"), branch, pos, header))
        } finally { c.close() }
    }
}
