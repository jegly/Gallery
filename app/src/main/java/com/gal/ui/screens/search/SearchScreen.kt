package com.gal.ui.screens.search

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val results = _query
        .debounce(300L)
        .distinctUntilChanged()
        .flatMapLatest { q -> repository.search(q) }
        .catch { emit(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList<Media>(),
        )

    fun onQueryChange(q: String) { _query.value = q }
    fun clearQuery() { _query.value = "" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onMediaClick: (Media) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    actions: MediaActionsViewModel = hiltViewModel(),
) {
    val query    by viewModel.query.collectAsStateWithLifecycle()
    val results  by viewModel.results.collectAsStateWithLifecycle()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    var expanded by rememberSaveable { mutableStateOf(false) }
    val selection = rememberSelectionState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { selection.clear() }
    val launch: (IntentSenderRequest?) -> Unit = { req ->
        if (req != null) launcher.launch(req) else selection.clear()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selection.isActive) {
            val selectedItems = results.filter { it.id in selection.selected }
            SelectionAppBar(
                selection = selection,
                allIds = results.map { it.id },
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
                    IconButton(onClick = {
                        actions.trash(selectedItems, launch)
                    }) { Icon(Icons.Outlined.Delete, contentDescription = "Trash") }
                },
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = viewModel::onQueryChange,
                        onSearch = { keyboard?.hide() },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        leadingIcon = {
                            if (expanded) {
                                IconButton(onClick = { expanded = false; viewModel.clearQuery() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            } else {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                            }
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearQuery) {
                                    Icon(Icons.Outlined.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                when {
                    query.isBlank() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Type to search your photos and videos",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                    results.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.empty_search, query),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                    else -> {
                        MediaGrid(
                            media = results,
                            columns = 3,
                            selectedIds = selection.selected,
                            showVideoDuration = settings.showVideoDuration,
                            hapticFeedback = settings.hapticFeedback,
                            onMediaClick = { item ->
                                if (selection.isActive) selection.toggle(item.id)
                                else { onMediaClick(item); expanded = false }
                            },
                            onMediaLongClick = { item -> selection.toggle(item.id) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
