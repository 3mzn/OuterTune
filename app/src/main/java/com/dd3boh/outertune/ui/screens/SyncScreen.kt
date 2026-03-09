package com.dd3boh.outertune.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.SpotifyApiTokenKey
import com.dd3boh.outertune.ui.dialog.FailedImportsDialog
import com.dd3boh.outertune.ui.dialog.ImportJsonPlaylistDialog
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.SyncViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    var (spotifyApiToken, setSpotifyApiToken) = rememberPreference(SpotifyApiTokenKey, "")
    val syncState by viewModel.syncState.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val failedImports by viewModel.failedImports.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showImportJsonDialog by remember { mutableStateOf(false) }
    var selectedJsonUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var jsonUriForImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var showFailedImportsDialog by remember { mutableStateOf(false) }

    // File picker for JSON
    val jsonFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedJsonUri = it
            jsonUriForImport = it // Store for import
            showImportJsonDialog = true
        }
    }

    // Clear URI when dialog is dismissed without confirming
    LaunchedEffect(showImportJsonDialog) {
        if (!showImportJsonDialog && jsonUriForImport != null) {
            // Dialog was dismissed, clear the URI
            selectedJsonUri = null
            jsonUriForImport = null
        }
    }

    // If we have a code, exchange it for a token
    if (spotifyApiToken.startsWith("CODE:")) {
        val code = spotifyApiToken.removePrefix("CODE:")
        LaunchedEffect(code) {
            val token = viewModel.exchangeCodeForToken(code)
            if (token != null) {
                setSpotifyApiToken(token)
            } else {
                setSpotifyApiToken("")
            }
        }
    }

    // Show failed imports dialog when import completes with failures
    LaunchedEffect(failedImports) {
        if (failedImports.isNotEmpty() && syncState is SyncViewModel.SyncState.Success) {
            showFailedImportsDialog = true
        }
    }

    // Dialogs
    if (showImportJsonDialog && jsonUriForImport != null) {
        ImportJsonPlaylistDialog(
            fileUri = jsonUriForImport!!,
            onDismiss = {
                showImportJsonDialog = false
            },
            onConfirm = { playlistName ->
                val uri = jsonUriForImport!! // Capture before clearing
                selectedJsonUri = null
                jsonUriForImport = null // Clear before closing dialog
                showImportJsonDialog = false
                viewModel.startJsonImport(uri, playlistName)
            }
        )
    }

    if (showFailedImportsDialog && failedImports.isNotEmpty()) {
        FailedImportsDialog(
            failedImports = failedImports,
            onDismiss = {
                showFailedImportsDialog = false
                viewModel.clearFailedImports()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Spotify Sync",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (spotifyApiToken.isNotEmpty()) "Spotify Account Linked" else "Spotify Not Linked",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { navController.navigate("spotify_login") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(if (spotifyApiToken.isEmpty()) "Link Spotify" else "Relink Spotify")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (syncState is SyncViewModel.SyncState.Syncing) {
            Text(
                text = "Syncing in Progress...",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.cancelJsonImport() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Cancel Import")
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.startSpotifyApiSync() },
                    enabled = spotifyApiToken.isNotEmpty() && !spotifyApiToken.startsWith("CODE:") && syncState !is SyncViewModel.SyncState.Syncing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Rounded.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Sync")
                }

                Button(
                    onClick = { jsonFilePicker.launch(arrayOf("application/json")) },
                    enabled = syncState !is SyncViewModel.SyncState.Syncing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Icon(Icons.Rounded.Upload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import from JSON")
                }
            }
            
            if (syncState is SyncViewModel.SyncState.Success || syncState is SyncViewModel.SyncState.Error || syncState is SyncViewModel.SyncState.Cancelled) {
                Spacer(modifier = Modifier.height(16.dp))
                val message = when (syncState) {
                    is SyncViewModel.SyncState.Error -> (syncState as SyncViewModel.SyncState.Error).message
                    is SyncViewModel.SyncState.Cancelled -> "Import was cancelled"
                    else -> statusText
                }
                val color = when (syncState) {
                    is SyncViewModel.SyncState.Success -> MaterialTheme.colorScheme.primary
                    is SyncViewModel.SyncState.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                }
                Text(
                    text = message,
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "This will match your Spotify liked songs with YouTube Music and add them to your OuterTune library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.sync)) },
        scrollBehavior = scrollBehavior
    )
}
