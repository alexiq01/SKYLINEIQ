package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.example.data.storage.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

data class AppLockState(
    val isEnabled: Boolean = false,
    val hasPin: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val lockTimeoutSeconds: Long = 0L,
    val isLocked: Boolean = false,
    val failedAttempts: Int = 0,
    val lockoutUntilMillis: Long = 0L
)

sealed interface PinVerifyResult {
    data object Success : PinVerifyResult
    data class WrongPin(val remainingAttempts: Int) : PinVerifyResult
    data class LockedOut(val remainingSeconds: Int) : PinVerifyResult
}

/**
 * Manages App Lock (Fingerprint / Biometric + 4-digit Salted SHA-256 PIN)
 * to protect subscriber account and service data on app launch and resume.
 */
object AppLockManager {

    const val PIN_LENGTH = 4
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MILLIS = 30_000L

    private const val PREFS_NAME = "skyline_app_lock_secure"
    private const val FALLBACK_PREFS_NAME = "skyline_app_lock_fallback"

    private const val KEY_LOCK_ENABLED = "lock_enabled"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_TIMEOUT_SECONDS = "lock_timeout_seconds"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until_millis"

    private val SUPPORTED_TIMEOUTS = listOf(0L, 30L, 60L)

    private val _lockStateFlow = MutableStateFlow(AppLockState())
    val lockStateFlow: StateFlow<AppLockState> = _lockStateFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var lastBackgroundTimestamp: Long = 0L

    @Volatile
    private var biometricPromptActive: Boolean = false

