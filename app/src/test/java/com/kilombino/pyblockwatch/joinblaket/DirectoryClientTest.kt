package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.hs
import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.offerFrame
import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.offerVec
import com.kilombino.pyblockwatch.joinblaket.FakeDirectory.Companion.proofFor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The directory transport and the orderbook round against an in-process directory replaying the
 * regtest transcript (`docs/xbt-taker-transcript.md` F1-F9, F14, F15) with the vectors' nicks,
 * keys and bond proof, so every frame the client must send is checked byte for byte.
 */
class DirectoryClientTest {
    private val taker = NickIdentity(FakeDirectory.takerKey)
    private val maker = NickIdentity(Vectors.key("maker1-nick"))
    private val dirId = hs.getString("directory_id")

    private fun client(port: Int, network: String = "xbt", id: String? = dirId, requireVerified: Boolean = true) =
        DirectoryClient("127.0.0.1", port, taker, network, id, requireVerified, allowClearnet = true,
            connectTimeoutMs = 5_000, peerlistTimeoutMs = 2_000, peerlistQuietMs = 300)

    /** F1-F5: handshake and nick authentication, exactly the vectors' frames. */
    private fun FakeDirectory.handshake() {
        assertEquals(hs.getString("handshake_envelope"), expect())
        send(hs.getString("directory_reply_dn_handshake"))
        send(hs.getString("directory_reply_challenge"))
        assertEquals(hs.getString("nick_auth_proof_envelope"), expect())
        send(hs.getString("directory_reply_result"))
    }

    @Test fun transcriptOrderbookRound() {
        val other = NickIdentity(Vectors.key("maker2-test"))
        val sybil = NickIdentity(Vectors.key("maker3-test"))
        var pongs = 0
        FakeDirectory {
            handshake()
            // F6/F7: GETPEERLIST, answered in two chunks; a PING in between must get its PONG.
            assertEquals("{\"type\": 791, \"line\": \"\"}", expect())
            send(Wire.envelope(Wire.PING, ""))
            assertEquals("{\"type\": 799, \"line\": \"\"}", expect()); pongs++
            send(Wire.envelope(Wire.PEERLIST, "${maker.nick};NOT-SERVING-ONION;F:neutrino_compat+peerlist_features+ping"))
            send(Wire.envelope(Wire.PEERLIST, "${taker.nick};NOT-SERVING-ONION;F:neutrino_compat+nick_auth+peerlist_features+ping,peerlist_features,bad nick;x"))
            // F8: the !orderbook PUBMSG.
            assertEquals(hs.getString("orderbook_request_envelope"), expect())
            // F9: the bonded offer, then noise and offers that must be refused.
            send("{\"type\": 685, \"line\": ${Wire.quote(offerVec.getString("privmsg_line"))}}")
            send(Wire.envelope(Wire.PUBMSG, "J5HP2NickOOOOOOO!PUBLIC!hp2 ac8d866c0c28"))
            send(Wire.envelope(Wire.PEERLIST, "J5HP2NickOOOOOOO;NOT-SERVING-ONION;D"))
            send(Wire.envelope(Wire.PEERLIST, "${maker.nick};NOT-SERVING-ONION;D"))
            send(offerFrame(other, taker.nick, "sw0absoffer 0 27300 100000000 0 500"))                  // no bond
            send(offerFrame(other, "J5SomeoneElseOOO", "sw0absoffer 1 27300 100000000 0 500!tbond ${proofFor(other.nick, "J5SomeoneElseOOO")}"))
            send(offerFrame(other, taker.nick, "sw0absoffer 2 27300 100000000 0 500!tbond ${proofFor(sybil.nick, taker.nick)}")) // proof of another maker
            send(offerFrame(sybil, taker.nick, "sw0reloffer 0 27300 100000000 0 0.0002!tbond ${proofFor(sybil.nick, taker.nick)}"))
            send(offerFrame(sybil, taker.nick, "sw0reloffer 0 27300 100000000 0 0.0002!tbond ${proofFor(sybil.nick, taker.nick)}").replace("100000000", "100000001")) // tampered
            send("this is not json")
            Thread.sleep(1_500)
        }.use { dir ->
            val c = client(dir.port)
            val r = runBlocking {
                c.connect()
                assertTrue(c.authenticated)
                assertEquals(setOf("nick_auth", "peerlist_features", "ping"), c.directoryFeatures)
                Orderbook.fetch(c, Orderbook.Timing(minWaitMs = 500, quietMs = 300, maxWaitMs = 4_000))
            }
            dir.finish()
            assertEquals(1, pongs)
            assertEquals(6, r.offerMessages)
            assertEquals(listOf(maker.nick to 0L, sybil.nick to 0L), r.offers.map { it.offer.counterparty to it.offer.oid })
            val o = r.offers[0].offer
            val want = offerVec.getJSONObject("parsed_offer")
            assertEquals(want.getLong("maxsize"), o.maxsize)
            assertEquals(want.getString("cjfee"), o.cjfee)
            assertEquals(offerVec.getJSONObject("parsed_bond").getString("p2wsh_script"), r.offers[0].claim.third)
            // Peers: the maker and the throw-away nick left; malformed entries were skipped.
            assertEquals(setOf(taker.nick), c.peers.keys)
            assertTrue("nick_auth" in c.peers.getValue(taker.nick).features)
            c.close()
            assertFalse(c.isConnected)
        }
    }

