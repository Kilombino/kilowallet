package com.kilombino.pyblockwatch.data

import com.kilombino.pyblockwatch.chain.NodeRpcBackend
import com.kilombino.pyblockwatch.chain.RpcNode
import com.kilombino.pyblockwatch.chain.WatchDescriptor
import com.kilombino.pyblockwatch.crypto.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JoinMarket wallet read from the user's node by RPC: the public descriptors the node gets
 * (the five jars' receive and change branches, one address per fidelity bond) and the name of the
 * node wallet that holds them. The BTC wallet's node wallets keep their names.
 */
class JamRpcTest {
    // JoinMarket-NG's get_account_zpub(0..4) for "abandon ×11 about" (as in JoinMarketTest).
    private val zpubs = listOf(
        "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
        "zpub6rFR7y4Q2AijF6Gk1bofHLs1d66hKFamhXWdWBup1Em25wfabZqkDqvaieV63fDQFaYmaatCG7jVNUpUiM2hAMo6SAVHcrUpSnHDpNzucB7",
        "zpub6rFR7y4Q2AijHxf5H8YD9SZ1S1hrLi3PmbR9iJeVVZSJmK8R86EPCwBhyTaycoeXEVqLigViktQUy2tt3yLnvcZ7BcXz9QxHrLjaTeJn3xL",
        "zpub6rFR7y4Q2AijKtJ66XKz29oDCtvXLTHgJ71fjNCS5kWGi97AcTfHkPPxL9GNPzR2TaqfcJx2WrcfQEHCjx7LcJz3jwwvQm4D1fcW7aiGxfT",
        "zpub6rFR7y4Q2AijM8GBWicX3FmPEK8juiGC1TueN7qQzGFLTKQbFrQsgBwrco3DgKidS4DwYUC12UULUux5XvPtgzmy1HoDpDhGABnnEyBQzsL",
    )

    private fun export(n: String = "mainnet", b: String = "[[82,1793491200]]", x: List<String> = zpubs) = JoinMarket.parse(
        JoinMarket.PREFIX + "{\"n\":\"$n\",\"x\":[" + x.joinToString(",") { "\"$it\"" } + "],\"b\":$b}", allowTestNetworks = true)

    @Test fun jarDescriptorsOnMainnet() {
        val d = JamWatch.rpcDescriptors(export())
        assertEquals(5 * 2 + 1, d.size)
        // m/84'/0'/0' of "abandon … about" as a node reads it (BIP-84's account 0, in xpub form).
        val acct0 = "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
        assertEquals(WatchDescriptor("wpkh($acct0/0/*)"), d[0])
        assertEquals(WatchDescriptor("wpkh($acct0/1/*)", internal = true), d[1])
        zpubs.forEachIndexed { md, z ->
            val k = NodeRpcBackend.toVersion(z, NodeRpcBackend.XPUB)
            assertTrue(k.startsWith("xpub"))
            assertEquals(listOf(WatchDescriptor("wpkh($k/0/*)"), WatchDescriptor("wpkh($k/1/*)", internal = true)), d.subList(md * 2, md * 2 + 2))
        }
        assertTrue(d.take(10).all { it.ranged })
        // Public keys only: no xprv/tprv and no zpub (a node reads xpub/tpub alone).
        assertFalse(d.any { it.desc.contains("prv") || it.desc.contains("zpub") })
    }

    @Test fun bondAddressDescriptors() {
        val e = export(b = "[[82,1793491200],[90,1814400000]]")
        val d = JamWatch.rpcDescriptors(e)
        val bonds = d.drop(10)
        assertEquals(JoinMarket.bondScripts(e).map { WatchDescriptor("addr(${it.address})") }, bonds)
        assertTrue(bonds.all { it.desc.startsWith("addr(bc1q") && !it.ranged && !it.internal })
        // No bonds: the jars alone.
        assertEquals(10, JamWatch.rpcDescriptors(export(b = "[]")).size)
    }

    /** A test network: tpub keys and bcrt1 bond addresses (the bond made from Jam on regtest, see JoinMarketTest). */
    @Test fun regtestDescriptors() {
        val vpub0 = "vpub5YimRAm5MY7jnzVfd7rocTTLPA6gtAogNZq8JwX5A3DNxsnCeXhNQ31D2yPno4AcTcNbeJr26CobRbG8TgxMhfGJqCFky5UHbqy5DDNyefJ"
        val e = export(n = "regtest", x = listOf(vpub0) + zpubs.drop(1))
        val d = JamWatch.rpcDescriptors(e)
        assertEquals("wpkh(tpubDCxX2sYFS5bDkSe5GKKYHjBW7tgyN1R3UchpLJvdbf54ohxeGRtd8MbDUe1cguVHe4vnK68DsuD5MXjxi9EXx16rb9EnNsaF5KT99CinaJz/0/*)",
            JamWatch.rpcDescriptors(export(n = "regtest"))[0].desc)
        assertTrue(d.take(10).all { it.desc.startsWith("wpkh(tpub") })
        assertEquals("addr(bcrt1qpxqaeuandampp5n2zlyfd2set6qc898c8lzktgkem4uk7r4aduysaeda8s)", d[10].desc)
    }

    @Test fun jamNodeWalletName() {
        fun name(e: JoinMarket.Export) = NodeRpcBackend(JamWatch.rpcNode(e, emptyList())).walletName
        val one = name(export())
        assertEquals("kilowallet-jm-" + JAM_SET_HASH, one)
        // The same wallet imported again: the same node wallet. A new bond: a new one.
        assertEquals(one, name(export()))
        assertNotEquals(one, name(export(b = "[[82,1793491200],[90,1814400000]]")))
        assertNotEquals(one, name(export(n = "regtest")))
        // The order of the set doesn't matter.
        val set = JamWatch.rpcDescriptors(export())
        assertEquals(NodeRpcBackend.setName("kilowallet-jm", set), NodeRpcBackend.setName("kilowallet-jm", set.reversed()))
        // What the node is asked to watch is that set.
        assertEquals(set, NodeRpcBackend(JamWatch.rpcNode(export(), emptyList())).watched)
    }

    /** The BTC wallet's node wallets, as existing users have them on their nodes: names and descriptors unchanged. */
    @Test fun btcWalletNodeWalletUnchanged() {
        val xpub = "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteo" +
            "GVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
        fun b(t: ScriptType) = NodeRpcBackend(RpcNode(emptyList(), xpub, t))
        assertEquals("kilowallet-8ec56f6390", b(ScriptType.P2WPKH).walletName)
        assertEquals("kilowallet-ade27e78ff", b(ScriptType.P2TR).walletName)
        assertEquals(listOf(WatchDescriptor("wpkh($xpub/0/*)"), WatchDescriptor("wpkh($xpub/1/*)", internal = true)), b(ScriptType.P2WPKH).watched)
        assertEquals(listOf(WatchDescriptor("tr($xpub/0/*)"), WatchDescriptor("tr($xpub/1/*)", internal = true)), b(ScriptType.P2TR).watched)
        // A zpub is read by the node as the same key in xpub form.
        val zpub = zpubs[0]
        assertEquals("wpkh(xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V/0/*)",
            NodeRpcBackend(RpcNode(emptyList(), zpub, ScriptType.P2WPKH)).watched[0].desc)
    }

    private companion object {
        const val JAM_SET_HASH = "8d6640b591"
    }
}
