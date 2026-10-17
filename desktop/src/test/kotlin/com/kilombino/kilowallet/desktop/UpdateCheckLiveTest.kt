package com.kilombino.kilowallet.desktop

import com.kilombino.pyblockwatch.data.UpdateCheck
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Against the real releases repo; only with KW_LIVE=1. */
class UpdateCheckLiveTest {
    @Test fun findsTheSignedRelease() {
        assumeTrue(System.getenv("KW_LIVE") == "1")
        val o = UpdateCheck.outcome("0.1.0-beta1") // a beta: every release so far is one
        assertTrue("$o", o is UpdateCheck.Outcome.Newer)
        val r = (o as UpdateCheck.Outcome.Newer).release
        println("found ${r.version} ${r.apkUrl} ${r.sha256}")
        assertNotNull(r.apkUrl); assertNotNull(r.sha256)
    }
}
