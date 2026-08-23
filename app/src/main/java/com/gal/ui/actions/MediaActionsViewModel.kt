package com.gal.ui.actions

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gal.data.repository.MediaRepository
import com.gal.model.Album
import com.gal.model.Media
import com.gal.security.ExifScrubber
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Cross-screen helper for media operations. Inject in any screen that needs bulk or single-item actions.
 *
 * All operations that require user permission (trash/delete/restore/favorite/rename/rotate/move) return
 * an [IntentSenderRequest] via callback; the caller launches it through a registered
 * [androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult] launcher.
 */
@HiltViewModel
class MediaActionsViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val exifScrubber: ExifScrubber,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val albums: Flow<List<Album>> = repository.albums()

    // ── Trash / Delete / Restore / Favorite ─────────────────────────────────

    fun trash(items: List<Media>, onRequest: (IntentSenderRequest?) -> Unit) =
        viewModelScope.launch { onRequest(repository.moveToTrash(items)) }

    fun deleteForever(items: List<Media>, onRequest: (IntentSenderRequest?) -> Unit) =
        viewModelScope.launch { onRequest(repository.delete(items)) }

    fun restore(items: List<Media>, onRequest: (IntentSenderRequest?) -> Unit) =
        viewModelScope.launch { onRequest(repository.restoreFromTrash(items)) }

    fun setFavorite(items: List<Media>, favorite: Boolean, onRequest: (IntentSenderRequest?) -> Unit) =
        viewModelScope.launch { onRequest(repository.setFavourite(items, favorite)) }

    // ── Share (EXIF-scrubbed) ───────────────────────────────────────────────

    fun share(items: List<Media>, onIntent: (Intent) -> Unit) = viewModelScope.launch {
        if (items.isEmpty()) return@launch
        val mime = items.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
        val uris = withContext(Dispatchers.IO) {
            items.mapNotNull {
                runCatching { exifScrubber.scrubAndShare(context, it.uri) }.getOrNull()
                    ?: it.uri
            }
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        onIntent(Intent.createChooser(intent, "Share"))
    }

    // ── Copy / Move to album ────────────────────────────────────────────────

    fun copyToAlbum(items: List<Media>, albumName: String, onDone: (Int) -> Unit = {}) =
        viewModelScope.launch { onDone(repository.copyToAlbum(items, albumName)) }

    fun moveToAlbum(
        items: List<Media>, albumName: String,
        onRequest: (IntentSenderRequest?) -> Unit,
    ) = viewModelScope.launch {
        val (_, req) = repository.moveToAlbum(items, albumName)
        onRequest(req)
    }

    // ── Rename / Rotate (single-item, two-phase) ────────────────────────────

    /** Request write access. The caller invokes [applyRename] after the launcher reports OK. */
    fun requestWrite(items: List<Media>, onRequest: (IntentSenderRequest?) -> Unit) =
        viewModelScope.launch { onRequest(repository.requestWrite(items)) }

    fun applyRename(media: Media, newName: String, onDone: (Boolean) -> Unit) =
        viewModelScope.launch { onDone(repository.applyRename(media, newName)) }

    fun applyRotate(media: Media, degrees: Int, onDone: (Boolean) -> Unit) =
        viewModelScope.launch { onDone(repository.applyRotate(media, degrees)) }

    // ── Open with ───────────────────────────────────────────────────────────

    fun openWith(media: Media) {
        val intent = repository.openWithIntent(media).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
