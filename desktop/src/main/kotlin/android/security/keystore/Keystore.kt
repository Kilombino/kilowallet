package android.security.keystore

import java.security.spec.AlgorithmParameterSpec

/** The key description the shared code hands the "AndroidKeyStore" generator (see DesktopKeyStore). */
class KeyGenParameterSpec private constructor(val keystoreAlias: String, val purposes: Int, val userAuthenticationRequired: Boolean) :
    AlgorithmParameterSpec {
    class Builder(private val alias: String, private val purposes: Int) {
        private var auth = false
        fun setBlockModes(vararg m: String) = this
        fun setEncryptionPaddings(vararg p: String) = this
        fun setKeySize(n: Int) = this
        fun setUserAuthenticationRequired(b: Boolean) = apply { auth = b }
        fun setInvalidatedByBiometricEnrollment(b: Boolean) = this
        fun setUserAuthenticationParameters(timeout: Int, type: Int) = this
        @Deprecated("") fun setUserAuthenticationValidityDurationSeconds(s: Int) = this
        fun setIsStrongBoxBacked(b: Boolean) = this
        fun setUnlockedDeviceRequired(b: Boolean) = this
        fun setRandomizedEncryptionRequired(b: Boolean) = this
        fun build() = KeyGenParameterSpec(alias, purposes, auth)
    }
}

object KeyProperties {
    const val KEY_ALGORITHM_AES = "AES"
    const val PURPOSE_ENCRYPT = 1; const val PURPOSE_DECRYPT = 2
    const val BLOCK_MODE_GCM = "GCM"; const val ENCRYPTION_PADDING_NONE = "NoPadding"
    const val AUTH_BIOMETRIC_STRONG = 1; const val AUTH_DEVICE_CREDENTIAL = 2
}

class KeyPermanentlyInvalidatedException(msg: String? = null) : java.security.InvalidKeyException(msg)
class UserNotAuthenticatedException(msg: String? = null) : java.security.InvalidKeyException(msg)
