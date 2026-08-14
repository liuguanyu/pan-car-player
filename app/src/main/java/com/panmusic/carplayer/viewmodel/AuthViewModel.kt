package com.panmusic.carplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panmusic.carplayer.repository.AuthRepository
import com.panmusic.carplayer.model.DeviceCodeResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        // 检查登录状态
        _uiState.value = _uiState.value.copy(
            isLoggedIn = authRepository.isLoggedIn()
        )
    }

    /**
     * 获取设备码
     */
    fun requestDeviceCode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = authRepository.requestDeviceCode()
            result.fold(
                onSuccess = { response ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        deviceCode = response.deviceCode,
                        userCode = response.userCode,
                        qrUrl = "${response.verificationUrl}?code=${response.userCode}",
                        isPolling = true
                    )
                    startPolling(response.deviceCode)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            )
        }
    }

    /**
     * 开始轮询 Token
     */
    private fun startPolling(deviceCode: String) {
        viewModelScope.launch {
            val result = authRepository.pollForToken(deviceCode)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isPolling = false,
                        isLoggedIn = true
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isPolling = false,
                        error = e.message
                    )
                }
            )
        }
    }

    /**
     * 登出
     */
    fun logout() {
        authRepository.logout()
        _uiState.value = AuthUiState()
    }
}

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isPolling: Boolean = false,
    val deviceCode: String = "",
    val userCode: String = "",
    val qrUrl: String = "",
    val error: String? = null
)
