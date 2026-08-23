package com.gal.ui.screens.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gal.R
import com.gal.model.Media
import com.gal.ui.actions.MediaActionsViewModel
import com.gal.ui.components.AlbumPickerSheet
import com.gal.ui.components.MediaSectionedGrid
import com.gal.ui.components.SelectionAppBar
import com.gal.ui.components.rememberSelectionState
import com.gal.ui.screens.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onMediaClick: (Media) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: TimelineViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val media       by viewModel.media.collectAsStateWithLifecycle()
    val sectioned   by viewModel.sectioned.collectAsStateWithLifecycle()
    val gridColumns by viewModel.gridColumns.collectAsStateWithLifecycle()
    val sortOrder   by viewModel.sortOrder.collectAsStateWithLifecycle()
    val filterType  by viewModel.filterType.collectAsStateWithLifecycle()
    val settings    by settingsViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var showSortMenu   by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }

    val selection = rememberSelectionState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { selection.clear() }
    val launch: (IntentSenderRequest?) -> Unit = { req ->
        if (req != null) launcher.launch(req) else selection.clear()
    }

    var pickerMode by remember { mutableStateOf<PickerMode?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                    title = {},
                    actions = {
                        SortMenu(sortOrder, viewModel::setSortOrder, showSortMenu) { showSortMenu = it }
                        FilterMenu(filterType, viewModel::setFilterType, showFilterMenu) { showFilterMenu = it }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Settings") } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(onClick = onSettingsClick) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { padding ->
        if (media.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.empty_timeline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(modifier = Modifier.padding(padding)) {
                MediaSectionedGrid(
                    sections = sectioned,
                    columns = gridColumns,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortMenu(
    current: SortOrder,
    onPick: (SortOrder) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Sort") } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = { onExpandedChange(true) }) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            SortOrder.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            sort.label,
                            color = if (sort == current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onPick(sort); onExpandedChange(false) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterMenu(
    current: FilterType,
    onPick: (FilterType) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Filter") } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = { onExpandedChange(true) }) {
                Icon(
                    Icons.Outlined.FilterList,
                    contentDescription = "Filter",
                    tint = if (current != FilterType.ALL) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            FilterType.entries.forEach { filter ->
                DropdownMenuItem(
                    text = {
                        Text(
                            filter.label,
                            color = if (filter == current) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onPick(filter); onExpandedChange(false) },
                )
            }
        }
    }
}
