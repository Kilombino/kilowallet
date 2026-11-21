package com.kilombino.pyblockwatch.roblo

import com.kilombino.pyblockwatch.coinjoin.Nip44
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.TestRelay
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.net.HttpLite
import com.kilombino.pyblockwatch.net.WsClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** F2: Roblosats notices in Kilowallet's watcher (gift wraps, garage, dedup, links). */
class RobloWatchTest {
    private val vector = JSONObject(javaClass.getResource("/roblo/giftwrap-nostr-tools.json")!!.readText())
    private val token = vector.getString("token")

    private class Mem : RobloStore.KeyValues {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]; override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }; override fun keys() = m.keys.toSet()
    }
    private val plain = object : RobloStore.Sealer { override fun seal(plain: String) = "s:$plain"; override fun open(sealed: String) = sealed.removePrefix("s:") }

    /** A gift wrap made by nostr-tools (the client's library), opened by Kilowallet's code. */
    @Test fun unwrapsTheClientsGiftWrap() {
        val sec = RobloGarage.secretOf(token)
        assertEquals(vector.getString("robotPub"), NostrEvent.pubOf(sec))
        val r = GiftWrap.unwrap(vector.getJSONObject("wrap"), sec)
        assertNotNull(r)
        assertEquals(14, r!!.kind)
        assertEquals(vector.getString("coordPub"), r.pubkey)
        assertEquals("kilobot/7", r.tag("order_id"))
        assertEquals("1", r.tag("action"))
        assertEquals("Order #7: lock your bond now.", r.content)
        assertTrue(r.id.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun anotherRobotOrATamperedWrapOpensNothing() {
        assertNull(GiftWrap.unwrap(vector.getJSONObject("wrap"), RobloGarage.secretOf("someone else's token")))
        val t = JSONObject(vector.getJSONObject("wrap").toString())
        t.put("content", t.getString("content").replaceRange(10, 12, "AA"))
        assertNull(GiftWrap.unwrap(t, RobloGarage.secretOf(token)))
        assertNull(GiftWrap.unwrap(JSONObject(vector.getJSONObject("wrap").toString()).put("kind", 4), RobloGarage.secretOf(token)))
    }

    /** A rumor that names another author than the seal's signer is refused (impersonation). */
    @Test fun aRumorMustBeTheSealsAuthors() {
        val robot = RobloGarage.secretOf(token); val robotPub = NostrEvent.pubOf(robot)
        val coord = BigInteger("22".repeat(32), 16); val liar = NostrEvent.pubOf(BigInteger("33".repeat(32), 16))
        fun wrap(rumorPub: String): JSONObject {
            val rumor = JSONObject().put("pubkey", rumorPub).put("created_at", 1795000000L).put("kind", 14)
                .put("tags", JSONArray().put(JSONArray().put("order_id").put("kilobot/9"))).put("content", "hi")
            val seal = NostrEvent.sign(coord, 13, emptyList(), Nip44.encrypt(rumor.toString(), Nip44.conversationKey(coord, Hashes.hexToBytes(robotPub))))
            val eph = NostrEvent.newSecret()
            return NostrEvent.sign(eph, 1059, listOf(listOf("p", robotPub)), Nip44.encrypt(seal.toJson().toString(), Nip44.conversationKey(eph, Hashes.hexToBytes(robotPub)))).toJson()
        }
        assertNotNull(GiftWrap.unwrap(wrap(NostrEvent.pubOf(coord)), robot))
        assertNull(GiftWrap.unwrap(wrap(liar), robot))
    }

    private fun garage(vararg slots: Pair<String, JSONObject?>): String {
        val g = JSONObject()
        slots.forEachIndexed { i, (tok, order) ->
            val s = JSONObject().put("token", tok).put("hashId", "h$i").put("nostrPubKey", NostrEvent.pubOf(RobloGarage.secretOf(tok)))
            if (order != null) s.put("activeOrder", order)
            g.put(tok, s)
        }
        return g.toString()
    }

    @Test fun readsTheGarageAndItsLiveOrder() {
        val g = garage(token to JSONObject().put("shortAlias", "kilobot").put("id", 24).put("status", 9),
            "other-token-xyz" to null)
        val robots = RobloGarage.robots(g)
        assertEquals(2, robots.size)
        assertEquals(vector.getString("robotPub"), robots[0].pubkey)
        assertEquals("kilobot/24", RobloGarage.liveOrder(robots)!!.path)
        // finished: nothing to follow live; an on-chain one still releasing: still followed
        assertNull(RobloGarage.liveOrder(RobloGarage.robots(garage(token to JSONObject().put("shortAlias", "kilobot").put("id", 24).put("status", 14)))))
        val releasing = JSONObject().put("shortAlias", "kilobot").put("id", 25).put("status", 14).put("escrow_type", "onchain").put("escrow_status_code", 4)
        assertEquals(25L, RobloGarage.liveOrder(RobloGarage.robots(garage(token to releasing)))!!.id)
        // a saved key that is not the token's is left out
        val bad = JSONObject().put("x", JSONObject().put("token", token).put("nostrPubKey", "00".repeat(32))).toString()
        assertTrue(RobloGarage.robots(bad).isEmpty())
        assertTrue(RobloGarage.robots("not json").isEmpty())
    }

    @Test fun relaysAndCoordinatorKeys() {
        assertEquals(listOf("ws://abc.onion/relay", "wss://relay.example/"), RobloGarage.relays("[\"ws://abc.onion/relay\",\"https://x\",\"wss://relay.example/\",\"ws://abc.onion/relay\"]"))
        assertTrue(RobloGarage.relays("").isEmpty())
        assertEquals(setOf("a".repeat(64)), RobloGarage.coordinatorKeys("[\"${"A".repeat(64)}\"]"))
    }

    @Test fun eachNoticeOnceEver() {
        val mem = HashMap<String, String>()
        val st = object : RobloDedup.Store { override fun get(key: String) = mem[key] ?: ""; override fun set(key: String, value: String) { mem[key] = value } }
        assertTrue(RobloDedup.shouldNotify(st, "a", 100))
        assertFalse(RobloDedup.shouldNotify(st, "a", 100))          // the relay sends it again
        assertTrue(RobloDedup.shouldNotify(st, "b", 100))           // another notice of the same second
        assertFalse(RobloDedup.shouldNotify(st, "c", 50))           // older than the last shown
        repeat(400) { RobloDedup.shouldNotify(st, "n$it", 200L + it) }
        assertEquals(RobloDedup.MAX_SEEN, RobloDedup.parse(mem[RobloDedup.KEY_SEEN]!!).size)
    }

    @Test fun inboxShowsOnlyFederationNoticesForOurRobots() {
        val store = RobloStore(Mem(), plain)
        val robots = RobloGarage.robots(garage(token to null))
        val shown = ArrayList<GiftWrap.Rumor>()
        RobloWatch.Inbox(store, robots, setOf(vector.getString("coordPub"))) { r, _ -> shown += r }.apply {
            handle(vector.getJSONObject("wrap")); handle(vector.getJSONObject("wrap"))
        }
        assertEquals(1, shown.size)
        // a fresh inbox (the watcher restarted): the persistent dedup still says no
        RobloWatch.Inbox(store, robots, setOf(vector.getString("coordPub"))) { r, _ -> shown += r }.handle(vector.getJSONObject("wrap"))
        assertEquals(1, shown.size)
        // not from a coordinator of the federation: nothing
        val other = RobloStore(Mem(), plain)
        RobloWatch.Inbox(other, robots, setOf("b".repeat(64))) { r, _ -> shown += r }.handle(vector.getJSONObject("wrap"))
        assertEquals(1, shown.size)
    }

    /** One look at a relay: REQ for the robots' gift wraps, EOSE, closed; the notice comes out. */
    @Test fun aLookAtTheRelay() {
        TestRelay().use { relay ->
            val direct = HttpLite.Connector { h, p, _ -> java.net.Socket(h, p) }
            val ws = WsClient(relay.url, direct, onText = {}); ws.connect()
            ws.send(JSONArray().put("EVENT").put(vector.getJSONObject("wrap")).toString()); Thread.sleep(300); ws.close()
            val net = RobloNet({ null }, { true }, allowLocal = true)
            val robots = RobloGarage.robots(garage(token to null))
            val shown = ArrayList<GiftWrap.Rumor>()
            val inbox = RobloWatch.Inbox(RobloStore(Mem(), plain), robots, emptySet()) { r, _ -> shown += r }
            RobloWatch.fetch(net, relay.url, robots.map { it.pubkey }, 1795000000L - RobloWatch.WRAP_SLACK, inbox, 5_000)
            assertEquals(listOf("Order #7: lock your bond now."), shown.map { it.content })
        }
    }

    @Test fun linksFromNotifications() {
        val robot = "ab".repeat(32)
        JSONObject(RobloLinks.navigation("kilobot/24", robot)!!).let { assertEquals("kilobot/24", it.getString("path")); assertEquals(robot, it.getString("robot")) }
        assertEquals("", JSONObject(RobloLinks.navigation("kilobot/24", "not hex")!!).getString("robot"))
        for (bad in listOf(null, "", "kilobot", "kilobot/24/x", "../24", "kilobot/24'); alert(1); ('", "a".repeat(65) + "/1"))
            assertNull(bad, RobloLinks.navigation(bad, robot))
        RobloLinks.pending = "x"; assertEquals("x", RobloLinks.take()); assertEquals("", RobloLinks.take())
        assertEquals("Kilobot #24", RobloWatch.title("kilobot/24"))
        assertTrue(RobloWatch.notificationId("abc") >= 7000)
        assertEquals(1059, RobloWatch.filter(listOf(robot), 5).getJSONArray("kinds").getInt(0))
    }
}