    fun init(context: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val p = getPrefs(context)
                    val loaded = readStateFromPrefs(p, currentlyLocked = false)
                    _lockStateFlow.value = loaded
                    initialized = true
                }
            }
        }
    }

    fun onAppLaunched(context: Context) {
        init(context)
        val appContext = context.applicationContext
        val current = _lockStateFlow.value
        val hasValidSession = SessionManager(appContext).hasValidSession()
        val canUnlock = current.hasPin || (current.isBiometricEnabled &&
            BiometricHelper.checkAvailability(appContext) == BiometricAvailability.AVAILABLE)

        if (current.isEnabled && canUnlock && hasValidSession) {
            _lockStateFlow.value = current.copy(isLocked = true)
        }
    }

    fun markBiometricPromptActive(active: Boolean) {
        biometricPromptActive = active
    }

    fun onAppMovedToBackground() {
        if (!biometricPromptActive) {
            lastBackgroundTimestamp = System.currentTimeMillis()
        }
    }

    fun onAppReturnedToForeground(context: Context) {
        init(context)
        if (biometricPromptActive) return
        val appContext = context.applicationContext
        val current = _lockStateFlow.value
        if (!current.isEnabled) return
        if (!SessionManager(appContext).hasValidSession()) return

        val canUnlock = current.hasPin || (current.isBiometricEnabled &&
            BiometricHelper.checkAvailability(appContext) == BiometricAvailability.AVAILABLE)
        if (!canUnlock) return

        val bgTime = lastBackgroundTimestamp
        if (bgTime <= 0L) return

        val elapsedMillis = System.currentTimeMillis() - bgTime
        val requiredMillis = current.lockTimeoutSeconds * 1000L
        if (elapsedMillis >= requiredMillis) {
            _lockStateFlow.value = current.copy(isLocked = true)
        }
        lastBackgroundTimestamp = 0L
    }

    fun lockNow(context: Context) {
        init(context)
        val current = _lockStateFlow.value
        if (current.isEnabled && (current.hasPin || current.isBiometricEnabled)) {
            _lockStateFlow.value = current.copy(isLocked = true)
        }
    }

    fun unlockApp(context: Context) {
        init(context)
        val p = getPrefs(context)
        p.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        lastBackgroundTimestamp = 0L
        _lockStateFlow.value = _lockStateFlow.value.copy(
            isLocked = false,
            failedAttempts = 0,
            lockoutUntilMillis = 0L
        )
    }

    fun setPinAndEnable(context: Context, pin: String, enableBiometric: Boolean? = null) {
        init(context)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashBase64 = hashPin(pin, salt)

        val current = _lockStateFlow.value
        val bioEnabled = enableBiometric ?: current.isBiometricEnabled

        val p = getPrefs(context)
        p.edit()
            .putString(KEY_PIN_SALT, saltBase64)
            .putString(KEY_PIN_HASH, hashBase64)
            .putBoolean(KEY_LOCK_ENABLED, true)
            .putBoolean(KEY_BIOMETRIC_ENABLED, bioEnabled)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()

        _lockStateFlow.value = readStateFromPrefs(p, currentlyLocked = false)
    }

    fun verifyPin(context: Context, inputPin: String): PinVerifyResult {
        init(context)
        val p = getPrefs(context)
        val now = System.currentTimeMillis()
        val lockoutUntil = p.getLong(KEY_LOCKOUT_UNTIL, 0L)
        if (lockoutUntil > now) {
            val remainingSec = ((lockoutUntil - now) / 1000L).toInt().coerceAtLeast(1)
            return PinVerifyResult.LockedOut(remainingSec)
        }

        val saltStr = p.getString(KEY_PIN_SALT, null)
        val expectedHashStr = p.getString(KEY_PIN_HASH, null)
        if (saltStr.isNullOrBlank() || expectedHashStr.isNullOrBlank()) {
            return PinVerifyResult.WrongPin(MAX_FAILED_ATTEMPTS)
        }

        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val actualHash = Base64.decode(hashPin(inputPin, salt), Base64.NO_WRAP)
        val expectedHash = Base64.decode(expectedHashStr, Base64.NO_WRAP)

        return if (MessageDigest.isEqual(actualHash, expectedHash)) {
            unlockApp(context)
            PinVerifyResult.Success
        } else {
            val nextFailed = p.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            if (nextFailed >= MAX_FAILED_ATTEMPTS) {
                val until = now + LOCKOUT_DURATION_MILLIS
                p.edit()
                    .putInt(KEY_FAILED_ATTEMPTS, 0)
                    .putLong(KEY_LOCKOUT_UNTIL, until)
                    .apply()
                _lockStateFlow.value = _lockStateFlow.value.copy(
                    failedAttempts = 0,
                    lockoutUntilMillis = until
                )
                PinVerifyResult.LockedOut((LOCKOUT_DURATION_MILLIS / 1000L).toInt())
            } else {
                p.edit().putInt(KEY_FAILED_ATTEMPTS, nextFailed).apply()
                _lockStateFlow.value = _lockStateFlow.value.copy(failedAttempts = nextFailed)
                PinVerifyResult.WrongPin(MAX_FAILED_ATTEMPTS - nextFailed)
            }
        }
    }

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        init(context)
        val p = getPrefs(context)
        p.edit()
            .putBoolean(KEY_LOCK_ENABLED, enabled)
            .apply()
        _lockStateFlow.value = readStateFromPrefs(
            p,
            currentlyLocked = if (enabled) _lockStateFlow.value.isLocked else false
        )
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        init(context)
        val p = getPrefs(context)
        p.edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
            .apply()
        _lockStateFlow.value = readStateFromPrefs(p, currentlyLocked = _lockStateFlow.value.isLocked)
    }

    fun setLockTimeoutSeconds(context: Context, seconds: Long) {
        init(context)
        val sanitized = if (seconds in SUPPORTED_TIMEOUTS) seconds else 0L
        val p = getPrefs(context)
        p.edit()
            .putLong(KEY_TIMEOUT_SECONDS, sanitized)
            .apply()
        _lockStateFlow.value = readStateFromPrefs(p, currentlyLocked = _lockStateFlow.value.isLocked)
    }

    /**
     * Clears app lock configuration when user explicitly logs out or resets forgotten PIN via logout.
     */
    fun resetLockOnLogout(context: Context) {
        init(context)
        val p = getPrefs(context)
        p.edit()
            .putBoolean(KEY_LOCK_ENABLED, false)
            .putBoolean(KEY_BIOMETRIC_ENABLED, false)
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
        lastBackgroundTimestamp = 0L
        _lockStateFlow.value = AppLockState()
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashed = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hashed, Base64.NO_WRAP)
    }

    private fun readStateFromPrefs(p: SharedPreferences, currentlyLocked: Boolean): AppLockState {
        val hasPin = !p.getString(KEY_PIN_HASH, null).isNullOrBlank()
        val rawTimeout = p.getLong(KEY_TIMEOUT_SECONDS, 0L)
        return AppLockState(
            isEnabled = p.getBoolean(KEY_LOCK_ENABLED, false),
            hasPin = hasPin,
            isBiometricEnabled = p.getBoolean(KEY_BIOMETRIC_ENABLED, false),
            lockTimeoutSeconds = if (rawTimeout in SUPPORTED_TIMEOUTS) rawTimeout else 0L,
            isLocked = currentlyLocked,
            failedAttempts = p.getInt(KEY_FAILED_ATTEMPTS, 0),
            lockoutUntilMillis = p.getLong(KEY_LOCKOUT_UNTIL, 0L)
        )
    }

    private fun getPrefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        val appContext = context.applicationContext
        val created = try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
        prefs = created
        return created
    }
}
