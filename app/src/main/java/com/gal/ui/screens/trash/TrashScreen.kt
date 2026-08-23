package com.gal.ui.screens.trash

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gal.R
import com.gal.data.repository.MediaRepository
import com.gal.model.Media
import com.gal.ui.actions.MediaActionsViewModel
import com.gal.ui.components.MediaGrid
import com.gal.ui.components.SelectionAppBar
import com.gal.ui.components.rememberSelectionState
import com.gal.ui.screens.settings.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    repository: MediaRepository,
) : ViewModel() {
    val media = repository.trash()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList<Media>(),
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    onMediaClick: (Media) -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val media    by viewModel.media.collectAsStateWithLifecycle()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()

    val selection = rememberSelectionState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { selection.clear() }
    val launch: (IntentSenderRequest?) -> Unit = { req ->
        if (req != null) launcher.launch(req) else selection.clear()
    }

    var showMenu by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (selection.isActive) {
                val selectedItems = media.filter { it.id in selection.selected }
                SelectionAppBar(
                    selection = selection,
                    allIds = media.map { it.id },
                    actions = {
                        IconButton(onClick = {
                            actions.restore(selectedItems, launch)
                        }) { Icon(Icons.Outlined.Restore, contentDescription = "Restore") }
                        IconButton(onClick = {
                            actions.deleteForever(selectedItems, launch)
                        }) { Icon(Icons.Outlined.DeleteForever, contentDescription = "Delete forever") }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.trash)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Restore all") },
                                    onClick = {
                                        showMenu = false
                                        actions.restore(media, launch)
                                    },
                                    enabled = media.isNotEmpty(),
                                )
                                DropdownMenuItem(
                                    text = { Text("Empty trash") },
                                    onClick = { showMenu = false; confirmEmpty = true },
                                    enabled = media.isNotEmpty(),
                                )
                            }
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
                    text = stringResource(R.string.empty_trash),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(modifier = Modifier.padding(padding)) {
                Text(
                    text = stringResource(R.string.trash_auto_delete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                MediaGrid(
                    media = media,
                    columns = 3,
                    selectedIds = selection.selected,
                    showVideoDuration = settings.showVideoDuration,
                    hapticFeedback = settings.hapticFeedback,
                    onMediaClick = { item ->
                        if (selection.isActive) selection.toggle(item.id)
                        else onMediaClick(item)
                    },
                    onMediaLongClick = { item -> selection.toggle(item.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty trash?") },
            text = { Text("All ${media.size} items will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    actions.deleteForever(media, launch)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") }
            },
        )
    }
}
