package com.securechat.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.data.model.Message
import com.securechat.app.data.model.Conversation
import com.securechat.app.repository.AccountRepository
import com.securechat.app.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for conversation list screen.
 */
data class ConversationListUiState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for conversation list.
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val accountRepository: AccountRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()
    
    init {
        // 拉取团队成员最新昵称，使「修改昵称」在会话列表即时生效
        viewModelScope.launch { accountRepository.fetchAndCacheContacts() }
        loadConversations()
    }
    
    private fun loadConversations() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            repository.getConversations()
                .catch { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
                .collect { conversations ->
                    _uiState.update {
                        it.copy(
                            conversations = conversations,
                            isLoading = false
                        )
                    }
                }
        }
    }
    
    fun refresh() {
        loadConversations()
    }
}
