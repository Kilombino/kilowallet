package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.TxBuilder
import com.kilombino.pyblockwatch.crypto.TxParse
import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.offerFrame
import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.proofFor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections

/**
 * [TakerSession] against an in-process directory whose makers answer like JoinBlaket makers
 * (pubkey, ioauth with a btc_sig, 0x21 signatures over the unsigned tx they get, `!push`): the
 * failure rules of spec section 7 without the regtest lab. Chain lookups come from a map.
 */
class TakerSessionTest {
    private val rng = SecureRandom()
    private val net = JbNetwork.REGTEST
    private val tip = 962_000
    private fun key(): BigInteger = BigInteger(256, rng).mod(com.kilombino.pyblockwatch.crypto.Secp256k1.N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
    private fun txid(): String = ByteArray(32).also(rng::nextBytes).toHex()
    private fun spkOf(k: BigInteger) = TakerTx.p2wpkh(TakerCoin(txid(), 0, 0, k, 0).pubkey)
    private fun address(k: BigInteger = key()) = net.p2wpkhAddress(spkOf(k))

    /** Our chain view: every coin of the test, unspent and confirmed; a pushed txid shows up. */
    private inner class FakeChain : ChainLookup {
        val utxos = Collections.synchronizedMap(HashMap<Pair<String, Long>, IoauthCheck.ChainUtxo>())
        val seen: MutableSet<String> = Collections.synchronizedSet(HashSet())
        override fun height() = tip
        override fun utxo(txid: String, vout: Long, scriptPubKey: ByteArray?): IoauthCheck.ChainUtxo? {
            if (txid in seen) return IoauthCheck.ChainUtxo(1, scriptPubKey ?: ByteArray(0), 0)
            return utxos[txid.lowercase() to vout]
        }
        override fun blockTime(height: Int) = System.currentTimeMillis() / 1000 - 30L * 86_400
    }

    /** A maker: its nick, NaCl key, one coin, a bond worth [bondSats] on chain, and how it misbehaves. */
    private inner class FakeMaker(chain: FakeChain, bondSats: Long, val silent: Boolean = false, val refuseSig: Boolean = false) {
        val id = NickIdentity.random()
        val naclSecret = Nacl.generateSecret()
        val utxoKey = key()
        val coin = TakerTx.Utxo(txid(), 0, 50_000_000, spkOf(utxoKey))
        val bondTxid = txid()
        var box: Nacl.Box? = null
        val fills = ArrayList<String>()
        init {
            chain.utxos[coin.outpoint] = IoauthCheck.ChainUtxo(coin.value, coin.scriptPubKey, 10)
            val proof = BondProof.decode(proofFor(id.nick, "J5x", bondTxid))!!
            chain.utxos[bondTxid to proof.vout] = IoauthCheck.ChainUtxo(bondSats, proof.scriptPubKey, 1000)
        }
    }

    private fun handshakeReply() = Wire.envelope(Wire.DN_HANDSHAKE, JSONObject().put("app-name", "joinmarket").put("directory", true)
        .put("proto-ver-min", 5).put("proto-ver-max", 5).put("features", JSONObject().put("peerlist_features", true).put("ping", true))
        .put("accepted", true).put("nick", "J5DirectoryOOOOO").put("network", net.wire).toString())

    /** Plays the directory and every maker until the taker hangs up; [pushed] collects `!push`ed txs. */
    private fun directory(chain: FakeChain, makers: List<FakeMaker>, pushed: MutableList<String>) = FakeDirectory {
        val taker = JSONObject(Wire.parse(expect()).line).getString("nick")
        send(handshakeReply())
        assertEquals(Wire.GETPEERLIST, Wire.parse(expect()).type)
        send(Wire.envelope(Wire.PEERLIST, makers.joinToString(",") { "${it.id.nick};NOT-SERVING-ONION;F:neutrino_compat+peerlist_features+ping" }))
        assertEquals("$taker!PUBLIC!orderbook", Wire.parse(expect()).line)
        for (m in makers) send(offerFrame(m.id, taker, "sw0reloffer 0 27300 100000000 0 0.0002!tbond ${proofFor(m.id.nick, taker, m.bondTxid)}"))
        val byNick = makers.associateBy { it.id.nick }
        var unsigned: TakerTx.Unsigned? = null
        while (true) {
            val line = runCatching { expect() }.getOrNull() ?: break
            val msg = Session.parsePrivmsg(Wire.parse(line)) ?: continue
            assertTrue("taker privmsg must verify", msg.verified)
            val m = byNick.getValue(msg.to)
            fun reply(cmd: String, data: String) = send(Session.privmsg(m.id, taker, cmd, data))
            when (msg.command) {
                "fill" -> {
                    val f = msg.data.split(" ")
                    m.fills += f[3]
                    if (m.silent) continue
                    m.box = Nacl.Box(m.naclSecret, Wire.hex(f[2]))
                    reply("pubkey", "${Nacl.publicKey(m.naclSecret).toHex()} features=neutrino_compat")
                }
                "auth" -> {
                    val rev = Podle.parseRevelation(m.box!!.decryptString(msg.data))
                    assertTrue("PoDLE must verify", Podle.verify(rev.p, rev.p2, rev.s, rev.e, Wire.hex(m.fills.last().substring(1))))
                    assertTrue("extended utxo for neutrino_compat makers", rev.scriptPubKeyHex != null && rev.height == tip - 9)
                    val authPub = TakerCoin(m.coin.txid, 0, 0, m.utxoKey, 0).pubkey
                    val sig = JmSig.signMessage(m.utxoKey, Nacl.publicKey(m.naclSecret).toHex().toByteArray())
                    val utxos = "${m.coin.txid}:0:${m.coin.scriptPubKey.toHex()}:${tip - 9}"
                    reply("ioauth", m.box!!.encrypt("$utxos ${authPub.toHex()} ${address()} ${address()} $sig"))
                }
                "tx" -> {
                    val raw = Base64.getDecoder().decode(m.box!!.decryptString(msg.data))
                    val p = TxParse.parse(raw.toHex())
                    val ins = p.inputs.map { i -> val c = chain.utxo(i.txid, i.vout.toLong())!!; TakerTx.Utxo(i.txid, i.vout.toLong(), c.value, c.scriptPubKey) }
                    val tx = TakerTx.Unsigned(ins, p.outputs.map { TxBuilder.Output(it.scriptPubKey, it.value) }, p.locktime,
                        ins.map { "?" }, p.outputs.map { TakerTx.OutputOwner("?", "?") })
                    assertTrue("the taker's sequence rule", p.inputs.all { it.sequence == tx.sequence })
                    unsigned = tx
                    if (m.refuseSig) { reply("error", "Transaction verification failed: CJ output value too low"); continue }
                    val idx = tx.indexOf(m.coin.txid, 0)
                    reply("sig", m.box!!.encrypt(Session.encodeSig(TakerTx.signInput(tx, idx, m.utxoKey), TakerCoin(m.coin.txid, 0, 0, m.utxoKey, 0).pubkey)))
                }
                "push" -> { pushed += msg.data; unsigned?.let { chain.seen += it.txid() } }
            }
        }
    }

    private class Env(override val chain: ChainLookup, private val port: Int, override val commitments: CommitmentStore) : TakerEnv {
        val broadcasts = ArrayList<String>()
        val steps = ArrayList<String>()
        override fun directory(identity: NickIdentity) = DirectoryClient("127.0.0.1", port, identity, JbNetwork.REGTEST.wire, null,
            requireVerified = false, connectTimeoutMs = 5_000, peerlistTimeoutMs = 2_000, peerlistQuietMs = 200)
        override fun broadcast(rawHex: String): String { broadcasts += rawHex; return "" }
        override suspend fun confirm(plan: TakerPlan) = true
        override fun progress(step: TakerStep) { steps += step::class.simpleName!! }
    }

    private val config = TakerConfig(network = net, orderbook = Orderbook.Timing(minWaitMs = 300, quietMs = 200, maxWaitMs = 2_000),
        makerTimeoutMs = 1_500, broadcastTimeoutMs = 1_000, pollMs = 100)

    private class Run(val result: TakerResult, val env: Env, val pushed: List<String>, val store: FileCommitmentStore, val coin: TakerCoin)

    private fun run(chain: FakeChain, makers: List<FakeMaker>, n: Int, minimum: Int): Run {
        val k = key()
        val coin = TakerCoin(txid(), 1, 50_000_000, k, tip - 9)
        chain.utxos[coin.outpoint] = IoauthCheck.ChainUtxo(coin.value, coin.scriptPubKey, 10)
        val pushed = Collections.synchronizedList(ArrayList<String>())
        val store = FileCommitmentStore(File.createTempFile("cmt", ".json").apply { delete(); deleteOnExit() })
        directory(chain, makers, pushed).use { dir ->
            val env = Env(chain, dir.port, store)
            val r = runBlocking { TakerSession(env, config).run(TakerRequest(address(), 10_000_000, 2.0, listOf(coin), address(), n, minimum)) }
            dir.finish()
            return Run(r, env, pushed, store, coin)
        }
    }

    /** A chosen maker that never answers `!fill` is replaced from the remaining offers with a fresh commitment. */
    @Test fun silentMakerIsReplaced() {
        val chain = FakeChain()
        // The silent one's bond dwarfs the others: it is (all but) certainly among the two first chosen.
        val silent = FakeMaker(chain, 200_000_000_000_000, silent = true)
        val a = FakeMaker(chain, 100_000); val c = FakeMaker(chain, 100_000)
        val r = run(chain, listOf(silent, a, c), n = 2, minimum = 1)
        val sent = r.result as TakerResult.Sent
        assertEquals(2, sent.makers)
        assertEquals(1, silent.fills.size)
        val first = listOf(a, c).first { it.fills.firstOrNull() == silent.fills[0] }
        val replacement = listOf(a, c).single { it !== first }
        assertNotEquals("the replacement gets a fresh commitment", first.fills.single(), replacement.fills.single())
        assertEquals(2, r.store.count())
        val tx = TxParse.parse(sent.rawHex)
        val inputs = tx.inputs.map { it.txid to it.vout.toLong() }.toSet()
        assertEquals(setOf(r.coin.outpoint, a.coin.outpoint, c.coin.outpoint), inputs)
        assertFalse(silent.coin.outpoint in inputs)
        assertTrue("every witness signed 0x21", tx.inputs.all { it.witness[0].last() == 0x21.toByte() })
        assertTrue(sent.viaMaker && sent.seen)
        assertEquals(1, r.pushed.size)
        assertTrue("nothing through our own backend", r.env.broadcasts.isEmpty())
        assertEquals(4000L, sent.makerFees)
        assertEquals(r.coin.value - 10_000_000 - 4000 - sent.txFee, sent.change)
    }

    /** Fewer makers than asked for are fine down to the minimum, and below it nothing is sent. */
    @Test fun goesOnWithFewerDownToTheMinimum() {
        run {
            val chain = FakeChain()
            val r = run(chain, listOf(FakeMaker(chain, 100_000, silent = true), FakeMaker(chain, 100_000)), n = 2, minimum = 1)
            assertEquals(1, (r.result as TakerResult.Sent).makers)
        }
        val chain = FakeChain()
        val r = run(chain, listOf(FakeMaker(chain, 100_000, silent = true), FakeMaker(chain, 100_000)), n = 2, minimum = 2)
        assertEquals(TakerText.fill(TakerText.TOO_FEW, 1, 2), (r.result as TakerResult.Failed).reason)
        assertTrue(r.pushed.isEmpty() && r.env.broadcasts.isEmpty())
        assertFalse("never got as far as !tx", "CollectingSignatures" in r.env.steps)
    }

    /** After `!tx` a maker that refuses to sign aborts the round: nothing is pushed or broadcast. */
    @Test fun refusedSignatureAbortsWithNothingBroadcast() {
        val chain = FakeChain()
        val r = run(chain, listOf(FakeMaker(chain, 100_000), FakeMaker(chain, 100_000, refuseSig = true)), n = 2, minimum = 1)
        assertEquals(TakerText.NO_SIGNATURE, (r.result as TakerResult.Failed).reason)
        assertTrue(r.pushed.isEmpty() && r.env.broadcasts.isEmpty())
        assertTrue("CollectingSignatures" in r.env.steps)
        assertFalse("Broadcasting" in r.env.steps)
    }

    @Test fun commitmentStoreKeepsPythonsFormat() {
        val f = File.createTempFile("cmt", ".json").apply { delete(); deleteOnExit() }
        val s = FileCommitmentStore(f)
        assertFalse(s.isUsed("AB".repeat(32)))
        s.markUsed("AB".repeat(32)); s.markUsed("ab".repeat(32))
        val o = JSONObject(f.readText())
        assertEquals(1, o.getJSONArray("used").length())
        assertEquals("ab".repeat(32), o.getJSONArray("used").getString(0))
        assertTrue(o.has("external"))
        assertTrue(FileCommitmentStore(f).isUsed("ab".repeat(32)))
    }

    @Test fun networksDecodeOnlyTheirOwnAddresses() {
        val spk = spkOf(key())
        val main = JbNetwork.MAINNET.p2wpkhAddress(spk); val reg = JbNetwork.REGTEST.p2wpkhAddress(spk)
        assertTrue(main.startsWith("bc1q") && reg.startsWith("bcrt1q"))
        assertTrue(JbNetwork.MAINNET.scriptPubKey(main).contentEquals(spk))
        assertTrue(JbNetwork.REGTEST.scriptPubKey(reg).contentEquals(spk))
        assertTrue(runCatching { JbNetwork.MAINNET.scriptPubKey(reg) }.isFailure)
        assertTrue(runCatching { JbNetwork.REGTEST.scriptPubKey(main) }.isFailure)
    }
}
