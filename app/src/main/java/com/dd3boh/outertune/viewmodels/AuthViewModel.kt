package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.social.SongListenedNotificationManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val username: String = "",
    val photoUrl: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val songListenedNotificationManager: SongListenedNotificationManager
) : ViewModel() {
    private val auth = FirebaseAuth.getInstance()

    private val _user = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val user: StateFlow<FirebaseUser?> = _user.asStateFlow()

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val currentUser = firebaseAuth.currentUser
            _user.value = currentUser

            if (currentUser != null) {
                viewModelScope.launch {
                    ensureUserProfileExists(currentUser)
                    // Start notification worker when user logs in
                    songListenedNotificationManager.startWorker()
                }
            } else {
                _profile.value = null
                // Stop notification worker when user logs out
                songListenedNotificationManager.stopWorker()
            }
        }

        // Load profile for already logged-in user on startup
        auth.currentUser?.let { currentUser ->
            viewModelScope.launch {
                ensureUserProfileExists(currentUser)
            }
        }
    }

    fun login(email: String, pass: String, onResult: (Boolean, Exception?) -> Unit) {
        if (email.isBlank() || pass.isBlank()) {
            onResult(false, Exception("Email and password cannot be empty."))
            return
        }
        auth.signInWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.let { currentUser ->
                        viewModelScope.launch {
                            ensureUserProfileExists(currentUser)
                        }
                    }
                }
                onResult(task.isSuccessful, task.exception)
            }
    }

    fun register(email: String, pass: String, onResult: (Boolean, Exception?) -> Unit) {
        if (email.isBlank() || pass.isBlank()) {
            onResult(false, Exception("Email and password cannot be empty."))
            return
        }
        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val currentUser = auth.currentUser
                    if (currentUser != null) {
                        viewModelScope.launch {
                            createInitialProfile(currentUser)
                        }
                    }
                }
                onResult(task.isSuccessful, task.exception)
            }
    }

    fun logout() {
        auth.signOut()
    }

    fun updateUsername(newUsername: String, onResult: (Boolean, String?) -> Unit) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            onResult(false, "Not logged in")
            return
        }
        val trimmed = newUsername.trim()
        if (trimmed.isEmpty()) {
            onResult(false, "Username cannot be empty")
            return
        }

        // Enforce uniqueness: no other user document should have this username
        firestore.collection("users")
            .whereEqualTo("username", trimmed)
            .get()
            .addOnSuccessListener { querySnapshot ->
                val conflict = querySnapshot.documents.any { it.id != currentUser.uid }
                if (conflict) {
                    onResult(false, "Username is already taken")
                    return@addOnSuccessListener
                }

                firestore.collection("users")
                    .document(currentUser.uid)
                    .set(
                        mapOf(
                            "username" to trimmed,
                            "updatedAt" to com.google.firebase.Timestamp.now()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                    .addOnSuccessListener {
                        val existing = _profile.value
                        _profile.value = if (existing != null) {
                            existing.copy(username = trimmed)
                        } else {
                            UserProfile(
                                uid = currentUser.uid,
                                email = currentUser.email.orElseUnknown(),
                                username = trimmed,
                                photoUrl = null
                            )
                        }
                        onResult(true, null)
                    }
                    .addOnFailureListener { e ->
                        onResult(false, e.message ?: "Failed to update username")
                    }
            }
            .addOnFailureListener { e ->
                onResult(false, e.message ?: "Failed to check username")
            }
    }

    private fun ensureUserProfileExists(user: FirebaseUser) {
        val docRef = firestore.collection("users").document(user.uid)
        docRef.get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val profile = UserProfile(
                        uid = snapshot.id,
                        email = snapshot.getString("email") ?: user.email.orEmpty(),
                        username = snapshot.getString("username") ?: "",
                        photoUrl = snapshot.getString("photoUrl")
                    )
                    _profile.value = profile
                } else {
                    createInitialProfile(user)
                }
            }
            .addOnFailureListener {
                // Keep existing profile/null on failure
            }
    }

    private fun createInitialProfile(user: FirebaseUser) {
        val profile = UserProfile(
            uid = user.uid,
            email = user.email.orElseUnknown(),
            username = "",
            photoUrl = null
        )

        firestore.collection("users")
            .document(user.uid)
            .set(
                mapOf(
                    "email" to profile.email,
                    "username" to profile.username,
                    "photoUrl" to profile.photoUrl,
                    "createdAt" to com.google.firebase.Timestamp.now(),
                    "updatedAt" to com.google.firebase.Timestamp.now()
                )
            )
            .addOnSuccessListener {
                _profile.value = profile
            }
            .addOnFailureListener {
                // Ignore for now; user can retry profile setup later
            }
    }
}

private fun String?.orElseUnknown(): String = this ?: "Unknown user"
