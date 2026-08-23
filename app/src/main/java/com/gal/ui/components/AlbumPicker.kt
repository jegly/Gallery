package com.gal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gal.model.Album
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPickerSheet(
    albumsFlow: Flow<List<Album>>,
    title: String,
    onDismiss: () -> Unit,
    onAlbumPicked: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val albums by albumsFlow.collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(12.dp))

            if (creating) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { creating = false; newName = "" }) { Text("Cancel") }
                    TextButton(
                        enabled = newName.isNotBlank(),
                        onClick = { onAlbumPicked(newName.trim()) },
                    ) { Text("Create") }
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    item {
                        AlbumRow(
                            leading = {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                                }
                            },
                            title = "New album…",
                            subtitle = null,
                            onClick = { creating = true },
                        )
                    }
                    items(albums, key = { it.id }) { album ->
                        AlbumRow(
                            leading = {
                                AsyncImage(
                                    model = album.coverUri,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                            },
                            title = album.name,
                            subtitle = "${album.mediaCount}",
                            onClick = { onAlbumPicked(album.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
