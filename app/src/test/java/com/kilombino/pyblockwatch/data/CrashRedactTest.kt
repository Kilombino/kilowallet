package com.kilombino.pyblockwatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrashRedactTest {
    @Test fun serversAreBlanked() {
        val t = "NodeException: http://u:p@192.168.1.20:8332/ refused; tried li7nwmrffmt6iut2ygolrs6mgrzwdxe26mtatzpgxvff3fri3rnq4iid.onion " +
            "and fulcrum.kilombino.com:17717, peer 2a01:4f8:1:2::5 at 10.0.0.3"
        val r = CrashReport.redact(t)
        assertFalse(r, r.contains("192.168"))
        assertFalse(r, r.contains("li7nwm"))
        assertFalse(r, r.contains("kilombino.com:17717"))
        assertFalse(r, r.contains("2a01"))
        assertFalse(r, r.contains("10.0.0.3"))
        // Code locations survive.
        assertEquals("at com.kilombino.pyblockwatch.ln.Lnd.pay(Lnd.kt:479)", CrashReport.redact("at com.kilombino.pyblockwatch.ln.Lnd.pay(Lnd.kt:479)"))
    }
}
