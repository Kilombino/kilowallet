package com.kilombino.pyblockwatch.roblo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The bridge the Roblosats client calls (window.AndroidAppRobosats), on Kilowallet's engines. */
class RobloBridgeTest {
    private class FakeHost : RobloBridge.Host {
        val js = LinkedBlockingQueue<String>()
        var clip: String? = null; var screenOn: Boolean? = null; var reloads = 0
        override fun evaluate(js: String) { this.js.add(js) }
        override fun copyToClipboard(text: String) { clip = text }
        override fun share(text: String) {}
        override fun keepScreenOn(on: Boolean) { screenOn = on }
        override fun reload() { reloads++ }
        override fun torStatus() = "ON"
        override fun appVersion() = "1.6.1-roblo"
        override fun recentLogs() = ""
    }
    private class Mem : RobloStore.KeyValues {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]; override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }; override fun keys() = m.keys.toSet()
    }
    private val sealer = object : RobloStore.Sealer { override fun seal(plain: String) = "s:$plain"; override fun open(sealed: String) = sealed.removePrefix("s:") }
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"

    private fun bridge(local: Boolean = true, clearnet: Boolean = true): Pair<RobloBridge, FakeHost> {
        val h = FakeHost(); val store = RobloStore(Mem(), sealer)
        if (clearnet) store.set(RobloStore.CLEARNET, "true")
        return RobloBridge(h, store, RobloNet({ null }, { store.clearnet() }, local)) to h
    }

    /** The value the page's promise [uuid] was resolved with (decoded), or null if it was rejected. */
    private fun answer(h: FakeHost): Pair<Boolean, String> {
        val js = h.js.poll(10, TimeUnit.SECONDS) ?: error("no answer")
        val ok = js.contains("onResolvePromise")
        val raw = Regex("'$uuid', '(.*)'\\)$", RegexOption.DOT_MATCHES_ALL).find(js)!!.groupValues[1]
        return ok to decode(raw)
    }
    private fun decode(s: String) = s.replace("\\u003C", "<").replace("\\u003E", ">").replace("\\u0026", "&")
        .replace("\\\"", "\"").replace("\\'", "'").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\")

    @Test fun storageRoundTrip() {
        val (b, h) = bridge()
        b.setEncryptedStorage(uuid, "garage_slots", " {\"x\":1} ")
        assertEquals(true to "garage_slots", answer(h))
        b.getEncryptedStorage(uuid, "garage_slots")
        assertEquals(true to "{\"x\":1}", answer(h))
        b.deleteEncryptedStorage(uuid, "garage_slots"); answer(h)
        b.getEncryptedStorage(uuid, "garage_slots")
        assertEquals(true to "", answer(h))
        b.getEncryptedStorage(uuid, "../../shared_prefs")
        assertFalse(answer(h).first)
    }

    @Test fun aCallWithoutAValidUuidIsIgnored() {
        val (b, h) = bridge()
        b.getEncryptedStorage("x'); alert(1); ('", "garage_slots")
        b.getTorStatus("not-a-uuid")
        assertNull(h.js.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun hostInfoAndTorStatus() {
        val (b, h) = bridge(clearnet = true)
        val info = JSONObject(b.hostInfo())
        assertEquals("kilowallet", info.getString("host")); assertEquals("1.6.1-roblo", info.getString("version"))
        assertTrue(info.getBoolean("clearnet"))
        // client kw4: the platform and the wallet functions offered (all four on Android)
        assertEquals("android", info.getString("platform"))
        assertEquals(RobloBridge.WALLET_FEATURES, (0 until info.getJSONArray("features").length()).map { info.getJSONArray("features").getString(it) })
        b.getTorStatus(uuid); assertEquals(true to "ON", answer(h)) // Tor runs in both modes (onion coordinators)
        assertTrue(b.isClearnet())
        val (b2, h2) = bridge(clearnet = false)
        assertFalse(JSONObject(b2.hostInfo()).getBoolean("clearnet"))
        b2.getTorStatus(uuid); assertEquals(true to "ON", answer(h2))
    }

    /** 0.9.11: the page's network mode, in place; only well-formed clearnet host names are kept. */
    @Test fun aHostWithoutLightningListsOnlyItsOnChainFunctions() {
        val store = RobloStore(Mem(), sealer)
        val h = object : RobloBridge.Host by FakeHost() {
            override fun platform() = "ios"
            override fun features() = listOf("walletPay", "walletAddress", "somethingElse")
        }
        val info = JSONObject(RobloBridge(h, store, RobloNet({ null }, { false }, false)).hostInfo())
        assertEquals("ios", info.getString("platform"))
        assertEquals("[\"walletPay\",\"walletAddress\"]", info.getJSONArray("features").toString())
    }

    @Test fun networkModeFromThePage() {
        val (b, _) = bridge(clearnet = false)
        assertFalse(b.isClearnet())
        b.setNetworkMode(true, true, "kilobot.kilombino.com,abc.onion,bad host,evil.com/x")
        assertTrue(b.isClearnet())
        assertTrue(JSONObject(b.hostInfo()).getBoolean("clearnet"))
        b.setNetworkMode(true, false, "")
        assertFalse(b.isClearnet())
    }

    @Test fun clipboardScreenAndRestart() {
        val (b, h) = bridge()
        b.copyToClipboard("lnbc1..."); b.keepScreenOn(true); b.restart()
        assertEquals("lnbc1...", h.clip); assertEquals(true, h.screenOn); assertEquals(1, h.reloads)
        assertEquals("", b.takePendingNavigation())
    }

    /** sendRequest answers {"json": …, "headers": …} as the Roblosats app does. */
    @Test fun requestToTheLabCoordinator() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        var request = ""
        Thread { server.accept().use { s ->
            val r = s.getInputStream().bufferedReader(); val sb = StringBuilder()
            while (true) { val l = r.readLine() ?: break; sb.append(l).append('\n'); if (l.isEmpty()) break }
            request = sb.toString()
            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 13\r\nX-Test: 1\r\n\r\n{\"version\":1}".toByteArray())
        } }.apply { isDaemon = true }.start()
        val (b, h) = bridge(local = true)
        b.sendRequest(uuid, "GET", "http://127.0.0.1:${server.localPort}/api/info/", "{\"Authorization\":\"Token abc\"}", "")
        val (ok, body) = answer(h)
        assertTrue(body, ok)
        val o = JSONObject(body)
        assertEquals(1, o.getJSONObject("json").getInt("version"))
        assertEquals("1", o.getJSONObject("headers").getString("x-test"))
        assertTrue(request.contains("Authorization: Token abc"))
        server.close()
    }

    @Test fun aLocalAddressIsRefusedOutsideTheLab() {
        val (b, h) = bridge(local = false)
        b.sendRequest(uuid, "GET", "http://127.0.0.1:1/api/info/", "{}", "")
        val (ok, msg) = answer(h)
        assertFalse(ok); assertTrue(msg.contains("local"))
        b.openWS(uuid, "ws://192.168.0.14:27778/relay")
        assertFalse(answer(h).first)
    }

    @Test fun badHeadersAreRefused() {
        val (b, h) = bridge()
        b.sendRequest(uuid, "GET", "http://127.0.0.1:1/", "not json", "")
        assertFalse(answer(h).first)
    }

    @Test fun encodingForJavaScriptCannotBreakOut() {
        val evil = "a'b\"c\\d\ne</script><img src=x onerror=alert(1)>& \u0000"
        val enc = RobloBridge.encodeForJavaScript(evil)
        assertFalse(enc.contains("'") && !enc.contains("\\'"))
        assertFalse(enc.contains("<")); assertFalse(enc.contains(">")); assertFalse(enc.contains("\n")); assertFalse(enc.contains(" ")); assertFalse(enc.contains("\u0000"))
        // Every quote is escaped: nothing ends the '…' literal early.
        assertFalse(Regex("(?<!\\\\)'").containsMatchIn(enc))
    }

    @Test fun answersWrapLikeTheApp() {
        assertEquals("{\"json\":{\"a\":1}, \"headers\": {}}", RobloBridge.wrapJson("{\"a\":1}", emptyMap()))
        assertEquals("{\"json\":null, \"headers\": {}}", RobloBridge.wrapJson("", emptyMap()))
        // An HTML error page (a proxy's 502) becomes a string, not broken JSON.
        val html = JSONObject(RobloBridge.wrapJson("<html>502</html>", emptyMap()))
        assertEquals("<html>502</html>", html.getString("json"))
        assertTrue(RobloBridge.validUuid(uuid)); assertFalse(RobloBridge.validUuid("$uuid'"))
        assertEquals(mapOf("A" to "1"), RobloBridge.parseHeaders("{\"A\":\"1\"}")); assertNull(RobloBridge.parseHeaders("[1"))
    }

    @Test fun afterCloseNothingIsAnswered() {
        val (b, h) = bridge()
        b.close()
        b.getEncryptedStorage(uuid, "garage_slots")
        b.sendRequest(uuid, "GET", "http://127.0.0.1:1/", "{}", "")
        assertNull(h.js.poll(300, TimeUnit.MILLISECONDS))
    }
}
