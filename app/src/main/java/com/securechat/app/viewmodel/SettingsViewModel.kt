// Settings ViewModel — 管理与设置相关的 UI 状态
package com.securechat.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页面 UI 状态
 */
data class SettingsUiState(
    val displayName: String = "",
    val message: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val prefs = context.getSharedPreferences("securechat_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            displayName = prefs.getString("auth_display_name", "") ?: ""
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState

    fun updateNickname(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(message = "昵称不能为空") }
            return
        }
        viewModelScope.launch {
            accountRepository.updateDisplayName(trimmed)
                .onSuccess {
                    _uiState.update { it.copy(displayName = trimmed, message = "昵称已更新") }
                    // 重新拉取成员昵称，使本机各列表即时反映新昵称
                    launch { accountRepository.fetchAndCacheContacts() }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(message = "昵称更新失败: ${e.message}") }
                }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        viewModelScope.launch {
            accountRepository.changePassword(oldPassword, newPassword)
                .onSuccess { _uiState.update { it.copy(message = "密码已修改") } }
                .onFailure { e ->
                    _uiState.update { it.copy(message = "密码修改失败: ${e.message}") }
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
