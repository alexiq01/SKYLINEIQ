package com.example.ui.admin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.admin.AdminAccount
import com.example.admin.AdminFirebaseRepository
import com.example.admin.AppBrandingManager
import com.example.admin.AppConfig
import com.example.admin.BroadcastNotification
import com.example.admin.LauncherIconPreset
import com.example.admin.RegisteredUser
import com.example.data.storage.SessionManager
import com.example.update.UpdateManager
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AdminGateState {
    data object Checking : AdminGateState
    data object GoogleSignInRequired : AdminGateState
    data class UnauthorizedGoogleAccount(val email: String) : AdminGateState
    data class SetupRequired(val email: String) : AdminGateState
    data class PasswordPrompt(val email: String) : AdminGateState
    data class Unlocked(val account: AdminAccount) : AdminGateState
}

data class AdminDashboardUiState(
    val gateState: AdminGateState = AdminGateState.Checking,
    val currentUserEmail: String? = null,
    val isAuthBusy: Boolean = false,
    val isSubmitting: Boolean = false,
    val selectedTab: Int = 0, // 0 = Users, 1 = Broadcasts, 2 = Branding & Updates
    val users: List<RegisteredUser> = emptyList(),
    val userSearchQuery: String = "",
    val broadcasts: List<BroadcastNotification> = emptyList(),
    val configLoaded: Boolean = false,
    val appDisplayName: String = "Skyline Users",
    val appSubtitle: String = "SKYLINE INTERNET",
    val launcherPreset: String = LauncherIconPreset.GLASS_BLUE.key,
    val latestVersionCodeInput: String = BuildConfig.VERSION_CODE.toString(),
    val latestVersionNameInput: String = BuildConfig.VERSION_NAME,
    val updateUrlInput: String = "",
    val forceUpdate: Boolean = false,
    val updateTitleInput: String = "تحديث جديد متوفر",
    val updateMessageInput: String = "يتوفر إصدار جديد من تطبيق Skyline Users.",
    val releaseNotesInput: String = "",
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class AdminViewModel(
    private val appContext: Context,
    private val repository: AdminFirebaseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminDashboardUiState())
    val uiState: StateFlow<AdminDashboardUiState> = _uiState.asStateFlow()

    private var adminStreamsJob: Job? = null
    private var configFormInitialized = false
    private var pendingLogin: com.example.admin.PendingAdminLogin? =
        com.example.admin.AdminPortalGateStore.consumePendingLogin()

    init {
        viewModelScope.launch {
            if (repository.currentUser() == null) {
                repository.attemptSilentGoogleSignIn(appContext)
            }
            observeAuth()
        }
    }

    private fun observeAuth() {
        viewModelScope.launch {
            repository.authStateFlow().collect { user ->
                onFirebaseUserChanged(user)
            }
        }
    }

    private fun onFirebaseUserChanged(user: FirebaseUser?) {
        if (user == null) {
            stopAdminStreams()
            _uiState.update {
                it.copy(
                    gateState = AdminGateState.GoogleSignInRequired,
                    currentUserEmail = null,
                    isAuthBusy = false
                )
            }
            return
        }

        val email = user.email.orEmpty()
        if (!repository.isCurrentUserBootstrappedAdmin(user)) {
            pendingLogin = null
            stopAdminStreams()
            _uiState.update {
                it.copy(
                    gateState = AdminGateState.UnauthorizedGoogleAccount(email),
                    currentUserEmail = email,
                    isAuthBusy = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                gateState = AdminGateState.Checking,
                currentUserEmail = email,
                isAuthBusy = false
            )
        }

        viewModelScope.launch {
            val existingUnlocked = (_uiState.value.gateState as? AdminGateState.Unlocked)?.account
            if (existingUnlocked != null && existingUnlocked.uid == user.uid) {
                return@launch
            }
            val result = repository.fetchAdminAccount(user.uid)
            result.onSuccess { account ->
                if (account == null) {
                    val pending = pendingLogin
                    pendingLogin = null
                    if (pending != null && pending.username.length >= 3 && pending.password.length >= 6) {
                        val setupResult = repository.createOrUpdateAdminCredentials(pending.username, pending.password)
                        setupResult.onSuccess { created ->
                            _uiState.update {
                                it.copy(
                                    gateState = AdminGateState.Unlocked(created),
                                    statusMessage = "تم ربط حساب الأدمن وفتح لوحة التحكم بنجاح"
                                )
                            }
                            startAdminStreams()
                            syncCurrentDeviceUserQuietly()
                        }.onFailure {
                            _uiState.update { it.copy(gateState = AdminGateState.SetupRequired(email)) }
                        }
                    } else {
                        _uiState.update {
                            it.copy(gateState = AdminGateState.SetupRequired(email))
                        }
                    }
                } else {
                    val pending = pendingLogin
                    pendingLogin = null
                    if (pending != null) {
                        val verified = repository.verifyAdminSecondaryCredentials(
                            pending.username,
                            pending.password
                        ).getOrDefault(false)
                        if (verified) {
                            _uiState.update {
                                it.copy(
                                    gateState = AdminGateState.Unlocked(account),
                                    statusMessage = "تم تسجيل الدخول إلى لوحة الأدمن"
                                )
                            }
                            startAdminStreams()
                            return@launch
                        }
                    }
                    _uiState.update {
                        it.copy(gateState = AdminGateState.PasswordPrompt(email))
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        gateState = AdminGateState.GoogleSignInRequired,
                        errorMessage = err.localizedMessage ?: "تعذر التحقق من حساب الأدمن"
                    )
                }
            }
        }
    }

    fun startGoogleSignIn(activityContext: Context) {
        _uiState.update { it.copy(isAuthBusy = true, errorMessage = null) }
        repository.onGoogleSignInClicked(
            context = activityContext,
            scope = viewModelScope,
            onAuthSuccess = {
                _uiState.update { it.copy(isAuthBusy = false) }
                // Quietly ensure the signed-in Google account is tracked in /users
                syncCurrentDeviceUserQuietly()
            },
            onAuthError = { msg ->
                _uiState.update { it.copy(isAuthBusy = false, errorMessage = msg) }
            },
            onAuthCancelled = {
                _uiState.update { it.copy(isAuthBusy = false) }
            }
        )
    }

    fun signOutGoogle(activityContext: Context) {
        pendingLogin = null
        stopAdminStreams()
        _uiState.update { it.copy(isAuthBusy = true, errorMessage = null) }
        repository.signOut(activityContext, viewModelScope) {
            _uiState.update {
                it.copy(
                    gateState = AdminGateState.GoogleSignInRequired,
                    currentUserEmail = null,
                    isAuthBusy = false,
                    statusMessage = "تم تسجيل الخروج من حساب Google"
                )
            }
        }
    }

    fun setupAdminCredentials(username: String, password: String, confirmPassword: String) {
        val cleanUser = username.trim()
        if (cleanUser.length < 3) {
            _uiState.update { it.copy(errorMessage = "اسم مستخدم الأدمن يجب أن يكون 3 أحرف على الأقل") }
            return
        }
        if (password.length < 6) {
            _uiState.update { it.copy(errorMessage = "كلمة المرور يجب أن تكون 6 أحرف على الأقل") }
            return
        }
        if (password != confirmPassword) {
            _uiState.update { it.copy(errorMessage = "كلمتا المرور غير متطابقتين") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val result = repository.createOrUpdateAdminCredentials(cleanUser, password)
            result.onSuccess { account ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        gateState = AdminGateState.Unlocked(account),
                        statusMessage = "تم إعداد حساب الأدمن وفتحه بنجاح"
                    )
                }
                startAdminStreams()
                syncCurrentDeviceUserQuietly()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.localizedMessage ?: "فشل حفظ بيانات الأدمن"
                    )
                }
            }
        }
    }

    fun verifyAdminLogin(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال اسم المستخدم وكلمة المرور") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val verifiedResult = repository.verifyAdminSecondaryCredentials(username, password)
            verifiedResult.onSuccess { matches ->
                if (matches) {
                    val uid = repository.currentUser()?.uid.orEmpty()
                    val account = repository.fetchAdminAccount(uid).getOrNull()
                        ?: AdminAccount(
                            id = uid,
                            uid = uid,
                            email = repository.currentUser()?.email.orEmpty(),
                            adminUsername = username.trim()
                        )
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            gateState = AdminGateState.Unlocked(account),
                            statusMessage = "مرحباً بك في لوحة تحكم الأدمن"
                        )
                    }
                    startAdminStreams()
                } else {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = "اسم المستخدم أو كلمة المرور غير صحيحة"
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.localizedMessage ?: "تعذر التحقق من بيانات الأدمن"
                    )
                }
            }
        }
    }

    fun updateAdminCredentials(username: String, password: String, confirmPassword: String) {
        val cleanUser = username.trim()
        if (cleanUser.length < 3) {
            _uiState.update { it.copy(errorMessage = "اسم المستخدم يجب أن يكون 3 أحرف على الأقل") }
            return
        }
        if (password.length < 6) {
            _uiState.update { it.copy(errorMessage = "كلمة المرور يجب أن تكون 6 أحرف على الأقل") }
            return
        }
        if (password != confirmPassword) {
            _uiState.update { it.copy(errorMessage = "كلمتا المرور غير متطابقتين") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val result = repository.createOrUpdateAdminCredentials(cleanUser, password)
            result.onSuccess { account ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        gateState = AdminGateState.Unlocked(account),
                        statusMessage = "تم تحديث اسم المستخدم وكلمة المرور بنجاح"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.localizedMessage ?: "فشل تحديث بيانات الدخول"
                    )
                }
            }
        }
    }

    fun lockPortal() {
        stopAdminStreams()
        val email = repository.currentUser()?.email.orEmpty()
        _uiState.update {
            it.copy(
                gateState = if (email.isNotBlank()) AdminGateState.PasswordPrompt(email) else AdminGateState.GoogleSignInRequired,
                statusMessage = "تم قفل لوحة الأدمن"
            )
        }
    }

    fun selectTab(tabIndex: Int) {
        _uiState.update { it.copy(selectedTab = tabIndex) }
    }

    fun updateUserSearchQuery(query: String) {
        _uiState.update { it.copy(userSearchQuery = query) }
    }

    fun updateAppDisplayName(value: String) {
        _uiState.update { it.copy(appDisplayName = value.take(30)) }
    }

    fun updateAppSubtitle(value: String) {
        _uiState.update { it.copy(appSubtitle = value.take(50)) }
    }

    fun selectLauncherPreset(presetKey: String) {
        _uiState.update { it.copy(launcherPreset = presetKey) }
    }

    fun updateVersionCodeInput(value: String) {
        _uiState.update { it.copy(latestVersionCodeInput = value.filter { ch -> ch.isDigit() }) }
    }

    fun updateVersionNameInput(value: String) {
        _uiState.update { it.copy(latestVersionNameInput = value.take(24)) }
    }

    fun updateUpdateUrlInput(value: String) {
        _uiState.update { it.copy(updateUrlInput = value.take(500)) }
    }

    fun setForceUpdate(enabled: Boolean) {
        _uiState.update { it.copy(forceUpdate = enabled) }
    }

    fun updateUpdateTitleInput(value: String) {
        _uiState.update { it.copy(updateTitleInput = value.take(80)) }
    }

    fun updateUpdateMessageInput(value: String) {
        _uiState.update { it.copy(updateMessageInput = value.take(300)) }
    }

    fun updateReleaseNotesInput(value: String) {
        _uiState.update { it.copy(releaseNotesInput = value.take(500)) }
    }

    fun saveAppConfig() {
        val current = _uiState.value
        val displayName = current.appDisplayName.trim()
        val versionCode = current.latestVersionCodeInput.trim().toLongOrNull()
        val versionName = current.latestVersionNameInput.trim()

        if (displayName.isEmpty() || displayName.length > 30) {
            _uiState.update { it.copy(errorMessage = "اسم التطبيق يجب أن يكون بين 1 و 30 حرفاً") }
            return
        }
        if (versionCode == null || versionCode < 1L) {
            _uiState.update { it.copy(errorMessage = "رقم الإصدار (Version Code) يجب أن يكون رقماً صحيحاً") }
            return
        }
        if (versionName.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال اسم الإصدار (مثال: 1.0.1)") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val result = repository.saveAppConfig(
                appDisplayName = displayName,
                appSubtitle = current.appSubtitle,
                launcherPreset = current.launcherPreset,
                latestVersionCode = versionCode,
                latestVersionName = versionName,
                updateUrl = current.updateUrlInput,
                forceUpdate = current.forceUpdate,
                updateTitle = current.updateTitleInput,
                updateMessage = current.updateMessageInput,
                releaseNotes = current.releaseNotesInput
            )
            result.onSuccess {
                val applied = AppConfig(
                    appDisplayName = displayName,
                    appSubtitle = current.appSubtitle.trim(),
                    launcherPreset = current.launcherPreset,
                    latestVersionCode = versionCode,
                    latestVersionName = versionName,
                    updateUrl = current.updateUrlInput.trim(),
                    forceUpdate = current.forceUpdate,
                    updateTitle = current.updateTitleInput.trim().ifEmpty { "تحديث جديد متوفر" },
                    updateMessage = current.updateMessageInput.trim().ifEmpty { "يتوفر إصدار جديد من التطبيق." },
                    releaseNotes = current.releaseNotesInput.trim()
                )
                AppBrandingManager.applyRemoteConfig(appContext, applied, applyLauncherSwitch = true)
                UpdateManager.applyFirestoreUpdateConfig(applied)
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        statusMessage = "تم حفظ وتعميم الهوية والتحديثات لجميع المستخدمين"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.localizedMessage ?: "فشل حفظ الإعدادات السحابية"
                    )
                }
            }
        }
    }

    fun publishBroadcast(title: String, body: String, category: String) {
        if (title.trim().isEmpty() || body.trim().isEmpty()) {
            _uiState.update { it.copy(errorMessage = "يرجى كتابة عنوان ونص الإشعار") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val result = repository.publishBroadcast(title, body, category)
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        statusMessage = "تم إرسال الإشعار الفوري إلى جميع المستخدمين عبر Firebase"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.localizedMessage ?: "فشل إرسال الإشعار"
                    )
                }
            }
        }
    }

    fun deleteBroadcast(broadcastId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val result = repository.deleteBroadcast(broadcastId)
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        statusMessage = "تم حذف الإشعار"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.localizedMessage ?: "تعذر حذف الإشعار"
                    )
                }
            }
        }
    }

    fun syncCurrentDeviceUser() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val ispUsername = try {
                SessionManager(appContext).getUsername()
            } catch (_: Throwable) {
                null
            }
            val result = repository.syncCurrentUserProfile(
                ispUsername = ispUsername,
                launcherPreset = AppBrandingManager.brandingFlow.value.preset.key
            )
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        statusMessage = "تمت مزامنة جهازك في سجل المستخدمين السحابي"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = err.localizedMessage ?: "فشلت المزامنة"
                    )
                }
            }
        }
    }

    private fun syncCurrentDeviceUserQuietly() {
        viewModelScope.launch {
            val ispUsername = try {
                SessionManager(appContext).getUsername()
            } catch (_: Throwable) {
                null
            }
            repository.syncCurrentUserProfile(
                ispUsername = ispUsername,
                launcherPreset = AppBrandingManager.brandingFlow.value.preset.key
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    private fun startAdminStreams() {
        if (adminStreamsJob?.isActive == true) return
        adminStreamsJob = viewModelScope.launch {
            launch {
                repository.observeUsersForAdmin()
                    .catch { error ->
                        val fsErr = unwrapFirestoreError(error)
                        if (fsErr == null && error is CancellationException) throw error
                        _uiState.update {
                            it.copy(errorMessage = "تعذر تحميل قائمة المستخدمين: ${fsErr?.message ?: error.message}")
                        }
                    }
                    .collect { users ->
                        _uiState.update { it.copy(users = users) }
                    }
            }

            launch {
                repository.observeAllBroadcastsForAdmin()
                    .catch { error ->
                        val fsErr = unwrapFirestoreError(error)
                        if (fsErr == null && error is CancellationException) throw error
                        _uiState.update {
                            it.copy(errorMessage = "تعذر تحميل الإشعارات: ${fsErr?.message ?: error.message}")
                        }
                    }
                    .collect { broadcasts ->
                        _uiState.update { it.copy(broadcasts = broadcasts) }
                    }
            }

            launch {
                repository.observeAppConfig()
                    .catch { error ->
                        val fsErr = unwrapFirestoreError(error)
                        if (fsErr == null && error is CancellationException) throw error
                    }
                    .collect { config ->
                        if (config != null && !configFormInitialized) {
                            configFormInitialized = true
                            _uiState.update {
                                it.copy(
                                    configLoaded = true,
                                    appDisplayName = config.appDisplayName,
                                    appSubtitle = config.appSubtitle,
                                    launcherPreset = config.launcherPreset,
                                    latestVersionCodeInput = config.latestVersionCode.toString(),
                                    latestVersionNameInput = config.latestVersionName,
                                    updateUrlInput = config.updateUrl,
                                    forceUpdate = config.forceUpdate,
                                    updateTitleInput = config.updateTitle,
                                    updateMessageInput = config.updateMessage,
                                    releaseNotesInput = config.releaseNotes
                                )
                            }
                        } else if (!configFormInitialized) {
                            configFormInitialized = true
                            val branding = AppBrandingManager.brandingFlow.value
                            _uiState.update {
                                it.copy(
                                    configLoaded = true,
                                    appDisplayName = branding.appDisplayName,
                                    appSubtitle = branding.appSubtitle,
                                    launcherPreset = branding.preset.key
                                )
                            }
                        }
                    }
            }
        }
    }

    private fun stopAdminStreams() {
        adminStreamsJob?.cancel()
        adminStreamsJob = null
        configFormInitialized = false
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? {
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
    }
}
