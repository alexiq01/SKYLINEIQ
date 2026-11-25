package com.example.admin

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppBrandingState(
    val appDisplayName: String = "Skyline Users",
    val appSubtitle: String = "SKYLINE INTERNET",
    val preset: LauncherIconPreset = LauncherIconPreset.GLASS_BLUE
) {
    val logoDrawableRes: Int
        get() = preset.previewDrawableRes
}

/**
 * Persists and broadcasts remote branding (display name, subtitle, icon preset)
 * and switches Android launcher `<activity-alias>` components when the admin
 * updates the active icon preset.
 */
object AppBrandingManager {

    private const val PREFS_NAME = "skyline_remote_branding"
    private const val KEY_APP_DISPLAY_NAME = "app_display_name"
    private const val KEY_APP_SUBTITLE = "app_subtitle"
    private const val KEY_LAUNCHER_PRESET = "launcher_preset"

    private val _brandingFlow = MutableStateFlow(AppBrandingState())
    val brandingFlow: StateFlow<AppBrandingState> = _brandingFlow.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                val appContext = context.applicationContext ?: context
                val prefs = getPrefs(appContext)
                val defaultName = try {
                    appContext.getString(R.string.app_name)
                } catch (_: Throwable) {
                    "Skyline Users"
                }
                val defaultSubtitle = try {
                    appContext.getString(R.string.app_subtitle)
                } catch (_: Throwable) {
                    "SKYLINE INTERNET"
                }
                val loaded = AppBrandingState(
                    appDisplayName = prefs.getString(KEY_APP_DISPLAY_NAME, defaultName)
                        ?.takeIf { it.isNotBlank() } ?: defaultName,
                    appSubtitle = prefs.getString(KEY_APP_SUBTITLE, defaultSubtitle)
                        ?.takeIf { it.isNotBlank() } ?: defaultSubtitle,
                    preset = LauncherIconPreset.fromKey(
                        prefs.getString(KEY_LAUNCHER_PRESET, LauncherIconPreset.GLASS_BLUE.key)
                    )
                )
                _brandingFlow.value = loaded
                initialized = true
            }
        }
    }

    fun applyRemoteConfig(context: Context, config: AppConfig, applyLauncherSwitch: Boolean = true) {
        val appContext = context.applicationContext ?: context
        init(appContext)

        val cleanName = config.appDisplayName.trim().take(30).ifEmpty { "Skyline Users" }
        val cleanSubtitle = config.appSubtitle.trim().take(50).ifEmpty { "SKYLINE INTERNET" }
        val targetPreset = LauncherIconPreset.fromKey(config.launcherPreset)

        val previousPreset = _brandingFlow.value.preset
        val updated = AppBrandingState(
            appDisplayName = cleanName,
            appSubtitle = cleanSubtitle,
            preset = targetPreset
        )

        getPrefs(appContext).edit()
            .putString(KEY_APP_DISPLAY_NAME, cleanName)
            .putString(KEY_APP_SUBTITLE, cleanSubtitle)
            .putString(KEY_LAUNCHER_PRESET, targetPreset.key)
            .apply()

        _brandingFlow.value = updated

        if (applyLauncherSwitch && previousPreset != targetPreset) {
            switchLauncherIconAlias(appContext, targetPreset)
        }
    }

    /**
     * Switches the active launcher `<activity-alias>` via PackageManager.
     * Enables the target alias first before disabling the others so the launcher entry is never dropped.
     */
    fun switchLauncherIconAlias(context: Context, targetPreset: LauncherIconPreset): Boolean {
        val appContext = context.applicationContext ?: context
        return try {
            val pm = appContext.packageManager
            val packageName = appContext.packageName

            val targetComponent = ComponentName(packageName, targetPreset.aliasClassName)
            if (pm.getComponentEnabledSetting(targetComponent) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    targetComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            for (preset in LauncherIconPreset.entries) {
                if (preset != targetPreset) {
                    val comp = ComponentName(packageName, preset.aliasClassName)
                    val currentState = pm.getComponentEnabledSetting(comp)
                    if (currentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(
                            comp,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    internal fun resetForTesting(context: Context) {
        synchronized(this) {
            getPrefs(context.applicationContext ?: context).edit().clear().commit()
            _brandingFlow.value = AppBrandingState()
            initialized = false
        }
    }
}
