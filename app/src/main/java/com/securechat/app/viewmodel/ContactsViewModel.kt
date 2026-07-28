package com.securechat.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.data.local.FriendDao
import com.securechat.app.data.local.FriendEntity
import com.securechat.app.data.local.FriendRequestDao
import com.securechat.app.data.local.FriendRequestEntity
import com.securechat.app.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactsUiState(
    val friends: List<FriendEntity> = emptyList(),
    val pendingRequests: List<FriendRequestEntity> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val friendRequestDao: FriendRequestDao,
    private val friendDao: FriendDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    init {
        // 本地好友实时驱动 UI（仅互为好友的成员）
        viewModelScope.launch {
            friendDao.getFriends().collect { friends ->
                _uiState.update { it.copy(friends = friends) }
            }
        }
        // 本地待处理请求实时驱动 UI
        viewModelScope.launch {
            friendRequestDao.getPendingRequests().collect { requests ->
                _uiState.update { it.copy(pendingRequests = requests) }
            }
        }
        // 拉取好友列表（昵称/在线状态）并刷新待处理请求
        viewModelScope.launch { accountRepository.fetchFriends() }
        loadPending()
    }

    fun loadPending() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            accountRepository.fetchPendingRequests()
                .onFailure { e -> _uiState.update { it.copy(message = it.message ?: "加载请求失败: ${e.message}", isLoading = false) } }
                .onSuccess { _uiState.update { it.copy(isLoading = false) } }
        }
    }

    fun sendRequest(targetId: String) {
        if (targetId.isBlank()) return
        viewModelScope.launch {
            accountRepository.sendFriendRequest(targetId)
                .onFailure { e -> _uiState.update { it.copy(message = "请求发送失败: ${e.message}") } }
                .onSuccess { _uiState.update { it.copy(message = "已向 $targetId 发送好友请求") } }
        }
    }

    fun accept(fromId: String) {
        viewModelScope.launch {
            accountRepository.respondFriendRequest(fromId, accept = true)
                .onFailure { e -> _uiState.update { it.copy(message = "接受失败: ${e.message}") } }
                .onSuccess { _uiState.update { it.copy(message = "已添加 $fromId 为联系人") } }
        }
    }

    fun reject(fromId: String) {
        viewModelScope.launch {
            accountRepository.respondFriendRequest(fromId, accept = false)
                .onFailure { e -> _uiState.update { it.copy(message = "操作失败: ${e.message}") } }
                .onSuccess { _uiState.update { it.copy(message = "已拒绝请求") } }
        }
    }

    fun removeFriend(userId: String) {
        viewModelScope.launch {
            accountRepository.removeFriend(userId)
                .onFailure { e -> _uiState.update { it.copy(message = "删除好友失败: ${e.message}") } }
                .onSuccess {
                    friendDao.deleteFriend(userId)
                    _uiState.update { it.copy(message = "已删除好友") }
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
