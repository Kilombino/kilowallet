package com.kilombino.kilowallet.desktop

import android.content.Context
import com.kilombino.pyblockwatch.data.AppUpdater
import com.kilombino.pyblockwatch.data.UpdateCheck
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Downloads the real release through GitHub's redirect and checks its signed hash; only with
 * KW_LIVE=1. A test run is not a packaged copy, so it stops at install with "not installed
 * from a release package": reaching that means the download and the hash were good.
 */
class AppUpdaterLiveTest {
    @Test fun downloadsAndVerifiesTheSignedRelease() {
        assumeTrue(System.getenv("KW_LIVE") == "1")
        val r = (UpdateCheck.outcome("0.0.1") as UpdateCheck.Outcome.Newer).release
        val tmp = Files.createTempDirectory("kw-update").toFile()
        val ctx = object : Context() { override val cacheDir: File get() = tmp }
        AppUpdater.start(ctx, r)
        val deadline = System.currentTimeMillis() + 300_000
        var s: AppUpdater.State
        do { Thread.sleep(200); s = AppUpdater.state.value } while (s !is AppUpdater.State.Failed && System.currentTimeMillis() < deadline)
        println("${r.version}: $s")
        assertTrue("$s", s is AppUpdater.State.Failed && "release package" in s.message)
        tmp.deleteRecursively()
    }
}
