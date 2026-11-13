package com.example.security

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NONE_ENROLLED
}

/**
 * Helper for checking device biometric capabilities and displaying the Android system
 * BiometricPrompt safely on a FragmentActivity.
 */
object BiometricHelper {

    private const val ALLOWED_BIOMETRICS = BIOMETRIC_STRONG or BIOMETRIC_WEAK

    fun checkAvailability(context: Context): BiometricAvailability {
        return try {
            val manager = BiometricManager.from(context.applicationContext)
            when (manager.canAuthenticate(ALLOWED_BIOMETRICS)) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricAvailability.HARDWARE_UNAVAILABLE
                else -> BiometricAvailability.HARDWARE_UNAVAILABLE
            }
        } catch (_: Exception) {
            BiometricAvailability.HARDWARE_UNAVAILABLE
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onFallbackToPin: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            AppLockManager.markBiometricPromptActive(true)
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    AppLockManager.markBiometricPromptActive(false)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    AppLockManager.markBiometricPromptActive(false)
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> {
                            onFallbackToPin()
                        }
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            onError("تم إيقاف البصمة مؤقتاً لكثرة المحاولات، يرجى إدخال رمز PIN")
                        }
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> {
                            onError("لا توجد بصمة مسجلة على هذا الجهاز")
                        }
                        else -> {
                            onError(errString.toString().ifBlank { "تعذر التحقق من البصمة" })
                        }
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Android BiometricPrompt handles retry UI inline
                }
            }

            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButtonText)
                .setAllowedAuthenticators(ALLOWED_BIOMETRICS)
                .setConfirmationRequired(false)
                .build()

            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            AppLockManager.markBiometricPromptActive(false)
            onError("تعذر تشغيل مستشعر البصمة، يرجى استخدام رمز PIN")
        }
    }

    fun openBiometricEnrollSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, ALLOWED_BIOMETRICS)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }
}
