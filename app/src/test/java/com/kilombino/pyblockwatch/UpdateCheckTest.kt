package com.kilombino.pyblockwatch

import com.kilombino.pyblockwatch.data.UpdateCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun versions() {
        assertTrue(UpdateCheck.isNewer("v0.20.0", "0.19.0"))
        assertTrue(UpdateCheck.isNewer("v0.20.0", "0.20.0-beta3"))   // the final beats its betas
        assertFalse(UpdateCheck.isNewer("v0.19.0", "0.20.0-beta3"))
        assertFalse(UpdateCheck.isNewer("v0.20.0", "0.20.0"))
        assertTrue(UpdateCheck.isNewer("v0.20.1", "0.20.0"))
        // The ROBLO beta is not a pre-release of its base: the plain 1.6.1 is not offered to it.
        assertFalse(UpdateCheck.isNewer("v1.6.1", "1.6.1-roblo"))
        assertFalse(UpdateCheck.isNewer("v1.6.1", "1.6.1-roblo2"))
        assertFalse(UpdateCheck.isNewer("v1.6.1", "1.6.1-roblo3"))
        assertFalse(UpdateCheck.isNewer("v1.6.0", "1.6.1-roblo"))
        assertTrue(UpdateCheck.isNewer("v1.6.2", "1.6.1-roblo"))
        assertTrue(UpdateCheck.isNewer("v1.0.0", "0.99.9"))
        assertFalse(UpdateCheck.isNewer("v0.9.0", "0.10.0"))
    }
}
