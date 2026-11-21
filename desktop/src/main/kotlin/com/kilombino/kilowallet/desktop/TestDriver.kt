package com.kilombino.kilowallet.desktop

import com.kilombino.pyblockwatch.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Window
import javax.accessibility.AccessibleContext

/**
 * Test builds only (-PkwDebug=true, never packaged): what is on the window's screens, for the
 * regtest lab's scenarios, which then click there with real mouse events (xdotool), as on Android
 * they read uiautomator's dump. Read-only: it lists the accessible elements (name, role, bounds on
 * screen) of every open window; it can't press anything itself. Loopback only.
 */
object TestDriver {
    fun start() {
        if (!BuildConfig.DEBUG) return
        val port = System.getProperty("kilowallet.testdriver")?.toIntOrNull() ?: return
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 0)
        server.createContext("/nodes") { ex ->
            val body = runCatching { nodes().toString() }.getOrElse { JSONObject().put("error", it.toString()).toString() }.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.executor = null
        server.start()
    }

    private fun nodes(): JSONArray {
        val out = JSONArray()
        val done = java.util.concurrent.CountDownLatch(1)
        javax.swing.SwingUtilities.invokeLater {
            try {
                for (w in Window.getWindows()) if (w.isShowing) w.accessibleContext?.let { walk(it, 0, out) }
            } finally { done.countDown() }
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    private fun walk(c: AccessibleContext, depth: Int, out: JSONArray) {
        if (depth > 60 || out.length() > 3000) return
        val name = c.accessibleName
        val comp = c.accessibleComponent
        if (!name.isNullOrBlank() && comp != null) runCatching {
            val p = comp.locationOnScreen; val s = comp.size
            if (p != null && s != null && s.width > 0 && s.height > 0)
                out.put(JSONObject().put("name", name).put("role", c.accessibleRole?.toDisplayString(java.util.Locale.ENGLISH) ?: "")
                    .put("x", p.x).put("y", p.y).put("w", s.width).put("h", s.height)
                    .put("enabled", c.accessibleStateSet?.contains(javax.accessibility.AccessibleState.ENABLED) ?: true))
        }
        for (i in 0 until c.accessibleChildrenCount) {
            val child = c.getAccessibleChild(i) ?: continue
            child.accessibleContext?.let { walk(it, depth + 1, out) }
        }
    }
}
