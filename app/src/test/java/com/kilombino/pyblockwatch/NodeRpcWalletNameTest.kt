package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.chain.NodeRpcBackend
import com.kilombino.pyblockwatch.chain.RpcNode
import com.kilombino.pyblockwatch.crypto.ScriptType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The node wallet a key is read with depends on its address type (see NodeRpcBackend.walletName). */
class NodeRpcWalletNameTest {
    private val xpub = "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteo" +
        "GVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
    private fun name(t: ScriptType) = NodeRpcBackend(RpcNode(emptyList(), xpub, t)).walletName

    @Test
    fun `taproot gets its own node wallet`() = assertNotEquals(name(ScriptType.P2WPKH), name(ScriptType.P2TR))

    @Test
    fun `native segwit keeps the name existing wallets have`() =
        assertEquals("kilowallet-" + com.kilombino.pyblockwatch.crypto.Hashes.sha256(xpub.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(10), name(ScriptType.P2WPKH))
}
