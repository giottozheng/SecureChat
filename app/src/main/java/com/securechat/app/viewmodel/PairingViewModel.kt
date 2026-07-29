// Pairing approval ViewModel — 管理待审批的桌面端配对请求，并在批准后共享历史
package com.securechat.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.repository.AccountRepository
import com.securechat.app.repository.MessageRepository
import com.securechat.app.repository.PendingPairing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PairingUiState(
    val pending: List<PendingPairing> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val justApprovedCode: String? = null,
    // 历史共享（P1）进度
    val historySharing: Boolean = false,
    val historySharedCount: Int? = null,
    val historyShareError: String? = null
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState

    fun loadPending() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            accountRepository.getPendingPairings()
                .onSuccess { list ->
                    _uiState.update { it.copy(isLoading = false, pending = list) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载失败") }
                }
        }
    }

    fun approve(code: String) {
        if (code.isBlank()) return
        // 在启动协程前取出 deviceId（批准后需要用它定向共享历史）
        val deviceId = _uiState.value.pending.find { it.code == code }?.deviceId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            accountRepository.approvePairing(code)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, justApprovedCode = code) }
                    loadPending() // 刷新列表
                    // 历史共享（P1）：批准后立即把本机历史用该桌面设备公钥重加密并上传
                    if (!deviceId.isNullOrBlank()) {
                        shareHistoryToDevice(deviceId)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "批准失败") }
                }
        }
    }

    /**
     * 把本机历史推送到目标桌面设备。失败不阻断配对本身，仅记录错误。
     */
    private fun shareHistoryToDevice(deviceId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(historySharing = true, historyShareError = null, historySharedCount = null) }
            messageRepository.shareAllHistoryWithDesktop(deviceId)
                .onSuccess { count ->
                    _uiState.update { it.copy(historySharing = false, historySharedCount = count) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(historySharing = false, historyShareError = e.message ?: "历史共享失败") }
                }
        }
    }

    fun clearApproved() {
        _uiState.update { it.copy(justApprovedCode = null) }
    }

    fun clearHistoryStatus() {
        _uiState.update { it.copy(historySharedCount = null, historyShareError = null) }
    }
}
