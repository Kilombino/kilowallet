package com.kilombino.pyblockwatch.coinjoin

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RSA blind signatures, RFC 9474, variant RSABSSA-SHA384-PSS-Randomized (Kilojoin v3).
 *
 * The pool's creator holds a key pair made for the round. Each member blinds its output with a
 * random factor and has the creator sign it without seeing it; later the member unblinds the
 * signature and hands in the output with it from a fresh identity. The signature proves a seat
 * paid for it, but not which one. Checked against the RFC's own test vector (BlindRsaTest).
 */
object BlindRsa {
    private const val H_LEN = 48          // SHA-384
    const val SALT_LEN = 48
    const val PREFIX_LEN = 32
    /** Round keys live minutes; 2048 bits keeps key generation quick on a phone. */
    const val KEY_BITS = 2048
    private val E = BigInteger.valueOf(65537)

    class PublicKey(val n: BigInteger, val e: BigInteger) {
        val modBits get() = n.bitLength()
        val modLen get() = (n.bitLength() + 7) / 8
        /** "n:e" in hex, to publish in the pool. */
        fun encode(): String = n.toString(16) + ":" + e.toString(16)
        companion object {
            fun decode(s: String): PublicKey {
                val (n, e) = s.split(':').also { require(it.size == 2) { "bad blind-signing key" } }
                val k = PublicKey(BigInteger(n, 16), BigInteger(e, 16))
                require(k.modBits in 2048..4096 && k.e == E) { "unsupported blind-signing key" }
                return k
            }
        }
    }

    class KeyPair(val public: PublicKey, val d: BigInteger) {
        fun encodePrivate(): String = public.encode() + ":" + d.toString(16)
        companion object {
            fun decode(s: String): KeyPair {
                val p = s.split(':').also { require(it.size == 3) { "bad blind-signing key" } }
                return KeyPair(PublicKey(BigInteger(p[0], 16), BigInteger(p[1], 16)), BigInteger(p[2], 16))
            }
        }
    }

