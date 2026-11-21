package com.kilombino.kilowallet.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeping the computer awake while ROBLO's Lightning payment is held, on each system. */
class SleepGuardTest {
    @Test fun eachSystemItsOwnInhibitorThatEndsWithTheApp() {
        val linux = SleepGuard.command(Platform.Os.LINUX, 4242, "Roblosats kilobot/7")
        if (linux != null) { // systemd-inhibit installed here
            assertTrue(linux[0].endsWith("systemd-inhibit"))
            assertTrue(linux.contains("--what=sleep:idle:handle-lid-switch")); assertTrue(linux.contains("--mode=block"))
            assertEquals(listOf("tail", "--pid=4242", "-f", "/dev/null"), linux.takeLast(4))
        }
        val win = SleepGuard.command(Platform.Os.WINDOWS, 4242, "x")!!
        assertEquals("powershell.exe", win[0])
        assertTrue(win.last().contains("SetThreadExecutionState([uint32]2147483649)")) // ES_CONTINUOUS | ES_SYSTEM_REQUIRED
        assertTrue(win.last().contains("Get-Process -Id 4242"))
        assertEquals(listOf("/usr/bin/caffeinate", "-i", "-m", "-s", "-w", "4242"), SleepGuard.command(Platform.Os.MAC, 4242, "x"))
    }

    @Test fun aLateTickMeansTheComputerSlept() {
        assertFalse(SleepGuard.woke(400)); assertFalse(SleepGuard.woke(20_000)); assertTrue(SleepGuard.woke(45_000))
    }

    @Test fun aClickedNotificationIsReadFromNotifySend() {
        assertTrue(Platform.clicked("default\n")); assertFalse(Platform.clicked("")); assertFalse(Platform.clicked("closed\n"))
        assertTrue(Platform.notifyClickCommand("t", "x").contains("--wait"))
    }
}
