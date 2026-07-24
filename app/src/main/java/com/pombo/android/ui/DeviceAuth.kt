package com.pombo.android.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Proof-of-ownership gate before revealing or destroying the private key.
 *
 * The web guards both actions behind the keystore password
 * (`authManager.getPrivateKey(password)`), since there the key is encrypted
 * with a password the user chose. This app's key lives in
 * EncryptedSharedPreferences under an Android Keystore master key, already
 * bound to the device lock — no password to ask for.
 *
 * The device credential is the equivalent boundary: it proves the person
 * holding the unlocked phone is the owner. Biometrics accept device
 * PIN/pattern/password as the fallback, so this works with no enrolled
 * biometric.
 *
 * [canAuthenticate] reports when the device has no lock configured at all, so
 * the caller can warn instead of silently letting the key out.
 */
object DeviceAuth {

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** True when the device has a biometric or a screen lock we can prompt for. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Prompts, then calls [onResult] with whether the user authenticated.
     * Errors and cancellations both resolve to `false` — the caller must treat
     * anything other than an explicit success as a refusal.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        /** Body text — where a warning about what is about to happen belongs. */
        description: String? = null,
        onResult: (Boolean) -> Unit
    ) {
        if (!canAuthenticate(activity)) { onResult(false); return }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    onResult(false)
                }

                // Deliberately not failing the whole flow on a single bad
                // fingerprint read — the prompt lets the user retry, and only
                // an error or cancellation ends it.
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .apply { description?.let { setDescription(it) } }
                .setAllowedAuthenticators(ALLOWED)
                .build()
        )
    }
}
