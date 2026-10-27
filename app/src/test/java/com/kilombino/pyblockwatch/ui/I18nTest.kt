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

    /** What a send or a perfect coin being prepared says while it waits: every text in every language. */
    @Test fun preparingTexts() {
        val keys = listOf("Reading your coins from the server…", "Proving your coins in their blocks…",
            "Checking your coins with the server…", "Preparing the payment to yourself…",
            "Finding unused addresses in the other wallet…", "{0} sats to a fresh address of yours.", "choosing coins…")
        for (l in I18n.LANGUAGES.map { it.first }.filter { it != I18n.EN }) {
            val o = JSONObject(File(dir, "$l.json").readText())
            keys.forEach { k -> assertTrue("$l lacks \"$k\"", o.has(k) && o.getString(k) != k) }
        }
        I18n.lang.value = I18n.ES
        assertEquals("Preparando el pago a ti mismo…", I18n.tr("Preparing the payment to yourself…"))
        assertEquals("12 345 sats a una dirección nueva tuya.", I18n.tr("12 345 sats to a fresh address of yours."))
        I18n.lang.value = I18n.ES
    }

    /**
     * The exact coin's panel and the coinjoin pools, as the code builds them: section labels upper-case
     * a template ("EXACT COIN · 12 345 SATS"), pieces joined with " · ", and whole sentences per case.
     */
    @Test fun exactCoinAndPoolTexts() {
        val keys = listOf(
            "A payment to yourself of exactly {0} sats, to a fresh {1} address of this wallet. It needs one confirmation before it can join; then it goes in with no change.",
            "native SegWit", "mixed", "ticked: {0} coin(s), {1} sats", "ticked: {0} coin(s), {1} sats — not enough for {2} plus the fee",
            "to {0} · from {1} coin(s) · fee {2} sats", "CONFIRM & SIGN", "Sent ✓", "one coin each", "up to {0} coins each", "closes in now",
            "this coin", "these {0} coins", "change address.", "{0} coin(s), {1} sats: {2} sats short",
            "{0} coin(s) · {1} sats → {2} mixed + {3} change · fee {4}", "Fee: 1 to 500 sat/vB.", "{0} sats  ★ EXACT", "TEST POOL",
            "Confirmed ✅", "Refused: {0}",
            "Checked: {0} people ({1} coins), {2} identical outputs of {3} sats, {4} of them yours; your change is right; total fee {5} sats.",
            "Checked: {0} people ({1} coins), {2} identical outputs of {3} sats, {4} of them yours; no change (an exact coin); total fee {5} sats.",
            "Checked: {0} people ({1} coins), {2} identical outputs of {3} sats, one of which is yours; your change is right; total fee {4} sats.",
            "Checked: {0} people ({1} coins), {2} identical outputs of {3} sats, one of which is yours; no change (an exact coin); total fee {4} sats.")
        val shown = listOf("EXACT COIN · 12 345 SATS", "JOIN · 3 × 12 345 SATS · TAPROOT",
            "A payment to yourself of exactly 12 345 sats, to a fresh native SegWit (bc1q) address of this wallet. It needs one confirmation before it can join; then it goes in with no change.",
            "Tick one or more coins, native SegWit and Taproot alike; with none ticked the biggest never-mixed ones go first. The exact coin itself is always native SegWit, the pool's type.",
            " · mixed", "ticked: 2 coin(s), 30 000 sats — not enough for 40 000 plus the fee",
            "to bc1q…abcd · from 2 coin(s) · fee 300 sats", "CONFIRM & SIGN",
            "Native SegWit (bc1q) coins only · up to 3 coins each", "Taproot (bc1p) coins only · one coin each",
            "Native SegWit (bc1q) coins only · up to 3 coins each · 3 outputs each (v4)",
            "3/5 people (min 2) · 2 sat/vB · closes in now",
            "With these 3 coins you get 1 234 sats of change back, and that change shows on the chain next to your coins. A single coin of exactly " +
                "12 345 sats goes in with no change at all, so nothing ties your mixed output to anything. You can make it now: a payment to " +
                "yourself (from any of your coins, bc1q and bc1p together), which needs one confirmation before it can join.",
            "Pick ONE confirmed native SegWit (bc1q) coin of at least 12 345 sats. Its leftover comes back to a fresh change address.\n\n" +
                "The mixed output goes to an unused address of the wallet chosen above.",
            "12 345 sats  ★ EXACT", "2 coin(s), 3 000 sats: 9 345 sats short", "2 coin(s) · 30 000 sats → 3 × 9 000 mixed + 2 700 change · fee 300",
            "Fee: 1 to 500 sat/vB.", "12 345 sats  · TEST POOL", "🔒 joined", "Confirmed ✅",
            "Checked: 3 people (4 coins), 3 identical outputs of 12 345 sats, one of which is yours; no change (an exact coin); total fee 900 sats.",
            "Checked: 3 people (4 coins), 6 identical outputs of 12 345 sats, 2 of them yours; your change is right; total fee 900 sats.",
            "POOLS",
            // The coinjoin intro, one line at a time as the screen translates it.
            "• Someone opens a pool (from 10 000 sats each to 1 BTC per output) and others join with one coin.\n",
            "• With enough people in, anyone asks to close and the rest accept.\n",
            "• Your wallet checks the transaction and you sign with your fingerprint. Nobody holds your ",
            "coins: the relay only passes encrypted messages.\n")
        val english = Regex("""\b(coins?|change address|fresh|ticked|mixed|each|yours|EXACT|of them|not enough|people)\b""")
        for (l in I18n.LANGUAGES.map { it.first }.filter { it != I18n.EN }) {
            val o = JSONObject(File(dir, "$l.json").readText())
            keys.forEach { k -> assertTrue("$l lacks \"$k\"", o.has(k) && o.getString(k) != k) }
            I18n.lang.value = l
            // Nothing left in English: "POOLS" is a word of its own in every language but Chinese.
            shown.filter { it != "POOLS" || l == I18n.ZH }.forEach { s ->
                val t = I18n.tr(s)
                assertTrue("$l: \"$s\" -> \"$t\"", t != s && !english.containsMatchIn(t))
            }
        }
        I18n.lang.value = I18n.ES
        assertEquals("MONEDA EXACTA · 12 345 SATS", I18n.tr("EXACT COIN · 12 345 SATS"))
        assertEquals("Solo monedas SegWit nativo (bc1q) · hasta 3 monedas cada uno", I18n.tr("Native SegWit (bc1q) coins only · up to 3 coins each"))
        assertEquals("Solo monedas Taproot (bc1p) · una moneda cada uno", I18n.tr("Taproot (bc1p) coins only · one coin each"))
        assertEquals("Un pago a ti mismo de exactamente 12 345 sats, a una dirección SegWit nativo (bc1q) nueva de este monedero. " +
            "Necesita una confirmación antes de poder unirse; después entra sin cambio.",
            I18n.tr("A payment to yourself of exactly 12 345 sats, to a fresh native SegWit (bc1q) address of this wallet. " +
                "It needs one confirmation before it can join; then it goes in with no change."))
        assertEquals("12 345 sats  ★ EXACTA", I18n.tr("12 345 sats  ★ EXACT"))
        assertEquals(" · mezclada", I18n.tr(" · mixed"))
        I18n.lang.value = I18n.ZH
        // A template that starts with its value keeps to its own piece of a " · " line.
        assertEquals("仅限原生 SegWit（bc1q）币 · 每人最多 3 枚币 · 每人 3 个输出（v4）",
            I18n.tr("Native SegWit (bc1q) coins only · up to 3 coins each · 3 outputs each (v4)"))
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

    /**
     * 1.3.3: another seat in a pool, the perpetual coinjoin and mined rewards still locked, as the code
     * builds them (pieces joined with " · ", values inside, upper-case labels), in every language.
     */
    /** The perpetual coinjoin's explanation, as the checkbox shows it, in every language. */
    @Test fun perpetualExplainTranslated() {
        val k = "When the round confirms, your mixed coin joins another pool by itself (or opens one with the whole coin, no change), again and again; each round costs it its fee share. It stops when you turn it off or spend the coin, or when it falls below a pool's minimum. Its pools use the fee you chose for this one. Only the hot wallet's output remixes, never a change. If this pool ends without a round, the same pool opens again, with the same coins and addresses, until one happens. While it is on, the key of this wallet's receive addresses of this type stays on the phone, encrypted, so it can sign without you; until its first round, that of its change addresses too."
        for (l in I18n.LANGUAGES.map { it.first }.filter { it != I18n.EN }) {
            val o = JSONObject(File(dir, "$l.json").readText())
            assertTrue("$l lacks the perpetual coinjoin's explanation", o.has(k) && o.getString(k) != k)
        }
    }

    @Test fun seatsPerpetualAndMinedRewardTexts() {
        val shown = listOf(
            "Another seat of yours counts as one more member: the others get less privacy than they see, and spending both results together later links them.",
            "ANOTHER SEAT · 100 000 SATS", "＋ JOIN WITH ANOTHER UTXO", "LEAVE THIS SEAT", "Leave with this seat?",
            "Your seat 2 of 2", "100 000 sats each · seat 2 of 2 · ♻ perpetual",
            "Joining in 42 s: a random wait, so your seats don't show up together.",
            "Only this seat leaves (its coin of 150 000 sats is yours to spend again); your other seat stays in the pool.",
            "♻ Perpetual coinjoin", "fee cap (sat/vB)", "Fee cap: 2 sat/vB", "Fee cap: 1 to 500 sat/vB.",
            "♻ Perpetual: in a round now", "♻ Perpetual: looking for its next pool in 37 s",
            "♻ Perpetual: stopped (the coin was spent or moved)", "♻ Perpetual: stopped (the fees rose above your cap (2 sat/vB))",
            "♻ Perpetual: stopped (turned off)", "Rounds so far: 3 (150 000 → 99 373 sats)", "99 373 sats · perpetual coin",
            "TURN ON AGAIN", "change the fee cap", "♻ perpetual coinjoin: sending it stops it",
            "Two or more of these coins came out of the same coinjoin round: spending them together links them, and shows they were one person's.",
            "⛏ mined reward · matures at block 979919 (~2026-11-02)",
            "of which 1 234 sats are mined rewards still locked (until block 979919, ~2026-11-02)",
            "of which 3 000 sats are mined rewards still locked: the next unlocks at block 979001 (~2026-10-28), the last at block 979919 (~2026-11-02)",
            "3 000 sats not spendable yet (mined rewards maturing)")
        val english = Regex("""\b(seats?|another|perpetual|stopped|mined|rewards?|locked|block|spendable|turned|fees?|coins?|links?)\b""")
        for (l in I18n.LANGUAGES.map { it.first }.filter { it != I18n.EN }) {
            I18n.lang.value = l
            shown.forEach { s ->
                val t = I18n.tr(s)
                assertTrue("$l: \"$s\" -> \"$t\"", t != s && !english.containsMatchIn(t.replace("coinjoin", "").replace("UTXO", "")))
            }
        }
        I18n.lang.value = I18n.ES
        assertEquals("100 000 sats cada una · puesto 2 de 2 · ♻ perpetuo", I18n.tr("100 000 sats each · seat 2 of 2 · ♻ perpetual"))
        assertEquals("♻ Perpetuo: detenido (la moneda se gastó o se movió)", I18n.tr("♻ Perpetual: stopped (the coin was spent or moved)"))
        assertEquals("de los cuales 1 234 sats son recompensas de minería aún bloqueadas (hasta el bloque 979919, ~2026-11-02)",
            I18n.tr("of which 1 234 sats are mined rewards still locked (until block 979919, ~2026-11-02)"))
        assertEquals("⛏ recompensa de minería · madura en el bloque 979919 (~2026-11-02)", I18n.tr("⛏ mined reward · matures at block 979919 (~2026-11-02)"))
        // The watch-only PSBT's instructions, in its two pieces as the code joins them.
        assertEquals("Lleva este PSBT a tu firmante: SeedSigner (BLAKE2b, escanea el QR animado) o Bitcoin Knots (walletprocesspsbt). " +
            "Tiene que firmar con el sighash unificado de BLAKE2b (0x21): una firma a la antigua también valdría en la spamchain, así que se rechaza.",
            I18n.tr("Take this PSBT to your signer: SeedSigner (BLAKE2b, scan the animated QR) or Bitcoin Knots (walletprocesspsbt). It must sign with the " +
                "BLAKE2b unified sighash (0x21): an old-style signature would also be valid on the spamchain, so it is refused."))
        assertEquals("Apunta la cámara al QR del PSBT firmado", I18n.tr("Point the camera at the signed PSBT's QR"))
        assertEquals("Una persona, un nodo: pon aquí el tuyo (también vale una dirección .onion, a través del Tor de la app). " +
            "Déjalo vacío para usar fulcrum.kilombino.com:17717, pensado para emergencias.",
            I18n.tr("One person, one node: put your own here (a .onion address works too, through the app's Tor). Leave empty to use " +
                "fulcrum.kilombino.com:17717, meant for emergencies."))
        assertEquals("Conectar al servidor a través de Tor", I18n.tr("Connect to the server through Tor"))
        assertEquals("♻ Perpetuo: su primer pool terminó sin ronda; el mismo pool se vuelve a abrir en 42 s",
            I18n.tr("♻ Perpetual: its first pool ended without a round; the same pool opens again in 42 s"))
        assertEquals("♻ Perpetuo: detenido (su primer pool terminó sin ronda y no se puede volver a abrir igual (un pool privado, o uno de una versión anterior): la moneda no se movió)",
            I18n.tr("♻ Perpetual: stopped (its first pool ended without a round and can't be opened again the same (a private pool, or one from an older version): the coin did not move)"))
        assertEquals("♻ Coinjoin perpetuo: tras esa ronda sigue volviendo a mezclarse (tope de comisión 2.5 sat/vB).",
            I18n.tr("♻ Perpetual coinjoin: after that round it keeps remixing (fee cap 2.5 sat/vB)."))
        assertEquals("Después se une sola al pool como otro puesto tuyo, tras una espera aleatoria, y la ronda va sola.",
            I18n.tr("Then it joins the pool by itself as another seat of yours, after a random wait, and the round runs on its own."))
        // BTC, never XBT, on screen.
        for (l in I18n.LANGUAGES.map { it.first }.filter { it != I18n.EN }) {
            val o = JSONObject(File(dir, "$l.json").readText())
            o.keys().forEach { k -> assertTrue("$l: XBT in \"$k\"", !k.contains("XBT") && !o.getString(k).contains("XBT")) }
        }
        I18n.lang.value = I18n.ES
    }
}
