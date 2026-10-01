package com.kilombino.pyblockwatch.ui

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * The unlock gate for spending. It runs the system BiometricPrompt with the Keystore
 * [Cipher] as its CryptoObject, so a success returns a cipher that is genuinely authorised —
 * the auth is bound to the key operation, not merely a screen the app could skip.
 */
object Biometric {

    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            // Before API 30 a device-credential CryptoObject is not supported; the Keystore
            // key requires a strong biometric per use, so that is what we ask for.
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onError: (String) -> Unit,
    ) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onError(errString.toString())

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c != null) onSuccess(c) else onError("No authenticated cipher returned.")
                }
            },
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators())
        // A negative button is mandatory unless device credential is an allowed authenticator.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) builder.setNegativeButtonText("Cancel")
        runCatching { prompt.authenticate(builder.build(), BiometricPrompt.CryptoObject(cipher)) }
            .onFailure { onError(it.message ?: "Could not start the unlock prompt.") }
    }

    /**
     * Asks the phone's owner to prove it is them (fingerprint, face or screen lock) before
     * showing something sensitive. No Keystore key is involved. On a phone with no screen
     * lock at all there is nothing to ask, and [onSuccess] runs straight away.
     */
    fun confirm(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        if (BiometricManager.from(activity).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            onSuccess()
            return
        }
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onError(errString.toString())

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                    onSuccess()
            },
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) builder.setNegativeButtonText("Cancel")
        runCatching { prompt.authenticate(builder.build()) }
            .onFailure { onError(it.message ?: "Could not start the unlock prompt.") }
    }
}
