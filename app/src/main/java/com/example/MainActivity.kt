package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.admin.AdminFirebaseRepository
import com.example.analytics.AnalyticsManager
import com.example.data.repository.AutoLoginCheckStatus
import com.example.data.repository.SkylineRepository
import com.example.data.storage.SessionManager
import com.example.security.AppLockManager
import com.example.security.AppLockScreen
import com.example.security.AppSecurityEngine
import com.example.ui.admin.AdminScreen
import com.example.ui.admin.AdminViewModel
import com.example.ui.home.HomeScreen
import com.example.ui.home.HomeViewModel
import com.example.ui.login.LoginScreen
import com.example.ui.login.LoginViewModel
import com.example.ui.splash.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.update.InAppUpdateDialogHost
import com.example.update.UpdateManager
import com.example.worker.NotificationScheduler

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppSecurityEngine.initializeSilently(this)
        AppLockManager.init(applicationContext)
        AppLockManager.onAppLaunched(applicationContext)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLockManager.onAppReturnedToForeground(applicationContext)
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!isChangingConfigurations) {
                    AppLockManager.onAppMovedToBackground()
                }
            }
        })

        com.example.notifications.NotificationInboxManager.init(applicationContext)
        com.example.notifications.FcmNotificationManager.initAndSyncTopics(applicationContext)
        com.example.notifications.FcmNotificationManager.handleLaunchedNotificationIntent(applicationContext, intent)
        com.example.ui.theme.ThemeController.init(this)
        AnalyticsManager.init(applicationContext)
        if (savedInstanceState == null) {
            val hasSession = try {
                SessionManager(applicationContext).hasValidSession()
            } catch (_: Throwable) {
                false
            }
            AnalyticsManager.logAppLaunch(
                hasSession = hasSession,
                isDarkMode = com.example.ui.theme.ThemeController.isDarkModeOverride == true,
                isAppLockEnabled = AppLockManager.lockStateFlow.value.isEnabled
            )
        }
        enableEdgeToEdge()

        // Schedule periodic WorkManager task to monitor subscription expiration dates
        NotificationScheduler.schedulePeriodicSubscriptionCheck(applicationContext)
        NotificationScheduler.schedulePeriodicSupportNotification(applicationContext)
        NotificationScheduler.checkCachedAndScheduleAlerts(applicationContext)

        setContent {
            MyApplicationTheme {
                val currentDensity = LocalDensity.current
                val fontScaleMultiplier = com.example.ui.theme.ThemeController.fontScaleMultiplier

                // Ensure full Arabic RTL support across all screens, and apply user's font scale
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalDensity provides Density(
                        density = currentDensity.density,
                        fontScale = currentDensity.fontScale * fontScaleMultiplier
                    )
                ) {
                    val appContext = LocalContext.current.applicationContext
                    LaunchedEffect(Unit) {
                        com.example.admin.RemoteSyncManager.start(appContext)
                        // Check for updates via Firebase Remote Config (Firestore listener overrides if present)
                        UpdateManager.checkForUpdates(appContext)
                    }

                    val lockState by AppLockManager.lockStateFlow.collectAsState()
                    NotificationPermissionHandler()
                    AppNavigation()
                    if (!lockState.isLocked) {
                        InAppUpdateDialogHost()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.example.notifications.FcmNotificationManager.handleLaunchedNotificationIntent(applicationContext, intent)
    }
}

@Composable
private fun NotificationPermissionHandler() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { /* Notification permission handled */ }
        )
        LaunchedEffect(Unit) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                launcher.launch(permission)
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current.applicationContext
    val navController = rememberNavController()
    val repository = remember { SkylineRepository(context) }
    val lockState by AppLockManager.lockStateFlow.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var launchAutoLoginStatus by remember { mutableStateOf(AutoLoginCheckStatus.IDLE) }

    LaunchedEffect(currentRoute, lockState.isLocked) {
        when {
            lockState.isLocked && currentRoute == "home" ->
                AnalyticsManager.logScreenView("app_lock", "AppLockScreen")
            currentRoute == "splash" ->
                AnalyticsManager.logScreenView("splash", "SplashScreen")
            currentRoute == "login" ->
                AnalyticsManager.logScreenView("login", "LoginScreen")
            currentRoute == "admin" ->
                AnalyticsManager.logScreenView("admin_portal", "AdminScreen")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "splash",
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)
                ) + fadeIn(animationSpec = tween(320))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(220))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth / 3 },
                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(220))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            composable("splash") {
                SplashScreen(
                    hasValidSession = repository.sessionManager.hasValidSession(),
                    onAttemptProviderAutoLogin = { repository.attemptLaunchProviderAutoLogin() },
                    onNavigateNext = { dest, status ->
                        launchAutoLoginStatus = status
                        navController.navigate(dest) {
                            popUpTo("splash") { inclusive = true }
                        }
                    }
                )
            }

            composable("login") {
                val loginViewModel: LoginViewModel = viewModel(
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return LoginViewModel(repository) as T
                        }
                    }
                )

                LoginScreen(
                    viewModel = loginViewModel,
                    launchAutoLoginStatus = launchAutoLoginStatus,
                    onLoginSuccess = {
                        navController.navigate("home") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onOpenHiddenAdmin = {
                        navController.navigate("admin")
                    }
                )
            }

            composable("home") {
                val homeViewModel: HomeViewModel = viewModel(
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return HomeViewModel(repository) as T
                        }
                    }
                )

                HomeScreen(
                    viewModel = homeViewModel,
                    onSessionExpired = {
                        launchAutoLoginStatus = AutoLoginCheckStatus.IDLE
                        navController.navigate("login") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onLogout = {
                        launchAutoLoginStatus = AutoLoginCheckStatus.IDLE
                        AppLockManager.unlockApp(context)
                        navController.navigate("login") {
                            popUpTo("home") { inclusive = true }
                        }
                    }
                )
            }

            composable("admin") {
                val adminRepository = remember { AdminFirebaseRepository(context) }
                val adminViewModel: AdminViewModel = viewModel(
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return AdminViewModel(context, adminRepository) as T
                        }
                    }
                )

                AdminScreen(
                    viewModel = adminViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // Security Gate Overlay: covers subscriber data immediately on launch & background resume
        AnimatedVisibility(
            visible = lockState.isLocked && currentRoute == "home",
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(220))
        ) {
            AppLockScreen(
                onUnlocked = {
                    AppLockManager.unlockApp(context)
                },
                onResetAndLogout = {
                    launchAutoLoginStatus = AutoLoginCheckStatus.IDLE
                    repository.logout()
                    AppLockManager.resetLockOnLogout(context)
                    navController.navigate("login") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            )
        }
    }
}