    private fun refused(script: FakeDirectory.() -> Unit, network: String = "xbt", id: String? = dirId, requireVerified: Boolean = true): String {
        FakeDirectory(script).use { dir ->
            val c = client(dir.port, network, id, requireVerified)
            try {
                runBlocking { c.connect() }
                fail("connected")
            } catch (e: DirectoryException) {
                assertFalse(c.isConnected)
                return e.message!!
            } finally { dir.close() }
        }
        error("unreachable")
    }

    @Test fun handshakeRefusals() {
        val dn = hs.getString("directory_reply_dn_handshake")
        // An upstream (SHA-256) directory answers "mainnet".
        assertEquals("directory network mismatch", refused({ expect(); send(dn.replace("\\\"xbt\\\"", "\\\"mainnet\\\"")) }))
        assertEquals("handshake rejected", refused({ expect(); send(dn.replace("\\\"accepted\\\": true", "\\\"accepted\\\": false")) }))
        // require_verified: the directory must offer nick_auth.
        assertTrue(refused({ expect(); send(dn.replace("\\\"nick_auth\\\": true, ", "")) }).contains("nick authentication"))
        // A challenge for another directory id is never answered.
        assertTrue(refused({ expect(); send(dn); send(hs.getString("directory_reply_challenge").replace(dirId, "127.0.0.1:15322")) })
            .contains("directory-id mismatch"))
        assertEquals("Directory rejected nick authentication", refused({
            expect(); send(dn); send(hs.getString("directory_reply_challenge")); expect()
            send(Wire.envelope(Wire.NICK_AUTH_RESULT, "{\"code\":\"invalid\",\"verified\":false}"))
        }))
        assertEquals("No handshake reply", refused({ expect(); Thread.sleep(6_000) }))
        assertTrue(refused({ expect(); send("{\"type\": 793") }).contains("Unreadable"))
        // require_verified without a configured directory id: refused before connecting.
        assertTrue(refused({}, id = null).contains("No expected nick authentication directory identity"))
    }

