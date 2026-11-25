package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.crypto.OpenSslCrypto
import com.example.data.api.ApiClient
import com.example.data.api.EncryptedPayloadRequest
import com.example.data.api.GenericActionResponse
import com.example.data.api.LoginCredentials
import com.example.data.api.RedeemRequest
import com.example.data.api.SkylineApiService
import com.example.data.api.UnifiedSubscription
import com.example.data.storage.SessionManager
import com.example.worker.NotificationScheduler
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.UUID

enum class AutoLoginCheckStatus {
    IDLE,
    CHECKING,
    SUCCESS_PROVIDER,
    NOT_ON_PROVIDER_NETWORK,
    NETWORK_UNAVAILABLE
}

class SkylineRepository(
    val context: Context,
    private val apiService: SkylineApiService = ApiClient.apiService,
    val sessionManager: SessionManager = SessionManager(context)
) {

    private val serializeNullsGson = com.google.gson.GsonBuilder().serializeNulls().create()

    suspend fun login(username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val credentials = LoginCredentials(
                username = username.trim(),
                password = password,
                language = "ar",
                captchaText = null,
                sessionId = UUID.randomUUID().toString()
            )
            val json = serializeNullsGson.toJson(credentials)
            val encryptedPayload = OpenSslCrypto.encrypt(json)
            val request = EncryptedPayloadRequest(payload = encryptedPayload)

            val response = apiService.login(request, "Bearer null")
            if (response.isSuccessful) {
                val body = response.body()
                val token = body?.token
                if (!token.isNullOrBlank() && (body.status == 200 || body.status == 1 || body.status == null)) {
                    sessionManager.saveSession(token = token, username = username.trim(), password = password)
                    return@withContext Result.success(token)
                }

                val msg = body?.message ?: "err_invalid_login"
                // If SAS4 server rejects password login (e.g., error_not_allowed / status -3 when IP/auto-login is enforced),
                // check real SAS4 auth/autoLogin in case user is connected directly to the Skyline ISP network.
                if (msg.equals("error_not_allowed", ignoreCase = true) || body?.status == -3) {
                    try {
                        val autoRes = apiService.autoLogin()
                        val autoBody = autoRes.body()
                        val autoToken = autoBody?.token
                        if (autoRes.isSuccessful && !autoToken.isNullOrBlank() && autoBody.status == 200) {
                            sessionManager.saveSession(token = autoToken, username = username.trim(), password = password)
                            return@withContext Result.success(autoToken)
                        }
                    } catch (_: Exception) {
                        // Fall through and surface error_not_allowed
                    }
                }
                Result.failure(Exception(msg))
            } else {
                val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val parsedMsg = try {
                    if (!errBody.isNullOrBlank()) {
                        ApiClient.gson.fromJson(errBody, com.example.data.api.LoginResponse::class.java)?.message
                    } else null
                } catch (_: Exception) { null }

                if (response.code() == 401 || response.code() == 403 || parsedMsg != null) {
                    Result.failure(Exception(parsedMsg ?: "err_invalid_login"))
                } else {
                    Result.failure(Exception("server_error_${response.code()}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun hasActiveNetworkConnection(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Strict launch-only automatic login when the device is connected directly to the Skyline ISP network.
     * Verifies authentic SAS4 `auth/autoLogin` token response and never fabricates dummy sessions on external networks.
     */
    suspend fun attemptLaunchProviderAutoLogin(timeoutMillis: Long = 4500L): AutoLoginCheckStatus =
        withContext(Dispatchers.IO) {
            if (sessionManager.hasValidSession()) {
                return@withContext AutoLoginCheckStatus.SUCCESS_PROVIDER
            }

            val resultStatus = withTimeoutOrNull(timeoutMillis) {
                try {
                    val autoRes = apiService.autoLogin()
                    val autoBody = autoRes.body()
                    val autoToken = autoBody?.token
                    if (autoRes.isSuccessful &&
                        !autoToken.isNullOrBlank() &&
                        (autoBody.status == 200 || autoBody.status == 1 || autoBody.status == null)
                    ) {
                        val resolvedUsername = try {
                            val userRes = apiService.getUser("Bearer $autoToken")
                            userRes.body()?.data?.username?.takeIf { it.isNotBlank() }
                        } catch (_: Exception) {
                            null
                        } ?: sessionManager.getUsername()?.takeIf { it.isNotBlank() }
                        ?: "مشترك سكايلان"

                        sessionManager.saveSession(
                            token = autoToken,
                            username = resolvedUsername
                        )
                        AutoLoginCheckStatus.SUCCESS_PROVIDER
                    } else {
                        AutoLoginCheckStatus.NOT_ON_PROVIDER_NETWORK
                    }
                } catch (_: Exception) {
                    if (hasActiveNetworkConnection()) {
                        AutoLoginCheckStatus.NOT_ON_PROVIDER_NETWORK
                    } else {
                        AutoLoginCheckStatus.NETWORK_UNAVAILABLE
                    }
                }
            }

            resultStatus ?: if (hasActiveNetworkConnection()) {
                AutoLoginCheckStatus.NOT_ON_PROVIDER_NETWORK
            } else {
                AutoLoginCheckStatus.NETWORK_UNAVAILABLE
            }
        }

    /**
     * Attempts direct ISP provider authentication (SAS4 IP-based `auth/autoLogin` or saved credentials).
     * Never creates dummy/unauthenticated tokens when connected to an external network.
     */
    suspend fun autoLoginWithGps(latitude: Double? = null, longitude: Double? = null): Result<String> = withContext(Dispatchers.IO) {
        // 1. Check direct ISP provider IP-based SAS4 autoLogin first
        try {
            val autoRes = apiService.autoLogin()
            val autoBody = autoRes.body()
            val autoToken = autoBody?.token
            if (autoRes.isSuccessful &&
                !autoToken.isNullOrBlank() &&
                (autoBody.status == 200 || autoBody.status == 1 || autoBody.status == null)
            ) {
                val resolvedUsername = sessionManager.getUsername()?.takeIf { it.isNotBlank() } ?: "مشترك سكايلان"
                sessionManager.saveSession(token = autoToken, username = resolvedUsername)
                return@withContext Result.success(autoToken)
            }
        } catch (_: Exception) {
        }

        // 2. If user already has stored credentials, attempt direct login
        val savedUser = sessionManager.getUsername()
        val savedPass = sessionManager.getPassword()
        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            val savedLoginRes = login(savedUser, savedPass)
            if (savedLoginRes.isSuccess) {
                return@withContext savedLoginRes
            }
        }

        // 3. Check strict JSON token response from provider gateway if reachable
        try {
            val urlBuilder = StringBuilder("http://admin.skylineiq.com/user")
            if (latitude != null && longitude != null) {
                urlBuilder.append("?lat=").append(latitude).append("&lng=").append(longitude)
            }

            val requestBuilder = Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; SkylineApp)")
                .header("Accept", "application/json")

            if (latitude != null && longitude != null) {
                requestBuilder.header("X-Location", "geo:$latitude,$longitude")
                requestBuilder.header("X-Client-Coordinates", "$latitude,$longitude")
            }

            val response = ApiClient.okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val responseBodyStr = response.body?.string().orEmpty().trim()
                if (responseBodyStr.startsWith("{")) {
                    val json = ApiClient.gson.fromJson(responseBodyStr, JsonObject::class.java)
                    val token = if (json.has("token") && !json.get("token").isJsonNull) json.get("token").asString else null
                    val status = if (json.has("status") && !json.get("status").isJsonNull) json.get("status").asInt else 200
                    val username = when {
                        json.has("username") && !json.get("username").isJsonNull -> json.get("username").asString
                        json.has("user") && !json.get("user").isJsonNull -> json.get("user").asString
                        else -> "مشترك سكايلان"
                    }

                    if (!token.isNullOrBlank() && (status == 200 || status == 1)) {
                        sessionManager.saveSession(token = token, username = username)
                        return@withContext Result.success(token)
                    }
                }
            }
            Result.failure(Exception("not_connected_to_provider"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkProviderConnectionAndAutoLogin(): Result<String> = autoLoginWithGps(null, null)

    private suspend fun trySilentTokenRefresh(): String? {
        val savedUser = sessionManager.getUsername()
        val savedPass = sessionManager.getPassword()
        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            val res = login(savedUser, savedPass)
            if (res.isSuccess) {
                return res.getOrNull()
            }
        }
        return try {
            val autoRes = apiService.autoLogin()
            val autoBody = autoRes.body()
            val autoToken = autoBody?.token
            if (autoRes.isSuccessful && !autoToken.isNullOrBlank() && autoBody.status == 200) {
                sessionManager.saveSession(
                    token = autoToken,
                    username = savedUser ?: "مشترك سكايلان",
                    password = savedPass
                )
                autoToken
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isSuccessStatus(status: Int?): Boolean {
        return status == null || status == 200 || status == 1
    }

    private fun isUnauthorizedResponse(httpCode: Int, bodyStatus: Int?): Boolean {
        return httpCode == 401 || bodyStatus == 401
    }

    suspend fun fetchSubscriptionData(): Result<UnifiedSubscription> = withContext(Dispatchers.IO) {
        var token = sessionManager.getToken()
        if (token.isNullOrBlank()) {
            token = trySilentTokenRefresh()
        }
        val previousCached = sessionManager.getCachedSubscription()
        if (token.isNullOrBlank()) {
            return@withContext if (previousCached != null) {
                Result.success(previousCached)
            } else {
                Result.failure(Exception("sync_unavailable"))
            }
        }

        try {
            var authHeader = "Bearer $token"
            var userDeferred = async { apiService.getUser(authHeader) }
            var serviceDeferred = async { apiService.getService(authHeader) }
            var dashboardDeferred = async { apiService.getDashboard(authHeader) }

            var userRes = userDeferred.await()
            var serviceRes = serviceDeferred.await()
            var dashboardRes = dashboardDeferred.await()

            val needsRefresh = isUnauthorizedResponse(userRes.code(), userRes.body()?.status) ||
                isUnauthorizedResponse(serviceRes.code(), serviceRes.body()?.status) ||
                isUnauthorizedResponse(dashboardRes.code(), dashboardRes.body()?.status)

            // If token expired (HTTP 401 or JSON status 401), silently refresh token without logging user out
            if (needsRefresh) {
                val refreshedToken = trySilentTokenRefresh()
                if (!refreshedToken.isNullOrBlank()) {
                    authHeader = "Bearer $refreshedToken"
                    userDeferred = async { apiService.getUser(authHeader) }
                    serviceDeferred = async { apiService.getService(authHeader) }
                    dashboardDeferred = async { apiService.getDashboard(authHeader) }

                    userRes = userDeferred.await()
                    serviceRes = serviceDeferred.await()
                    dashboardRes = dashboardDeferred.await()
                }
            }

            // Never kick user out on 401 — return cached data if server still rejects
            val stillUnauthorized = isUnauthorizedResponse(userRes.code(), userRes.body()?.status) ||
                isUnauthorizedResponse(serviceRes.code(), serviceRes.body()?.status) ||
                isUnauthorizedResponse(dashboardRes.code(), dashboardRes.body()?.status)
            if (stillUnauthorized) {
                return@withContext if (previousCached != null) {
                    Result.success(previousCached)
                } else {
                    Result.failure(Exception("sync_unavailable"))
                }
            }

            val freshUser = if (userRes.isSuccessful && isSuccessStatus(userRes.body()?.status)) userRes.body()?.data else null
            val freshService = if (serviceRes.isSuccessful && isSuccessStatus(serviceRes.body()?.status)) serviceRes.body()?.data else null
            val freshDashboard = if (dashboardRes.isSuccessful && isSuccessStatus(dashboardRes.body()?.status)) dashboardRes.body()?.data else null

            // Protect cached data: never overwrite valid cache with an all-null response
            if (freshUser == null && freshService == null && freshDashboard == null) {
                return@withContext if (previousCached != null) {
                    Result.success(previousCached)
                } else {
                    Result.failure(Exception("sync_empty_response"))
                }
            }

            val mergedUser = freshUser ?: previousCached?.user
            val mergedService = freshService ?: previousCached?.service
            val mergedDashboard = freshDashboard ?: previousCached?.dashboard
            val isPartialFallback = (freshUser == null || freshService == null) && previousCached != null

            val unified = UnifiedSubscription(
                user = mergedUser,
                service = mergedService,
                dashboard = mergedDashboard,
                lastUpdatedMillis = System.currentTimeMillis(),
                isFromCache = isPartialFallback
            )

            // Cache locally for offline resilience
            sessionManager.saveCache(unified)

            // Schedule WorkManager notifications for subscription expiration
            mergedService?.expiration?.let { exp ->
                NotificationScheduler.scheduleSubscriptionAlerts(context, exp)
                NotificationScheduler.schedulePeriodicSubscriptionCheck(context)
                NotificationScheduler.checkAndTriggerRenewalNotificationIfNeeded(context, exp, mergedService.profileName)
            }

            Result.success(unified)
        } catch (e: Exception) {
            // Return cached data if available on network failure
            if (previousCached != null) {
                Result.success(previousCached)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun redeemCode(pin: String): Result<GenericActionResponse> = withContext(Dispatchers.IO) {
        var token = sessionManager.getToken()
        if (token.isNullOrBlank()) {
            token = trySilentTokenRefresh()
        }
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(Exception("unauthorized"))
        }

        try {
            val json = ApiClient.gson.toJson(RedeemRequest(pin = pin.trim()))
            val encryptedRequest = EncryptedPayloadRequest(payload = OpenSslCrypto.encrypt(json))
            var response = apiService.redeem("Bearer $token", encryptedRequest)

            if (isUnauthorizedResponse(response.code(), response.body()?.status)) {
                val refreshedToken = trySilentTokenRefresh()
                if (!refreshedToken.isNullOrBlank()) {
                    response = apiService.redeem("Bearer $refreshedToken", encryptedRequest)
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (isSuccessStatus(body.status)) {
                    Result.success(body)
                } else {
                    Result.failure(Exception(body.message ?: "err_redeem_failed"))
                }
            } else {
                val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val parsedMsg = try {
                    if (!errBody.isNullOrBlank()) {
                        ApiClient.gson.fromJson(errBody, GenericActionResponse::class.java)?.message
                    } else null
                } catch (_: Exception) { null }
                Result.failure(Exception(parsedMsg ?: "err_redeem_failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        try {
            NotificationScheduler.cancelExpirationOneTimeWorks(context)
        } catch (_: Exception) {
        }
        sessionManager.clearSession()
    }
}
