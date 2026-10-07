package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.chain.CertificateChangedException
import com.kilombino.pyblockwatch.chain.Chain
import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.crypto.TxParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** What the wallet must not take on a server's word: a transaction's content, a changed certificate. */
class ServerTrustTest {
    @Test fun txidOfASegwitTransactionLeavesTheWitnessOut() {
        // The 2-person coinjoin of kilojoin/docs/vectors.json (segwit, 0x21 signatures).
        val full = java.io.File(System.getProperty("user.home") + "/Projects/kilojoin/docs/vectors.json")
        assumeTrue(full.exists())
        val v = org.json.JSONObject(full.readText()).getJSONObject("transaction")
        assertEquals(v.getString("txid"), TxParse.txid(TxParse.parse(v.getString("raw_tx"))))
    }

    @Test fun aTamperedTransactionHasAnotherTxid() {
        val full = java.io.File(System.getProperty("user.home") + "/Projects/kilojoin/docs/vectors.json")
        assumeTrue(full.exists())
        val v = org.json.JSONObject(full.readText()).getJSONObject("transaction")
        val good = v.getString("raw_tx")
        // Change one byte of the first output's value: what a lying server would do to redirect money.
        val i = good.indexOf("1027000000000000")
        assumeTrue(i > 0)
        val bad = good.substring(0, i) + "1127000000000000" + good.substring(i + 16)
        assertNotEquals(v.getString("txid"), TxParse.txid(TxParse.parse(bad)))
    }

    @Test fun aChangedCertificateIsRefusedBeforeAnythingIsSent() {
        val ep = NodeEndpoint.default(Chain.BLAKE2B)
        val ok = runCatching { ElectrumClient(ep, null).also { it.connect(); it.close() } }
        assumeTrue("needs the network", ok.isSuccess)
        val wrongPin = "00".repeat(32)
        val r = runCatching { ElectrumClient(ep, wrongPin).connect() }
        assertTrue("expected CertificateChangedException, got ${r.exceptionOrNull()}", r.exceptionOrNull() is CertificateChangedException)
        // The right pin still connects.
        val c = ElectrumClient(ep, null); c.connect(); val fp = c.serverFingerprint!!; c.close()
        ElectrumClient(ep, fp).also { it.connect(); it.close() }
    }

    @Test fun aTransactionFromTheServerMustHashToTheTxidAsked() {
        val ep = NodeEndpoint.default(Chain.BLAKE2B)
        val c = ElectrumClient(ep, null)
        assumeTrue("needs the network", runCatching { c.connect() }.isSuccess)
        try {
            // A real coinjoin on mainnet (Kilojoin's first StartOS round).
            val txid = "f643e28c0a9a039634d9868c7346f9059e453b92530f2e669440d3d9370e93ad"
            val hex = c.transaction(txid)
            assertEquals(txid, TxParse.txid(TxParse.parse(hex)))
        } finally { c.close() }
    }
}
