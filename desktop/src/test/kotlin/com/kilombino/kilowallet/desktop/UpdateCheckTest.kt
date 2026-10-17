package com.kilombino.kilowallet.desktop

import com.kilombino.pyblockwatch.data.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop updater trusts only sums signed by the pinned release key (made by packaging/sign-release.py). */
class UpdateCheckTest {
    private val sums = "e80b71cd14d3cbd65f4173abcbfcf01a545dbca32a72d575108b553a648cc96f  Kilowallet-9.9.9-x86_64.AppImage\n"
    private val sig = "200949c9bfe91f4499c362138b2b5574e970bcf9767f3fdc8099872d5d9563da0faeae9f0dd3ce95216c6fb62db9eaa731c8fd164459e0913de9a077d3046ea5"

    @Test fun signedSumsGiveTheHash() =
        assertEquals("e80b71cd14d3cbd65f4173abcbfcf01a545dbca32a72d575108b553a648cc96f",
            UpdateCheck.signedHash(sums, sig, "Kilowallet-9.9.9-x86_64.AppImage"))

    @Test fun aChangedSumsFileIsRefused() {
        assertNull(UpdateCheck.signedHash(sums.replace("e80b", "e80c"), sig, "Kilowallet-9.9.9-x86_64.AppImage"))
        assertNull(UpdateCheck.signedHash(sums, sig.replaceRange(0, 2, "21"), "Kilowallet-9.9.9-x86_64.AppImage"))
        assertNull(UpdateCheck.signedHash(sums, sig, "Kilowallet-9.9.8-x86_64.AppImage"))
    }

    @Test fun versionsOrder() {
        assertTrue(UpdateCheck.isNewer("desktop-v0.1.0-beta2", "0.1.0-beta1"))
        assertTrue(UpdateCheck.isNewer("v0.1.0", "0.1.0-beta9"))
        assertTrue(UpdateCheck.isNewer("v0.2.0-beta1", "0.1.0"))
        assertFalse(UpdateCheck.isNewer("v0.1.0-beta1", "0.1.0-beta1"))
        assertFalse(UpdateCheck.isNewer("v0.1.0-beta1", "0.1.0"))
        assertTrue(UpdateCheck.isNewer("v0.1.0-beta10", "0.1.0-beta9"))
    }

    @Test fun theVersionComesFromTheSignedFileName() {
        assertEquals("0.1.0-beta6", UpdateCheck.signedVersion("Kilowallet-0.1.0-beta6-x86_64.AppImage"))
        assertEquals("0.1.0-beta6", UpdateCheck.signedVersion("kilowallet_0.1.0-beta6_amd64.deb"))
        assertEquals("0.2.0", UpdateCheck.signedVersion("Kilowallet-0.2.0-windows-x64.msi"))
        assertEquals("0.2.0", UpdateCheck.signedVersion("Kilowallet-0.2.0-macos-arm64.dmg"))
        assertNull(UpdateCheck.signedVersion("SHA256SUMS"))
    }
}
