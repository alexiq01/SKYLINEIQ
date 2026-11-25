package com.example.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.admin.LauncherIconPreset
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.SkylineBlue
import com.example.ui.theme.SkylineError

@Composable
fun AdminConfigTab(
    state: AdminDashboardUiState,
    currentAdminUsername: String,
    isDark: Boolean,
    onDisplayNameChange: (String) -> Unit,
    onSubtitleChange: (String) -> Unit,
    onPresetSelect: (String) -> Unit,
    onVersionCodeChange: (String) -> Unit,
    onVersionNameChange: (String) -> Unit,
    onUpdateUrlChange: (String) -> Unit,
    onForceUpdateChange: (Boolean) -> Unit,
    onUpdateTitleChange: (String) -> Unit,
    onUpdateMessageChange: (String) -> Unit,
    onReleaseNotesChange: (String) -> Unit,
    onSaveConfig: () -> Unit,
    onUpdateCredentials: (username: String, pw: String, confirm: String) -> Unit
) {
    // 1. App Branding & Launcher Icon Switching
    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Brush, contentDescription = null, tint = SkylineBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "تغيير اسم وأيقونة التطبيق عن بُعد",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            OutlinedTextField(
                value = state.appDisplayName,
                onValueChange = onDisplayNameChange,
                label = { Text("اسم التطبيق الظاهر (${state.appDisplayName.length}/30)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_config_app_name"),
                shape = RoundedCornerShape(14.dp)
            )

            OutlinedTextField(
                value = state.appSubtitle,
                onValueChange = onSubtitleChange,
                label = { Text("الشعار الفرعي أسفل الاسم") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_config_app_subtitle"),
                shape = RoundedCornerShape(14.dp)
            )

            Text(
                text = "اختر تصميم أيقونة الشاشة الرئيسية والشعار الداخلي:",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LauncherIconPreset.entries.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { preset ->
                            val isSelected = state.launcherPreset == preset.key
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onPresetSelect(preset.key) }
                                    .testTag("preset_${preset.key}"),
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) {
                                    SkylineBlue.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) SkylineBlue else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(
                                        painter = painterResource(id = preset.previewDrawableRes),
                                        contentDescription = preset.displayTitleAr,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = preset.displayTitleAr,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = preset.launcherLabel,
                                            fontSize = 10.5.sp,
                                            color = SkylineBlue,
                                            maxLines = 1
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "مختارة",
                                            tint = SkylineBlue,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 2. Future App Updates Management
    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = SkylineBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "إدارة تحديثات التطبيق مستقبلاً",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "الإصدار الحالي المثبّت: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.latestVersionCodeInput,
                    onValueChange = onVersionCodeChange,
                    label = { Text("رقم الإصدار (Code)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_config_version_code"),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = state.latestVersionNameInput,
                    onValueChange = onVersionNameChange,
                    label = { Text("اسم الإصدار (Name)") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_config_version_name"),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            OutlinedTextField(
                value = state.updateUrlInput,
                onValueChange = onUpdateUrlChange,
                label = { Text("رابط تحميل APK الجديد") },
                placeholder = { Text("https://example.com/skyline-users-latest.apk") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_config_update_url"),
                shape = RoundedCornerShape(14.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (state.forceUpdate) SkylineError.copy(alpha = 0.1f)
                        else SkylineBlue.copy(alpha = 0.06f)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "إجبار المستخدمين على التحديث",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "عند التفعيل لن يتمكن المستخدم من إغلاق نافذة التحديث",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.forceUpdate,
                    onCheckedChange = onForceUpdateChange,
                    modifier = Modifier.testTag("switch_force_update"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = SkylineError
                    )
                )
            }

            OutlinedTextField(
                value = state.updateTitleInput,
                onValueChange = onUpdateTitleChange,
                label = { Text("عنوان نافذة التحديث") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            OutlinedTextField(
                value = state.updateMessageInput,
                onValueChange = onUpdateMessageChange,
                label = { Text("رسالة التحديث") },
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            OutlinedTextField(
                value = state.releaseNotesInput,
                onValueChange = onReleaseNotesChange,
                label = { Text("ما الجديد في الإصدار (اختياري)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Button(
                onClick = onSaveConfig,
                enabled = !state.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_save_app_config"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SkylineBlue)
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("حفظ وتعميم الهوية والتحديث", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // 3. Change Admin Secondary Username / Password
    var newUsername by remember(currentAdminUsername) { mutableStateOf(currentAdminUsername) }
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }

    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = SkylineBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "تغيير يوزر وباسورد الأدمن في Firebase",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider()
            OutlinedTextField(
                value = newUsername,
                onValueChange = { newUsername = it },
                label = { Text("يوزر نيم الأدمن") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            OutlinedTextField(
                value = newPw,
                onValueChange = { newPw = it },
                label = { Text("كلمة المرور الجديدة") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            OutlinedTextField(
                value = confirmPw,
                onValueChange = { confirmPw = it },
                label = { Text("تأكيد كلمة المرور الجديدة") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Button(
                onClick = {
                    onUpdateCredentials(newUsername, newPw, confirmPw)
                    newPw = ""
                    confirmPw = ""
                },
                enabled = !state.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("btn_update_admin_credentials"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Text("تحديث بيانات دخول الأدمن", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
