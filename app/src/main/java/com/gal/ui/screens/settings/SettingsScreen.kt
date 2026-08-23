package com.gal.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gal.data.repository.MediaRepository
import com.gal.ui.theme.AppFont
import com.gal.ui.theme.CatppuccinMocha
import com.gal.ui.theme.DraculaColors
import com.gal.ui.theme.ptyxisEntries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class SettingsState(
    val gridColumns: Int = 3,
    val stripExifOnShare: Boolean = true,
    val biometricLock: Boolean = false,
    val secureDelete: Boolean = true,
    val secureThumbnailCache: Boolean = false,
    val showVideoDuration: Boolean = true,
    val hapticFeedback: Boolean = true,
    val keepScreenOn: Boolean = false,
    val blockScreenshots: Boolean = false,
    val filterTouches: Boolean = true,
    val themeMode: String = "system",
    val catppuccinAccent: String = "mauve",
    val draculaAccent: String = "purple",
    val ptyxisPalette: String = "nord",
    val appFont: String = "dotgothic16",
    val isLoaded: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<androidx.datastore.preferences.core.Preferences>,
    private val mediaRepository: MediaRepository,
) : ViewModel() {
    companion object {
        val KEY_GRID                   = intPreferencesKey("grid_columns")
        val KEY_EXIF                   = booleanPreferencesKey("strip_exif")
        val KEY_BIOMETRIC              = booleanPreferencesKey("biometric_lock")
        val KEY_SECURE_DELETE          = booleanPreferencesKey("secure_delete")
        val KEY_SECURE_THUMBNAIL_CACHE = booleanPreferencesKey("secure_thumbnail_cache")
        val KEY_SHOW_VIDEO_DURATION    = booleanPreferencesKey("show_video_duration")
        val KEY_HAPTIC_FEEDBACK        = booleanPreferencesKey("haptic_feedback")
        val KEY_KEEP_SCREEN_ON         = booleanPreferencesKey("keep_screen_on")
        val KEY_BLOCK_SCREENSHOTS      = booleanPreferencesKey("block_screenshots")
        val KEY_FILTER_TOUCHES         = booleanPreferencesKey("filter_touches")
        val KEY_THEME_MODE             = stringPreferencesKey("theme_mode")
        val KEY_CATPPUCCIN_ACCENT      = stringPreferencesKey("catppuccin_accent")
        val KEY_DRACULA_ACCENT         = stringPreferencesKey("dracula_accent")
        val KEY_PTYXIS_PALETTE         = stringPreferencesKey("ptyxis_palette")
        val KEY_APP_FONT               = stringPreferencesKey("app_font")
    }

    val state = dataStore.data.map { p ->
        SettingsState(
            gridColumns          = p[KEY_GRID]                   ?: 3,
            stripExifOnShare     = p[KEY_EXIF]                   ?: true,
            biometricLock        = p[KEY_BIOMETRIC]              ?: false,
            secureDelete         = p[KEY_SECURE_DELETE]          ?: true,
            secureThumbnailCache = p[KEY_SECURE_THUMBNAIL_CACHE] ?: false,
            showVideoDuration    = p[KEY_SHOW_VIDEO_DURATION]    ?: true,
            hapticFeedback       = p[KEY_HAPTIC_FEEDBACK]        ?: true,
            keepScreenOn         = p[KEY_KEEP_SCREEN_ON]         ?: false,
            blockScreenshots     = p[KEY_BLOCK_SCREENSHOTS]      ?: false,
            filterTouches        = p[KEY_FILTER_TOUCHES]         ?: true,
            themeMode            = p[KEY_THEME_MODE]             ?: "system",
            catppuccinAccent     = p[KEY_CATPPUCCIN_ACCENT]      ?: "mauve",
            draculaAccent        = p[KEY_DRACULA_ACCENT]         ?: "purple",
            ptyxisPalette        = p[KEY_PTYXIS_PALETTE]         ?: "nord",
            appFont              = p[KEY_APP_FONT]               ?: "dotgothic16",
            isLoaded             = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setGridColumns(v: Int)           = viewModelScope.launch { dataStore.edit { it[KEY_GRID]                   = v } }
    fun setStripExif(v: Boolean)         = viewModelScope.launch { dataStore.edit { it[KEY_EXIF]                   = v } }
    fun setBiometric(v: Boolean)         = viewModelScope.launch { dataStore.edit { it[KEY_BIOMETRIC]              = v } }
    fun setSecureDelete(v: Boolean)      = viewModelScope.launch { dataStore.edit { it[KEY_SECURE_DELETE]          = v } }
    fun setSecureThumbnailCache(v: Boolean) = viewModelScope.launch { dataStore.edit { it[KEY_SECURE_THUMBNAIL_CACHE] = v } }
    fun setShowVideoDuration(v: Boolean) = viewModelScope.launch { dataStore.edit { it[KEY_SHOW_VIDEO_DURATION]    = v } }
    fun setHapticFeedback(v: Boolean)    = viewModelScope.launch { dataStore.edit { it[KEY_HAPTIC_FEEDBACK]        = v } }
    fun setKeepScreenOn(v: Boolean)      = viewModelScope.launch { dataStore.edit { it[KEY_KEEP_SCREEN_ON]         = v } }
    fun setBlockScreenshots(v: Boolean)  = viewModelScope.launch { dataStore.edit { it[KEY_BLOCK_SCREENSHOTS]      = v } }
    fun setFilterTouches(v: Boolean)     = viewModelScope.launch { dataStore.edit { it[KEY_FILTER_TOUCHES]         = v } }
    fun setThemeMode(v: String)          = viewModelScope.launch { dataStore.edit { it[KEY_THEME_MODE]             = v } }
    fun setCatppuccinAccent(v: String)   = viewModelScope.launch { dataStore.edit { it[KEY_CATPPUCCIN_ACCENT]      = v } }
    fun setDraculaAccent(v: String)      = viewModelScope.launch { dataStore.edit { it[KEY_DRACULA_ACCENT]         = v } }
    fun setPtyxisPalette(v: String)      = viewModelScope.launch { dataStore.edit { it[KEY_PTYXIS_PALETTE]         = v } }
    fun setAppFont(v: String)            = viewModelScope.launch { dataStore.edit { it[KEY_APP_FONT]               = v } }

    suspend fun importFiles(uris: List<Uri>): Int = mediaRepository.importFiles(uris)

    /** Backs up the whole (non-trashed) library into the SAF folder the user picked. */
    suspend fun exportLibrary(destTreeUri: Uri, onProgress: (Int, Int) -> Unit): Int =
        mediaRepository.exportLibrary(destTreeUri, mediaRepository.timeline().first(), onProgress)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onFindDuplicates: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                val count = viewModel.importFiles(uris)
                importing = false
                snackbarHostState.showSnackbar(
                    if (count > 0) "Imported $count item${if (count == 1) "" else "s"} into Gal"
                    else "Nothing imported"
                )
            }
        }
    }

    // (copied, total) while a backup is running; null when idle.
    var backupProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch {
                backupProgress = 0 to 0
                val count = viewModel.exportLibrary(treeUri) { copied, total ->
                    backupProgress = copied to total
                }
                backupProgress = null
                snackbarHostState.showSnackbar(
                    if (count > 0) "Backed up $count item${if (count == 1) "" else "s"} to folder"
                    else "Nothing to back up"
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Grid")
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Columns", style = MaterialTheme.typography.bodyLarge)
                    Text("${state.gridColumns}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = state.gridColumns.toFloat(),
                    onValueChange = { viewModel.setGridColumns(it.roundToInt()) },
                    valueRange = 2f..6f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingsToggle(
                title = "Video duration badge",
                subtitle = "Show clip length on video thumbnails",
                checked = state.showVideoDuration,
                onCheckedChange = { viewModel.setShowVideoDuration(it) },
            )
            SettingsToggle(
                title = "Haptic feedback",
                subtitle = "Vibrate when long-pressing to select media",
                checked = state.hapticFeedback,
                onCheckedChange = { viewModel.setHapticFeedback(it) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("Library")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !importing) {
                        importLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Import media", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Copy photos or videos from anywhere into your gallery",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onFindDuplicates)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Find duplicates", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Scan your library for exact-copy photos and videos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = backupProgress == null) { backupLauncher.launch(null) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Backup to folder", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Copy your library to a folder you choose — stays fully offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (backupProgress != null) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.Save,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("Privacy")
            SettingsToggle(
                title = "Biometric Lock",
                subtitle = "Require biometric authentication to open the app",
                checked = state.biometricLock,
                onCheckedChange = { viewModel.setBiometric(it) },
            )
            SettingsToggle(
                title = "Strip metadata on share",
                subtitle = "Removes GPS, device info and timestamps before sharing",
                checked = state.stripExifOnShare,
                onCheckedChange = { viewModel.setStripExif(it) },
            )
            SettingsToggle(
                title = "Secure delete",
                subtitle = "Overwrite file contents before deletion to reduce recovery risk",
                checked = state.secureDelete,
                onCheckedChange = { viewModel.setSecureDelete(it) },
            )
            SettingsToggle(
                title = "Memory-only thumbnails",
                subtitle = "Disable thumbnail disk cache — previews are not stored on device. Takes effect on next launch.",
                checked = state.secureThumbnailCache,
                onCheckedChange = { viewModel.setSecureThumbnailCache(it) },
            )
            SettingsToggle(
                title = "Block screenshots",
                subtitle = "Prevent screenshots and screen recording throughout the entire app",
                checked = state.blockScreenshots,
                onCheckedChange = { viewModel.setBlockScreenshots(it) },
            )
            SettingsToggle(
                title = "Tapjacking protection",
                subtitle = "Ignore taps when another window overlays this app",
                checked = state.filterTouches,
                onCheckedChange = { viewModel.setFilterTouches(it) },
            )
            SettingsToggle(
                title = "Keep screen on",
                subtitle = "Prevent the screen from sleeping while viewing media",
                checked = state.keepScreenOn,
                onCheckedChange = { viewModel.setKeepScreenOn(it) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionHeader("Display")
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val themeOptions = listOf(
                    "system"     to "System",
                    "wallpaper"  to "Wallpaper",
                    "catppuccin" to "Catppuccin",
                    "dracula"    to "Dracula",
                    "ptyxis"     to "Ptyxis",
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    themeOptions.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = state.themeMode == key,
                            onClick  = { viewModel.setThemeMode(key) },
                            shape    = SegmentedButtonDefaults.itemShape(index = i, count = themeOptions.size),
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }
                if (state.themeMode == "catppuccin" || state.themeMode == "dracula") {
                    val context = LocalContext.current
                    val dynamicPrimary = androidx.compose.material3.dynamicDarkColorScheme(context).primary
                    val accentEntries: List<Pair<String, Color>>
                    val currentAccentKey: String
                    val onAccentPicked: (String) -> Unit
                    if (state.themeMode == "catppuccin") {
                        accentEntries    = listOf("dynamic" to dynamicPrimary) + CatppuccinMocha.accents.map { (k, v) -> k to v.second }
                        currentAccentKey = state.catppuccinAccent
                        onAccentPicked   = { viewModel.setCatppuccinAccent(it) }
                    } else {
                        accentEntries    = listOf("dynamic" to dynamicPrimary) + DraculaColors.accents.map { (k, v) -> k to v.second }
                        currentAccentKey = state.draculaAccent
                        onAccentPicked   = { viewModel.setDraculaAccent(it) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        accentEntries.forEach { (key, color) ->
                            val isSelected = key == currentAccentKey
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        else Modifier
                                    )
                                    .clickable { onAccentPicked(key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (key == "dynamic") {
                                    Icon(
                                        Icons.Outlined.AutoAwesome,
                                        contentDescription = "Wallpaper color",
                                        tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.Black.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (state.themeMode == "ptyxis") {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(ptyxisEntries, key = { it.key }) { entry ->
                            val isSelected = entry.key == state.ptyxisPalette
                            Column(
                                modifier = Modifier
                                    .width(56.dp)
                                    .clickable { viewModel.setPtyxisPalette(entry.key) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(entry.swatch)
                                        .then(
                                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.Black.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Text(
                                    entry.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                Text(
                    "Font",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp),
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(AppFont.entries, key = { it.key }) { font ->
                        val isSelected = font.key == state.appFont
                        Column(
                            modifier = Modifier
                                .width(84.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .then(
                                    if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                    else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                .clickable { viewModel.setAppFont(font.key) }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Aa",
                                fontFamily = font.family,
                                fontSize = 20.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                font.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            var aboutExpanded by remember { mutableStateOf(false) }
            val uriHandler = LocalUriHandler.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { aboutExpanded = !aboutExpanded }
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "About",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    if (aboutExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = aboutExpanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Text("Gallery", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Zero network · EXIF stripped on share · No cloud · No tracking",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Dev — Jegly", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "github.com/jegly",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { uriHandler.openUri("https://github.com/jegly") },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "jegly.xyz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { uriHandler.openUri("https://www.jegly.xyz") },
                    )
                }
            }
        }
    }

    backupProgress?.let { (copied, total) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Backing up…") },
            text = {
                Column {
                    Text(if (total > 0) "$copied / $total items" else "Preparing…")
                    Spacer(Modifier.height(12.dp))
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { copied.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
