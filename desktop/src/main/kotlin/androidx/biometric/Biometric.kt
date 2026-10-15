package androidx.biometric

import androidx.fragment.app.FragmentActivity
import com.kilombino.kilowallet.desktop.PasswordPrompt
import java.util.concurrent.Executor
import javax.crypto.Cipher

/**
 * The fingerprint prompt, on the desktop: the wallet's password. A success hands back the same
 * cipher (its key was unwrapped by the unlocked keystore) only once the password typed is right.
 */
class BiometricPrompt(activity: FragmentActivity, private val executor: Executor, private val callback: AuthenticationCallback) {
    abstract class AuthenticationCallback {
        open fun onAuthenticationError(errorCode: Int, errString: CharSequence) {}
        open fun onAuthenticationSucceeded(result: AuthenticationResult) {}
        open fun onAuthenticationFailed() {}
    }
    class AuthenticationResult(val cryptoObject: CryptoObject?)
    class CryptoObject(val cipher: Cipher?)
    class PromptInfo private constructor(val title: String, val subtitle: String) {
        class Builder {
            private var title = ""; private var subtitle = ""
            fun setTitle(t: CharSequence) = apply { title = t.toString() }
            fun setSubtitle(t: CharSequence?) = apply { subtitle = t?.toString() ?: "" }
            fun setDescription(t: CharSequence?) = this
            fun setAllowedAuthenticators(a: Int) = this
            fun setNegativeButtonText(t: CharSequence) = this
            fun setConfirmationRequired(b: Boolean) = this
            fun build() = PromptInfo(title, subtitle)
        }
    }

    fun authenticate(info: PromptInfo, crypto: CryptoObject) = ask(info) { callback.onAuthenticationSucceeded(AuthenticationResult(crypto)) }
    fun authenticate(info: PromptInfo) = ask(info) { callback.onAuthenticationSucceeded(AuthenticationResult(null)) }
    fun cancelAuthentication() {}

    private fun ask(info: PromptInfo, ok: () -> Unit) {
        PasswordPrompt.ask(info.title, info.subtitle) { passed ->
            executor.execute { if (passed) ok() else callback.onAuthenticationError(ERROR_USER_CANCELED, "Cancelled") }
        }
    }

    companion object {
        const val ERROR_USER_CANCELED = 10; const val ERROR_NEGATIVE_BUTTON = 13; const val ERROR_CANCELED = 5
        const val ERROR_LOCKOUT = 7; const val ERROR_NO_BIOMETRICS = 11; const val ERROR_HW_NOT_PRESENT = 12
    }
}

class BiometricManager private constructor() {
    fun canAuthenticate(authenticators: Int): Int = BIOMETRIC_SUCCESS
    @Deprecated("") fun canAuthenticate(): Int = BIOMETRIC_SUCCESS
    object Authenticators { const val BIOMETRIC_STRONG = 0xF; const val BIOMETRIC_WEAK = 0xFF; const val DEVICE_CREDENTIAL = 0x8000 }
    companion object {
        const val BIOMETRIC_SUCCESS = 0; const val BIOMETRIC_ERROR_NONE_ENROLLED = 11; const val BIOMETRIC_ERROR_NO_HARDWARE = 12
        const val BIOMETRIC_ERROR_HW_UNAVAILABLE = 1; const val BIOMETRIC_STATUS_UNKNOWN = -1; const val BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED = 15
        const val BIOMETRIC_ERROR_UNSUPPORTED = -2
        fun from(ctx: android.content.Context) = BiometricManager()
    }
}
