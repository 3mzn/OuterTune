package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.viewmodels.AuthViewModel
import com.dd3boh.outertune.viewmodels.SocialViewModel
import com.dd3boh.outertune.viewmodels.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    navController: NavController,
) {
    val authViewModel: AuthViewModel = hiltViewModel()
    val user by authViewModel.user.collectAsState()
    val profile by authViewModel.profile.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.social)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            actions = {
                if (user != null) {
                    IconButton(onClick = { authViewModel.logout() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = "Logout",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            windowInsets = TopBarInsets
        )

        Box(
            modifier = Modifier.weight(1f)
        ) {
            if (user == null) {
                FirebaseLoginScreen(authViewModel = authViewModel)
            } else if (profile?.username.isNullOrEmpty()) {
                ProfileSetupScreen(
                    onProfileComplete = {
                        // Force recomposition by navigating to the same destination
                        // The profile state will be updated and show SocialDashboard
                    },
                    authViewModel = authViewModel
                )
            } else {
                SocialDashboard(
                    authViewModel = authViewModel,
                    navController = navController,
                    username = profile?.username ?: (user?.email ?: "Unknown user")
                )
            }
        }
    }
}

@Composable
fun SocialDashboard(
    authViewModel: AuthViewModel,
    navController: NavController,
    username: String,
    socialViewModel: SocialViewModel = hiltViewModel()
) {
    val relationships by socialViewModel.relationships.collectAsState()
    val users by socialViewModel.users.collectAsState()
    
    val friendsList = users.filter { relationships.friends.contains(it.uid) }
    val pendingRequestsCount = relationships.incomingRequests.values.count { it.status == "pending" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Hello, $username!",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { navController.navigate("social_users") },
                modifier = Modifier.weight(1f)
            ) {
                Text("Add friend")
            }
            
            OutlinedButton(
                onClick = { navController.navigate("social_requests") },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (pendingRequestsCount > 0) "Requests ($pendingRequestsCount)" else "Requests")
            }

            OutlinedButton(
                onClick = { authViewModel.logout() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Logout")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "Friends",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Start)
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        if (friendsList.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("No friends yet", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(friendsList, key = { it.uid }) { friend ->
                    FriendRow(
                        friend = friend,
                        onRemove = { socialViewModel.removeFriend(friend.uid) }
                    )
                }
            }
        }
    }
}

@Composable
fun FriendRow(friend: UserProfile, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = friend.username, style = MaterialTheme.typography.titleMedium)
            Text(text = friend.email, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Person, contentDescription = "Remove friend", tint = MaterialTheme.colorScheme.error)
        }
    }
}
