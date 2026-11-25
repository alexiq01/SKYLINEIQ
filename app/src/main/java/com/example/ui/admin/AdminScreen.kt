package com.example.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.admin.BroadcastNotification
import com.example.admin.LauncherIconPreset
import com.example.admin.RegisteredUser
import com.example.notifications.FcmNotificationManager
import com.example.ui.components.AppleAmbientBackground
import com.example.ui.components.LiquidGlassCard
import com.example.ui.theme.SkylineBlue
import com.example.ui.theme.SkylineCyan
import com.example.ui.theme.SkylineError
import com.example.ui.theme.SkylineSuccess
import com.example.ui.theme.isAppInDarkTheme
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val isDark = isAppInDarkTheme()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler { onBack() }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { err ->
            snackbarHostState.showSnackbar(err)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                            tint = SkylineBlue,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "لوحة تحكم الأدمن",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("btn_admin_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (state.gateState is AdminGateState.Unlocked) {
                        IconButton(
                            onClick = { viewModel.lockPortal() },
                            modifier = Modifier.testTag("btn_admin_lock")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "قفل اللوحة",
                                tint = SkylineBlue
                            )
                        }
                        IconButton(
                            onClick = { viewModel.signOutGoogle(context) },
                            modifier = Modifier.testTag("btn_admin_signout")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "تسجيل خروج Google",
                                tint = SkylineError
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isDark) Color(0xF2071120) else Color(0xEBFFFFFF)
                )
            )
        }
    ) { innerPadding ->
        AppleAmbientBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            isDark = isDark
        ) {
            val unlocked = state.gateState as? AdminGateState.Unlocked
            if (unlocked == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AdminAuthGateContent(
                        gateState = state.gateState,
                        isAuthBusy = state.isAuthBusy,
                        isSubmitting = state.isSubmitting,
                        isDark = isDark,
                        onGoogleSignIn = { viewModel.startGoogleSignIn(context) },
                        onGoogleSignOut = { viewModel.signOutGoogle(context) },
                        onSyncUserDevice = { viewModel.syncCurrentDeviceUser() },
                        onSetupCredentials = { u, p, c -> viewModel.setupAdminCredentials(u, p, c) },
                        onVerifyLogin = { u, p -> viewModel.verifyAdminLogin(u, p) }
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(
                        selectedTabIndex = state.selectedTab,
                        containerColor = if (isDark) Color(0xCC0B172B) else Color(0xCCFFFFFF),
                        contentColor = SkylineBlue
                    ) {
                        Tab(
                            selected = state.selectedTab == 0,
                            onClick = { viewModel.selectTab(0) },
                            text = { Text("المستخدمون (${state.users.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                            icon = { Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("admin_tab_users")
                        )
                        Tab(
                            selected = state.selectedTab == 1,
                            onClick = { viewModel.selectTab(1) },
                            text = { Text("الإشعارات", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                            icon = { Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("admin_tab_broadcasts")
                        )
                        Tab(
                            selected = state.selectedTab == 2,
                            onClick = { viewModel.selectTab(2) },
                            text = { Text("الهوية والتحديث", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                            icon = { Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("admin_tab_config")
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        when (state.selectedTab) {
                            0 -> AdminUsersTab(
                                adminUsername = unlocked.account.adminUsername,
                                users = state.users,
                                searchQuery = state.userSearchQuery,
                                broadcastsCount = state.broadcasts.size,
                                isSubmitting = state.isSubmitting,
                                isDark = isDark,
                                onSearchChange = viewModel::updateUserSearchQuery,
                                onSyncCurrentDevice = viewModel::syncCurrentDeviceUser
                            )

                            1 -> AdminBroadcastsTab(
                                broadcasts = state.broadcasts,
                                isSubmitting = state.isSubmitting,
                                isDark = isDark,
                                onPublish = viewModel::publishBroadcast,
                                onDelete = viewModel::deleteBroadcast
                            )

                            else -> AdminConfigTab(
                                state = state,
                                currentAdminUsername = unlocked.account.adminUsername,
                                isDark = isDark,
                                onDisplayNameChange = viewModel::updateAppDisplayName,
                                onSubtitleChange = viewModel::updateAppSubtitle,
                                onPresetSelect = viewModel::selectLauncherPreset,
                                onVersionCodeChange = viewModel::updateVersionCodeInput,
                                onVersionNameChange = viewModel::updateVersionNameInput,
                                onUpdateUrlChange = viewModel::updateUpdateUrlInput,
                                onForceUpdateChange = viewModel::setForceUpdate,
                                onUpdateTitleChange = viewModel::updateUpdateTitleInput,
                                onUpdateMessageChange = viewModel::updateUpdateMessageInput,
                                onReleaseNotesChange = viewModel::updateReleaseNotesInput,
                                onSaveConfig = viewModel::saveAppConfig,
                                onUpdateCredentials = viewModel::updateAdminCredentials
                            )
                        }
                        Spacer(modifier = Modifier.height(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminUsersTab(
    adminUsername: String,
    users: List<RegisteredUser>,
    searchQuery: String,
    broadcastsCount: Int,
    isSubmitting: Boolean,
    isDark: Boolean,
    onSearchChange: (String) -> Unit,
    onSyncCurrentDevice: () -> Unit
) {
    val latestCount = users.count { it.appVersionCode >= BuildConfig.VERSION_CODE.toLong() }
    val filteredUsers = remember(users, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) users else users.filter {
            it.displayName.lowercase(Locale.ROOT).contains(q) ||
                it.ispUsername.orEmpty().lowercase(Locale.ROOT).contains(q) ||
                it.userId.lowercase(Locale.ROOT).contains(q)
        }
    }

    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "مرحباً، $adminUsername",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "إحصائيات مباشرة من Firebase Firestore",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onSyncCurrentDevice,
                    enabled = !isSubmitting,
                    modifier = Modifier.testTag("btn_sync_admin_device")
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = "مزامنة جهازي", tint = SkylineBlue)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                KpiBadge(
                    title = "إجمالي المستخدمين",
                    value = users.size.toString(),
                    accent = SkylineBlue,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("kpi_total_users")
                )
                KpiBadge(
                    title = "أحدث إصدار",
                    value = latestCount.toString(),
                    accent = SkylineSuccess,
                    modifier = Modifier.weight(1f)
                )
                KpiBadge(
                    title = "الإشعارات المرسلة",
                    value = broadcastsCount.toString(),
                    accent = SkylineCyan,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        label = { Text("ابحث بالاسم أو يوزر المشترك") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("input_admin_user_search"),
        shape = RoundedCornerShape(14.dp)
    )

    if (filteredUsers.isEmpty()) {
        LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Group, contentDescription = null, tint = SkylineBlue, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (users.isEmpty()) "لا يوجد مستخدمون مسجلون بعد في السحابة" else "لا توجد نتائج مطابقة للبحث",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        filteredUsers.forEach { user ->
            RegisteredUserCard(user = user, isDark = isDark)
        }
    }
}

@Composable
private fun KpiBadge(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RegisteredUserCard(user: RegisteredUser, isDark: Boolean) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US) }
    val timeLabel = user.updatedAt?.toDate()?.let { dateFormat.format(it) } ?: "الآن"
    val preset = LauncherIconPreset.fromKey(user.launcherPreset)

    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SkylineBlue.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = SkylineBlue)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.displayName.ifBlank { "مشترك Skyline" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!user.ispUsername.isNullOrBlank()) {
                    Text(
                        text = "حساب الشبكة: ${user.ispUsername}",
                        fontSize = 12.sp,
                        color = SkylineBlue,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "الإصدار v${user.appVersionName.ifBlank { "1.0" }} (${user.appVersionCode}) • ${preset.launcherLabel}",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = timeLabel,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AdminBroadcastsTab(
    broadcasts: List<BroadcastNotification>,
    isSubmitting: Boolean,
    isDark: Boolean,
    onPublish: (title: String, body: String, category: String) -> Unit,
    onDelete: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(FcmNotificationManager.TYPE_FCM_GENERAL) }

    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = SkylineBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "إرسال إشعار فوري لجميع المستخدمين",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val categories = listOf(
                    FcmNotificationManager.TYPE_FCM_GENERAL to "عام",
                    FcmNotificationManager.TYPE_FCM_OFFER to "عرض",
                    FcmNotificationManager.TYPE_FCM_MAINTENANCE to "صيانة"
                )
                categories.forEach { (key, label) ->
                    FilterChip(
                        selected = category == key,
                        onClick = { category = key },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SkylineBlue.copy(alpha = 0.2f),
                            selectedLabelColor = SkylineBlue
                        )
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(100) },
                label = { Text("عنوان الإشعار") },
                placeholder = { Text("مثال: عرض خاص على باقات الإنترنت") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_broadcast_title"),
                shape = RoundedCornerShape(14.dp)
            )

            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(1000) },
                label = { Text("نص الإشعار") },
                placeholder = { Text("اكتب الرسالة التي ستصل فوراً لكل المستخدمين...") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_broadcast_body"),
                shape = RoundedCornerShape(14.dp)
            )

            Button(
                onClick = {
                    onPublish(title, body, category)
                    if (title.isNotBlank() && body.isNotBlank()) {
                        title = ""
                        body = ""
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("btn_publish_broadcast"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SkylineBlue)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("إرسال للجميع عبر Firebase", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    Text(
        text = "سجل الإشعارات المرسلة (${broadcasts.size})",
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )

    if (broadcasts.isEmpty()) {
        LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
            Text(
                text = "لم يتم إرسال أي إشعارات من اللوحة بعد.",
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(18.dp)
            )
        }
    } else {
        broadcasts.forEach { item ->
            LiquidGlassCard(modifier = Modifier.fillMaxWidth(), isDark = isDark) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val badgeLabel = when (item.category) {
                            FcmNotificationManager.TYPE_FCM_OFFER -> "عروض"
                            FcmNotificationManager.TYPE_FCM_MAINTENANCE -> "صيانة"
                            else -> "عام"
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SkylineBlue.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = badgeLabel,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = SkylineBlue,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = item.title,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.body,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { onDelete(item.id) },
                        modifier = Modifier.testTag("btn_delete_broadcast_${item.id}")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف الإشعار", tint = SkylineError)
                    }
                }
            }
        }
    }
}
