package com.dd3boh.outertune.ui.dialog

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R

@Composable
fun ImportJsonPlaylistDialog(
    fileUri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (playlistName: String) -> Unit,
) {
    TextFieldDialog(
        icon = { Icon(imageVector = Icons.Rounded.Upload, contentDescription = null) },
        title = { Text(text = "Import from JSON") },
        initialTextFieldValue = TextFieldValue(""),
        onDismiss = onDismiss,
        onDone = { playlistName ->
            if (playlistName.isNotBlank()) {
                onConfirm(playlistName)
            }
        },
        extraContent = {
            Column(
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 40.dp)
            ) {
                Text(
                    text = "Enter playlist name",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "If a playlist with this name exists, songs will be added to it. Otherwise, a new playlist will be created.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    )
}
