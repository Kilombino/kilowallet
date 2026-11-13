package com.kilombino.pyblockwatch.joinblaket

import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.joinblaket.Vectors.big
import com.kilombino.pyblockwatch.joinblaket.Vectors.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Vector sections `nick` and `handshake`: nick derivation, privmsg signatures, JMP-0005. */
class NickTest {
    private val v = Vectors.section("nick")
    private val id = NickIdentity(big(v.getString("private_key")))

    @Test fun nickDerivation() {
        assertEquals(v.getString("pubkey"), id.pubkeyHex)
        assertEquals(v.getString("pubkey_hex_ascii_sha256"), Hashes.sha256(id.pubkeyHex.toByteArray()).toHex())
        assertEquals(v.getString("nick"), id.nick)
        assertEquals(v.getString("nick_from_pubkey"), Nick.fromPubkey(v.getString("pubkey")))
        assertTrue(Nick.isValid(id.nick))
        // The maker nick of the vectors comes from the generator's fixed key.
        assertEquals(v.getString("maker_nick"), NickIdentity(Vectors.key("maker1-nick")).nick)
    }

    @Test fun privmsgSignature() {
        val data = v.getString("signed_data")
        assertEquals(v.getString("hostid"), Wire.ONION_HOSTID)
        assertEquals(v.getString("message_hash"), JmSig.messageHash(data + Wire.ONION_HOSTID).toHex())
        val signed = id.signData(data)
        assertEquals(v.getString("signed_suffix"), signed)
        assertEquals(v.getString("signature_b64"), signed.substringAfterLast(' '))
        val line = "${id.nick}!${v.getString("maker_nick")}!fill $signed"
        assertEquals(v.getString("privmsg_line"), line)
    }

    @Test fun privmsgVerification() {
        val want = v.getJSONObject("verify_signed_privmsg")
        val rest = v.getString("privmsg_line").split("!", limit = 3)[2]
        val got = Nick.verifyPrivmsg(id.nick, rest)
        assertEquals(want.getBoolean("ok"), got.ok)
        assertEquals(want.getString("command"), got.command)
        assertEquals(want.getString("data"), got.data)
        // Wrong claimed sender, tampered data, high-S twin: all refused.
        assertFalse(Nick.verifyPrivmsg(v.getString("maker_nick"), rest).ok)
        assertFalse(Nick.verifyPrivmsg(id.nick, rest.replace(" 30000000 ", " 30000001 ")).ok)
        val der = Base64.getDecoder().decode(v.getString("signature_b64"))
        val sig = JmSig.parseStrictDer(der)!!
        val highS = com.kilombino.pyblockwatch.crypto.Ecdsa.der(sig.copy(s = com.kilombino.pyblockwatch.crypto.Secp256k1.N - sig.s))
        assertNull(JmSig.parseStrictDer(highS))
        assertFalse(Nick.verifyPrivmsg(id.nick, rest.replace(v.getString("signature_b64"), Base64.getEncoder().encodeToString(highS))).ok)
    }

    @Test fun handshakeAndNickAuth() {
        val hs = Vectors.section("handshake")
        assertEquals(hs.getString("nick"), id.nick)
        val line = NickAuth.handshakeLine(id.nick, "xbt", nickAuth = true)
        assertEquals(hs.getString("handshake_line"), line)
        assertEquals(hs.getString("handshake_envelope"), Wire.envelope(Wire.HANDSHAKE, line))
        assertEquals(hs.getString("handshake_line_sha256"), NickAuth.handshakeSha256(line))

        val dn = Wire.parse(hs.getString("directory_reply_dn_handshake"))
        assertNull(NickAuth.checkDirectoryReply(dn, "xbt", requireNickAuth = true))
        // An upstream (SHA-256 Bitcoin) directory answers "mainnet": refused.
        assertEquals("directory network mismatch", NickAuth.checkDirectoryReply(Wire.Frame(dn.type, dn.line.replace("\"xbt\"", "\"mainnet\"")), "xbt"))
        assertTrue(NickAuth.checkDirectoryReply(Wire.Frame(dn.type, dn.line.replace("\"nick_auth\": true, ", "")), "xbt")!!.contains("nick authentication"))

        val chFrame = Wire.parse(hs.getString("directory_reply_challenge"))
        assertEquals(Wire.NICK_AUTH_CHALLENGE, chFrame.type)
        val ch = NickAuth.parseChallenge(chFrame.line)
        assertEquals(hs.getString("challenge"), ch.challenge)
        assertEquals(hs.getString("directory_id"), ch.directoryId)

        val msg = NickAuth.signedMessage(ch.challenge, ch.directoryId, NickAuth.handshakeSha256(line), id.nick, id.pubkeyHex)
        assertEquals(hs.getString("nick_auth_signed_message_hex"), msg.toHex())
        assertEquals(hs.getString("nick_auth_message_hash"), JmSig.messageHash(msg).toHex())

        val proof = NickAuth.proofLine(id, ch, line)
        val want = hs.getJSONObject("nick_auth_proof")
        assertEquals("{\"pubkey\":\"${want.getString("pubkey")}\",\"signature\":\"${want.getString("signature")}\"}", proof)
        assertEquals(hs.getString("nick_auth_proof_envelope"), Wire.envelope(Wire.NICK_AUTH_PROOF, proof))
        assertTrue(JmSig.verifyMessage(msg, want.getString("signature"), hex(want.getString("pubkey"))))

        val result = Wire.parse(hs.getString("directory_reply_result"))
        assertEquals(Wire.NICK_AUTH_RESULT, result.type)
        assertEquals(hs.getBoolean("authenticated"), NickAuth.resultOk(result.line))
        assertFalse(NickAuth.resultOk("{\"code\":\"invalid\",\"verified\":false}"))

        assertEquals(hs.getString("orderbook_request_envelope"), Wire.envelope(Wire.PUBMSG, "${id.nick}!PUBLIC!orderbook"))
    }

    @Test fun jsonQuoteMatchesPython() {
        // json.dumps escapes quotes, backslashes, control and non-ASCII characters (incl. DEL).
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\\u007f\\u00e9\\ud83d\\ude00\"", Wire.quote("a\"b\\c\n\u0001\u007fé😀"))
    }
}
