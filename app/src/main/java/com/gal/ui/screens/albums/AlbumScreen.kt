package com.gal.ui.screens.albums

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gal.data.repository.MediaRepository
import com.gal.model.Media
import com.gal.ui.actions.MediaActionsViewModel
import com.gal.ui.components.AlbumPickerSheet
import com.gal.ui.components.MediaGrid
import com.gal.ui.components.SelectionAppBar
import com.gal.ui.components.rememberSelectionState
import com.gal.ui.screens.settings.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
) : ViewModel() {

    private val albumId: Long = checkNotNull(savedStateHandle["albumId"])

    val media = repository.album(albumId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList<Media>(),
        )

    val albumName = media.value.firstOrNull()?.albumName ?: ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    albumName: String,
    onBack: () -> Unit,
    onMediaClick: (Media, Int) -> Unit,
    viewModel: AlbumViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val media    by viewModel.media.collectAsStateWithLifecycle()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val selection = rememberSelectionState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { selection.clear() }
    val launch: (IntentSenderRequest?) -> Unit = { req ->
        if (req != null) launcher.launch(req) else selection.clear()
    }

    var pickerMode by remember { mutableStateOf<PickerMode?>(null) }

    Scaffold(
        topBar = {
            if (selection.isActive) {
                val selectedItems = media.filter { it.id in selection.selected }
                SelectionAppBar(
                    selection = selection,
                    allIds = media.map { it.id },
                    actions = {
                        IconButton(onClick = {
                            actions.share(selectedItems) { intent ->
                                context.startActivity(intent)
                                selection.clear()
                            }
                        }) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
                        IconButton(onClick = {
                            actions.setFavorite(selectedItems, true, launch)
                        }) { Icon(Icons.Outlined.FavoriteBorder, contentDescription = "Favorite") }
                        IconButton(onClick = { pickerMode = PickerMode.COPY }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy to album")
                        }
                        IconButton(onClick = { pickerMode = PickerMode.MOVE }) {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = "Move to album")
                        }
                        IconButton(onClick = {
                            actions.trash(selectedItems, launch)
                        }) { Icon(Icons.Outlined.Delete, contentDescription = "Trash") }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = albumName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { padding ->
        if (media.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No items",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            MediaGrid(
                media = media,
                columns = 3,
                selectedIds = selection.selected,
                showVideoDuration = settings.showVideoDuration,
                hapticFeedback = settings.hapticFeedback,
                onMediaClick = { item ->
                    if (selection.isActive) {
                        selection.toggle(item.id)
                    } else {
                        val index = media.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        onMediaClick(item, index)
                    }
                },
                onMediaLongClick = { item -> selection.toggle(item.id) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }

        pickerMode?.let { mode ->
            val selectedItems = media.filter { it.id in selection.selected }
            AlbumPickerSheet(
                albumsFlow = actions.albums,
                title = if (mode == PickerMode.COPY) "Copy to album" else "Move to album",
                onDismiss = { pickerMode = null },
                onAlbumPicked = { album ->
                    if (mode == PickerMode.COPY) {
                        actions.copyToAlbum(selectedItems, album) { selection.clear() }
                    } else {
                        actions.moveToAlbum(selectedItems, album, launch)
                    }
                    pickerMode = null
                },
            )
        }
    }
}

private enum class PickerMode { COPY, MOVE }
