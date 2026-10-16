package com.kilombino.pyblockwatch.ui

import com.kilombino.pyblockwatch.ui.i18n.I18n
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The language tables and the translator, as the app ships them (assets/i18n). */
class I18nTest {
    private val dir = listOf(File("src/main/assets/i18n"), File("app/src/main/assets/i18n"), File("../app/src/main/assets/i18n"))
        .first { it.isDirectory }

    @Before fun tables() {
        I18n.loader = { l -> File(dir, "$l.json").takeIf { it.exists() }?.readText() }
    }

    @Test fun eachLanguageAndEnglishAsWritten() {
        I18n.lang.value = I18n.ES
        assertEquals("Recibir", I18n.tr("Receive"))
        assertEquals("Monedero caliente", I18n.tr("Hot wallet"))
        I18n.lang.value = I18n.EN
        assertEquals("Receive", I18n.tr("Receive"))
        I18n.lang.value = I18n.ZH
        assertEquals("接收", I18n.tr("Receive"))
        I18n.lang.value = I18n.ES
    }

    @Test fun templatesUpperCaseAndListItems() {
        I18n.lang.value = I18n.ES
        assertEquals("\"pato\" no es una palabra de recuperación.", I18n.tr("\"pato\" is not a seed word."))
        assertEquals("CONTROL DE MONEDAS", I18n.tr("COIN CONTROL"))
        assertEquals("•  Recibir", I18n.tr("•  Receive"))
        assertEquals("Recibir · Monedero caliente", I18n.tr("Receive · Hot wallet"))
        // Pieces with their own spaces around the dot keep them.
        assertEquals("m/84'/0'/0'/0/1  ·  recibir", I18n.tr("m/84'/0'/0'/0/1  ·  receive"))
        assertEquals("🔥 CALIENTE", I18n.tr("🔥 HOT"))
        // Not in the table: as is.
        assertEquals("xyzzy 42", I18n.tr("xyzzy 42"))
    }

    @Test fun everyTableKeepsThePlaceholdersAndTheEdges() {
        for (l in I18n.LANGUAGES.map { it.first }.filter { it != I18n.EN && File(dir, "$it.json").exists() }) {
            val o = JSONObject(File(dir, "$l.json").readText())
            for (k in o.keys()) {
                val v = o.getString(k)
                val ph = { s: String -> Regex("""\{\d+\}""").findAll(s).map { it.value }.sorted().toList() }
                assertEquals("$l: $k", ph(k), ph(v))
                assertTrue("$l edges: $k", k.takeWhile { it.isWhitespace() } == v.takeWhile { it.isWhitespace() } &&
                    k.takeLastWhile { it.isWhitespace() } == v.takeLastWhile { it.isWhitespace() })
            }
        }
    }
}
