package com.kilombino.kilowallet.desktop

import com.kilombino.kilowallet.desktop.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kilombino.pyblockwatch.ui.i18n.I18n as AppI18n

/** The update dialog as the desktop shows it: its own sentences, translated paragraph by paragraph. */
class DesktopI18nTest {
    private val update = "Update now: it downloads here, is checked against the published hash and this app's certificate, " +
        "and installs over this one keeping everything."
    private val unsigned = "\n\nThis release can't be installed from here (no APK, or no published hash to check it against): open its page instead."

    @After fun english() { AppI18n.lang.value = AppI18n.EN }

    @Test fun signedReleaseSaysTheSignatureWasChecked() {
        AppI18n.lang.value = AppI18n.EN
        val en = I18n.tr(update)
        assertTrue(en, en.startsWith("✅ Signature checked"))
        AppI18n.lang.value = AppI18n.ES
        val es = I18n.tr(update)
        assertTrue(es, es.startsWith("✅ Firma comprobada"))
        assertFalse(es, "Update now" in es)
    }

    @Test fun unsignedReleaseDoesNotClaimASignature() {
        for (l in listOf(AppI18n.EN, AppI18n.ES, "fr", "de", "zh")) {
            AppI18n.lang.value = l
            val s = I18n.tr(update + unsigned)
            assertTrue("$l: $s", s.startsWith("⚠️"))
            assertFalse("$l: $s", "✅" in s)
        }
    }

    @Test fun everyLanguageHasTheDesktopSentences() {
        for (l in listOf("es", "zh", "fr", "it", "de", "ca", "pt")) {
            AppI18n.lang.value = l
            for (s in listOf(update, "Checking the hash and the certificate…", "Desktop beta for Windows", "Desktop beta for macOS")) {
                val t = I18n.tr(s)
                assertFalse("$l: $t", t.contains("Update now") || t.contains("Checking the") || t.startsWith("Desktop beta"))
            }
        }
        AppI18n.lang.value = "es"
        assertEquals("Beta de escritorio para macOS", I18n.tr("Desktop beta for macOS"))
    }
}
