package com.pocketrocket.assistant

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * The fingerprint popup (decision 57), Android's own prompt with the phone's PIN as fallback. The platform's, since androidx's
 * needs a FragmentActivity and only Android 16 matters here (decision 13). No key or time window sits behind it, so
 * nothing carries over: each call asks again, and a passive unlock like a face still needs a confirm tap.
 */
object Fingerprint {
    private const val MAX_DESCRIPTION = 400
    const val NOT_POSSIBLE = "This phone has no screen lock or fingerprint to check, so this can't be approved."

    /** Calls [onDone] once, with null for confirmed or the reason it wasn't. Returns how to take it down. */
    fun ask(activity: Activity, approval: Approval, onDone: (refused: String?) -> Unit): () -> Unit {
        if (!available(activity)) {
            onDone(NOT_POSSIBLE)
            return {}
        }
        val cancel = CancellationSignal()
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(approval.headline)
            .setDescription(approval.detail.take(MAX_DESCRIPTION))
            .setConfirmationRequired(true)
        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> builder.setAllowedAuthenticators(AUTHENTICATORS)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(cancel, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(null)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone("Not confirmed.")
        })
        return { cancel.cancel() }
    }

    /** Whether the phone has a screen lock or fingerprint to ask. Without one, a fingerprint-protected action can't be approved at all. */
    fun available(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> context.getSystemService(BiometricManager::class.java).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure
        else -> false
    }

    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
}
