package com.gal.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import com.gal.data.local.LocalDataSource
import com.gal.model.Album
import com.gal.model.Media
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSource: LocalDataSource,
) {
    fun timeline(): Flow<List<Media>> = dataSource.timeline()
    fun favourites(): Flow<List<Media>> = dataSource.favourites()
    fun trash(): Flow<List<Media>> = dataSource.trash()
    fun albums(): Flow<List<Album>> = dataSource.albums()
    fun album(albumId: Long): Flow<List<Media>> = dataSource.album(albumId)
    fun search(query: String): Flow<List<Media>> = dataSource.search(query)

    suspend fun setFavourite(media: Media, favourite: Boolean): IntentSenderRequest? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createFavoriteRequest(context.contentResolver, listOf(media.uri), favourite)
                IntentSenderRequest.Builder(pi.intentSender).build()
            } else {
                context.contentResolver.update(media.uri, ContentValues().apply { put(MediaStore.Files.FileColumns.IS_FAVORITE, if (favourite) 1 else 0) }, null, null)
                null
            }
        } catch (e: RecoverableSecurityException) {
            IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
        } catch (e: Exception) { null }
    }

    suspend fun moveToTrash(media: Media): IntentSenderRequest? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createTrashRequest(context.contentResolver, listOf(media.uri), true)
                IntentSenderRequest.Builder(pi.intentSender).build()
            } else {
                context.contentResolver.update(media.uri, ContentValues().apply { put(MediaStore.Files.FileColumns.IS_TRASHED, 1) }, null, null)
                null
            }
        } catch (e: Exception) { null }
    }

    suspend fun restoreFromTrash(media: Media): IntentSenderRequest? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createTrashRequest(context.contentResolver, listOf(media.uri), false)
                IntentSenderRequest.Builder(pi.intentSender).build()
            } else {
                context.contentResolver.update(media.uri, ContentValues().apply { put(MediaStore.Files.FileColumns.IS_TRASHED, 0) }, null, null)
                null
            }
        } catch (e: Exception) { null }
    }

    suspend fun delete(media: Media): IntentSenderRequest? = withContext(Dispatchers.IO) {
        // Secure Delete: Attempt to overwrite before final deletion
        secureOverwrite(media.uri)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(media.uri))
                IntentSenderRequest.Builder(pi.intentSender).build()
            } else {
                context.contentResolver.delete(media.uri, null, null)
                null
            }
        } catch (e: Exception) { null }
    }

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

    /** Copies external URIs into Pictures/Gal via MediaStore. Returns the number successfully imported. */
    suspend fun importFiles(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var count = 0
        for (uri in uris) {
            try {
                val mimeType = resolver.getType(uri) ?: continue
                if (!mimeType.startsWith("image/") && !mimeType.startsWith("video/")) continue

                val displayName = resolver.query(
                    uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: "${System.currentTimeMillis()}"

                val collection = if (mimeType.startsWith("video/"))
                    android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else
                    android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val values = ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Gal")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val destUri = resolver.insert(collection, values) ?: continue
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        resolver.openOutputStream(destUri)?.use { output -> input.copyTo(output) }
                    }
                    resolver.update(
                        destUri,
                        ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) },
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
