package com.example.admin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface AdminLoginMatch {
    data object None : AdminLoginMatch
    data class MatchedSecrets(val username: String, val password: String) : AdminLoginMatch
    data class MatchedLocalVerifier(val username: String, val password: String) : AdminLoginMatch
    data class BootstrapEmailEntry(val email: String) : AdminLoginMatch
}

data class PendingAdminLogin(
    val username: String,
    val password: String
)

/**
 * Stores a salted SHA-256 verifier for the Admin's dedicated username & password
 * so the hidden Admin Portal can be triggered directly from the standard LoginScreen
 * without any visible Admin buttons and without storing plaintext passwords.
 */
object AdminPortalGateStore {

    private const val PREFS_NAME = "skyline_admin_gate_secure"
    private const val KEY_ADMIN_USERNAME = "gate_admin_username"
    private const val KEY_PASSWORD_SALT = "gate_password_salt"
    private const val KEY_PASSWORD_HASH = "gate_password_hash"

    @Volatile
    private var pendingLogin: PendingAdminLogin? = null

    fun setPendingLogin(username: String, password: String) {
        pendingLogin = PendingAdminLogin(username = username.trim(), password = password)
    }

    fun consumePendingLogin(): PendingAdminLogin? {
        val current = pendingLogin
        pendingLogin = null
        return current
    }

    fun saveLocalVerifier(
        context: Context,
        adminUsername: String,
        passwordSalt: String,
        passwordHash: String
    ) {
        val cleanUsername = adminUsername.trim()
        if (cleanUsername.isEmpty() || passwordSalt.isBlank() || passwordHash.isBlank()) return
        getSecurePrefs(context.applicationContext ?: context).edit()
            .putString(KEY_ADMIN_USERNAME, cleanUsername)
            .putString(KEY_PASSWORD_SALT, passwordSalt)
            .putString(KEY_PASSWORD_HASH, passwordHash)
            .apply()
    }

    fun getSavedAdminUsername(context: Context): String? {
        return getSecurePrefs(context.applicationContext ?: context)
            .getString(KEY_ADMIN_USERNAME, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun evaluateLoginEntry(
        context: Context,
        inputUsername: String,
        inputPassword: String,
        secretsUsername: String = "",
        secretsPassword: String = ""
    ): AdminLoginMatch {
        val cleanUser = inputUsername.trim()
        if (cleanUser.isEmpty() || inputPassword.isEmpty()) return AdminLoginMatch.None

        // 1. Check optional AI Studio Secrets-configured admin credentials
        val cleanSecretUser = secretsUsername.trim()
        if (cleanSecretUser.isNotEmpty() && secretsPassword.isNotEmpty()) {
            val userOk = MessageDigest.isEqual(
                cleanUser.toByteArray(StandardCharsets.UTF_8),
                cleanSecretUser.toByteArray(StandardCharsets.UTF_8)
            )
            val passOk = MessageDigest.isEqual(
                inputPassword.toByteArray(StandardCharsets.UTF_8),
                secretsPassword.toByteArray(StandardCharsets.UTF_8)
            )
            if (userOk && passOk) {
                return AdminLoginMatch.MatchedSecrets(cleanUser, inputPassword)
            }
        }

        // 2. Check locally synced salted SHA-256 verifier from Firebase /admins/{uid}
        val prefs = getSecurePrefs(context.applicationContext ?: context)
        val savedUser = prefs.getString(KEY_ADMIN_USERNAME, null)?.trim().orEmpty()
        val savedSalt = prefs.getString(KEY_PASSWORD_SALT, null).orEmpty()
        val savedHash = prefs.getString(KEY_PASSWORD_HASH, null).orEmpty()
        if (savedUser.isNotEmpty() && savedSalt.isNotEmpty() && savedHash.isNotEmpty()) {
            val userOk = MessageDigest.isEqual(
                cleanUser.toByteArray(StandardCharsets.UTF_8),
                savedUser.toByteArray(StandardCharsets.UTF_8)
            )
            if (userOk && AdminPasswordHasher.verifyPassword(savedSalt, savedHash, inputPassword)) {
                return AdminLoginMatch.MatchedLocalVerifier(cleanUser, inputPassword)
            }
        }

        // 3. Authorized Admin Google email allows entering the hidden portal for first-time setup or recovery
        if (cleanUser.equals(AdminFirebaseRepository.BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)) {
            return AdminLoginMatch.BootstrapEmailEntry(cleanUser)
        }

        return AdminLoginMatch.None
    }

    internal fun clearForTesting(context: Context) {
        pendingLogin = null
        getSecurePrefs(context.applicationContext ?: context).edit().clear().commit()
    }

    private fun getSecurePrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
}
