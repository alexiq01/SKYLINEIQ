package com.example.security

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.SkylineBlue
import com.example.ui.theme.SkylineCyan
import com.example.ui.theme.SkylineSuccess
import com.example.ui.theme.isAppInDarkTheme
import com.example.ui.util.HapticFeedbackHelper

private enum class PendingSecurityAction {
    NONE,
    DISABLE_LOCK,
    CHANGE_PIN
}

/**
 * Reusable Security & App Lock Settings card allowing users to configure Fingerprint / PIN lock,
 * auto-lock timeout, change PIN, and immediately lock the app.
 */
@Composable
fun AppLockSettingsCard(
    onShowMessage: (String) -> Unit,
    onDismissSheet: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val haptic = LocalHapticFeedback.current
    val isDark = isAppInDarkTheme()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        AppLockManager.init(context)
    }

    val lockState by AppLockManager.lockStateFlow.collectAsState()
    var biometricStatus by remember { mutableStateOf(BiometricHelper.checkAvailability(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                biometricStatus = BiometricHelper.checkAvailability(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showPinSetupDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf(PendingSecurityAction.NONE) }
    var showVerifyCurrentPinDialog by remember { mutableStateOf(false) }

    fun handleDisableLockVerified() {
        AppLockManager.setAppLockEnabled(context, false)
        onShowMessage("تم إيقاف قفل التطبيق")
    }

    fun startVerificationForAction(action: PendingSecurityAction) {
        pendingAction = action
        if (lockState.isBiometricEnabled && biometricStatus == BiometricAvailability.AVAILABLE && activity != null) {
            BiometricHelper.authenticate(
                activity = activity,
                title = "تأكيد الهوية",
                subtitle = if (action == PendingSecurityAction.DISABLE_LOCK) {
                    "تحقق لإيقاف قفل التطبيق"
                } else {
                    "تحقق لتغيير رمز PIN"
                },
                negativeButtonText = "استخدام رمز PIN",
                onSuccess = {
                    when (action) {
                        PendingSecurityAction.DISABLE_LOCK -> handleDisableLockVerified()
                        PendingSecurityAction.CHANGE_PIN -> showPinSetupDialog = true
                        PendingSecurityAction.NONE -> {}
                    }
                    pendingAction = PendingSecurityAction.NONE
                },
                onFallbackToPin = {
                    showVerifyCurrentPinDialog = true
                },
                onError = { msg ->
                    onShowMessage(msg)
                    showVerifyCurrentPinDialog = true
                }
            )
        } else if (lockState.hasPin) {
            showVerifyCurrentPinDialog = true
        } else {
            if (action == PendingSecurityAction.DISABLE_LOCK) {
                handleDisableLockVerified()
            } else if (action == PendingSecurityAction.CHANGE_PIN) {
                showPinSetupDialog = true
            }
            pendingAction = PendingSecurityAction.NONE
        }
    }

    fun toggleMasterLock(targetEnabled: Boolean) {
        HapticFeedbackHelper.performClick(context, haptic)
        if (targetEnabled) {
            if (lockState.hasPin) {
                AppLockManager.setAppLockEnabled(context, true)
                onShowMessage("تم تفعيل قفل التطبيق لحماية خصوصيتك")
            } else {
                showPinSetupDialog = true
            }
        } else {
            startVerificationForAction(PendingSecurityAction.DISABLE_LOCK)
        }
    }

    fun toggleBiometric(targetEnabled: Boolean) {
        HapticFeedbackHelper.performClick(context, haptic)
        if (!targetEnabled) {
            AppLockManager.setBiometricEnabled(context, false)
            onShowMessage("تم إيقاف الفتح ببصمة الإصبع")
            return
        }
        when (biometricStatus) {
            BiometricAvailability.AVAILABLE -> {
                if (activity != null) {
                    BiometricHelper.authenticate(
                        activity = activity,
                        title = "تفعيل بصمة الإصبع",
                        subtitle = "ضع إصبعك على المستشعر لتأكيد التفعيل",
                        negativeButtonText = "إلغاء",
                        onSuccess = {
                            AppLockManager.setBiometricEnabled(context, true)
                            onShowMessage("تم تفعيل الفتح ببصمة الإصبع بنجاح")
                        },
                        onFallbackToPin = {},
                        onError = { msg -> onShowMessage(msg) }
                    )
                }
            }
            BiometricAvailability.NONE_ENROLLED -> {
                onShowMessage("يرجى تسجيل بصمة إصبع في إعدادات الجهاز أولاً")
                BiometricHelper.openBiometricEnrollSettings(context)
            }
            else -> {
                onShowMessage("مستشعر البصمة غير متوفر على هذا الجهاز")
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("card_app_lock_settings"),
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
            // Header
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
                                    if (lockState.isEnabled) {
                                        listOf(SkylineSuccess, SkylineCyan)
                                    } else {
                                        listOf(SkylineBlue, SkylineCyan)
                                    }
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (lockState.isEnabled) Icons.Default.Lock else Icons.Default.Security,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "القفل والخصوصية",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "حماية بيانات الاشتراك بالبصمة أو رمز PIN",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = (if (lockState.isEnabled) SkylineSuccess else Color(0xFF64748B)).copy(alpha = 0.14f),
                    border = BorderStroke(
                        1.dp,
                        (if (lockState.isEnabled) SkylineSuccess else Color(0xFF64748B)).copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        text = if (lockState.isEnabled) "محمي" else "غير مفعّل",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (lockState.isEnabled) SkylineSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Master App Lock Switch
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { toggleMasterLock(!lockState.isEnabled) },
                shape = RoundedCornerShape(14.dp),
                color = if (lockState.isEnabled) {
                    SkylineSuccess.copy(alpha = if (isDark) 0.14f else 0.08f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                border = BorderStroke(
                    1.dp,
                    if (lockState.isEnabled) {
                        SkylineSuccess.copy(alpha = 0.32f)
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
                            text = "قفل التطبيق عند الفتح",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (lockState.hasPin) {
                                "يطلب رمز PIN أو بصمة الإصبع قبل عرض بيانات اشتراكك"
                            } else {
                                "أنشئ رمز PIN من 4 أرقام لتفعيل حماية التطبيق والخصوصية"
                            },
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = lockState.isEnabled,
                        onCheckedChange = { toggleMasterLock(it) },
                        modifier = Modifier.testTag("switch_app_lock_enabled"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SkylineSuccess
                        )
                    )
                }
            }

            // Detailed App Lock Controls
            AnimatedVisibility(
                visible = lockState.isEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Biometric Toggle
                    val bioSubtitle = when (biometricStatus) {
                        BiometricAvailability.AVAILABLE -> "استخدام بصمة الإصبع لفتح التطبيق بسرعة"
                        BiometricAvailability.NONE_ENROLLED -> "لا توجد بصمة مسجلة في الجهاز — اضغط لإضافتها"
                        else -> "مستشعر البصمة غير متوفر على هذا الجهاز"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (biometricStatus == BiometricAvailability.AVAILABLE || lockState.isBiometricEnabled) {
                                    toggleBiometric(!lockState.isBiometricEnabled)
                                } else if (biometricStatus == BiometricAvailability.NONE_ENROLLED) {
                                    BiometricHelper.openBiometricEnrollSettings(context)
                                } else {
                                    onShowMessage("مستشعر البصمة غير متوفر على هذا الجهاز")
                                }
                            }
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
                                    .background(SkylineCyan.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    tint = SkylineCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "الفتح ببصمة الإصبع",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = bioSubtitle,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Switch(
                            checked = lockState.isBiometricEnabled,
                            enabled = biometricStatus == BiometricAvailability.AVAILABLE || lockState.isBiometricEnabled,
                            onCheckedChange = { toggleBiometric(it) },
                            modifier = Modifier.testTag("switch_biometric_lock"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SkylineBlue
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto-lock Delay Selector
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = SkylineBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "إعادة القفل عند الخروج للخلفية",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val options = listOf(
                            0L to "فوراً",
                            30L to "بعد 30 ثانية",
                            60L to "بعد دقيقة"
                        )
                        options.forEach { (sec, label) ->
                            val selected = lockState.lockTimeoutSeconds == sec
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        HapticFeedbackHelper.performClick(context, haptic)
                                        AppLockManager.setLockTimeoutSeconds(context, sec)
                                        onShowMessage("تم ضبط إعادة القفل: $label")
                                    }
                                    .testTag("chip_lock_timeout_$sec"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) {
                                    SkylineBlue
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) SkylineBlue
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
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                HapticFeedbackHelper.performClick(context, haptic)
                                startVerificationForAction(PendingSecurityAction.CHANGE_PIN)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("btn_change_pin"),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SkylineBlue),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkylineBlue)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "تغيير رمز PIN",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                HapticFeedbackHelper.performClick(context, haptic)
                                onDismissSheet?.invoke()
                                AppLockManager.lockNow(context)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("btn_lock_app_now"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = SkylineBlue.copy(alpha = 0.14f),
                                contentColor = SkylineBlue
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "قفل الآن",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPinSetupDialog) {
        PinSetupDialog(
            isChangingExisting = lockState.hasPin,
            biometricAvailable = biometricStatus == BiometricAvailability.AVAILABLE,
            onDismiss = {
                showPinSetupDialog = false
                pendingAction = PendingSecurityAction.NONE
            },
            onPinConfirmed = { newPin, enableBio ->
                AppLockManager.setPinAndEnable(
                    context = context,
                    pin = newPin,
                    enableBiometric = enableBio
                )
                showPinSetupDialog = false
                pendingAction = PendingSecurityAction.NONE
                onShowMessage(
                    if (enableBio) "تم حفظ رمز PIN وتفعيل القفل بالبصمة بنجاح"
                    else "تم حفظ رمز PIN وتفعيل قفل التطبيق بنجاح"
                )
            }
        )
    }

    if (showVerifyCurrentPinDialog) {
        VerifyCurrentPinDialog(
            onDismiss = {
                showVerifyCurrentPinDialog = false
                pendingAction = PendingSecurityAction.NONE
            },
            onVerified = {
                showVerifyCurrentPinDialog = false
                val action = pendingAction
                pendingAction = PendingSecurityAction.NONE
                when (action) {
                    PendingSecurityAction.DISABLE_LOCK -> handleDisableLockVerified()
                    PendingSecurityAction.CHANGE_PIN -> showPinSetupDialog = true
                    PendingSecurityAction.NONE -> {}
                }
            }
        )
    }
}

@Composable
private fun PinSetupDialog(
    isChangingExisting: Boolean,
    biometricAvailable: Boolean,
    onDismiss: () -> Unit,
    onPinConfirmed: (pin: String, enableBiometric: Boolean) -> Unit
) {
    var firstPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var enableBio by remember { mutableStateOf(biometricAvailable) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isChangingExisting) "تعيين رمز PIN جديد" else "إنشاء رمز PIN للقفل",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "أدخل رمزاً مكوناً من 4 أرقام لحماية بيانات اشتراكك عند فتح التطبيق.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = firstPin,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(AppLockManager.PIN_LENGTH)
                        firstPin = digits
                        errorText = null
                    },
                    label = { Text("رمز PIN (4 أرقام)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_setup_pin")
                )

                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(AppLockManager.PIN_LENGTH)
                        confirmPin = digits
                        errorText = null
                    },
                    label = { Text("تأكيد رمز PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_confirm_pin")
                )

                if (biometricAvailable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { enableBio = !enableBio }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = SkylineBlue,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "تفعيل الفتح ببصمة الإصبع أيضاً",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Switch(
                            checked = enableBio,
                            onCheckedChange = { enableBio = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SkylineBlue
                            )
                        )
                    }
                }

                errorText?.let { err ->
                    Text(
                        text = err,
                        fontSize = 12.sp,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        firstPin.length != AppLockManager.PIN_LENGTH -> {
                            errorText = "يجب أن يتكون الرمز من 4 أرقام"
                        }
                        firstPin != confirmPin -> {
                            errorText = "الرمزان غير متطابقين، يرجى التحقق"
                        }
                        else -> {
                            onPinConfirmed(firstPin, enableBio)
                        }
                    }
                },
                modifier = Modifier.testTag("btn_save_setup_pin"),
                colors = ButtonDefaults.buttonColors(containerColor = SkylineBlue)
            ) {
                Text("حفظ وتفعيل", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
private fun VerifyCurrentPinDialog(
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "تأكيد رمز PIN الحالي",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "أدخل رمز PIN الحالي للمتابعة.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { input ->
                        pinInput = input.filter { it.isDigit() }.take(AppLockManager.PIN_LENGTH)
                        errorText = null
                    },
                    label = { Text("رمز PIN الحالي") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_verify_current_pin")
                )
                errorText?.let { err ->
                    Text(
                        text = err,
                        fontSize = 12.sp,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pinInput.length != AppLockManager.PIN_LENGTH) {
                        errorText = "أدخل 4 أرقام"
                        return@Button
                    }
                    when (val res = AppLockManager.verifyPin(context, pinInput)) {
                        is PinVerifyResult.Success -> onVerified()
                        is PinVerifyResult.WrongPin -> {
                            errorText = "رمز غير صحيح (متبقي ${res.remainingAttempts} محاولات)"
                            pinInput = ""
                        }
                        is PinVerifyResult.LockedOut -> {
                            errorText = "محاولات خاطئة كثيرة، انتظر ${res.remainingSeconds} ثانية"
                            pinInput = ""
                        }
                    }
                },
                modifier = Modifier.testTag("btn_confirm_current_pin"),
                colors = ButtonDefaults.buttonColors(containerColor = SkylineBlue)
            ) {
                Text("تأكيد", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
