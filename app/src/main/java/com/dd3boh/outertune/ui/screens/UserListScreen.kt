package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.viewmodels.SocialViewModel
import com.dd3boh.outertune.viewmodels.UserProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen(
    navController: NavController,
    socialViewModel: SocialViewModel = hiltViewModel()
) {
    val users by socialViewModel.users.collectAsState()
    val relationships by socialViewModel.relationships.collectAsState()
    val error by socialViewModel.error.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { Text("Add friend") },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            windowInsets = TopBarInsets
        )

        if (error != null) {
            Text(
                text = error ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(users, key = { it.uid }) { user ->
                UserRow(
                    user = user,
                    isFriend = relationships.friends.contains(user.uid),
                    outgoingRequest = relationships.outgoingRequests[user.uid],
                    onRequest = { socialViewModel.sendFriendRequest(user.uid) }
                )
            }
        }
    }
}

@Composable
private fun UserRow(
    user: UserProfile,
    isFriend: Boolean,
    outgoingRequest: com.dd3boh.outertune.social.FriendRequest?,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = false) { }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = if (user.username.isNotBlank()) user.username else user.email,
                style = MaterialTheme.typography.titleMedium
            )
            if (user.username.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        when {
            isFriend -> {
                Text(
                    text = "Friends",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            outgoingRequest != null && outgoingRequest.status == "pending" -> {
                Text(
                    text = "Requested",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            else -> {
                Button(onClick = onRequest) {
                    Text("Request")
                }
            }
        }
    }
}

