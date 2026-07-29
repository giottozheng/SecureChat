// Pairing approval ViewModel — 管理待审批的桌面端配对请求
package com.securechat.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.repository.AccountRepository
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
    val justApprovedCode: String? = null
)

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val accountRepository: AccountRepository
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            accountRepository.approvePairing(code)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, justApprovedCode = code) }
                    loadPending() // 刷新列表
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "批准失败") }
                }
        }
    }

    fun clearApproved() {
        _uiState.update { it.copy(justApprovedCode = null) }
    }
}
