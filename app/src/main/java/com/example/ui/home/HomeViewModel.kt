package com.example.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.analytics.AnalyticsManager
import com.example.data.api.UnifiedSubscription
import com.example.data.repository.SkylineRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HomeUiState(
    val subscription: UnifiedSubscription? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isOfflineMode: Boolean = false,
    val lastUpdatedFormatted: String = ""
)

sealed interface HomeEvent {
    data object SessionExpired : HomeEvent
    data object LoggedOut : HomeEvent
}

class HomeViewModel(
    private val repository: SkylineRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>()
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    init {
        loadData()
    }

    fun loadData(isRefresh: Boolean = false) {
        if (isRefresh) {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null, successMessage = null)
        } else {
            // First load from cache if present for instant rendering
            val cached = repository.sessionManager.getCachedSubscription()
            if (cached != null) {
                _uiState.value = _uiState.value.copy(
                    subscription = cached,
                    isOfflineMode = true,
                    lastUpdatedFormatted = formatTimestamp(cached.lastUpdatedMillis)
                )
            } else {
                _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            }
        }

        viewModelScope.launch {
            val result = repository.fetchSubscriptionData()
            result.onSuccess { unified ->
                AnalyticsManager.logSubscriptionFetch(
                    isRefresh = isRefresh,
                    success = true,
                    isFromCache = unified.isFromCache
                )
                _uiState.value = _uiState.value.copy(
                    subscription = unified,
                    isLoading = false,
                    isRefreshing = false,
                    isOfflineMode = unified.isFromCache,
                    lastUpdatedFormatted = formatTimestamp(unified.lastUpdatedMillis),
                    errorMessage = null
                )
            }.onFailure {
                val cached = repository.sessionManager.getCachedSubscription()
                val resolvedSub = cached ?: _uiState.value.subscription
                AnalyticsManager.logSubscriptionFetch(
                    isRefresh = isRefresh,
                    success = false,
                    isFromCache = resolvedSub != null
                )
                _uiState.value = _uiState.value.copy(
                    subscription = resolvedSub,
                    isLoading = false,
                    isRefreshing = false,
                    isOfflineMode = resolvedSub != null,
                    errorMessage = if (resolvedSub != null) {
                        "تعذر تحديث البيانات من الخادم، يتم عرض آخر بيانات متوفرة"
                    } else {
                        "تعذر تحميل بيانات الاشتراك من الخادم، يرجى التأكد من الاتصال والمحاولة مجدداً"
                    }
                )
            }
        }
    }

    fun redeemPin(pin: String) {
        if (pin.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        AnalyticsManager.logPinRenewalAttempt()
        viewModelScope.launch {
            val result = repository.redeemCode(pin)
            result.onSuccess {
                AnalyticsManager.logPinRenewalResult(success = true)
                val refreshed = repository.fetchSubscriptionData().getOrNull()
                if (refreshed != null) {
                    _uiState.value = _uiState.value.copy(
                        subscription = refreshed,
                        isLoading = false,
                        isRefreshing = false,
                        isOfflineMode = refreshed.isFromCache,
                        lastUpdatedFormatted = formatTimestamp(refreshed.lastUpdatedMillis),
                        successMessage = "تم شحن الكارت بنجاح!"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        successMessage = "تم شحن الكارت بنجاح!"
                    )
                }
                val exp = refreshed?.service?.expiration
                val plan = refreshed?.service?.profileName
                com.example.worker.NotificationScheduler.sendSubscriptionRenewedNotification(
                    context = repository.context,
                    planName = plan,
                    newExpirationDate = exp,
                    dedupExpirationKey = exp
                )
            }.onFailure {
                AnalyticsManager.logPinRenewalResult(success = false)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "فشل شحن الكارت، يرجى التأكد من صحة الرمز المدخل"
                )
            }
        }
    }

    fun logout() {
        AnalyticsManager.logLogout()
        repository.logout()
        viewModelScope.launch {
            _events.emit(HomeEvent.LoggedOut)
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    private fun formatTimestamp(millis: Long): String {
        if (millis <= 0) return ""
        val sdf = SimpleDateFormat("dd MMMM yyyy - hh:mm a", Locale("ar"))
        return sdf.format(Date(millis))
    }
}