    fun generate(bits: Int = KEY_BITS, rnd: SecureRandom = SecureRandom()): KeyPair {
        while (true) {
            val p = BigInteger.probablePrime(bits / 2, rnd)
            val q = BigInteger.probablePrime(bits - bits / 2, rnd)
            if (p == q) continue
            val n = p.multiply(q)
            if (n.bitLength() != bits) continue
            val phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE))
            if (phi.gcd(E) != BigInteger.ONE) continue
            return KeyPair(PublicKey(n, E), E.modInverse(phi))
        }
    }

    /** The randomized preparation: a 32-byte random prefix before the message. */
    fun prepare(msg: ByteArray, rnd: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(PREFIX_LEN).also { rnd.nextBytes(it) } + msg

    class Blinded(val blindedMsg: ByteArray, val inv: BigInteger)

    /** Blind [prepared] for [pk]; [salt] and [r] only for test vectors. */
    fun blind(pk: PublicKey, prepared: ByteArray, rnd: SecureRandom = SecureRandom(),
              salt: ByteArray? = null, r: BigInteger? = null): Blinded {
        val em = pssEncode(prepared, pk.modBits - 1, salt ?: ByteArray(SALT_LEN).also { rnd.nextBytes(it) })
        val m = BigInteger(1, em)
        require(m.gcd(pk.n) == BigInteger.ONE) { "invalid input" }
        var rr = r
        while (rr == null) {
            val c = BigInteger(pk.modBits, rnd)
            if (c.signum() > 0 && c < pk.n && c.gcd(pk.n) == BigInteger.ONE) rr = c
        }
        val inv = rr.modInverse(pk.n)
        val z = m.multiply(rr.modPow(pk.e, pk.n)).mod(pk.n)
        return Blinded(i2osp(z, pk.modLen), inv)
    }

    /** The signer's side: signs what it cannot read, and checks its own result (fault attacks). */
    fun blindSign(kp: KeyPair, blindedMsg: ByteArray): ByteArray {
        val pk = kp.public
        require(blindedMsg.size == pk.modLen) { "unexpected input size" }
        val m = BigInteger(1, blindedMsg)
        require(m < pk.n) { "invalid message length" }
        val s = m.modPow(kp.d, pk.n)
        check(s.modPow(pk.e, pk.n) == m) { "signing failure" }
        return i2osp(s, pk.modLen)
    }

    /** Unblinds [blindSig] into a signature on [prepared], and checks it. */
    fun finalize(pk: PublicKey, prepared: ByteArray, blindSig: ByteArray, inv: BigInteger): ByteArray {
        require(blindSig.size == pk.modLen) { "unexpected input size" }
        val z = BigInteger(1, blindSig)
        val sig = i2osp(z.multiply(inv).mod(pk.n), pk.modLen)
        require(verify(pk, prepared, sig)) { "invalid signature" }
        return sig
    }

    /** RSASSA-PSS-VERIFY with SHA-384, MGF1-SHA-384 and a 48-byte salt. */
    fun verify(pk: PublicKey, prepared: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != pk.modLen) return false
        val s = BigInteger(1, sig)
        if (s >= pk.n) return false
        val emBits = pk.modBits - 1
        val emLen = (emBits + 7) / 8
        val m = s.modPow(pk.e, pk.n)
        if (m.bitLength() > emLen * 8) return false
        return pssVerify(prepared, i2osp(m, emLen), emBits)
    }

    // ---------------------------------------------------------------- EMSA-PSS (RFC 8017 9.1)

    private fun sha384(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-384").apply { parts.forEach { update(it) } }.digest()

    private fun mgf1(seed: ByteArray, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var counter = 0
        while (out.size() < len) {
            out.write(sha384(seed, byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())))
            counter++
        }
        return out.toByteArray().copyOf(len)
    }

    internal fun pssEncode(msg: ByteArray, emBits: Int, salt: ByteArray): ByteArray {
        val emLen = (emBits + 7) / 8
        require(emLen >= H_LEN + salt.size + 2) { "encoding error" }
        val h = sha384(ByteArray(8), sha384(msg), salt)
        val db = ByteArray(emLen - H_LEN - 1)
        db[db.size - salt.size - 1] = 1
        salt.copyInto(db, db.size - salt.size)
        val mask = mgf1(h, db.size)
        for (i in db.indices) db[i] = (db[i].toInt() xor mask[i].toInt()).toByte()
        db[0] = (db[0].toInt() and (0xFF ushr (8 * emLen - emBits))).toByte()
        return db + h + byteArrayOf(0xBC.toByte())
    }

    private fun pssVerify(msg: ByteArray, em: ByteArray, emBits: Int): Boolean {
        val emLen = em.size
        if (emLen < H_LEN + SALT_LEN + 2 || em.last() != 0xBC.toByte()) return false
        val masked = em.copyOfRange(0, emLen - H_LEN - 1)
        val h = em.copyOfRange(emLen - H_LEN - 1, emLen - 1)
        val topBits = 8 * emLen - emBits
        if (masked[0].toInt() and (0xFF shl (8 - topBits)) and 0xFF != 0) return false
        val mask = mgf1(h, masked.size)
        val db = ByteArray(masked.size) { (masked[it].toInt() xor mask[it].toInt()).toByte() }
        db[0] = (db[0].toInt() and (0xFF ushr topBits)).toByte()
        val psLen = emLen - H_LEN - SALT_LEN - 2
        for (i in 0 until psLen) if (db[i] != 0.toByte()) return false
        if (db[psLen] != 1.toByte()) return false
        val salt = db.copyOfRange(db.size - SALT_LEN, db.size)
        return MessageDigest.isEqual(h, sha384(ByteArray(8), sha384(msg), salt))
    }

    private fun i2osp(x: BigInteger, len: Int): ByteArray {
        val b = x.toByteArray()
        return when {
            b.size == len -> b
            b.size == len + 1 && b[0] == 0.toByte() -> b.copyOfRange(1, b.size)
            b.size < len -> ByteArray(len - b.size) + b
            else -> throw IllegalArgumentException("integer too large")
        }
    }
}
