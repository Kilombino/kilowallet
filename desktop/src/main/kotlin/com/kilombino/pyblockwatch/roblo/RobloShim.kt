package com.kilombino.pyblockwatch.roblo

import org.json.JSONArray
import org.json.JSONObject

/**
 * The page side of the desktop's ROBLO bridge, apart from JCEF (so it is tested on any JVM): the
 * script that becomes window.AndroidAppRobosats, and the dispatcher of the page's messages to
 * [RobloBridge]. On Android the WebView does both itself (addJavascriptInterface).
 */
object RobloShim {
    /** Every async method of [RobloBridge] the page may call (the closed list [dispatch] knows). */
    val ASYNC = listOf("generateRoboname", "generateRobohash", "copyToClipboard", "shareText", "keepScreenOn", "getTorStatus",
        "getRecentLogs", "restart", "getEncryptedStorage", "setEncryptedStorage", "deleteEncryptedStorage", "sendRequest", "sendBinary",
        "getBinary", "sendText", "openWS", "sendWsMessage")

    fun script(infoJson: String, explorer: String, navigation: String, features: List<String>, query: String): String {
        val e = RobloBridge::encodeForJavaScript
        val methods = (ASYNC + RobloBridge.WALLET_FEATURES.filter { it in features }).joinToString(",") { "'$it'" }
        return """(function(){var info=JSON.parse('${e(infoJson)}');var nav='${e(navigation)}';var explorer='${e(explorer)}';""" +
            """function q(m,a){var f=window.$query;if(!f){console.error('bridge missing for '+m);return;}f({request:JSON.stringify({m:m,a:a}),persistent:false,onSuccess:function(){},onFailure:function(c,t){console.warn('bridge '+m+': '+t);}});}""" +
            """var A={hostInfo:function(){return JSON.stringify(info);},appVersion:function(){return info.version;},isClearnet:function(){return !!info.clearnet;},""" +
            """hostExplorer:function(){return explorer;},takePendingNavigation:function(){var n=nav;nav='';return n;},""" +
            """setNetworkMode:function(p,c,h){info.clearnet=!!c;q('setNetworkMode',[!!p,!!c,String(h==null?'':h)]);}};""" +
            """[$methods].forEach(function(m){A[m]=function(){q(m,Array.prototype.slice.call(arguments));};});""" +
            """Object.defineProperty(window,'AndroidAppRobosats',{value:Object.freeze(A),writable:false,configurable:false});""" +
            """Object.defineProperty(window,'__kwNavigate',{value:function(n){nav=String(n||'');},writable:false,configurable:false});})();"""
    }

    /**
     * One message of the page: {m: method, a: [arguments]}, to the bridge (closed list, typed
     * arguments; a wallet method only when [features] offers it). False when it was refused.
     */
    fun dispatch(request: String, b: RobloBridge, features: List<String>): Boolean {
        val o = JSONObject(request)
        val a = o.optJSONArray("a") ?: JSONArray()
        fun s(i: Int): String = if (i >= a.length() || a.isNull(i)) "" else a.get(i).toString()
        fun d(i: Int): Double = if (i >= a.length() || a.isNull(i)) Double.NaN else a.optDouble(i, Double.NaN)
        fun z(i: Int): Boolean = i < a.length() && a.optBoolean(i, false)
        when (o.optString("m")) {
            "setNetworkMode" -> b.setNetworkMode(z(0), z(1), s(2))
            "generateRoboname" -> b.generateRoboname(s(0), s(1))
            "generateRobohash" -> b.generateRobohash(s(0), s(1))
            "copyToClipboard" -> b.copyToClipboard(s(0))
            "shareText" -> b.shareText(s(0))
            "keepScreenOn" -> b.keepScreenOn(z(0))
            "getTorStatus" -> b.getTorStatus(s(0))
            "getRecentLogs" -> b.getRecentLogs(s(0))
            "restart" -> b.restart()
            "getEncryptedStorage" -> b.getEncryptedStorage(s(0), s(1))
            "setEncryptedStorage" -> b.setEncryptedStorage(s(0), s(1), s(2))
            "deleteEncryptedStorage" -> b.deleteEncryptedStorage(s(0), s(1))
            "sendRequest" -> b.sendRequest(s(0), s(1), s(2), s(3), s(4))
            "sendBinary" -> b.sendBinary(s(0), s(1), s(2), s(3))
            "getBinary" -> b.getBinary(s(0), s(1))
            "sendText" -> b.sendText(s(0), s(1), s(2), s(3), s(4))
            "openWS" -> b.openWS(s(0), s(1))
            "sendWsMessage" -> b.sendWsMessage(s(0), s(1), s(2))
            "walletPay" -> if ("walletPay" in features) b.walletPay(s(0), s(1), d(2), s(3), d(4)) else return false
            "walletPayInvoice" -> if ("walletPayInvoice" in features) b.walletPayInvoice(s(0), s(1), s(2)) else return false
            "walletAddress" -> if ("walletAddress" in features) b.walletAddress(s(0), s(1), s(2)) else return false
            "walletInvoice" -> if ("walletInvoice" in features) b.walletInvoice(s(0), d(1), s(2)) else return false
            else -> return false
        }
        return true
    }
}
