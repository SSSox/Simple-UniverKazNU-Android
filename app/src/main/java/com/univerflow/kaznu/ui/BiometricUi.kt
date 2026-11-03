package com.univerflow.kaznu.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/** The system biometric prompt tied to a Keystore cipher: the key is usable only right after a real fingerprint/face match. */
object BiometricUi {
    /** True when strong biometrics (fingerprint, 3D face) are present and enrolled on this phone. */
    fun available(context: Context) =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    enum class Failure { CANCELLED, UNAVAILABLE }

    fun prompt(activity: FragmentActivity, cipher: Cipher, title: String, subtitle: String, negative: String,
               onSuccess: (Cipher) -> Unit, onFailure: (Failure) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val unlocked = result.cryptoObject?.cipher
                if (unlocked != null) onSuccess(unlocked) else onFailure(Failure.UNAVAILABLE)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val byUser = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onFailure(if (byUser) Failure.CANCELLED else Failure.UNAVAILABLE)
            }
            // A wrong finger is retried by the system prompt itself; nothing is counted here.
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(negative).setConfirmationRequired(false).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
