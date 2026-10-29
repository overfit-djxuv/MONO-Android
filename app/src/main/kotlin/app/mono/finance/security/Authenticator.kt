package app.mono.finance.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.compositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Asks the phone's owner to prove it is them: fingerprint or face, falling back to the device PIN, pattern or password. */
object Authenticator {
    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** null if the lock can be used, otherwise why not. */
    fun unavailableReason(context: Context): String? = when (BiometricManager.from(context).canAuthenticate(ALLOWED)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set a screen lock (PIN, pattern or fingerprint) in your phone’s settings first, then turn this on."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "This phone can’t verify you right now."
        else -> "This phone can’t verify you right now."
    }

    fun prompt(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val bp = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        })
        bp.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(ALLOWED).build())
    }
}

/** Lets a screen ask for authentication without knowing about the activity. */
val LocalAuthenticate = compositionLocalOf<(String, (Boolean) -> Unit) -> Unit> { { _, done -> done(false) } }
