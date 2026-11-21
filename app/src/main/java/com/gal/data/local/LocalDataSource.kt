package com.gal.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import com.gal.model.Album
import com.gal.model.Media
import com.gal.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDataSource @Inject constructor(
    private val contentResolver: ContentResolver,
    private val refresher: MediaStoreRefresher,
) {
    private val mediaProjection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.BUCKET_ID,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Files.FileColumns.DATE_ADDED,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.WIDTH,
        MediaStore.Files.FileColumns.HEIGHT,
        MediaStore.Files.FileColumns.ORIENTATION,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.IS_FAVORITE,
        MediaStore.Files.FileColumns.IS_TRASHED,
        MediaStore.Video.VideoColumns.DURATION,
    )

    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** URIs MediaStore notifies on when a photo or video is added, finished, changed or removed. */
    private val observedUris = listOf(
        filesUri,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
    )

    /**
     * Emits fresh results whenever MediaStore changes.
     *
     * Change notifications only signal "re-query"; the query itself runs on [Dispatchers.IO].
     * Signals that arrive while a query is running are conflated into one follow-up query, so
     * a burst (a camera finishing a pending photo, a bulk copy) costs at most two queries and
     * the newest state is never dropped.
     *
     * Results are ordered newest capture first ([Media.dateTaken]) unless [keepSqlOrder].
     * [includeTrashed] must be true for the trash query — on Android 11+ MediaStore filters
     * trashed rows out of any default query, so an IS_TRASHED=1 SQL clause returns nothing
     * unless we pass MATCH_INCLUDE via the Bundle-based query overload.
     */
    private fun observeQuery(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        includeTrashed: Boolean = false,
        keepSqlOrder: Boolean = false,
    ): Flow<List<Media>> = callbackFlow<Unit> {
        trySend(Unit)

        // null Handler: onChange arrives on a binder thread; it only signals, never queries.
        val observer = object : ContentObserver(null as Handler?) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        observedUris.forEach { contentResolver.registerContentObserver(it, true, observer) }

        // Also re-query when something pokes the refresher (app resume, pull-to-refresh, a
        // known external write). drop(1): the StateFlow replays its current value on collect.
        val tickJob = launch {
            refresher.tick.drop(1).collect { trySend(Unit) }
        }

        awaitClose {
            contentResolver.unregisterContentObserver(observer)
            tickJob.cancel()
        }
    }
        .conflate()
        .map {
            val list = query(selection, selectionArgs, sortOrder, includeTrashed)
            if (keepSqlOrder) list else list.sortedWith(NEWEST_TAKEN_FIRST)
        }
        .flowOn(Dispatchers.IO)

    private fun query(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        includeTrashed: Boolean,
    ): List<Media> {
        val args = Bundle().apply {
            if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            if (includeTrashed) {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
        }
        return contentResolver.query(filesUri, mediaProjection, args, null)?.use { cursor ->
            buildList {
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val dateModCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val orientCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.ORIENTATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val favCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_FAVORITE)
                val trashedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_TRASHED)
                val durCol = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val type = when (cursor.getInt(typeCol)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaType.IMAGE
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaType.VIDEO
                        else -> continue
                    }
                    val contentUri = when (type) {
                        MediaType.IMAGE -> ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        MediaType.VIDEO -> ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                        )
                    }
                    val name = cursor.getString(nameCol)
                    val addedMs = cursor.getLong(dateAddedCol) * 1000
                    val modifiedMs = cursor.getLong(dateModCol) * 1000
                    val takenMs = if (cursor.isNull(dateTakenCol)) 0L else cursor.getLong(dateTakenCol)
                    add(
                        Media(
                            id = id,
                            uri = contentUri,
                            type = type,
                            mimeType = cursor.getString(mimeCol) ?: "",
                            displayName = name,
                            albumId = cursor.getLong(bucketIdCol),
                            albumName = cursor.getString(bucketNameCol) ?: "Unknown",
                            dateAdded = Date(addedMs),
                            dateModified = Date(modifiedMs),
                            width = cursor.getInt(widthCol),
                            height = cursor.getInt(heightCol),
                            orientation = cursor.getInt(orientCol),
                            sizeBytes = cursor.getLong(sizeCol),
                            duration = if (durCol >= 0) cursor.getLong(durCol) else null,
                            isFavourite = cursor.getInt(favCol) == 1,
                            isTrashed = cursor.getInt(trashedCol) == 1,
                            dateTaken = Date(captureTimeMs(takenMs, name, modifiedMs, addedMs)),
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private val mediaTypes =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    // SQL sort is only a tie-breaker hint; observeQuery re-sorts by capture time.
    private val sqlNewest = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"

    fun timeline(): Flow<List<Media>> = observeQuery(
        selection = "$mediaTypes AND ${MediaStore.Files.FileColumns.IS_TRASHED}=0",
        selectionArgs = null,
        sortOrder = sqlNewest,
    )

    fun favourites(): Flow<List<Media>> = observeQuery(
        selection = "$mediaTypes AND ${MediaStore.Files.FileColumns.IS_FAVORITE}=1 AND ${MediaStore.Files.FileColumns.IS_TRASHED}=0",
        selectionArgs = null,
        sortOrder = sqlNewest,
    )

    fun trash(): Flow<List<Media>> = observeQuery(
        selection = "$mediaTypes AND ${MediaStore.Files.FileColumns.IS_TRASHED}=1",
        selectionArgs = null,
        sortOrder = "${MediaStore.Files.FileColumns.DATE_EXPIRES} DESC",
        includeTrashed = true,
        keepSqlOrder = true,
    )

    fun album(albumId: Long): Flow<List<Media>> = observeQuery(
        selection = "$mediaTypes AND ${MediaStore.Files.FileColumns.BUCKET_ID}=? AND ${MediaStore.Files.FileColumns.IS_TRASHED}=0",
        selectionArgs = arrayOf(albumId.toString()),
        sortOrder = sqlNewest,
    )

    fun albums(): Flow<List<Album>> = timeline().map { mediaList ->
        // mediaList is newest capture first, so each group's first item is its newest.
        mediaList
            .groupBy { it.albumId }
            .map { (_, items) -> items.first() to items.size }
            .sortedByDescending { (newest, _) -> newest.dateTaken }
            .map { (newest, count) ->
                Album(
                    id = newest.albumId,
                    name = newest.albumName,
                    coverUri = newest.uri,
                    mediaCount = count,
                )
            }
    }

    fun search(query: String): Flow<List<Media>> {
        if (query.isBlank()) {
            return kotlinx.coroutines.flow.flowOf(emptyList())
        }
        // SQL LIKE with parameter binding — safe against injection. The wildcards live in
        // the bound argument, not the SQL string. % and _ inside the query string act as
        // LIKE wildcards, which is reasonable user-search behaviour.
        val pattern = "%$query%"
        return observeQuery(
            selection = "$mediaTypes AND ${MediaStore.Files.FileColumns.IS_TRASHED}=0" +
                " AND (${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" +
                " OR ${MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME} LIKE ?)",
            selectionArgs = arrayOf(pattern, pattern),
            sortOrder = sqlNewest,
        )
    }

    companion object {
        /** Newest capture first; id breaks ties so the order is stable between queries. */
        val NEWEST_TAKEN_FIRST: Comparator<Media> =
            compareByDescending<Media> { it.dateTaken }.thenByDescending { it.id }

        /** Earliest plausible capture time (2000-01-01); anything before is a bogus clock. */
        private const val MIN_PLAUSIBLE_MS = 946_684_800_000L

        // IMG_20240315_142233.jpg, PXL_20240315_142233123.jpg, Screenshot_2024-03-15-14-22-33.png,
        // VID_20240315_142233.mp4, signal-2024-03-15-142233.jpg, 2024-03-15 14.22.33.jpg ...
        private val NAME_DATE = Regex(
            """(?<!\d)((?:19|20)\d{2})[-_.]?(\d{2})[-_.]?(\d{2})""" +
                """(?:[ _T-]?(\d{2})[-_.:h]?(\d{2})[-_.:m]?(\d{2})(?:\d{3})?)?(?!\d)"""
        )

        /**
         * Capture time: MediaStore DATE_TAKEN (EXIF DateTimeOriginal / video creation time),
         * else a date in the file name (files without EXIF, e.g. screenshots and messenger
         * images, keep their capture date in the name), else the file's modified time.
         */
        fun captureTimeMs(takenMs: Long, name: String?, modifiedMs: Long, addedMs: Long): Long {
            if (takenMs >= MIN_PLAUSIBLE_MS) return takenMs
            dateFromName(name)?.let { return it }
            if (modifiedMs >= MIN_PLAUSIBLE_MS) return modifiedMs
            return addedMs
        }

        fun dateFromName(name: String?): Long? {
            if (name.isNullOrEmpty()) return null
            val m = NAME_DATE.find(name) ?: return null
            val g = m.groupValues
            return runCatching {
                LocalDateTime.of(
                    g[1].toInt(), g[2].toInt(), g[3].toInt(),
                    g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
                ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()?.takeIf { it in MIN_PLAUSIBLE_MS..(System.currentTimeMillis() + DAY_MS) }
        }

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