    @Test fun withoutNickAuthTheHandshakeOffersNone() {
        FakeDirectory {
            assertEquals(Wire.envelope(Wire.HANDSHAKE, NickAuth.handshakeLine(taker.nick, "xbt-regtest", nickAuth = false)), expect())
            send(hs.getString("directory_reply_dn_handshake").replace("\\\"xbt\\\"", "\\\"xbt-regtest\\\""))
            // No challenge expected from us: the next thing we send is a privmsg.
            assertEquals(Session.privmsg(taker, maker.nick, "fill", "0 1 2 P3"), expect())
        }.use { dir ->
            val c = client(dir.port, "xbt-regtest", id = null, requireVerified = false)
            runBlocking { c.connect(); c.sendPrivate(maker.nick, "fill", "0 1 2 P3") }
            dir.finish()
            assertFalse(c.authenticated)
            c.close()
        }
    }

    @Test fun nickAuthAfterTheHandshakeClosesTheConnection() {
        FakeDirectory {
            handshake()
            send(Wire.envelope(Wire.PUBMSG, "J5HP2NickOOOOOOO!PUBLIC!hp2 00"))
            send(hs.getString("directory_reply_challenge"))
            clientClosed()
        }.use { dir ->
            val c = client(dir.port)
            runBlocking {
                c.connect()
                assertEquals(Wire.PUBMSG, c.receive(2_000)!!.type)
                try { c.receive(2_000); fail("no error") } catch (e: DirectoryException) {
                    assertTrue(e.message!!.contains("Out-of-order nick authentication"))
                }
            }
            dir.finish()
        }
    }

    @Test fun oversizedLinesAndClosedConnections() {
        FakeDirectory {
            handshake()
            send(Wire.envelope(Wire.PUBMSG, "x".repeat(DirectoryClient.MAX_RECEIVED_LINE)))
            clientClosed()
        }.use { dir ->
            val c = client(dir.port)
            runBlocking {
                c.connect()
                try { c.receive(5_000); fail("no error") } catch (e: DirectoryException) { assertEquals("Message too large", e.message) }
            }
            dir.finish()
        }
        FakeDirectory { handshake() }.use { dir ->
            val c = client(dir.port)
            runBlocking {
                c.connect()
                dir.finish()
                dir.close()
                try { c.receive(5_000); fail("no error") } catch (e: DirectoryException) { assertTrue(e.message!!.contains("closed")) }
                // What we send must fit the directory's line limit.
                try { c.sendPublic("x".repeat(DirectoryClient.MAX_SENT_LINE)); fail("sent") } catch (_: DirectoryException) {}
            }
        }
    }

    @Test fun receiveTimesOutQuietly() {
        FakeDirectory { handshake(); Thread.sleep(1_000) }.use { dir ->
            val c = client(dir.port)
            runBlocking { c.connect(); assertNull(c.receive(200)) }
            dir.finish(); c.close()
        }
    }

    @Test fun onionNeedsAProxyAndClearnetNeedsPermission() {
        val e1 = runCatching { runBlocking { DirectoryClient("${"a".repeat(56)}.onion", 5222, taker, directoryId = "x").connect() } }.exceptionOrNull()
        assertTrue(e1!!.message!!.contains("needs Tor"))
        val e2 = runCatching { runBlocking { DirectoryClient("192.0.2.1", 5222, taker, directoryId = "x").connect() } }.exceptionOrNull()
        assertTrue(e2!!.message!!.contains("non-onion"))
    }

    @Test fun peerlistEntries() {
        val p = DirectoryClient.parsePeerlist("J5aaaaaaaaaaaaaa;abc.onion:5222;F:ping+nick_auth,J5bbbbbbbbbbbbbb;NOT-SERVING-ONION;D,meta,,J5c c;x,J5dddddddddddddd;x;F:a,b")
        // Entries are split on ',' first, so "F:a,b" is feature "a" and a metadata entry "b".
        assertEquals(listOf("J5aaaaaaaaaaaaaa" to false, "J5bbbbbbbbbbbbbb" to true, "J5dddddddddddddd" to false), p.map { it.first.nick to it.second })
        assertEquals(setOf("ping", "nick_auth"), p[0].first.features)
        assertEquals("abc.onion:5222", p[0].first.location)
    }
}
