package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.admin.AdminPasswordHasher
import com.example.admin.AppBrandingManager
import com.example.admin.AppConfig
import com.example.admin.LauncherIconPreset
import com.example.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdminRobolectricTest {

    @Test
    fun `test AdminPasswordHasher hashes with random salt and verifies securely`() {
        val salt1 = AdminPasswordHasher.generateSaltHex()
        val salt2 = AdminPasswordHasher.generateSaltHex()
        assertNotEquals(salt1, salt2)

        val hash1 = AdminPasswordHasher.hashPassword(salt1, "SkylineAdmin#2026")
        assertTrue(AdminPasswordHasher.verifyPassword(salt1, hash1, "SkylineAdmin#2026"))
        assertFalse(AdminPasswordHasher.verifyPassword(salt1, hash1, "WrongPassword"))
        assertFalse(AdminPasswordHasher.verifyPassword(salt2, hash1, "SkylineAdmin#2026"))
    }

    @Test
    fun `test AppBrandingManager persists remote branding and enforces 30 char Play Policy limit`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppBrandingManager.resetForTesting(context)

        AppBrandingManager.init(context)
        assertEquals("Skyline Users", AppBrandingManager.brandingFlow.value.appDisplayName)
        assertEquals(LauncherIconPreset.GLASS_BLUE, AppBrandingManager.brandingFlow.value.preset)

        val longTitle = "Skyline Users Super Extended Title Exceeding 30 Chars"
        AppBrandingManager.applyRemoteConfig(
            context = context,
            config = AppConfig(
                appDisplayName = longTitle,
                appSubtitle = "PRO NETWORK",
                launcherPreset = LauncherIconPreset.ROYAL_GOLD.key
            ),
            applyLauncherSwitch = false
        )

        val updated = AppBrandingManager.brandingFlow.value
        assertEquals(30, updated.appDisplayName.length)
        assertEquals("PRO NETWORK", updated.appSubtitle)
        assertEquals(LauncherIconPreset.ROYAL_GOLD, updated.preset)
        assertEquals(R.drawable.skyline_icon_gold_src_1795608869173, updated.logoDrawableRes)

        // Verify persistence after re-init
        AppBrandingManager.init(context)
        assertEquals(LauncherIconPreset.ROYAL_GOLD, AppBrandingManager.brandingFlow.value.preset)

        AppBrandingManager.resetForTesting(context)
    }

    @Test
    fun `test LauncherIconPreset resolves all 4 presets cleanly with default fallback`() {
        assertEquals(LauncherIconPreset.GLASS_BLUE, LauncherIconPreset.fromKey("glass_blue"))
        assertEquals(LauncherIconPreset.EMERALD_PRO, LauncherIconPreset.fromKey("emerald_pro"))
        assertEquals(LauncherIconPreset.ROYAL_GOLD, LauncherIconPreset.fromKey("royal_gold"))
        assertEquals(LauncherIconPreset.MIDNIGHT_NET, LauncherIconPreset.fromKey("midnight_net"))
        assertEquals(LauncherIconPreset.GLASS_BLUE, LauncherIconPreset.fromKey("unknown_preset"))
    }

    @Test
    fun `test UpdateManager applies Firestore Admin update config with force flag`() {
        UpdateManager.resetForTesting()

        // Newer version with APK URL triggers update state
        UpdateManager.applyFirestoreUpdateConfig(
            config = AppConfig(
                appDisplayName = "Skyline Users",
                latestVersionCode = 10L,
                latestVersionName = "2.5.0",
                updateUrl = "https://cdn.example.com/skyline-2.5.0.apk",
                forceUpdate = true,
                updateTitle = "تحديث مهم",
                updateMessage = "يرجى التحديث للاستمرار",
                releaseNotes = "- تحسين الأداء"
            ),
            currentVersionCode = 1L,
            currentVersionName = "1.0.0"
        )

        val activeUpdate = UpdateManager.updateState.value
        assertNotNull(activeUpdate)
        assertEquals(10L, activeUpdate?.latestVersionCode)
        assertEquals("2.5.0", activeUpdate?.latestVersionName)
        assertEquals("https://cdn.example.com/skyline-2.5.0.apk", activeUpdate?.updateUrl)
        assertTrue(activeUpdate?.isForceUpdate == true)

        // Force update cannot be dismissed
        UpdateManager.dismissUpdate()
        assertNotNull(UpdateManager.updateState.value)

        // Same or older version clears the update dialog
        UpdateManager.applyFirestoreUpdateConfig(
            config = AppConfig(
                latestVersionCode = 1L,
                latestVersionName = "1.0.0",
                updateUrl = "https://cdn.example.com/skyline-1.0.0.apk",
                forceUpdate = true
            ),
            currentVersionCode = 1L,
            currentVersionName = "1.0.0"
        )
        assertNull(UpdateManager.updateState.value)

        UpdateManager.resetForTesting()
    }

    @Test
    fun `test AdminPortalGateStore intercepts hidden admin credentials without matching regular subscribers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.admin.AdminPortalGateStore.clearForTesting(context)

        // Regular subscriber username does not trigger hidden Admin portal
        val regularMatch = com.example.admin.AdminPortalGateStore.evaluateLoginEntry(
            context = context,
            inputUsername = "subscriber_01",
            inputPassword = "user_pass_123",
            secretsUsername = "",
            secretsPassword = ""
        )
        assertEquals(com.example.admin.AdminLoginMatch.None, regularMatch)

        // Bootstrapped admin email triggers first-run hidden entry
        val bootstrapMatch = com.example.admin.AdminPortalGateStore.evaluateLoginEntry(
            context = context,
            inputUsername = "yassindevai01@gmail.com",
            inputPassword = "initial_setup_pass",
            secretsUsername = "",
            secretsPassword = ""
        )
        assertTrue(bootstrapMatch is com.example.admin.AdminLoginMatch.BootstrapEmailEntry)

        // Saved salted SHA-256 verifier matches dedicated admin username & password
        val salt = AdminPasswordHasher.generateSaltHex()
        val hash = AdminPasswordHasher.hashPassword(salt, "StrongAdmin#99")
        com.example.admin.AdminPortalGateStore.saveLocalVerifier(
            context = context,
            adminUsername = "skyline_boss",
            passwordSalt = salt,
            passwordHash = hash
        )

        val localMatch = com.example.admin.AdminPortalGateStore.evaluateLoginEntry(
            context = context,
            inputUsername = "skyline_boss",
            inputPassword = "StrongAdmin#99",
            secretsUsername = "",
            secretsPassword = ""
        )
        assertTrue(localMatch is com.example.admin.AdminLoginMatch.MatchedLocalVerifier)

        // Wrong password for admin username falls back cleanly
        val wrongPassMatch = com.example.admin.AdminPortalGateStore.evaluateLoginEntry(
            context = context,
            inputUsername = "skyline_boss",
            inputPassword = "WrongPassword",
            secretsUsername = "",
            secretsPassword = ""
        )
        assertEquals(com.example.admin.AdminLoginMatch.None, wrongPassMatch)

        // Secrets-configured credentials match cleanly when provided
        val secretMatch = com.example.admin.AdminPortalGateStore.evaluateLoginEntry(
            context = context,
            inputUsername = "env_admin",
            inputPassword = "EnvPassword!23",
            secretsUsername = "env_admin",
            secretsPassword = "EnvPassword!23"
        )
        assertTrue(secretMatch is com.example.admin.AdminLoginMatch.MatchedSecrets)

        // Transient pending credentials are wiped on single consume
        com.example.admin.AdminPortalGateStore.setPendingLogin("skyline_boss", "StrongAdmin#99")
        val firstConsume = com.example.admin.AdminPortalGateStore.consumePendingLogin()
        assertNotNull(firstConsume)
        assertEquals("skyline_boss", firstConsume?.username)
        assertNull(com.example.admin.AdminPortalGateStore.consumePendingLogin())

        com.example.admin.AdminPortalGateStore.clearForTesting(context)
    }
}
