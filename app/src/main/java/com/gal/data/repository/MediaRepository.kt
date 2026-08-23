package com.gal.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.gal.data.local.LocalDataSource
import com.gal.model.Album
import com.gal.model.Media
import com.gal.model.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSource: LocalDataSource,
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val KEY_SECURE_DELETE = booleanPreferencesKey("secure_delete")
    }
    fun timeline(): Flow<List<Media>> = dataSource.timeline()
    fun favourites(): Flow<List<Media>> = dataSource.favourites()
    fun trash(): Flow<List<Media>> = dataSource.trash()
    fun albums(): Flow<List<Album>> = dataSource.albums()
    fun album(albumId: Long): Flow<List<Media>> = dataSource.album(albumId)
    fun search(query: String): Flow<List<Media>> = dataSource.search(query)

    // ── Single-item ops (kept for callers using one Media) ──────────────────

    suspend fun setFavourite(media: Media, favourite: Boolean): IntentSenderRequest? =
        setFavourite(listOf(media), favourite)

    suspend fun moveToTrash(media: Media): IntentSenderRequest? = moveToTrash(listOf(media))

    suspend fun restoreFromTrash(media: Media): IntentSenderRequest? = restoreFromTrash(listOf(media))

    suspend fun delete(media: Media): IntentSenderRequest? = delete(listOf(media))

    // ── Batch ops ───────────────────────────────────────────────────────────

    suspend fun setFavourite(items: List<Media>, favourite: Boolean): IntentSenderRequest? =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext null
            try {
                val pi = MediaStore.createFavoriteRequest(
                    context.contentResolver, items.map { it.uri }, favourite,
                )
                IntentSenderRequest.Builder(pi.intentSender).build()
            } catch (e: RecoverableSecurityException) {
                IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
            } catch (e: Exception) { null }
        }

    suspend fun moveToTrash(items: List<Media>): IntentSenderRequest? = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext null
        try {
            val pi = MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, true)
            IntentSenderRequest.Builder(pi.intentSender).build()
        } catch (e: Exception) { null }
    }

    suspend fun restoreFromTrash(items: List<Media>): IntentSenderRequest? = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext null
        try {
            val pi = MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, false)
            IntentSenderRequest.Builder(pi.intentSender).build()
        } catch (e: Exception) { null }
    }

    suspend fun delete(items: List<Media>): IntentSenderRequest? = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext null
        if (dataStore.data.first()[KEY_SECURE_DELETE] != false) {
            items.forEach { secureOverwrite(it.uri) }
        }
        try {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri })
            IntentSenderRequest.Builder(pi.intentSender).build()
        } catch (e: Exception) { null }
    }

    // ── Rename / Rotate / Move-Copy / OpenWith ──────────────────────────────

    /** Request write access to one item; on grant, the caller can perform the rename via [applyRename]. */
    suspend fun requestWrite(items: List<Media>): IntentSenderRequest? = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext null
        try {
            val pi = MediaStore.createWriteRequest(context.contentResolver, items.map { it.uri })
            IntentSenderRequest.Builder(pi.intentSender).build()
        } catch (e: Exception) { null }
    }

    /** Renames a file via MediaStore. Call after the user grants the write request. */
    suspend fun applyRename(media: Media, newName: String): Boolean = withContext(Dispatchers.IO) {
        if (newName.isBlank()) return@withContext false
        val sanitized = sanitizeFilename(newName) ?: return@withContext false
        runCatching {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sanitized)
            }
            context.contentResolver.update(media.uri, cv, null, null) > 0
        }.getOrDefault(false)
    }

    /** Strips path traversal + reserved FAT/MediaStore characters. Returns null if nothing left. */
    private fun sanitizeFilename(input: String): String? {
        val cleaned = input
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trimStart('.')
            .take(200)
        return cleaned.takeIf { it.isNotBlank() }
    }

    /**
     * Rotates an image by [degrees] (multiple of 90). For JPEG/HEIC/WebP this writes the
     * EXIF orientation tag (lossless). For other formats it re-encodes the bitmap.
     * Call after the user grants the write request.
     */
    suspend fun applyRotate(media: Media, degrees: Int): Boolean = withContext(Dispatchers.IO) {
        if (media.type != MediaType.IMAGE) return@withContext false
        val turns = ((degrees / 90) % 4 + 4) % 4
        if (turns == 0) return@withContext true
        val mime = media.mimeType.lowercase()
        val supportsExif = mime in listOf(
            "image/jpeg", "image/jpg", "image/heic", "image/heif", "image/webp",
        )
        runCatching {
            if (supportsExif) rotateViaExif(media.uri, turns)
            else rotateViaReencode(media.uri, turns)
        }.getOrDefault(false)
    }

    private fun rotateViaExif(uri: Uri, turns: Int): Boolean {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            val current = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
            val rotated = rotateOrientation(current, turns)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, rotated.toString())
            exif.saveAttributes()
            return true
        }
        return false
    }

    private fun rotateOrientation(orientation: Int, turns: Int): Int {
        // Sequence of orientations under successive 90° CW rotations starting from NORMAL.
        val cycle = intArrayOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        val idx = cycle.indexOf(orientation).let { if (it < 0) 0 else it }
        return cycle[(idx + turns) % 4]
    }

    private fun rotateViaReencode(uri: Uri, turns: Int): Boolean {
        val resolver = context.contentResolver
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return false
        val matrix = Matrix().apply { postRotate(turns * 90f) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val format = when (resolver.getType(uri)?.lowercase()) {
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> Bitmap.CompressFormat.JPEG
        }
        resolver.openOutputStream(uri, "wt")?.use { out ->
            rotated.compress(format, 95, out)
        } ?: return false
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
        return true
    }

    /** Copies items into Pictures/[albumName]. Returns number successful. */
    suspend fun copyToAlbum(items: List<Media>, albumName: String): Int =
        copyOrMove(items, albumName, deleteSource = false)

    /** Moves items into Pictures/[albumName]. Deletion of the source uses createDeleteRequest. */
    suspend fun moveToAlbum(
        items: List<Media>, albumName: String,
    ): Pair<Int, IntentSenderRequest?> = withContext(Dispatchers.IO) {
        val copied = copyOrMove(items, albumName, deleteSource = false)
        val delReq = if (copied > 0) delete(items) else null
        copied to delReq
    }

    private suspend fun copyOrMove(
        items: List<Media>, albumName: String, deleteSource: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty() || albumName.isBlank()) return@withContext 0
        val safeAlbum = sanitizeFilename(albumName)?.take(100) ?: return@withContext 0
        val resolver = context.contentResolver
        var count = 0
        for (m in items) {
            try {
                val collection = if (m.type == MediaType.VIDEO)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, m.displayName ?: "${m.id}")
                    put(MediaStore.MediaColumns.MIME_TYPE, m.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$safeAlbum")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val destUri = resolver.insert(collection, values) ?: continue
                try {
                    resolver.openInputStream(m.uri)?.use { input ->
                        resolver.openOutputStream(destUri)?.use { output -> input.copyTo(output) }
                    }
                    resolver.update(
                        destUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null, null,
                    )
                    count++
                } catch (e: Exception) {
                    resolver.delete(destUri, null, null)
                }
            } catch (_: Exception) {}
        }
        count
    }

    /** Builds an ACTION_VIEW chooser intent for a single media item. */
    fun openWithIntent(media: Media): Intent {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(media.uri, media.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(view, null)
    }

    // ── Secure delete + import (unchanged) ──────────────────────────────────

    // Best-effort only: NAND wear-levelling means the OS may redirect writes to
    // different physical blocks, so overwritten bytes are not guaranteed to be
    // unrecoverable on flash storage. This reduces casual recovery risk but is
    // not a substitute for full-disk encryption (which Android already provides).
    private fun secureOverwrite(uri: Uri) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val size = pfd.statSize
                if (size > 0) {
                    val os = FileOutputStream(pfd.fileDescriptor)
                    val buffer = ByteArray(64 * 1024)
                    val random = SecureRandom()
                    var written = 0L

                    // Pass 1: cryptographically random data
                    while (written < size) {
                        val toWrite = Math.min(buffer.size.toLong(), size - written).toInt()
                        random.nextBytes(buffer)
                        os.write(buffer, 0, toWrite)
                        written += toWrite
                    }
                    os.flush()

                    // Pass 2: Zeros
                    os.channel.position(0)
                    java.util.Arrays.fill(buffer, 0.toByte())
                    written = 0L
                    while (written < size) {
                        val toWrite = Math.min(buffer.size.toLong(), size - written).toInt()
                        os.write(buffer, 0, toWrite)
                        written += toWrite
                    }
                    os.flush()
                }
            }
        } catch (e: Exception) {
            // Overwrite failed (likely permission denied for writing without a request)
            // We continue to the actual deletion request regardless
        }
    }

    suspend fun copyToVaultStaging(media: Media): File = withContext(Dispatchers.IO) {
        val vaultDir = File(context.filesDir, "vault_staging").also { it.mkdirs() }
        val ext = media.mimeType.substringAfterLast("/")
        val dest = File(vaultDir, "${media.id}.$ext")
        context.contentResolver.openInputStream(media.uri)?.use { it.copyTo(dest.outputStream()) }
            ?: throw IllegalStateException("Cannot open ${media.uri}")
        dest
    }

    // ── Local folder backup (SAF) ────────────────────────────────────────────

    /**
     * Copies [items] into the user-picked SAF tree at [destTreeUri]. Purely local — no network
     * involved, consistent with the app's zero-network stance. Skips a file if a same-named
     * file already exists at the destination (idempotent across repeated backup runs). Reports
     * progress via [onProgress] after each item. Returns the number of items now present at the
     * destination (freshly copied or already there).
     */
    suspend fun exportLibrary(
        destTreeUri: Uri,
        items: List<Media>,
        onProgress: (copied: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        val destDir = DocumentFile.fromTreeUri(context, destTreeUri) ?: return@withContext 0
        val existingNames = destDir.listFiles().mapNotNull { it.name }.toSet()
        var count = 0
        items.forEachIndexed { index, media ->
            val name = media.displayName ?: "${media.id}"
            runCatching {
                if (name !in existingNames) {
                    val newFile = destDir.createFile(media.mimeType, name)
                        ?: throw IllegalStateException("Could not create $name at destination")
                    context.contentResolver.openInputStream(media.uri)?.use { input ->
                        context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                            input.copyTo(output)
                        } ?: throw IllegalStateException("Could not open output stream for $name")
                    } ?: throw IllegalStateException("Could not open $name")
                }
                count++
            }
            onProgress(index + 1, items.size)
        }
        count
    }

    // ── Duplicate finder ─────────────────────────────────────────────────────

    /**
     * Groups the (non-trashed) library into exact-content duplicate sets. Cheap size grouping
     * first, then a streamed SHA-256 hash only within same-size groups so we never hash the
     * whole library. Each returned group is sorted oldest-first (index 0 = the copy to keep by
     * default); groups always have 2+ items.
     */
    suspend fun findDuplicates(): List<List<Media>> = withContext(Dispatchers.IO) {
        timeline().first()
            .groupBy { it.sizeBytes }
            .values
            .filter { it.size > 1 }
            .flatMap { sameSize ->
                sameSize.groupBy { hashContent(it.uri) }.values.filter { it.size > 1 }
            }
            .map { group -> group.sortedBy { it.dateAdded } }
    }

    private fun hashContent(uri: Uri): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault(uri.toString()) // hash failed — fall back to a value unique to this item so it never falsely joins another group

    /** Copies external URIs into Pictures/Gal via MediaStore. Returns the number successfully imported. */
    suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var count = 0
        for (uri in uris) {
            try {
                val mimeType = resolver.getType(uri) ?: continue
                if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue

                val displayName = resolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: "${System.currentTimeMillis()}"

                val collection = if (mimeType.startsWith("video/"))
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Gal")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val destUri = resolver.insert(collection, values) ?: continue
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        resolver.openOutputStream(destUri)?.use { output -> input.copyTo(output) }
                    }
                    resolver.update(
                        destUri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null, null,
                    )
                    count++
                } catch (e: Exception) {
                    resolver.delete(destUri, null, null)
                }
            } catch (_: Exception) {}
        }
        count
    }
}
