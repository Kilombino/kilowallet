package com.kilombino.pyblockwatch.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The diagnostic log: what it keeps (rotation, caps, age) and what never reaches its files. */
class DiagLogTest {
    private lateinit var dir: File
    private var clock = 1_795_000_000_000L

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("diag").toFile()
        DiagLog.now = { clock }
        DiagLog.initDir(dir)
        DiagLog.clear()
    }

    @After fun tearDown() { dir.deleteRecursively(); DiagLog.now = { System.currentTimeMillis() } }

    private fun files(): List<File> { DiagLog.flush(); return dir.listFiles()!!.filter { it.name.startsWith("diag-") } }
    private fun onDisk(): String { DiagLog.flush(); return files().joinToString("\n") { it.readText() } }

    // Real-looking secrets, as a careless caller might pass them.
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val xprv = "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
    private val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    private val wif = "5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ"
    private val wifC = "KwdMAjGmerYanjeui5SHS7JkmpZvVipYvB2LJGU1ZxJwYvP98617"
    private val invoice = "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpuaztrnwngzn3kdzw5hydlzf03qdgm2hdq27cqv3agm2awhz5se903vruatfhq77w3ls4evs3ch9zw97j25emudupq63nyw24cg27h2rspfj9srp"
    private val offer = "lno1pg257enxv4ezqcneype82um50ynhxgrwdajx283qfwdpl28qqmc78ymlvhmxcsywdk5wrjnj36jryg488qwlrnzyjczlqs85ck65ycmkdk92smwt9zuewdzfe7v4aavvaz5kgv9mkk63v3s0ge0f099kssh3yc95qztx504hu92hnx8ctzhtt08pgk0texz0509tk"
    private val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    private val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val legacy = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
    private val txid = "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16"
    private val macaroonHex = "0201036c6e6402f801030a10" + "ab".repeat(100)
    private val blob = "AgEDbG5kAvgBAwoQ" + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo".repeat(5)

    @Test fun secretsNeverReachTheFile() {
        val secrets = listOf(mnemonic, xprv, zpub, wif, wifC, invoice, offer, nsec, macaroonHex, blob)
        DiagLog.i("t", "words $mnemonic")
        DiagLog.i("t", "1. abandon 2. abandon 3. abandon 4. abandon 5. abandon 6. abandon 7. abandon 8. abandon 9. abandon 10. abandon 11. abandon 12. about")
        DiagLog.i("t", "keys $xprv $zpub $wif $wifC")
        DiagLog.w("t", "paying $invoice or $offer")
        DiagLog.e("t", "nostr $nsec", IllegalStateException("macaroon $macaroonHex"))
        DiagLog.i("t", "blob $blob")
        DiagLog.i("t", "passphrase=correct horse battery staple and password: hunter2 seed=\"x y z\"")
        DiagLog.i("t", "preimage: $txid")
        val disk = onDisk()
        for (s in secrets) assertFalse("leaked: ${s.take(12)}", disk.contains(s))
        for (s in listOf("abandon abandon", "hunter2", "horse", "x y z", "2. abandon")) assertFalse("leaked: $s", disk.contains(s))
        assertTrue(disk.contains("<words>")); assertTrue(disk.contains("<xprv>")); assertTrue(disk.contains("<xpub>"))
        assertTrue(disk.contains("<wif>")); assertTrue(disk.contains("<invoice>")); assertTrue(disk.contains("<bolt12>"))
        assertTrue(disk.contains("<nsec>")); assertTrue(disk.contains("<hidden>"))
    }

    @Test fun addressesAndTxidsAreCut() {
        DiagLog.i("t", "sent to $address and $legacy in $txid:1")
        val disk = onDisk()
        assertFalse(disk.contains(address)); assertFalse(disk.contains(legacy)); assertFalse(disk.contains(txid))
        assertTrue(disk.contains("bc1qar…dq")); assertTrue(disk.contains("1BvBMS…N2")); assertTrue(disk.contains("f4184f…16:1"))
    }

    @Test fun ordinaryTextStaysAsItIs() {
        val line = "pool 3f2a (4 × 1 000 000 sats, v6) seat ab12cd: SIGNING · node height 983 400 · Lnd.kt:479 · java.net.UnknownHostException"
        assertEquals(line, Scrub.clean(line))
        // Five wordlist words are a sentence, not a mnemonic.
        val five = "abandon ability able about above"
        assertEquals(five, Scrub.clean(five))
    }

    // Everything the independent review found leaking from 1.6.5-beta1.
    @Test fun reviewLeaksAreClosed() {
        val ark = "ark1qq4hfssprtcgnyqkp5m8x6yzlm8fq4y2c8w6s0nqegq2p7rz5n4d0xmvh3jkyl0vxzsw9a4j0ru6ttqzxtxh"
        val sp = "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"
        val cases = mapOf(
            "ark address" to "sent to $ark",
            "sp address" to "pay $sp",
            "8 words" to "restore: abandon ability able about above absent absorb abstract",
            "gapped words" to "abandon ability able wrod about above absent absorb abstract absurd abuse access",
            "json words" to "[\"abandon\",\"ability\",\"able\",\"about\",\"above\",\"absent\"]",
            "json passphrase" to "{\"mnemonic\":\"x\",\"passphrase\":\"hunter2\"}",
            "rpcpassword" to "bitcoind rpcpassword=hunter2 rpc_pass=hunter3",
            "basic auth" to "header Authorization: Basic Ym9iOmh1bnRlcjI= sent",
            "bearer" to "using Bearer eyJhbGciOiJIUzI1NiJ9.e30.ZRrHA1JJJW8opsbCGfG_HACGpVUMN_a9IV7pAx_Zmeo now",
            "0x key" to "key 0x" + "1f".repeat(32),
            "seed_hex" to "seed_" + "ab".repeat(32),
            "32-hex entropy" to "entropy " + "7f".repeat(16),
            "glued 32 hex" to "id=" + "c0ffee00".repeat(4) + "!",
            "descriptor origin" to "wpkh([d34db33f/84h/0h/0h]xpub6CUGRUonZSQ4TWtTMmzXdrXDtypWKiKrhko4egpiMZbpiaQL2jkwSB1icqYh2cfDfVxdx4df189oLKnC5fSwqPfgyP3hooxujYzAu3fDVmz/0/*)",
            "user:pass url" to "rpc http://bob:hunter2@192.168.1.5:8332/ failed",
            "ipv4" to "Could not connect to 203.0.113.5:50002",
            "ipv6" to "peer [2001:db8::8a2e:370:7334]:9735 down",
            "host" to "Could not resolve casa.duckdns.org",
            "host:port" to "server mynode.example.net:50002 timed out",
            "onion" to "via abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvw.onion:50002",
            "lightning address" to "paying alice@getalby.com",
        )
        val secrets = listOf(ark, sp, "abandon ability able", "able about above", "hunter2", "hunter3", "Ym9iOmh1bnRlcjI",
            "eyJhbGciOiJIUzI1NiJ9", "1f".repeat(32), "ab".repeat(32), "7f".repeat(16), "c0ffee00".repeat(4),
            "d34db33f", "84h/0h/0h", "bob", "192.168.1.5", "203.0.113.5", "2001:db8", "casa.duckdns", "mynode.example",
            "abcdefghijklmnop", "alice@", "getalby")
        for (msg in cases.values) DiagLog.i("t", msg)
        val disk = onDisk()
        for (s in secrets) assertFalse("leaked: $s\n$disk", disk.contains(s))
        for (p in listOf("<address>", "<words>", "[<origin>]", "<onion>", "<lnaddress>", "<host>", "<ip>", "<url>"))
            assertTrue("missing $p\n$disk", disk.contains(p))
    }

    @Test fun byteCapCountsMultiByteCharacters() {
        // "⏎" and "…" are three bytes each in UTF-8: the cap is on bytes, not characters.
        val chunk = "⏎…".repeat(400)
        repeat(1_000) { DiagLog.i("t", "$it $chunk") }
        val f = files()
        assertTrue(f.size <= 2)
        assertTrue(f.all { it.length() <= DiagLog.MAX_FILE })
    }

    @Test fun oneLinePerCallWithTimeLevelAndTag() {
        DiagLog.w("node", "first\nsecond")
        val lines = onDisk().trimEnd().lines()
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].matches(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ W node: first ⏎ second")))
    }

    @Test fun rotatesAtTheCapAndNeverPassesTwoFiles() {
        val chunk = "x ".repeat(750)
        repeat(1_400) { DiagLog.i("t", "$it $chunk") }
        val f = files()
        assertTrue(f.size <= 2)
        assertTrue(f.all { it.length() <= DiagLog.MAX_FILE })
        assertTrue(f.sumOf { it.length() } <= 2 * DiagLog.MAX_FILE)
        // The newest line is kept, the oldest gone.
        val t = DiagLog.text()
        assertTrue(t.contains("1399 x")); assertFalse(t.contains(" 0 x"))
    }

    @Test fun oldHistoryGoes() {
        DiagLog.i("t", "old one")
        clock += DiagLog.MAX_AGE_MS / 2 + 1_000
        DiagLog.i("t", "middle one")            // starts a new file: the old moves aside
        assertTrue(DiagLog.text().contains("old one"))
        clock += DiagLog.MAX_AGE_MS / 2 + 1_000
        DiagLog.i("t", "new one")               // the oldest file goes
        val t = DiagLog.text()
        assertFalse(t.contains("old one")); assertTrue(t.contains("middle one")); assertTrue(t.contains("new one"))
    }

    @Test fun tailCutsAtALine() {
        repeat(50) { DiagLog.i("t", "line $it") }
        val t = DiagLog.tail(200)
        assertTrue(t.length <= 200)
        assertTrue(t.startsWith("20"))
        assertTrue(t.trimEnd().endsWith("line 49"))
    }

    @Test fun clearEmptiesIt() {
        DiagLog.i("t", "something")
        DiagLog.clear()
        assertEquals("", DiagLog.text())
    }

    @Test fun headerHasNoIdentifiers() {
        val h = DiagLog.header("1.6.5-beta1 (248)", "15", 35, "Google Pixel 8", "wallet: hot · BTC: own node by RPC · Tor: on · watching $zpub")
        assertTrue(h.startsWith("Kilowallet 1.6.5-beta1 (248) · Android 15 (SDK 35) · Google Pixel 8 · wallet: hot"))
        assertFalse(h.contains(zpub))
    }
}
