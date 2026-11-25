package com.example.admin

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.BuildConfig
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Repository backing Firebase Auth (Google Sign-In via Jetpack Credential Manager)
 * and Firestore enterprise database operations for Admin management, user counting,
 * instant broadcasts, remote branding, and app update configuration.
 */
class AdminFirebaseRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val appContext: Context? = null
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth,
        appContext = context.applicationContext ?: context
    )

    companion object {
        private const val TAG = "AdminFirebaseRepo"
        const val BOOTSTRAPPED_ADMIN_EMAIL = "yassindevai01@gmail.com"
        const val COLLECTION_ADMINS = "admins"
        const val COLLECTION_USERS = "users"
        const val COLLECTION_APP_CONFIG = "app_config"
        const val DOC_APP_CONFIG_MAIN = "main"
        const val COLLECTION_BROADCASTS = "broadcasts"
    }

    fun authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun currentUser(): FirebaseUser? = auth.currentUser

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("يجب تسجيل الدخول بحساب Google أولاً.")
    }

    fun isCurrentUserBootstrappedAdmin(user: FirebaseUser? = auth.currentUser): Boolean {
        if (user == null) return false
        return user.isEmailVerified && user.email.equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
    }

    suspend fun attemptSilentGoogleSignIn(context: Context): Boolean {
        if (auth.currentUser != null) return true
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            return false
        }
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        return try {
            val cred = credentialManager.getCredential(context, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        scope: CoroutineScope,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: () -> Unit
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onAuthError("إعدادات Google Sign-In غير مكتملة (default_web_client_id مفقود)")
            return
        }
        val activity = context as? Activity
        if (activity == null) {
            onAuthError("تعذر فتح نافذة تسجيل دخول Google")
            return
        }
        val credentialManager = CredentialManager.create(context)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val cred = credentialManager.getCredential(activity, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    onAuthSuccess()
                } else {
                    onAuthError("نوع بيانات الاعتماد غير متوافق")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e("Auth", "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "فشل تسجيل الدخول بحساب Google")
            }
        }
    }

    fun signOut(context: Context, scope: CoroutineScope, onSignOutComplete: () -> Unit = {}) {
        val credentialManager = CredentialManager.create(context)
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w("Auth", "Failed to clear credential state: ${e.message}")
                } finally {
                    auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }

    suspend fun fetchAdminAccount(uid: String): Result<AdminAccount?> {
        val path = "$COLLECTION_ADMINS/$uid"
        return try {
            require(uid.isNotBlank()) { "Admin UID must not be blank" }
            val snapshot = db.collection(COLLECTION_ADMINS).document(uid).get().await()
            val account = if (snapshot.exists()) {
                snapshot.toObject(AdminAccount::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else {
                null
            }
            Result.success(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun createOrUpdateAdminCredentials(
        adminUsername: String,
        rawPassword: String
    ): Result<AdminAccount> {
        val user = auth.currentUser
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول بحساب Google للأدمن أولاً"))
        val uid = user.uid
        val email = user.email?.trim().orEmpty()
        val cleanUsername = adminUsername.trim()
        val path = "$COLLECTION_ADMINS/$uid"

        return try {
            require(cleanUsername.length in 3..40) { "اسم المستخدم يجب أن يكون بين 3 و 40 حرفاً" }
            require(rawPassword.length >= 6) { "كلمة المرور يجب أن لا تقل عن 6 أحرف" }
            require(email.length >= 3) { "البريد الإلكتروني للحساب غير متوفر" }

            val salt = AdminPasswordHasher.generateSaltHex()
            val hash = AdminPasswordHasher.hashPassword(salt, rawPassword)
            val docRef = db.collection(COLLECTION_ADMINS).document(uid)
            val existing = docRef.get().await()

            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "email" to email,
                        "adminUsername" to cleanUsername,
                        "passwordSalt" to salt,
                        "passwordHash" to hash,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "uid" to uid,
                        "email" to email,
                        "adminUsername" to cleanUsername,
                        "passwordSalt" to salt,
                        "passwordHash" to hash,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val updatedSnap = docRef.get().await()
            val saved = updatedSnap.toObject(
                AdminAccount::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: AdminAccount(
                id = uid,
                uid = uid,
                email = email,
                adminUsername = cleanUsername,
                passwordSalt = salt,
                passwordHash = hash
            )
            appContext?.let { ctx ->
                AdminPortalGateStore.saveLocalVerifier(
                    context = ctx,
                    adminUsername = saved.adminUsername,
                    passwordSalt = saved.passwordSalt,
                    passwordHash = saved.passwordHash
                )
            }
            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun verifyAdminSecondaryCredentials(
        adminUsername: String,
        rawPassword: String
    ): Result<Boolean> {
        val uid = auth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول بحساب Google أولاً"))
        val accountResult = fetchAdminAccount(uid)
        val account = accountResult.getOrElse { return Result.failure(it) }
            ?: return Result.success(false)

        val usernameMatches = account.adminUsername.equals(adminUsername.trim(), ignoreCase = false)
        val passwordMatches = AdminPasswordHasher.verifyPassword(
            saltHex = account.passwordSalt,
            expectedHashHex = account.passwordHash,
            rawPassword = rawPassword
        )
        if (usernameMatches && passwordMatches) {
            appContext?.let { ctx ->
                AdminPortalGateStore.saveLocalVerifier(
                    context = ctx,
                    adminUsername = account.adminUsername,
                    passwordSalt = account.passwordSalt,
                    passwordHash = account.passwordHash
                )
            }
        }
        return Result.success(usernameMatches && passwordMatches)
    }

    suspend fun syncCurrentUserProfile(
        ispUsername: String?,
        launcherPreset: String
    ): Result<Unit> {
        val user = auth.currentUser
            ?: return Result.failure(IllegalStateException("المستخدم غير مسجل الدخول بحساب Google"))
        val uid = user.uid
        val path = "$COLLECTION_USERS/$uid"
        return try {
            val cleanDisplay = (user.displayName ?: user.email?.substringBefore("@") ?: "مشترك Skyline")
                .trim().take(80).ifEmpty { "مشترك Skyline" }
            val cleanIsp = ispUsername?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
            val safePreset = LauncherIconPreset.fromKey(launcherPreset).key
            val docRef = db.collection(COLLECTION_USERS).document(uid)
            val existing = docRef.get().await()

            if (existing.exists()) {
                val updateMap = mutableMapOf<String, Any>(
                    "displayName" to cleanDisplay,
                    "appVersionName" to BuildConfig.VERSION_NAME.take(24).ifEmpty { "1.0.0" },
                    "appVersionCode" to BuildConfig.VERSION_CODE.toLong().coerceAtLeast(1L),
                    "launcherPreset" to safePreset,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (cleanIsp != null) {
                    updateMap["ispUsername"] = cleanIsp
                }
                docRef.update(updateMap).await()
            } else {
                val createMap = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "displayName" to cleanDisplay,
                    "appVersionName" to BuildConfig.VERSION_NAME.take(24).ifEmpty { "1.0.0" },
                    "appVersionCode" to BuildConfig.VERSION_CODE.toLong().coerceAtLeast(1L),
                    "launcherPreset" to safePreset,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (cleanIsp != null) {
                    createMap["ispUsername"] = cleanIsp
                }
                docRef.set(createMap).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    fun observeUsersForAdmin(): Flow<List<RegisteredUser>> {
        val path = COLLECTION_USERS
        return db.collection(COLLECTION_USERS)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    RegisteredUser::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    fun observeAppConfig(): Flow<AppConfig?> {
        val docRef = db.collection(COLLECTION_APP_CONFIG).document(DOC_APP_CONFIG_MAIN)
        return docRef.snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(AppConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                } else {
                    null
                }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null) {
                    handleFirestoreError(exceptionToLog, OperationType.GET, docRef.path)
                }
                throw error
            }
    }

    suspend fun getAppConfig(): Result<AppConfig?> {
        val docRef = db.collection(COLLECTION_APP_CONFIG).document(DOC_APP_CONFIG_MAIN)
        return try {
            val snap = docRef.get().await()
            val config = if (snap.exists()) {
                snap.toObject(AppConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            } else {
                null
            }
            Result.success(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun saveAppConfig(
        appDisplayName: String,
        appSubtitle: String,
        launcherPreset: String,
        latestVersionCode: Long,
        latestVersionName: String,
        updateUrl: String,
        forceUpdate: Boolean,
        updateTitle: String,
        updateMessage: String,
        releaseNotes: String
    ): Result<Unit> {
        val docRef = db.collection(COLLECTION_APP_CONFIG).document(DOC_APP_CONFIG_MAIN)
        return try {
            val uid = requireUserId()
            val cleanDisplayName = appDisplayName.trim()
            val cleanSubtitle = appSubtitle.trim()
            val cleanVersionName = latestVersionName.trim()
            val cleanUpdateUrl = updateUrl.trim()
            val cleanUpdateTitle = updateTitle.trim().ifEmpty { "تحديث جديد متوفر" }
            val cleanUpdateMessage = updateMessage.trim().ifEmpty { "يتوفر إصدار جديد من التطبيق." }
            val cleanReleaseNotes = releaseNotes.trim()
            val safePreset = LauncherIconPreset.fromKey(launcherPreset).key

            require(cleanDisplayName.length in 1..30) { "اسم التطبيق يجب أن يكون بين 1 و 30 حرفاً" }
            require(cleanSubtitle.length <= 50) { "العنوان الفرعي يجب ألا يتجاوز 50 حرفاً" }
            require(latestVersionCode in 1L..1_000_000L) { "رقم الإصدار غير صالح" }
            require(cleanVersionName.length in 1..24) { "اسم الإصدار يجب أن يكون بين 1 و 24 حرفاً" }
            require(cleanUpdateUrl.length <= 500) { "رابط التحديث طويل جداً" }
            require(cleanUpdateTitle.length in 1..80) { "عنوان التحديث طويل جداً" }
            require(cleanUpdateMessage.length in 1..300) { "نص رسالة التحديث طويل جداً" }
            require(cleanReleaseNotes.length <= 500) { "ملاحظات الإصدار طويلة جداً" }

            val existing = docRef.get().await()
            val payload = mutableMapOf<String, Any>(
                "appDisplayName" to cleanDisplayName,
                "appSubtitle" to cleanSubtitle,
                "launcherPreset" to safePreset,
                "latestVersionCode" to latestVersionCode,
                "latestVersionName" to cleanVersionName,
                "updateUrl" to cleanUpdateUrl,
                "forceUpdate" to forceUpdate,
                "updateTitle" to cleanUpdateTitle.take(80),
                "updateMessage" to cleanUpdateMessage.take(300),
                "releaseNotes" to cleanReleaseNotes.take(500),
                "updatedByUid" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (existing.exists()) {
                docRef.update(payload).await()
            } else {
                payload["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    fun observePublishedBroadcasts(): Flow<List<BroadcastNotification>> {
        val path = COLLECTION_BROADCASTS
        return db.collection(COLLECTION_BROADCASTS)
            .whereEqualTo("published", true)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    BroadcastNotification::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    fun observeAllBroadcastsForAdmin(): Flow<List<BroadcastNotification>> {
        val path = COLLECTION_BROADCASTS
        return db.collection(COLLECTION_BROADCASTS)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    BroadcastNotification::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val exceptionToLog = firestoreError ?: (error as? Exception)
                if (exceptionToLog != null) {
                    handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun publishBroadcast(
        title: String,
        body: String,
        category: String
    ): Result<Unit> {
        var docPath = COLLECTION_BROADCASTS
        return try {
            val uid = requireUserId()
            val cleanTitle = title.trim()
            val cleanBody = body.trim()
            val safeCategory = if (category in setOf("fcm_general", "fcm_offer", "fcm_maintenance")) {
                category
            } else {
                "fcm_general"
            }

            require(cleanTitle.length in 1..100) { "عنوان الإشعار يجب أن يكون بين 1 و 100 حرف" }
            require(cleanBody.length in 1..1000) { "نص الإشعار يجب أن يكون بين 1 و 1000 حرف" }

            val docRef = db.collection(COLLECTION_BROADCASTS).document()
            docPath = docRef.path
            docRef.set(
                mapOf(
                    "title" to cleanTitle,
                    "body" to cleanBody,
                    "category" to safeCategory,
                    "published" to true,
                    "senderUid" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteBroadcast(broadcastId: String): Result<Unit> {
        val docRef = db.collection(COLLECTION_BROADCASTS).document(broadcastId)
        return try {
            requireUserId()
            require(broadcastId.isNotBlank()) { "معرّف الإشعار غير صالح" }
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }
}
