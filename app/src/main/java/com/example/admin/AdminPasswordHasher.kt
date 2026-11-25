package com.example.admin

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cryptographic helper for secondary admin password hashing using a random salt + SHA-256.
 * Never embeds any static passwords or secrets in source code.
 */
object AdminPasswordHasher {

    private val random = SecureRandom()

    fun generateSaltHex(byteLength: Int = 16): String {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPassword(saltHex: String, rawPassword: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$saltHex:$rawPassword".toByteArray(StandardCharsets.UTF_8)
        val hashed = digest.digest(input)
        return hashed.joinToString("") { "%02x".format(it) }
    }

    fun verifyPassword(saltHex: String, expectedHashHex: String, rawPassword: String): Boolean {
        if (saltHex.isBlank() || expectedHashHex.isBlank() || rawPassword.isBlank()) return false
        val actual = hashPassword(saltHex, rawPassword)
        return MessageDigest.isEqual(
            actual.toByteArray(StandardCharsets.UTF_8),
            expectedHashHex.toByteArray(StandardCharsets.UTF_8)
        )
    }
}
