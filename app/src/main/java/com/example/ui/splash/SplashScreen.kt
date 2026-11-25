package com.example.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.analytics.AnalyticsManager
import com.example.data.repository.AutoLoginCheckStatus
import com.example.ui.theme.SkylineBlue
import com.example.ui.theme.SkylineCyan
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    hasValidSession: Boolean,
    onAttemptProviderAutoLogin: suspend () -> AutoLoginCheckStatus = { AutoLoginCheckStatus.NOT_ON_PROVIDER_NETWORK },
    onNavigateNext: (destination: String, autoLoginStatus: AutoLoginCheckStatus) -> Unit
) {
    var statusMessage by remember {
        mutableStateOf(
            if (hasValidSession) "جاري تجهيز حسابك ..." else "جاري التحقق من الاتصال بشبكة المزود ..."
        )
    }
    val branding by com.example.admin.AppBrandingManager.brandingFlow.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )

    LaunchedEffect(Unit) {
        if (hasValidSession) {
            delay(1000)
            onNavigateNext("home", AutoLoginCheckStatus.IDLE)
        } else {
            AnalyticsManager.logLoginAttempt("auto_provider")
            val status = coroutineScope {
                val minSplashDelay = async { delay(1000) }
                val autoLoginCheck = async { onAttemptProviderAutoLogin() }
                minSplashDelay.await()
                autoLoginCheck.await()
            }

            if (status == AutoLoginCheckStatus.SUCCESS_PROVIDER) {
                AnalyticsManager.logLoginSuccess("auto_provider")
                statusMessage = "تم الدخول التلقائي عبر شبكة Skyline"
                delay(350)
                onNavigateNext("home", AutoLoginCheckStatus.SUCCESS_PROVIDER)
            } else {
                val failReason = if (status == AutoLoginCheckStatus.NETWORK_UNAVAILABLE) {
                    "network_unavailable"
                } else {
                    "not_on_provider_network"
                }
                AnalyticsManager.logLoginFailure("auto_provider", failReason)
                onNavigateNext("login", status)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF041328),
                        Color(0xFF072347),
                        Color(0xFF0A376E)
                    )
                )
            )
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Ambient glow logo
            Box(
                modifier = Modifier
                    .size(124.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(SkylineCyan.copy(alpha = 0.4f), Color.Transparent)
                        )
                    )
                    .border(2.dp, SkylineCyan.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = branding.logoDrawableRes),
                    contentDescription = branding.appDisplayName,
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = branding.appDisplayName,
                fontSize = 31.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 1.2.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = branding.appSubtitle,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = SkylineCyan,
                letterSpacing = 3.5.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "بوابة إدارة الاشتراكات والخدمات",
                fontSize = 14.sp,
                color = Color(0xFFCBD5E1),
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(52.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    color = SkylineCyan,
                    strokeWidth = 3.5.dp,
                    trackColor = SkylineBlue.copy(alpha = 0.25f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(50.dp),
                color = Color.White.copy(alpha = 0.1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, SkylineCyan.copy(alpha = 0.35f)),
                modifier = Modifier.testTag("splash_status_pill")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = SkylineCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusMessage,
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

