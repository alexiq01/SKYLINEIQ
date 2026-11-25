package com.example.security

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.R
import com.example.analytics.AnalyticsManager
import com.example.ui.theme.SkylineBlue
import com.example.ui.theme.SkylineCyan
import com.example.ui.util.HapticFeedbackHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-screen Biometric & 4-Digit PIN Lock Screen guarding subscriber data.
 */
@Composable
fun AppLockScreen(
    onUnlocked: () -> Unit,
    onResetAndLogout: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val lockState by AppLockManager.lockStateFlow.collectAsState()
    val biometricAvailability = remember(context) { BiometricHelper.checkAvailability(context) }
    val canUseBiometric = lockState.isBiometricEnabled && biometricAvailability == BiometricAvailability.AVAILABLE

    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingSeconds by remember { mutableIntStateOf(0) }
    var showForgotDialog by remember { mutableStateOf(false) }
    var hasAutoTriggeredBiometric by remember { mutableStateOf(false) }

    val shakeOffset = remember { Animatable(0f) }

    // Prevent Back button from bypassing the lock screen
    BackHandler(enabled = true) {
        (context as? Activity)?.moveTaskToBack(true)
    }

    // Track brute-force lockout countdown if active
    LaunchedEffect(lockState.lockoutUntilMillis) {
        while (true) {
            val remaining = ((lockState.lockoutUntilMillis - System.currentTimeMillis()) / 1000L).toInt()
            if (remaining > 0) {
                lockoutRemainingSeconds = remaining
                delay(500L)
            } else {
                lockoutRemainingSeconds = 0
                break
            }
        }
    }

    val launchBiometric: () -> Unit = {
        if (activity != null && canUseBiometric) {
            BiometricHelper.authenticate(
                activity = activity,
                title = "فتح قفل Skyline",
                subtitle = "تحقق بالبصمة لعرض بيانات اشتراكك",
                negativeButtonText = if (lockState.hasPin) "استخدام رمز PIN" else "إلغاء",
                onSuccess = {
                    errorMessage = null
                    AnalyticsManager.logAppUnlocked("biometric")
                    AppLockManager.unlockApp(context)
                    onUnlocked()
                },
                onFallbackToPin = {
                    // User chose PIN keypad
                },
                onError = { msg ->
                    errorMessage = msg
                }
            )
        }
    }

    // Auto-prompt fingerprint authentication once when lock screen appears
    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric && !hasAutoTriggeredBiometric) {
            hasAutoTriggeredBiometric = true
            delay(180)
            launchBiometric()
        }
    }

    fun triggerWrongPinFeedback(message: String) {
        errorMessage = message
        enteredPin = ""
        scope.launch {
            val offsets = listOf(-18f, 18f, -14f, 14f, -8f, 8f, 0f)
            for (target in offsets) {
                shakeOffset.animateTo(target, animationSpec = tween(42))
            }
        }
    }

    fun handleDigit(digit: String) {
        if (lockoutRemainingSeconds > 0) return
        if (enteredPin.length >= AppLockManager.PIN_LENGTH) return
        HapticFeedbackHelper.performClick(context, haptic)
        errorMessage = null
        val updated = enteredPin + digit
        enteredPin = updated

        if (updated.length == AppLockManager.PIN_LENGTH) {
            when (val result = AppLockManager.verifyPin(context, updated)) {
                is PinVerifyResult.Success -> {
                    enteredPin = ""
                    AnalyticsManager.logAppUnlocked("pin")
                    onUnlocked()
                }
                is PinVerifyResult.WrongPin -> {
                    triggerWrongPinFeedback(
                        "رمز PIN غير صحيح (متبقي ${result.remainingAttempts} محاولات)"
                    )
                }
                is PinVerifyResult.LockedOut -> {
                    lockoutRemainingSeconds = result.remainingSeconds
                    triggerWrongPinFeedback(
                        "محاولات خاطئة كثيرة، انتظر ${result.remainingSeconds} ثانية"
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF030A18),
                        Color(0xFF06132B),
                        Color(0xFF0A1D3B)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("app_lock_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Branding & Lock Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        SkylineCyan.copy(alpha = 0.30f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Image(
                        painter = painterResource(id = R.drawable.ic_skyline_logo),
                        contentDescription = null,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, SkylineCyan.copy(alpha = 0.5f), CircleShape)
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(28.dp),
                        shape = CircleShape,
                        color = SkylineBlue,
                        border = BorderStroke(2.dp, Color(0xFF06132B))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "تطبيق Skyline محمي",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (canUseBiometric && !lockState.hasPin) {
                        "استخدم بصمة الإصبع لفتح التطبيق"
                    } else if (canUseBiometric) {
                        "أدخل رمز PIN أو استخدم بصمة الإصبع لعرض بيانات اشتراكك"
                    } else {
                        "أدخل رمز PIN المكون من 4 أرقام لعرض بيانات اشتراكك"
                    },
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )
            }

            // Center PIN Dots + Error / Lockout Feedback
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 20.dp)
            ) {
                if (lockState.hasPin) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(AppLockManager.PIN_LENGTH) { index ->
                                val filled = index < enteredPin.length
                                val hasError = errorMessage != null && enteredPin.isEmpty()
                                val dotScale by animateFloatAsState(
                                    targetValue = if (filled) 1.18f else 1f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                    label = "pin_dot_scale"
                                )
                                val dotColor by animateColorAsState(
                                    targetValue = when {
                                        hasError -> Color(0xFFEF4444)
                                        filled -> SkylineCyan
                                        else -> Color.White.copy(alpha = 0.14f)
                                    },
                                    label = "pin_dot_color"
                                )
                                val borderColor by animateColorAsState(
                                    targetValue = when {
                                        hasError -> Color(0xFFEF4444)
                                        filled -> SkylineCyan
                                        else -> Color.White.copy(alpha = 0.35f)
                                    },
                                    label = "pin_dot_border"
                                )

                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .scale(dotScale)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                        .border(1.5.dp, borderColor, CircleShape)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                AnimatedVisibility(visible = lockoutRemainingSeconds > 0 || errorMessage != null) {
                    val displayMsg = if (lockoutRemainingSeconds > 0) {
                        "تم إيقاف المحاولات مؤقتاً • انتظر $lockoutRemainingSeconds ثانية"
                    } else {
                        errorMessage ?: ""
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = displayMsg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFCA5A5),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Keypad / Biometric CTA
            if (lockState.hasPin) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val rows = listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9")
                        )
                        rows.forEach { rowDigits ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(22.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                rowDigits.forEach { digit ->
                                    PinKeypadDigitButton(
                                        digit = digit,
                                        enabled = lockoutRemainingSeconds <= 0,
                                        onClick = { handleDigit(digit) }
                                    )
                                }
                            }
                        }

                        // Bottom Row: Biometric | 0 | Backspace
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(22.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (canUseBiometric) {
                                PinKeypadActionButton(
                                    contentDescription = "فتح ببصمة الإصبع",
                                    testTag = "pin_key_biometric",
                                    tint = SkylineCyan,
                                    highlight = true,
                                    onClick = {
                                        HapticFeedbackHelper.performClick(context, haptic)
                                        launchBiometric()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = "فتح ببصمة الإصبع",
                                        tint = SkylineCyan,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.size(68.dp))
                            }

                            PinKeypadDigitButton(
                                digit = "0",
                                enabled = lockoutRemainingSeconds <= 0,
                                onClick = { handleDigit("0") }
                            )

                            PinKeypadActionButton(
                                contentDescription = "مسح رقم",
                                testTag = "pin_key_backspace",
                                tint = Color.White,
                                highlight = false,
                                enabled = enteredPin.isNotEmpty(),
                                onClick = {
                                    if (enteredPin.isNotEmpty()) {
                                        HapticFeedbackHelper.performClick(context, haptic)
                                        enteredPin = enteredPin.dropLast(1)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "مسح رقم",
                                    tint = if (enteredPin.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            } else if (canUseBiometric) {
                Button(
                    onClick = {
                        HapticFeedbackHelper.performClick(context, haptic)
                        launchBiometric()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("btn_unlock_biometric_only"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SkylineBlue,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "فتح القفل ببصمة الإصبع",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Bottom Recovery Action
            TextButton(
                onClick = { showForgotDialog = true },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_forgot_pin_logout")
            ) {
                Text(
                    text = "نسيت رمز القفل؟ تسجيل الخروج",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }

    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { showForgotDialog = false },
            title = {
                Text(
                    text = "إعادة تعيين القفل",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "لحماية خصوصية اشتراكك، يتطلب إزالة رمز PIN تسجيل الخروج من حسابك وإعادة تسجيل الدخول مجدداً. هل تريد المتابعة؟",
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showForgotDialog = false
                        onResetAndLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("تسجيل الخروج وإزالة القفل", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgotDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun PinKeypadDigitButton(
    digit: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = SkylineCyan),
                onClick = onClick
            )
            .testTag("pin_key_$digit"),
        shape = CircleShape,
        color = Color.White.copy(alpha = if (enabled) 0.08f else 0.03f),
        border = BorderStroke(
            1.dp,
            Color.White.copy(alpha = if (enabled) 0.16f else 0.06f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = digit,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
private fun PinKeypadActionButton(
    contentDescription: String,
    testTag: String,
    tint: Color,
    highlight: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = tint),
                onClick = onClick
            )
            .testTag(testTag),
        shape = CircleShape,
        color = if (highlight) SkylineCyan.copy(alpha = 0.14f) else Color.Transparent,
        border = if (highlight) {
            BorderStroke(1.dp, SkylineCyan.copy(alpha = 0.4f))
        } else {
            null
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}
