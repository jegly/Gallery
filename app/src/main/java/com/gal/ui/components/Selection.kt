package com.gal.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Stable
class SelectionState internal constructor(initial: Set<Long>) {
    var selected by mutableStateOf(initial)
        private set

    val isActive: Boolean get() = selected.isNotEmpty()
    val count: Int get() = selected.size

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun set(ids: Collection<Long>) {
        selected = ids.toSet()
    }

    fun clear() {
        selected = emptySet()
    }
}

@Composable
fun rememberSelectionState(): SelectionState =
    rememberSaveable(saver = SelectionSaver) { SelectionState(emptySet()) }

private val SelectionSaver: Saver<SelectionState, List<Long>> = Saver(
    save = { it.selected.toList() },
    restore = { SelectionState(it.toSet()) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionAppBar(
    selection: SelectionState,
    allIds: Collection<Long>,
    actions: @Composable RowScope.() -> Unit,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
) {
    TopAppBar(
        title = { Text("${selection.count}") },
        navigationIcon = {
            IconButton(onClick = { selection.clear() }) {
                Icon(Icons.Outlined.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = {
                if (selection.count == allIds.size) selection.clear() else selection.set(allIds)
            }) {
                Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
            }
            actions()
        },
        colors = colors,
    )
}
