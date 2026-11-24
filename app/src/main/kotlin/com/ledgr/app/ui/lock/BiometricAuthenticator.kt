package com.ledgr.app.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Whether this phone can show the secure unlock prompt right now. */
enum class LockAvailability {
    Available,

    /** No PIN/pattern/password/biometric is set up on the device. */
    NoCredential,

    /** Hardware missing, unavailable or unsupported. */
    Unavailable,
}

/** Why an unlock attempt did not succeed. [systemCancelled] = the system dismissed the prompt (e.g. app went to background). */
data class UnlockFailure(val message: String, val systemCancelled: Boolean)

/**
 * Thin wrapper over `BiometricPrompt`: biometric first, device credential (PIN/pattern/password) as fallback.
 * Must be created in `Activity.onCreate`, with callbacks that outlive the activity (they point at a ViewModel),
 * so a prompt that survives a configuration change still reports its result.
 *
 * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is used instead of STRONG because the STRONG+CREDENTIAL combination
 * is unsupported on Android 10 (our minSdk).
 */
class BiometricAuthenticator(
    private val activity: FragmentActivity,
    title: String,
    subtitle: String,
    private val onSuccess: () -> Unit,
    private val onFailure: (UnlockFailure) -> Unit,
) {
    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure(
                    UnlockFailure(
                        message = errString.toString(),
                        systemCancelled = errorCode == BiometricPrompt.ERROR_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_TIMEOUT,
                    ),
                )
            }
            // onAuthenticationFailed (a wrong finger) keeps the system prompt open; nothing to do.
        },
    )

    private val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(AUTHENTICATORS)
        .setConfirmationRequired(false)
        .build()

    fun availability(): LockAvailability =
        when (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> LockAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LockAvailability.NoCredential
            else -> LockAvailability.Unavailable
        }

    fun authenticate() {
        prompt.authenticate(promptInfo)
    }

    private companion object {
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
