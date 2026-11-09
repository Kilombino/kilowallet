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
    // The 2-person coinjoin of Kilojoin's docs/vectors.json (segwit, 0x21 signatures), vendored.
    private fun vectors() = org.json.JSONObject(
        javaClass.classLoader!!.getResource("kilojoin-vectors.json")!!.readText()).getJSONObject("transaction")

    @Test fun theDefaultServerIsPinnedFromTheFirstConnection() {
        val ep = NodeEndpoint.default(Chain.BLAKE2B)
        assertEquals("506dadc710c5abaeb13191056c5aaf47035d30e08bd869f7b4fbe6e13745d5a7",
            com.kilombino.pyblockwatch.data.Store.BUILT_IN_PINS["${ep.host}:${ep.port}"])
    }
    @Test fun txidOfASegwitTransactionLeavesTheWitnessOut() {
        val v = vectors()
        assertEquals(v.getString("txid"), TxParse.txid(TxParse.parse(v.getString("raw_tx"))))
    }

    @Test fun aTamperedTransactionHasAnotherTxid() {
        val v = vectors()
        val good = v.getString("raw_tx")
        // Change one byte of the first output's value: what a lying server would do to redirect money.
        val i = good.indexOf("1027000000000000")
        assumeTrue(i > 0)
        val bad = good.substring(0, i) + "1127000000000000" + good.substring(i + 16)
        assertNotEquals(v.getString("txid"), TxParse.txid(TxParse.parse(bad)))
    }

    @Test fun theDefaultServerConnectsWithTheBuiltInPinAfterMovingToLetsEncrypt() {
        // Every installed copy holds the old self-signed pin; the server now shows a
        // Let's Encrypt certificate. It must still connect, through the authority check.
        val ep = NodeEndpoint.default(Chain.BLAKE2B)
        val pin = com.kilombino.pyblockwatch.data.Store.BUILT_IN_PINS["${ep.host}:${ep.port}"]
        val probe = runCatching { ElectrumClient(ep, null).also { it.connect(); it.close() } }
        assumeTrue("needs the network", probe.isSuccess)
        val c = ElectrumClient(ep, pin)
        c.connect()
        try {
            if (!pin.equals(c.serverFingerprint, ignoreCase = true)) assertTrue(c.caVerified)
        } finally { c.close() }
    }

    @Test fun aCertificateNameMatchesLikeABrowser() {
        assertTrue(ElectrumClient.nameMatches("fulcrum.kilombino.com", "FULCRUM.kilombino.com"))
        assertTrue(ElectrumClient.nameMatches("*.kilombino.com", "fulcrum.kilombino.com"))
        assertTrue(!ElectrumClient.nameMatches("*.kilombino.com", "a.fulcrum.kilombino.com"))
        assertTrue(!ElectrumClient.nameMatches("*.kilombino.com", "kilombino.com"))
        assertTrue(!ElectrumClient.nameMatches("fulcrum.kilombino.com.evil.net", "fulcrum.kilombino.com"))
    }

    @Test fun aChangedCertificateIsRefusedBeforeAnythingIsSent() {
        // The same server by its address: not on the list of names an authority may vouch for,
        // so only the pin counts there.
        val def = NodeEndpoint.default(Chain.BLAKE2B)
        val ip = runCatching { java.net.InetAddress.getByName(def.host).hostAddress }.getOrNull()
        assumeTrue("needs the network", ip != null)
        val ep = def.copy(host = ip!!)
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
