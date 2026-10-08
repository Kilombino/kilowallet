package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.chain.ElectrumClient
import com.kilombino.pyblockwatch.chain.NodeEndpoint
import com.kilombino.pyblockwatch.chain.RpcConn
import com.kilombino.pyblockwatch.chain.RpcNode
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The RPC backend answering the wallet's Electrum questions from a real node. Runs when
 * KW_RPC_URL (http://user:pass@host:port) and KW_RPC_KEY (an account key with history) are set.
 */
class NodeRpcTest {
    @Test fun parsesConnectionTexts() {
        assertEquals(RpcConn("http://10.0.0.5:8332/", "u", "p:x"), RpcConn.parse("btcrpc://u:p%3Ax@10.0.0.5:8332"))
        assertEquals(RpcConn("http://abc.onion:8332/", "", ""), RpcConn.parse("abc.onion"))
        assertTrue(RpcConn.parse("http://a:b@xyz.onion:18443").isOnion)
    }

    @Test fun answersLikeAnElectrumServer() {
        val url = System.getenv("KW_RPC_URL"); val key = System.getenv("KW_RPC_KEY")
        assumeTrue(url != null && key != null)
        val c = ElectrumClient(NodeEndpoint("node", 0, true, RpcNode(listOf(RpcConn.parse(url!!)), key!!, ScriptType.P2WPKH)), null)
        c.connect()
        assertTrue(c.isOwnNode)
        val acct = Bip32.parseExtendedPubKey(key)
        fun sh(b: Int, i: Int) = Address.electrumScriptHash(Address.scriptPubKey(Bip32.derivePath(acct, b, i).pubkey(), ScriptType.P2WPKH))
        // Receive 0 and 1 got the two test payments; change got the RBF change and the CPFP.
        assertTrue(c.history(sh(0, 0)).isNotEmpty())
        assertTrue(c.status(sh(0, 0)) != null)
        assertEquals(null, c.status(sh(0, 50)))
        val all = (0..1).flatMap { b -> (0..5).map { i -> c.history(sh(b, i)) } }.flatten().map { it.txid }.toSet()
        println("txs seen: " + all.size + " height " + c.blockHeight() + " " + c.serverVersion)
        assertTrue(all.size >= 4)
        c.close()
    }
}
