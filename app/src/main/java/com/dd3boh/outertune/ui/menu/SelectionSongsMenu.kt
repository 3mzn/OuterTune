package com.dd3boh.outertune.ui.menu

import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSyncUtils
import com.dd3boh.outertune.R
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.ExoDownloadService
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.social.SongSharingRepository
import com.dd3boh.outertune.social.SocialRepository
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.AddToQueueDialog
import com.dd3boh.outertune.ui.dialog.DefaultDialog
import com.dd3boh.outertune.ui.dialog.SendToFriendsDialog
import com.dd3boh.outertune.utils.getDownloadState
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.runtime.rememberCoroutineScope
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SongSharingRepositoryEntryPoint {
    fun songSharingRepository(): SongSharingRepository
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SocialRepositoryEntryPoint {
    fun socialRepository(): SocialRepository
}

/**
 * Generic song menu
 */
@Composable
fun SelectionMediaMetadataMenu(
    navController: NavController,
    selection: List<MediaMetadata>,
    onDismiss: () -> Unit,
    clearAction: () -> Unit,
    onRemoveFromHistory: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val queueBoard by playerConnection.queueBoard.collectAsState()
    val syncUtils = LocalSyncUtils.current
    val coroutineScope = rememberCoroutineScope()
    
    // Inject repositories via Hilt
    val songSharingRepository = remember {
        try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                SongSharingRepositoryEntryPoint::class.java
            ).songSharingRepository()
        } catch (e: Exception) {
            null
        }
    }
    
    val socialRepository = remember {
        try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                SocialRepositoryEntryPoint::class.java
            ).socialRepository()
        } catch (e: Exception) {
            null
        }
    }

    val allInLibrary by remember(selection) { // exclude local songs
        mutableStateOf(selection.isNotEmpty() && selection.all { !it.isLocal && it.inLibrary != null })
    }
    val allLocal by remember(selection) { // if only local songs in this selection
        mutableStateOf(selection.isNotEmpty() && selection.all { it.isLocal })
    }

    val allLiked by remember(selection) {
        mutableStateOf(selection.isNotEmpty() && selection.all { it.liked })
    }

    var downloadState by remember {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    var showChooseQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showChoosePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }
    var showRemoveDownloadDialog by remember {
        mutableStateOf(false)
    }
    var showSendToFriendsDialog by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(selection) {
        if (selection.isEmpty()) {
            onDismiss()
        } else {
            val selection = selection.filterNot { it.isLocal }
            if (selection.isEmpty()) return@LaunchedEffect
            downloadUtil.downloads.collect { downloads ->
                downloadState = getDownloadState(selection.map { downloads[it.id] })
            }
        }
    }


    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        GridMenuItem(
            icon = R.drawable.play,
            title = R.string.play
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = "Selection",
                    items = selection
                )
            )
            clearAction()
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            title = R.string.play_next,
        ) {
            onDismiss()
            playerConnection.enqueueNext(selection.map { it.toMediaItem() })
            clearAction()
        }

        GridMenuItem(
            icon = R.drawable.shuffle_on,
            title = R.string.shuffle
        ) {
            onDismiss()
            playerConnection.playQueue(
                ListQueue(
                    title = "Selection",
                    items = selection,
                    startShuffled = true,
                )
            )
            clearAction()
        }

        GridMenuItem(
            icon = R.drawable.queue_music,
            title = R.string.add_to_queue
        ) {
            showChooseQueueDialog = true
        }

        GridMenuItem(
            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
            title = R.string.add_to_playlist
        ) {
            showChoosePlaylistDialog = true
        }

        // Send to Friends action
        if (!allLocal && songSharingRepository != null && FirebaseAuth.getInstance().currentUser != null) {
            GridMenuItem(
                icon = Icons.Rounded.Send,
                title = R.string.send_to_friends
            ) {
                showSendToFriendsDialog = true
            }
        }

        if (!allLocal) {
            if (allInLibrary) {
                GridMenuItem(
                    icon = Icons.Rounded.LibraryAddCheck,
                    title = R.string.remove_all_from_library
                ) {
                    database.transaction {
                        selection.forEach { song ->
                            toggleInLibrary(song.id, null)
                        }
                    }
                }
            } else {
                GridMenuItem(
                    icon = Icons.Rounded.LibraryAdd,
                    title = R.string.add_all_to_library
                ) {
                    database.transaction {
                        selection.forEach { song ->
                            if (!song.isLocal) {
                                toggleInLibrary(song.id, LocalDateTime.now())
                            }
                        }
                    }
                }
            }
        }

        GridMenuItem(
            icon = if (allLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            tint = { if (allLiked) MaterialTheme.colorScheme.error else LocalContentColor.current },
            title = if (allLiked) R.string.action_remove_like_all else R.string.action_like_all,
        ) {
            database.query {
                if (allLiked) {
                    selection.forEach { song ->
                        val s = song.toSongEntity().toggleLike()
                        update(s)
                        if (!s.isLocal) {
                            syncUtils.likeSong(s)
                        }
                    }
                } else {
                    selection.filter { !it.liked }.forEach { song ->
                        val s = song.toSongEntity().toggleLike()
                        update(s)
                        if (!s.isLocal) {
                            syncUtils.likeSong(s)
                        }
                    }
                }
            }
        }

        DownloadGridMenu(
            state = downloadState,
            onDownload = {
                val songs = selection.filterNot { it.isLocal }
                downloadUtil.download(songs)
            },
            onRemoveDownload = {
                showRemoveDownloadDialog = true
            }
        )

        if (onRemoveFromHistory != null) {
            GridMenuItem(
                icon = Icons.Rounded.Delete,
                title = R.string.remove_from_history,
            ) {
                onRemoveFromHistory()
                onDismiss()
                clearAction()
            }
        }
    }

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showChooseQueueDialog) {
        AddToQueueDialog(
            onAdd = { queueName ->
                val q = queueBoard.addQueue(
                    queueName,
                    selection,
                    forceInsert = true,
                    delta = false
                )
                q?.let {
                    queueBoard.setCurrQueue(it)
                }
            },
            onDismiss = {
                showChooseQueueDialog = false
            }
        )
    }


    if (showChoosePlaylistDialog) {
        AddToPlaylistDialog(
            navController = navController,
            songIds = selection.map { it.id },
            onPreAdd = {
                selection.forEach { s ->
                    database.insert(s)
                }
                emptyList()
            },
            onDismiss = { showChoosePlaylistDialog = false }
        )
    }

    if (showRemoveDownloadDialog) {
        DefaultDialog(
            onDismiss = { showRemoveDownloadDialog = false },
            content = {
                Text(
                    text = stringResource(R.string.remove_download_playlist_confirm, "selection"),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            },
            buttons = {
                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }

                TextButton(
                    onClick = {
                        showRemoveDownloadDialog = false
                        selection.forEach { song ->
                            DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                song.id,
                                false
                            )
                        }
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    if (showSendToFriendsDialog && songSharingRepository != null && socialRepository != null) {
        val relationshipState by socialRepository.observeRelationships().collectAsState(
            initial = com.dd3boh.outertune.social.RelationshipState(
                emptyMap(), emptyMap(), emptySet()
            )
        )
        val allUsers by socialRepository.getAllUsers().collectAsState(initial = emptyList())
        val friendProfiles = remember(allUsers, relationshipState) {
            allUsers.filter { it.uid in relationshipState.friends }
                .associateBy { it.uid }
        }

        SendToFriendsDialog(
            songCount = selection.size,
            relationshipState = relationshipState,
            friendProfiles = friendProfiles,
            onDismiss = { showSendToFriendsDialog = false },
            onSend = { selectedFriendUids ->
                coroutineScope.launch {
                    try {
                        val songsToSend = selection.filterNot { it.isLocal }
                        if (songsToSend.isEmpty()) {
                            Log.d("SelectionSongsMenu", "⚠️ No remote songs to send")
                            return@launch
                        }

                        Log.d("SelectionSongsMenu", "📤 Starting send: ${songsToSend.size} songs to ${selectedFriendUids.size} friends")
                        Log.d("SelectionSongsMenu", "📋 Songs: ${songsToSend.map { it.title }}")
                        Log.d("SelectionSongsMenu", "👥 Friend UIDs: $selectedFriendUids")
                        
                        val successCount = songSharingRepository.sendSongsToFriends(
                            songs = songsToSend,
                            friendUids = selectedFriendUids,
                            friendProfiles = friendProfiles
                        )
                        Log.d("SelectionSongsMenu", "✅ Successfully sent $successCount songs")
                        
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context,
                                "Sent $successCount songs to ${selectedFriendUids.size} friends",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        
                        showSendToFriendsDialog = false
                        onDismiss()
                        clearAction()
                    } catch (e: Exception) {
                        Log.e("SelectionSongsMenu", "❌ Error sending songs", e)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context,
                                "Error sending songs: ${e.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        )
    }
}
