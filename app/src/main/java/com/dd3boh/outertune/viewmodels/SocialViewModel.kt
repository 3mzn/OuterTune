package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.social.FriendRequest
import com.dd3boh.outertune.social.RelationshipState
import com.dd3boh.outertune.social.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val socialRepository: SocialRepository
) : ViewModel() {

    private val _users = MutableStateFlow<List<UserProfile>>(emptyList())
    val users: StateFlow<List<UserProfile>> = _users.asStateFlow()

    private val _relationships =
        MutableStateFlow(RelationshipState(emptyMap(), emptyMap(), emptySet()))
    val relationships: StateFlow<RelationshipState> = _relationships.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            socialRepository.getAllUsers().collectLatest { profiles ->
                _users.value = profiles
            }
        }
        viewModelScope.launch {
            socialRepository.observeRelationships().collectLatest { state ->
                _relationships.value = state
            }
        }
    }

    fun sendFriendRequest(toUid: String) {
        socialRepository.sendFriendRequest(toUid) { success, message ->
            if (!success) {
                _error.value = message ?: "Failed to send request"
            } else {
                _error.value = null
            }
        }
    }

    fun acceptFriendRequest(request: FriendRequest) {
        socialRepository.acceptFriendRequest(request) { success, message ->
            if (!success) {
                _error.value = message ?: "Failed to accept request"
            } else {
                _error.value = null
            }
        }
    }

    fun rejectFriendRequest(requestId: String) {
        socialRepository.rejectFriendRequest(requestId) { success, message ->
            if (!success) {
                _error.value = message ?: "Failed to reject request"
            } else {
                _error.value = null
            }
        }
    }

    fun removeFriend(otherUid: String) {
        socialRepository.removeFriend(otherUid) { success, message ->
            if (!success) {
                _error.value = message ?: "Failed to remove friend"
            } else {
                _error.value = null
            }
        }
    }
}

