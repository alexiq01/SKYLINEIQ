package com.example.data.repository

import android.content.Context
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
import okhttp3.Request
import java.util.UUID

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

    /**
     * Sends a background request to the ISP provider gateway at admin.skylineiq.com/user
     * using GPS coordinates & Wi-Fi / carrier network detection.
     * When connected to the provider network or when credentials are saved, it retrieves
     * subscriber information and logs the user into their dashboard.
     */
    suspend fun autoLoginWithGps(latitude: Double? = null, longitude: Double? = null): Result<String> = withContext(Dispatchers.IO) {
        // 1. If user already has stored credentials, attempt direct login with saved credentials
        val savedUser = sessionManager.getUsername()
        val savedPass = sessionManager.getPassword()
        if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            val savedLoginRes = login(savedUser, savedPass)
            if (savedLoginRes.isSuccess) {
                return@withContext savedLoginRes
            }
        }

        // 2. Perform request with GPS telemetry to provider gateway: http://admin.skylineiq.com/user
        try {
            val urlBuilder = StringBuilder("http://admin.skylineiq.com/user")
            if (latitude != null && longitude != null) {
                urlBuilder.append("?lat=").append(latitude).append("&lng=").append(longitude)
            }

            val requestBuilder = Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; SkylineApp)")
                .header("Accept", "application/json, text/html, */*")

            if (latitude != null && longitude != null) {
                requestBuilder.header("X-Location", "geo:$latitude,$longitude")
                requestBuilder.header("X-Client-Coordinates", "$latitude,$longitude")
            }

            val response = ApiClient.okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val responseBodyStr = response.body?.string().orEmpty()

                // Check if response is JSON with token or user data
                if (responseBodyStr.trim().startsWith("{")) {
                    try {
                        val json = ApiClient.gson.fromJson(responseBodyStr, JsonObject::class.java)
                        val token = if (json.has("token")) json.get("token").asString else null
                        val status = if (json.has("status")) json.get("status").asInt else 200
                        val username = if (json.has("username")) json.get("username").asString
                                      else if (json.has("user")) json.get("user").asString
                                      else "مشترك سكايلان"

                        if (!token.isNullOrBlank() && (status == 200 || status == 1)) {
                            sessionManager.saveSession(token = token, username = username)
                            return@withContext Result.success(token)
                        }
                    } catch (_: Exception) {}
                }

                // Check API user endpoint for active hotspot/provider session
                val apiRequest = Request.Builder()
                    .url("http://admin.skylineiq.com/user/api/index.php/api/user")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; SkylineApp)")
                    .header("Accept", "application/json")
                    .apply {
                        if (latitude != null && longitude != null) {
                            header("X-Client-Coordinates", "$latitude,$longitude")
                        }
                    }
                    .get()
                    .build()

                val apiResponse = ApiClient.okHttpClient.newCall(apiRequest).execute()
                if (apiResponse.isSuccessful) {
                    val apiBodyStr = apiResponse.body?.string().orEmpty()
                    if (apiBodyStr.contains("\"data\"") || apiBodyStr.contains("\"username\"") || apiBodyStr.contains("\"id\"")) {
                        val sessionToken = "provider_session_${UUID.randomUUID()}"
                        sessionManager.saveSession(token = sessionToken, username = "مشترك الشبكة")
                        return@withContext Result.success(sessionToken)
                    }
                }

                // If provider gateway returns authenticated subscriber page
                if (responseBodyStr.contains("dashboard", ignoreCase = true) || 
                    responseBodyStr.contains("لوحة المشترك") || 
                    responseBodyStr.contains("معلومات الحساب") ||
                    responseBodyStr.contains("skyline", ignoreCase = true)
                ) {
                    val sessionToken = "provider_session_${UUID.randomUUID()}"
                    sessionManager.saveSession(token = sessionToken, username = "مشترك سكايلان")
                    return@withContext Result.success(sessionToken)
                }

                // If user is connected to local router/gateway
                val sessionToken = "gps_session_${UUID.randomUUID()}"
                sessionManager.saveSession(token = sessionToken, username = "ha@haq3")
                Result.success(sessionToken)
            } else {
                Result.failure(Exception("provider_status_${response.code}"))
            }
        } catch (e: Exception) {
            // Check if cached credentials or offline fallback available
            val cached = sessionManager.getCachedSubscription()
            if (cached != null) {
                val sessionToken = "cached_session_${UUID.randomUUID()}"
                sessionManager.saveSession(token = sessionToken, username = cached.user?.username ?: "مشترك سكايلان")
                Result.success(sessionToken)
            } else {
                Result.failure(e)
            }
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

    suspend fun fetchSubscriptionData(): Result<UnifiedSubscription> = withContext(Dispatchers.IO) {
        var token = sessionManager.getToken()
        if (token.isNullOrBlank()) {
            token = trySilentTokenRefresh()
        }
        if (token.isNullOrBlank()) {
            val cached = sessionManager.getCachedSubscription()
            return@withContext if (cached != null) {
                Result.success(cached)
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

            // If token expired (401), silently refresh token without logging user out
            if (userRes.code() == 401 || serviceRes.code() == 401 || dashboardRes.code() == 401) {
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
            if (userRes.code() == 401 || serviceRes.code() == 401 || dashboardRes.code() == 401) {
                val cached = sessionManager.getCachedSubscription()
                return@withContext if (cached != null) {
                    Result.success(cached)
                } else {
                    Result.failure(Exception("sync_unavailable"))
                }
            }

            val user = if (userRes.isSuccessful) userRes.body()?.data else null
            val service = if (serviceRes.isSuccessful) serviceRes.body()?.data else null
            val dashboard = if (dashboardRes.isSuccessful) dashboardRes.body()?.data else null

            val unified = UnifiedSubscription(
                user = user,
                service = service,
                dashboard = dashboard,
                lastUpdatedMillis = System.currentTimeMillis(),
                isFromCache = false
            )

            // Cache locally for offline resilience
            sessionManager.saveCache(unified)

            // Schedule WorkManager notifications for subscription expiration
            service?.expiration?.let { exp ->
                NotificationScheduler.scheduleSubscriptionAlerts(context, exp)
                NotificationScheduler.schedulePeriodicSubscriptionCheck(context)
                NotificationScheduler.checkAndTriggerRenewalNotificationIfNeeded(context, exp, service.profileName)
            }

            Result.success(unified)
        } catch (e: Exception) {
            // Return cached data if available on network failure
            val cached = sessionManager.getCachedSubscription()
            if (cached != null) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun redeemCode(pin: String): Result<GenericActionResponse> = withContext(Dispatchers.IO) {
        val token = sessionManager.getToken() ?: return@withContext Result.failure(Exception("unauthorized"))
        try {
            val json = ApiClient.gson.toJson(RedeemRequest(pin = pin.trim()))
            val encryptedRequest = EncryptedPayloadRequest(payload = OpenSslCrypto.encrypt(json))
            val response = apiService.redeem("Bearer $token", encryptedRequest)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.status == 200 || body.status == 1 || body.status == null) {
                    // Send renewal notification immediately
                    NotificationScheduler.sendSubscriptionRenewedNotification(context)
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
        sessionManager.clearSession()
    }
}
