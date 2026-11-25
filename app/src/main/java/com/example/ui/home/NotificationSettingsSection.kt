package com.example.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.analytics.AnalyticsManager
import com.example.notifications.FcmNotificationManager
import com.example.ui.theme.SkylineBlue
import com.example.ui.theme.SkylineCyan
import com.example.ui.theme.SkylineSuccess
import com.example.ui.theme.isAppInDarkTheme
import com.example.ui.util.HapticFeedbackHelper
import com.example.worker.ExpirationNotificationWorker
import com.example.worker.NotificationScheduler

@Composable
fun ScheduledNotificationsSettingsCard(
    expirationDateStr: String,
    planName: String?,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = isAppInDarkTheme()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Load persisted settings into StateFlow on first composition
    LaunchedEffect(Unit) {
        NotificationScheduler.getNotificationSettings(context)
    }

    val settings by NotificationScheduler.settingsFlow.collectAsState()
    val isWorkEnqueued by remember(context) {
        NotificationScheduler.observePeriodicWorkScheduled(context)
    }.collectAsState(initial = settings.backgroundAlertsEnabled)
    val isThreeDayWorkScheduled by remember(context) {
        NotificationScheduler.observeThreeDayAlertWorkScheduled(context)
    }.collectAsState(initial = false)

    var hasOsPermission by remember {
        mutableStateOf(checkSystemNotificationsReady(context))
    }

    // Re-check OS notification permission whenever user returns from system settings
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOsPermission = checkSystemNotificationsReady(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasOsPermission = checkSystemNotificationsReady(context)
        if (granted && hasOsPermission) {
            onShowMessage("تم تفعيل إذن إشعارات النظام بنجاح")
            if (settings.backgroundAlertsEnabled) {
                NotificationScheduler.schedulePeriodicSubscriptionCheck(context)
                NotificationScheduler.checkCachedAndScheduleAlerts(context)
            }
        } else {
            openAppNotificationSettings(context)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_notification_settings"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF0D1B33) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            1.dp,
            if (isDark) SkylineBlue.copy(alpha = 0.25f) else Color(0xFFE2E8F0)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Card Header + Live WorkManager Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    if (settings.backgroundAlertsEnabled) {
                                        listOf(SkylineBlue, SkylineCyan)
                                    } else {
                                        listOf(Color(0xFF64748B), Color(0xFF94A3B8))
                                    }
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (settings.backgroundAlertsEnabled) {
                                Icons.Default.NotificationsActive
                            } else {
                                Icons.Default.NotificationsOff
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "إعدادات الإشعارات المجدولة",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "تنبيهات النظام في الخلفية (WorkManager)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                val statusText: String
                val statusColor: Color
                when {
                    !settings.backgroundAlertsEnabled -> {
                        statusText = "متوقف"
                        statusColor = Color(0xFFEF4444)
                    }
                    isWorkEnqueued -> {
                        statusText = "مجدول ويعمل"
                        statusColor = SkylineSuccess
                    }
                    else -> {
                        statusText = "مفعّل"
                        statusColor = SkylineBlue
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = statusText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // System Notification Permission Warning Banner (Android 13+ or blocked channel)
            if (!hasOsPermission) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFEF4444).copy(alpha = if (isDark) 0.16f else 0.08f),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "إذن الإشعارات معطّل على الجهاز",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "لضمان وصول تنبيهات انتهاء الاشتراك عند إغلاق التطبيق، يرجى السماح للتطبيق بإرسال الإشعارات.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                HapticFeedbackHelper.performClick(context, haptic)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openAppNotificationSettings(context)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("btn_grant_notification_permission"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "منح إذن الإشعارات الآن",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Master Switch Row
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        HapticFeedbackHelper.performClick(context, haptic)
                        val enabled = !settings.backgroundAlertsEnabled
                        NotificationScheduler.updateNotificationSettings(
                            context,
                            settings.copy(backgroundAlertsEnabled = enabled)
                        )
                        AnalyticsManager.logNotificationSettingChanged("background_alerts", enabled)
                        onShowMessage(
                            if (enabled) {
                                "تم تفعيل وجدولة إشعارات النظام في الخلفية"
                            } else {
                                "تم إيقاف إشعارات الخلفية المجدولة"
                            }
                        )
                    },
                shape = RoundedCornerShape(14.dp),
                color = if (settings.backgroundAlertsEnabled) {
                    SkylineBlue.copy(alpha = if (isDark) 0.14f else 0.07f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                border = BorderStroke(
                    1.dp,
                    if (settings.backgroundAlertsEnabled) {
                        SkylineBlue.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "تفعيل إشعارات النظام المجدولة",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "إرسال تنبيهات تلقائية عند انتهاء الاشتراك أو اقتراب موعده حتى لو كان التطبيق مغلقاً",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = settings.backgroundAlertsEnabled,
                        onCheckedChange = { enabled ->
                            HapticFeedbackHelper.performClick(context, haptic)
                            NotificationScheduler.updateNotificationSettings(
                                context,
                                settings.copy(backgroundAlertsEnabled = enabled)
                            )
                            AnalyticsManager.logNotificationSettingChanged("background_alerts", enabled)
                            onShowMessage(
                                if (enabled) {
                                    "تم تفعيل وجدولة إشعارات النظام في الخلفية"
                                } else {
                                    "تم إيقاف إشعارات الخلفية المجدولة"
                                }
                            )
                        },
                        modifier = Modifier.testTag("switch_workmanager_alerts"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SkylineBlue
                        )
                    )
                }
            }

            // Granular Milestone & Schedule Controls (shown when Master Switch is enabled)
            AnimatedVisibility(
                visible = settings.backgroundAlertsEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "مواعيد التنبيهات المجدولة",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    NotificationToggleRow(
                        icon = Icons.Default.EventBusy,
                        iconTint = Color(0xFFEF4444),
                        title = "تنبيه عند انتهاء الاشتراك",
                        subtitle = "إشعار فوري عند انتهاء الصلاحية وقبله بساعة واحدة",
                        checked = settings.alertOnExpirationEnabled,
                        testTag = "switch_alert_expired",
                        onToggle = { checked ->
                            HapticFeedbackHelper.performClick(context, haptic)
                            NotificationScheduler.updateNotificationSettings(
                                context,
                                settings.copy(alertOnExpirationEnabled = checked)
                            )
                            AnalyticsManager.logNotificationSettingChanged("alert_on_expiration", checked)
                            onShowMessage(
                                if (checked) "تم تفعيل تنبيه انتهاء الاشتراك"
                                else "تم إيقاف تنبيه انتهاء الاشتراك"
                            )
                        }
                    )

                    val threeDayTriggerMillis = remember(expirationDateStr) {
                        NotificationScheduler.getThreeDayTriggerMillis(expirationDateStr)
                    }
                    val threeDaySubtitle = remember(
                        settings.alert3DaysEnabled,
                        isThreeDayWorkScheduled,
                        expirationDateStr,
                        threeDayTriggerMillis
                    ) {
                        val now = System.currentTimeMillis()
                        val alreadySent = NotificationScheduler.wasAlertNotifiedForDate(
                            context,
                            ExpirationNotificationWorker.ALERT_3D,
                            expirationDateStr
                        )
                        when {
                            !settings.alert3DaysEnabled ->
                                "تذكير مبكر لتجديد الاشتراك قبل انتهائه بـ 72 ساعة"
                            alreadySent ->
                                "تم إرسال تنبيه قبل 3 أيام لهذه الدورة بنجاح"
                            threeDayTriggerMillis != null && threeDayTriggerMillis > now && isThreeDayWorkScheduled -> {
                                val sdf = java.text.SimpleDateFormat("d MMMM yyyy - hh:mm a", java.util.Locale("ar"))
                                "مجدول عبر WorkManager ليصل في: ${sdf.format(java.util.Date(threeDayTriggerMillis))}"
                            }
                            threeDayTriggerMillis != null && threeDayTriggerMillis <= now &&
                                NotificationScheduler.isSubscriptionActive(expirationDateStr) ->
                                "الاشتراك حالياً ضمن مهلة الـ 3 أيام قبل الانتهاء"
                            else ->
                                "تذكير مبكر لتجديد الاشتراك قبل انتهائه بـ 72 ساعة"
                        }
                    }

                    NotificationToggleRow(
                        icon = Icons.Default.AccessTime,
                        iconTint = Color(0xFFF59E0B),
                        title = "تنبيه مسبق قبل 3 أيام",
                        subtitle = threeDaySubtitle,
                        checked = settings.alert3DaysEnabled,
                        testTag = "switch_alert_3d",
                        onToggle = { checked ->
                            HapticFeedbackHelper.performClick(context, haptic)
                            NotificationScheduler.updateNotificationSettings(
                                context,
                                settings.copy(alert3DaysEnabled = checked)
                            )
                            AnalyticsManager.logNotificationSettingChanged("alert_3_days", checked)
                            onShowMessage(
                                if (checked) "تم تفعيل تنبيه قبل 3 أيام"
                                else "تم إيقاف تنبيه قبل 3 أيام"
                            )
                        }
                    )

                    NotificationToggleRow(
                        icon = Icons.Default.Alarm,
                        iconTint = SkylineBlue,
                        title = "تنبيه مسبق قبل 24 ساعة",
                        subtitle = "تذكير أخير قبل انتهاء صلاحية الإنترنت بيوم واحد",
                        checked = settings.alert24HoursEnabled,
                        testTag = "switch_alert_24h",
                        onToggle = { checked ->
                            HapticFeedbackHelper.performClick(context, haptic)
                            NotificationScheduler.updateNotificationSettings(
                                context,
                                settings.copy(alert24HoursEnabled = checked)
                            )
                            AnalyticsManager.logNotificationSettingChanged("alert_24_hours", checked)
                            onShowMessage(
                                if (checked) "تم تفعيل تنبيه قبل 24 ساعة"
                                else "تم إيقاف تنبيه قبل 24 ساعة"
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // WorkManager Periodic Frequency Selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = SkylineBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "معدل الفحص الدوري في الخلفية",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يحدد كل كم ساعة يتحقق النظام من حالة اشتراكك تلقائياً دون فتح التطبيق",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val intervals = listOf(
                            6L to "كل 6 ساعات",
                            12L to "كل 12 ساعة",
                            24L to "كل 24 ساعة"
                        )
                        intervals.forEach { (hours, label) ->
                            val isSelected = settings.checkIntervalHours == hours
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        HapticFeedbackHelper.performClick(context, haptic)
                                        if (settings.checkIntervalHours != hours) {
                                            NotificationScheduler.updateNotificationSettings(
                                                context,
                                                settings.copy(checkIntervalHours = hours)
                                            )
                                            AnalyticsManager.logNotificationIntervalChanged(hours)
                                            onShowMessage("تم تحديث الفحص الدوري إلى $label")
                                        }
                                    }
                                    .testTag("chip_interval_${hours}h"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    SkylineBlue
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) SkylineBlue
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                )
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Immediate WorkManager Check Button
                    FilledTonalButton(
                        onClick = {
                            HapticFeedbackHelper.performClick(context, haptic)
                            NotificationScheduler.triggerImmediateCheck(context)
                            AnalyticsManager.logManualBackgroundCheck()
                            onShowMessage("تم تشغيل فحص الخلفية المجدول الآن")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("btn_run_workmanager_check_now"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SkylineBlue.copy(alpha = 0.14f),
                            contentColor = SkylineBlue
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "فحص الاشتراك في الخلفية الآن",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "تجربة شكل الإشعارات على الجهاز",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    HapticFeedbackHelper.performClick(context, haptic)
                    NotificationScheduler.triggerTestNotification(
                        context = context,
                        alertType = ExpirationNotificationWorker.ALERT_3D,
                        expirationDateStr = expirationDateStr
                    )
                    AnalyticsManager.logTestNotificationTriggered("3_days")
                    onShowMessage("تم إرسال إشعار (متبقي 3 أيام) إلى شريط الإشعارات")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_test_3d_notification"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SkylineBlue),
                border = BorderStroke(1.dp, SkylineBlue)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("تجربة إشعار متبقي 3 أيام", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    HapticFeedbackHelper.performClick(context, haptic)
                    NotificationScheduler.triggerTestNotification(
                        context = context,
                        alertType = ExpirationNotificationWorker.ALERT_EXPIRED,
                        expirationDateStr = expirationDateStr
                    )
                    AnalyticsManager.logTestNotificationTriggered("expired")
                    onShowMessage("تم إرسال إشعار (انتهاء الاشتراك) إلى شريط الإشعارات")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_test_expired_notification"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.75f))
            ) {
                Icon(
                    imageVector = Icons.Default.EventBusy,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("تجربة إشعار انتهاء الاشتراك", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    HapticFeedbackHelper.performClick(context, haptic)
                    NotificationScheduler.sendSubscriptionRenewedNotification(
                        context = context,
                        planName = planName,
                        newExpirationDate = expirationDateStr
                    )
                    AnalyticsManager.logTestNotificationTriggered("renewed")
                    onShowMessage("تم إرسال إشعار (تم التفعيل بنجاح! استمتع بجودة الإنترنت)")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_test_renewal_notification"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SkylineSuccess),
                border = BorderStroke(1.dp, SkylineSuccess)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SkylineSuccess,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "تجربة إشعار التفعيل والتجديد",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkylineSuccess
                )
            }
        }
    }
}

@Composable
fun FcmNotificationSettingsCard(
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDark = isAppInDarkTheme()

    LaunchedEffect(Unit) {
        FcmNotificationManager.getPreferences(context)
    }

    val prefs by FcmNotificationManager.preferencesFlow.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_fcm_notification_settings"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF0D1B33) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            1.dp,
            if (isDark) SkylineCyan.copy(alpha = 0.25f) else Color(0xFFE2E8F0)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(SkylineBlue, SkylineCyan)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "إشعارات Firebase السحابية",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "استقبال الأخبار والعروض وتنبيهات الصيانة فوراً",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SkylineSuccess.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, SkylineSuccess.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "مفعّل",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkylineSuccess,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // General Broadcasts (always active)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("row_fcm_general_topic"),
                shape = RoundedCornerShape(14.dp),
                color = SkylineBlue.copy(alpha = if (isDark) 0.12f else 0.06f),
                border = BorderStroke(1.dp, SkylineBlue.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(SkylineBlue.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = null,
                                tint = SkylineBlue,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "الإشعارات العامة",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "تنبيهات وإعلانات Skyline Users الرئيسية",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SkylineBlue.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = "نشط دائماً",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkylineBlue,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(6.dp))

            NotificationToggleRow(
                icon = Icons.Default.LocalOffer,
                iconTint = SkylineSuccess,
                title = "العروض والباقات الجديدة",
                subtitle = "استقبال إشعارات العروض الحصرية والتخفيضات",
                checked = prefs.offersEnabled,
                testTag = "switch_fcm_offers",
                onToggle = { enabled ->
                    HapticFeedbackHelper.performClick(context, haptic)
                    FcmNotificationManager.setOffersEnabled(context, enabled)
                    onShowMessage(
                        if (enabled) "تم تفعيل إشعارات العروض والباقات"
                        else "تم إيقاف إشعارات العروض"
                    )
                }
            )

            NotificationToggleRow(
                icon = Icons.Default.Build,
                iconTint = Color(0xFFF59E0B),
                title = "تنبيهات الصيانة والتحديثات",
                subtitle = "إشعارات أعمال الصيانة الدورية وحالة الشبكة",
                checked = prefs.maintenanceEnabled,
                testTag = "switch_fcm_maintenance",
                onToggle = { enabled ->
                    HapticFeedbackHelper.performClick(context, haptic)
                    FcmNotificationManager.setMaintenanceEnabled(context, enabled)
                    onShowMessage(
                        if (enabled) "تم تفعيل إشعارات الصيانة والتحديثات"
                        else "تم إيقاف إشعارات الصيانة"
                    )
                }
            )
        }
    }
}

@Composable
private fun NotificationToggleRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SkylineBlue
            )
        )
    }
}

private fun checkSystemNotificationsReady(context: Context): Boolean {
    val managerEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val permissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    return managerEnabled && permissionGranted
}

private fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
