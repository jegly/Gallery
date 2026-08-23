package com.gal.ui.screens.duplicates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gal.data.repository.MediaRepository
import com.gal.model.Media
import com.gal.ui.actions.MediaActionsViewModel
import com.gal.ui.components.MediaCell
import com.gal.ui.components.SelectionAppBar
import com.gal.ui.components.rememberSelectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {
    private val _groups = MutableStateFlow<List<List<Media>>>(emptyList())
    val groups: StateFlow<List<List<Media>>> = _groups.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _hasScanned = MutableStateFlow(false)
    val hasScanned: StateFlow<Boolean> = _hasScanned.asStateFlow()

    fun scan() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _groups.value = repository.findDuplicates()
            _hasScanned.value = true
            _isScanning.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onMediaClick: (Media) -> Unit,
    viewModel: DuplicatesViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val hasScanned by viewModel.hasScanned.collectAsStateWithLifecycle()

    val selection = rememberSelectionState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { selection.clear() }
    val launch: (IntentSenderRequest?) -> Unit = { req ->
        if (req != null) launcher.launch(req) else selection.clear()
    }

    var confirmTrash by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasScanned) viewModel.scan()
    }

    // Every time a fresh scan lands, default-select everything but the oldest copy in each
    // group (index 0, the one we sort to the front) — the likely-unwanted duplicates.
    LaunchedEffect(groups) {
        if (groups.isNotEmpty()) selection.set(groups.flatMap { it.drop(1) }.map { it.id })
    }

    val allDuplicateIds = groups.flatMap { it.drop(1) }.map { it.id }

    Scaffold(
        topBar = {
            if (selection.isActive) {
                val selectedItems = groups.flatten().filter { it.id in selection.selected }
                SelectionAppBar(
                    selection = selection,
                    allIds = allDuplicateIds,
                    actions = {
                        IconButton(onClick = { confirmTrash = true }) {
                            Icon(Icons.Outlined.DeleteForever, contentDescription = "Move to trash")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Find duplicates") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.scan() }, enabled = !isScanning) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Rescan")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                isScanning -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Scanning your library for exact duplicates…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                hasScanned && groups.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No duplicates found",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Every photo and video in your library is unique.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "${groups.size} duplicate set${if (groups.size == 1) "" else "s"} · " +
                            "the oldest copy in each set is kept by default, marked KEEP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(groups, key = { group -> group.first().id }) { group ->
                            DuplicateGroupRow(
                                group = group,
                                selectedIds = selection.selected,
                                onMediaClick = { item ->
                                    if (selection.isActive) selection.toggle(item.id) else onMediaClick(item)
                                },
                                onMediaLongClick = { item -> selection.toggle(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmTrash) {
        val count = selection.count
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move $count item${if (count == 1) "" else "s"} to trash?") },
            text = { Text("They'll stay in Trash until you empty it or restore them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    val selectedItems = groups.flatten().filter { it.id in selection.selected }
                    actions.trash(selectedItems, launch)
                }) { Text("Move to trash") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DuplicateGroupRow(
    group: List<Media>,
    selectedIds: Set<Long>,
    onMediaClick: (Media) -> Unit,
    onMediaLongClick: (Media) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            "${group.size} copies · ${formatBytes(group.first().sizeBytes)} each",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(group, key = { it.id }) { item ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MediaCell(
                        media = item,
                        isSelected = item.id in selectedIds,
                        onClick = { onMediaClick(item) },
                        onLongClick = { onMediaLongClick(item) },
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        if (item.id == group.first().id) "KEEP" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

private fun formatBytes(b: Long) = when {
    b < 1024 -> "$b B"
    b < 1048576 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1048576.0)
}
