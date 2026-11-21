package com.kilombino.pyblockwatch.roblo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local origin that serves the bundled client, and nothing else. */
class RobloAssetsTest {
    private val h = "appassets.androidplatform.net"

    @Test fun onlyOurOriginAndFolder() {
        assertEquals("roblo/index.html", RobloAssets.assetPath("https", h, "/roblo/"))
        assertEquals("roblo/static/frontend/main.js", RobloAssets.assetPath("https", h, "/roblo/static/frontend/main.js"))
        assertNull(RobloAssets.assetPath("http", h, "/roblo/index.html"))
        assertNull(RobloAssets.assetPath("https", "evil.com", "/roblo/index.html"))
        assertNull(RobloAssets.assetPath("https", h, "/i18n/es.json"))          // Kilowallet's own assets stay out
        assertNull(RobloAssets.assetPath("https", h, "/roblo/../i18n/es.json"))
        assertNull(RobloAssets.assetPath("https", h, "/roblo/static//x"))
    }

    @Test fun typesForTheBundle() {
        assertEquals("application/wasm", RobloAssets.mimeOf("roblo/static/frontend/x.module.wasm"))
        assertEquals("text/javascript", RobloAssets.mimeOf("a.js")); assertEquals("audio/mpeg", RobloAssets.mimeOf("a.mp3"))
        assertEquals("font/woff2", RobloAssets.mimeOf("a.woff2")); assertEquals("application/octet-stream", RobloAssets.mimeOf("a.exe"))
    }

    /** The page can't reach any server itself: no remote origin in the policy. */
    @Test fun thePolicyHasNoRemoteOrigin() {
        val csp = RobloAssets.CSP
        assertTrue(csp.contains("connect-src 'self' data: blob:"))
        assertTrue(csp.contains("default-src 'self'"))
        assertFalse(csp.contains("http:")); assertFalse(csp.contains("https:")); assertFalse(csp.contains("ws:")); assertFalse(csp.contains("*"))
        assertFalse(csp.contains("'unsafe-eval'"))
    }
}
