package com.kilombino.kilowallet.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceTest {
    @Test fun aSecondCopyShowsTheFirstAndLeaves() {
        val shown = CountDownLatch(1)
        assertTrue(SingleInstance.acquire(onShow = { shown.countDown() }))
        try {
            assertFalse(SingleInstance.acquire(onShow = {}, waitMs = 0))
            assertTrue("the first copy was asked to show its window", shown.await(5, TimeUnit.SECONDS))
        } finally { SingleInstance.release() }
        // Once released (the updater's restart), a new copy starts.
        assertTrue(SingleInstance.acquire(onShow = {}, waitMs = 0))
        SingleInstance.release()
    }
}
