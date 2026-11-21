package com.kilombino.pyblockwatch.roblo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The desktop's ROBLO host apart from Chromium: the page's shim, its messages to the bridge, the bundle. */
class DesktopRobloTest {
    private class Host : RobloBridge.Host {
        val js = LinkedBlockingQueue<String>()
        var clip: String? = null; var reloads = 0
        override fun evaluate(js: String) { this.js.add(js) }
        override fun copyToClipboard(text: String) { clip = text }
        override fun share(text: String) {}
        override fun keepScreenOn(on: Boolean) {}
        override fun reload() { reloads++ }
        override fun torStatus() = "ON"
        override fun appVersion() = "0.1.0-beta24-roblo1"
        override fun recentLogs() = ""
        override fun platform() = "desktop"
    }
    private class Mem : RobloStore.KeyValues {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]; override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }; override fun keys() = m.keys.toSet()
    }
    private val sealer = object : RobloStore.Sealer { override fun seal(plain: String) = "s:$plain"; override fun open(sealed: String) = sealed.removePrefix("s:") }
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"
    private val all = RobloBridge.WALLET_FEATURES

    private fun bridge(): Triple<RobloBridge, Host, RobloStore> {
        val h = Host(); val st = RobloStore(Mem(), sealer)
        return Triple(RobloBridge(h, st, RobloNet({ null }, { false }, false)), h, st)
    }
    private fun msg(m: String, vararg a: Any?) = JSONObject().put("m", m).put("a", JSONArray(a.map { it ?: JSONObject.NULL })).toString()

    @After fun clear() { RobloWallet.request.value = null }

    @Test fun messagesReachTheBridgeWithTheirArguments() {
        val (b, h, st) = bridge()
        assertTrue(RobloShim.dispatch(msg("setEncryptedStorage", uuid, "garage_slots", "{\"a\":1}"), b, all))
        val js = h.js.poll(5, TimeUnit.SECONDS)!!
        assertTrue(js.contains("onResolvePromise('$uuid', 'garage_slots')"))
        assertEquals("{\"a\":1}", st.get("garage_slots"))
        assertTrue(RobloShim.dispatch(msg("copyToClipboard", "hello"), b, all)); assertEquals("hello", h.clip)
        assertTrue(RobloShim.dispatch(msg("restart"), b, all)); assertEquals(1, h.reloads)
        // 0.9.11 Clearnet mode, sent by the page with its hosts
        assertTrue(RobloShim.dispatch(msg("setNetworkMode", true, true, "kilobot.kilombino.com"), b, all))
        assertTrue(st.clearnet()); assertEquals(setOf("kilobot.kilombino.com"), st.clearnetHosts())
    }

    @Test fun unknownCallsAndJavaNamesAreRefused() {
        val (b, _, _) = bridge()
        for (m in listOf("close", "dropConnections", "getClass", "hashCode", "wait", "evaluate", "", "toString"))
            assertFalse(m, RobloShim.dispatch(msg(m, uuid), b, all))
    }

    @Test fun aWalletFunctionOnlyWhenOffered() {
        val (b, h, _) = bridge()
        assertFalse(RobloShim.dispatch(msg("walletPayInvoice", uuid, "lnbcrt1xyz00000000000000000000", "kilobot/7"), b, listOf("walletPay", "walletAddress")))
        assertNull(RobloWallet.request.value)
        assertNull(h.js.poll(300, TimeUnit.MILLISECONDS))
        assertTrue(RobloShim.dispatch(msg("walletPay", uuid, "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", 3330, "kilobot/7", 1.5), b, all))
        val r = RobloWallet.request.value as RobloWallet.Request.Pay
        assertEquals(3330L, r.sats); assertEquals("kilobot/7", r.orderRef); assertEquals(1.5, r.feeHint, 0.0)
    }

    @Test fun missingOrNullArgumentsAreEmptyNotTheWordNull() {
        val (b, h, _) = bridge()
        assertTrue(RobloShim.dispatch(msg("getEncryptedStorage", uuid, null), b, all))
        assertTrue(h.js.poll(5, TimeUnit.SECONDS)!!.contains("onRejectPromise"))  // "" is not a key
        assertTrue(RobloShim.dispatch(msg("walletPay", uuid), b, all))           // no address/amount: refused by the bridge
        assertTrue(h.js.poll(5, TimeUnit.SECONDS)!!.contains("bad address"))
        assertNull(RobloWallet.request.value)
    }

    @Test fun theShimHasTheValuesAndOnlyTheOfferedWalletMethods() {
        val info = JSONObject().put("host", "kilowallet").put("version", "0.1.0-beta24-roblo1").put("clearnet", false).put("platform", "desktop")
            .put("features", JSONArray(listOf("walletPay"))).toString()
        val s = RobloShim.script(info, "https://mempool.kilombino.com/", "{\"path\":\"/order/kilobot/7\"}", listOf("walletPay"), "kwRobloQuery")
        assertTrue(s.contains("'walletPay'")); assertFalse(s.contains("'walletPayInvoice'")); assertFalse(s.contains("'walletInvoice'"))
        for (m in RobloShim.ASYNC) assertTrue(m, s.contains("'$m'"))
        assertTrue(s.contains("window.kwRobloQuery")); assertTrue(s.contains("Object.freeze(A)"))
        assertTrue(s.contains("takePendingNavigation")); assertTrue(s.contains("isClearnet"))
    }

    @Test fun nothingInTheValuesCanEndTheScriptOrTheString() {
        val evil = "x'</script><script>alert(1)</script>\\'\n"
        val s = RobloShim.script("{\"version\":\"$evil\"}".let { JSONObject().put("v", evil).toString() }, evil, evil, emptyList(), "q")
        assertFalse(s.contains("</script>")); assertFalse(s.contains("<script>"))
        assertFalse(s.contains("\n"))
        // the single quotes of the values are all escaped
        assertFalse(Regex("[^\\\\]'alert").containsMatchIn(s))
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, c) in files) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun theBundleIsServedWithItsPolicyAndTheShimFirst() {
        val bundle = RobloAssets.unzip(zip("index.html" to "<html><head><script src=\"./static/frontend/main.js\"></script></head></html>",
            "static/frontend/main.js" to "x()", "../evil" to "no"))
        assertNull(bundle["roblo/../evil"]); assertEquals(2, bundle.size)
        val idx = RobloAssets.answer("https://appassets.androidplatform.net/roblo/index.html", { "var SHIM=1;" }, bundle)
        assertEquals(200, idx.status); assertEquals(RobloAssets.CSP, idx.headers["Content-Security-Policy"]); assertEquals("no-store", idx.headers["Cache-Control"])
        val html = String(idx.body)
        assertTrue(html.indexOf("var SHIM=1;") in 0 until html.indexOf("main.js"))
        val js = RobloAssets.answer("https://appassets.androidplatform.net/roblo/static/frontend/main.js?x=1", { error("not for js") }, bundle)
        assertEquals("text/javascript", js.mime); assertEquals("x()", String(js.body)); assertNull(js.headers["Content-Security-Policy"])
        assertEquals(404, RobloAssets.answer("https://appassets.androidplatform.net/roblo/nope.js", { "" }, bundle).status)
        assertEquals(403, RobloAssets.answer("https://evil.com/roblo/index.html", { "" }, bundle).status)
        assertEquals(403, RobloAssets.answer("http://appassets.androidplatform.net/roblo/index.html", { "" }, bundle).status)
    }

    /** The pinned client in this build is the one its CLIENT file names, and it really is served. */
    @Test fun theBuildCarriesThePinnedClient() {
        val files = RobloAssets.files()
        assertTrue(files.containsKey("roblo/index.html")); assertTrue(files.containsKey("roblo/LICENSE"))
        val source = String(files.getValue("roblo/SOURCE"))
        if (!com.kilombino.pyblockwatch.BuildConfig.ROBLO_LAB) assertTrue(source.contains("base=relative"))
    }
}
