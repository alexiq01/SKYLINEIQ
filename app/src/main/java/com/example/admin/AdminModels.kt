package com.example.admin

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class AdminAccount(
    @DocumentId val id: String = "",
    val uid: String = "",
    val email: String = "",
    val adminUsername: String = "",
    val passwordSalt: String = "",
    val passwordHash: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class RegisteredUser(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val ispUsername: String? = null,
    val appVersionName: String = "",
    val appVersionCode: Long = 1L,
    val launcherPreset: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class AppConfig(
    @DocumentId val id: String = "",
    val appDisplayName: String = "Skyline Users",
    val appSubtitle: String = "SKYLINE INTERNET",
    val launcherPreset: String = LauncherIconPreset.GLASS_BLUE.key,
    val latestVersionCode: Long = 1L,
    val latestVersionName: String = "1.0.0",
    val updateUrl: String = "",
    val forceUpdate: Boolean = false,
    val updateTitle: String = "تحديث جديد متوفر",
    val updateMessage: String = "يتوفر إصدار جديد من تطبيق Skyline Users.",
    val releaseNotes: String = "",
    val updatedByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class BroadcastNotification(
    @DocumentId val id: String = "",
    val title: String = "",
    val body: String = "",
    val category: String = "fcm_general",
    val published: Boolean = true,
    val senderUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

enum class LauncherIconPreset(
    val key: String,
    val displayTitleAr: String,
    val launcherLabel: String,
    val aliasClassName: String,
    @DrawableRes val previewDrawableRes: Int
) {
    GLASS_BLUE(
        key = "glass_blue",
        displayTitleAr = "أزرق زجاجي (الافتراضي)",
        launcherLabel = "Skyline Users",
        aliasClassName = "com.example.MainActivityGlass",
        previewDrawableRes = R.drawable.skyline_users_icon_1795607135251
    ),
    EMERALD_PRO(
        key = "emerald_pro",
        displayTitleAr = "أخضر زمردي (Pro)",
        launcherLabel = "Skyline Pro",
        aliasClassName = "com.example.MainActivityEmerald",
        previewDrawableRes = R.drawable.skyline_icon_emerald_src_1795608869307
    ),
    ROYAL_GOLD(
        key = "royal_gold",
        displayTitleAr = "ذهبي ملكي (VIP)",
        launcherLabel = "Skyline VIP",
        aliasClassName = "com.example.MainActivityGold",
        previewDrawableRes = R.drawable.skyline_icon_gold_src_1795608869173
    ),
    MIDNIGHT_NET(
        key = "midnight_net",
        displayTitleAr = "ليلي داكن (Net)",
        launcherLabel = "Skyline Net",
        aliasClassName = "com.example.MainActivityDark",
        previewDrawableRes = R.drawable.skyline_icon_dark_src_1795608869246
    );

    companion object {
        fun fromKey(raw: String?): LauncherIconPreset {
            return entries.firstOrNull { it.key == raw } ?: GLASS_BLUE
        }
    }
}
