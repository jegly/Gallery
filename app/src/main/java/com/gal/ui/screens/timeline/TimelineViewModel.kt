package com.gal.ui.screens.timeline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gal.data.repository.MediaRepository
import com.gal.model.DateGroup
import com.gal.model.Media
import com.gal.model.MediaType
import com.gal.ui.screens.settings.SettingsViewModel
import com.gal.util.groupedByDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// Entries are stored by ordinal in DataStore: append new ones, never reorder.
enum class SortOrder(val label: String) {
    DATE_ADDED_DESC("Newest first"),   // by capture time (Media.dateTaken)
    DATE_ADDED_ASC("Oldest first"),    // by capture time (Media.dateTaken)
    DATE_MODIFIED_DESC("Recently modified"),
    SIZE_DESC("Largest first"),
    NAME_ASC("Name A–Z"),
}

enum class FilterType(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
}

/**
 * The timeline as the grid shows it: filtered, sorted and grouped by day, computed off the main
 * thread. The grid and the media viewer both use this, so swiping in the viewer always moves to
 * the item that sits next to it in the grid.
 */
fun timelineSections(
    repository: MediaRepository,
    dataStore: DataStore<Preferences>,
): Flow<List<DateGroup>> = combine(
    repository.timeline(),
    dataStore.data.map { it[TimelineViewModel.KEY_SORT] ?: 0 }.distinctUntilChanged(),
    dataStore.data.map { it[TimelineViewModel.KEY_FILTER] ?: 0 }.distinctUntilChanged(),
    dataStore.data.map { it[SettingsViewModel.KEY_SHOW_VIDEOS_TAB] ?: false }.distinctUntilChanged(),
) { list, sortIdx, filterIdx, videosTab ->
    val sort = SortOrder.entries.getOrElse(sortIdx) { SortOrder.DATE_ADDED_DESC }
    val filter = FilterType.entries.getOrElse(filterIdx) { FilterType.ALL }
    // With the Videos tab on, videos live there, so "All" here means all photos.
    val effective = if (videosTab && filter == FilterType.ALL) FilterType.PHOTOS else filter
    val filtered = when (effective) {
        FilterType.ALL    -> list
        FilterType.PHOTOS -> list.filter { it.type == MediaType.IMAGE }
        FilterType.VIDEOS -> list.filter { it.type == MediaType.VIDEO }
    }
    // The repository already delivers newest capture first; ties break on id for a stable order.
    when (sort) {
        SortOrder.DATE_ADDED_DESC    -> filtered
        SortOrder.DATE_ADDED_ASC     -> filtered.sortedWith(compareBy<Media> { it.dateTaken }.thenBy { it.id })
        SortOrder.DATE_MODIFIED_DESC -> filtered.sortedWith(compareByDescending<Media> { it.dateModified }.thenByDescending { it.id })
        SortOrder.SIZE_DESC          -> filtered.sortedWith(compareByDescending<Media> { it.sizeBytes }.thenByDescending { it.id })
        SortOrder.NAME_ASC           -> filtered.sortedWith(compareBy<Media> { it.displayName?.lowercase() }.thenBy { it.id })
    }.groupedByDay()
}.flowOn(Dispatchers.Default)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val dataStore: DataStore<Preferences>,
) : ViewModel() {

    companion object {
        val KEY_GRID    = intPreferencesKey("grid_columns")
        val KEY_SORT    = intPreferencesKey("sort_order")
        val KEY_FILTER  = intPreferencesKey("filter_type")
    }

    private val prefs = dataStore.data

    val gridColumns = prefs
        .map { it[KEY_GRID] ?: 3 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    val sortOrder = prefs
        .map { SortOrder.entries.getOrElse(it[KEY_SORT] ?: 0) { SortOrder.DATE_ADDED_DESC } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOrder.DATE_ADDED_DESC)

    val filterType = prefs
        .map { FilterType.entries.getOrElse(it[KEY_FILTER] ?: 0) { FilterType.ALL } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterType.ALL)

    /** Grouped by day for header rendering; this is exactly what the grid shows. */
    val sectioned: kotlinx.coroutines.flow.StateFlow<List<DateGroup>> =
        timelineSections(repository, dataStore)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The grid's items in on-screen order (the media viewer pages through the same list). */
    val media = sectioned
        .map { groups -> groups.flatMap { it.items } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSortOrder(sort: SortOrder) = viewModelScope.launch {
        dataStore.edit { it[KEY_SORT] = sort.ordinal }
    }

    fun setFilterType(filter: FilterType) = viewModelScope.launch {
        dataStore.edit { it[KEY_FILTER] = filter.ordinal }
    }
}
