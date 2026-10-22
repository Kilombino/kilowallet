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

    /** The JoinMarket wallet's texts, as the code builds them (values, upper-case labels, " · " pieces). */
    @Test fun joinMarketTexts() {
        I18n.lang.value = I18n.ES
        assertEquals("El fidelity bond se desbloquea en 7 días", I18n.tr("Fidelity bond unlocks in 7 days"))
        assertEquals("JoinMarket · Fidelity bond desbloqueado", I18n.tr("JoinMarket · Fidelity bond unlocked"))
        assertEquals("🔒 Bloqueado hasta el 2026-11-01 (dentro de 12 días)", I18n.tr("🔒 Locked until 2026-11-01 (in 12 days)"))
        assertEquals("MONEDERO JOINMARKET · TOTAL", I18n.tr("JOINMARKET WALLET · TOTAL"))
        assertEquals("JOINMARKET · SOLO LECTURA", I18n.tr("JOINMARKET · READ-ONLY"))
        assertEquals("bote #2 · m/84'/0'/2'", I18n.tr("jar #2 · m/84'/0'/2'"))
        I18n.lang.value = I18n.ZH
        assertEquals("忠诚保证金已解锁", I18n.tr("Fidelity bond unlocked"))
        I18n.lang.value = I18n.ES
    }

    /** Spending a bond with SeedSigner: every text in every language, as the code builds it. */
    @Test fun bondSpendTexts() {
        val keys = listOf("SPEND WITH SEEDSIGNER", "Spend fidelity bond · SeedSigner", "Unlocked since {0} · {1}",
            "Reading the signed PSBT: {0}%", "You receive {0} sats · fee {1} sats", "Fee: {0} sats ({1} vB)",
            "The bond was signed the old way, which is also valid on the SHA-256 chain (replayable). Use a signer that supports the BLAKE2b unified sighash (0x21). Nothing was sent.")
        for (l in I18n.LANGUAGES.map { it.first }.filter { it != I18n.EN }) {
            val o = JSONObject(File(dir, "$l.json").readText())
            keys.forEach { k -> assertTrue("$l lacks \"$k\"", o.has(k) && o.getString(k) != k) }
        }
        I18n.lang.value = I18n.ES
        assertEquals("GASTAR FIDELITY BOND · SEEDSIGNER", I18n.tr("SPEND FIDELITY BOND · SEEDSIGNER"))
        assertEquals("Desbloqueado desde el 2026-11-01 · m/84'/1'/0'/2/82", I18n.tr("Unlocked since 2026-11-01 · m/84'/1'/0'/2/82"))
        assertEquals("Recibes 49 999 680 sats · comisión 188 sats", I18n.tr("You receive 49 999 680 sats · fee 188 sats"))
        assertEquals("Leyendo el PSBT firmado: 44%", I18n.tr("Reading the signed PSBT: 44%"))
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
