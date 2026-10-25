package com.kilombino.kilowallet.desktop

import android.app.DesktopNotifications
import android.app.Notification
import android.app.NotificationManager
import androidx.activity.compose.DesktopBack
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopShimsTest {
    @Test fun escapeClosesTheLatestEnabledScreen() {
        var closed = ""
        val outer = mutableStateOf(true) to mutableStateOf({ closed = "outer" })
        val inner = mutableStateOf(false) to mutableStateOf({ closed = "inner" })
        val add = DesktopBack::class.java.declaredMethods.first { it.name.startsWith("add") }.apply { isAccessible = true }
        val remove = DesktopBack::class.java.declaredMethods.first { it.name.startsWith("remove") }.apply { isAccessible = true }
        add.invoke(DesktopBack, outer); add.invoke(DesktopBack, inner)
        try {
            assertTrue(DesktopBack.press()); assertEquals("outer", closed)
            inner.first.value = true
            assertTrue(DesktopBack.press()); assertEquals("inner", closed)
        } finally { remove.invoke(DesktopBack, outer); remove.invoke(DesktopBack, inner) }
        assertFalse("nothing to close: Escape is left alone", DesktopBack.press())
    }

    @Test fun roundCardsAreKeptQuietAndListedAsUp() {
        val popped = mutableListOf<String>()
        val before = DesktopNotifications.popup
        DesktopNotifications.popup = { t, _ -> popped += t }
        try {
            val nm = NotificationManager()
            val card = Notification.Builder(DesktopApp).setContentTitle("Round").setContentText("confirmed")
                .setGroup("coinjoin_rounds").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).build()
            nm.notify(5001, card)
            nm.notify(5002, Notification.Builder(DesktopApp).setContentTitle("Sign now").setContentText("x").build())
            assertEquals(listOf("Sign now"), popped)
            assertEquals(setOf(5001, 5002), nm.activeNotifications.map { it.id }.toSet())
            nm.cancel(5001); nm.cancel(5002)
            assertTrue(nm.activeNotifications.none { it.id == 5001 || it.id == 5002 })
        } finally { DesktopNotifications.popup = before }
    }

    @Test fun theLightningEngineLoads() {
        // Its static block loads libgojni (the engine built for this platform) and starts the Go side.
        val c = Class.forName("lndmobile.Lndmobile")
        assertTrue(c.methods.any { it.name == "estimateSplice" })
    }

    /** What the Telegram alerts say when Tor is not up (its last line, its log to copy), from the desktop's tor. */
    @Test fun torsOutputIsKeptForTheAlerts() {
        if (Platform.os == Platform.Os.WINDOWS) return
        val dir = kotlin.io.path.createTempDirectory("kw-fake-tor").toFile()
        val old = System.getProperty("kilowallet.natives")
        java.io.File(dir, "tor").apply {
            writeText("#!/bin/sh\necho 'Oct 25 10:00:00.000 [notice] Bootstrapped 45% (requesting_descriptors): Asking for relay descriptors'\nsleep 30\n")
            setExecutable(true, true)
        }
        val log = java.io.File(dir, "tor.log")
        System.setProperty("kilowallet.natives", dir.path)
        org.torproject.jni.TorService.logTo = log
        val svc = org.torproject.jni.TorService()
        try {
            svc.onBind(null)
            val end = System.currentTimeMillis() + 10_000
            while (!log.exists() || log.length() == 0L) { require(System.currentTimeMillis() < end) { "no tor output" }; Thread.sleep(100) }
            Thread.sleep(200)
            assertTrue(svc.alive)
            assertEquals("Bootstrapped 45% (requesting_descriptors): Asking for relay descriptors", org.torproject.jni.TorService.lastLine)
            assertTrue(log.readText().contains("Bootstrapped 45%"))
        } finally {
            svc.onDestroy()
            if (old == null) System.clearProperty("kilowallet.natives") else System.setProperty("kilowallet.natives", old)
            org.torproject.jni.TorService.logTo = null
            dir.deleteRecursively()
        }
        assertFalse(svc.alive)
    }
}
