package com.gal.security

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strips GPS, device, and timing EXIF data before sharing.
 * Works on a temp copy — original file is never modified.
 */
@Singleton
class ExifScrubber @Inject constructor() {

    private val stripTags = listOf(
        ExifInterface.TAG_GPS_LATITUDE,         ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,         ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_SPEED,            ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,            ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,    ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_MAKE,                 ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,             ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE,            ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,   ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,      ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,            ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,   ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    )

    /** Returns a FileProvider URI to a scrubbed temp copy. Call from IO dispatcher. */
    fun scrubAndShare(context: Context, sourceUri: Uri): Uri {
        val ext = context.contentResolver.getType(sourceUri)
            ?.substringAfterLast("/") ?: "jpg"
        val tmp = File(context.cacheDir, "share_${System.currentTimeMillis()}.$ext")

        context.contentResolver.openInputStream(sourceUri)?.use { it.copyTo(tmp.outputStream()) }
            ?: throw IllegalArgumentException("Cannot open: $sourceUri")

        // Strip EXIF from all formats ExifInterface supports writing back to
        if (ext.lowercase() in listOf("jpg", "jpeg", "heic", "heif", "webp")) {
            ExifInterface(tmp).apply {
                stripTags.forEach { setAttribute(it, null) }
                saveAttributes()
            }
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp)
    }

    fun clearCache(context: Context) {
        context.cacheDir.listFiles { f ->
            f.name.startsWith("share_") &&
                    f.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic")
        }?.forEach { it.delete() }
    }
}

/**
 * Allowlist validator — only content:// and file:// URIs
 * may reach Coil or ExoPlayer. Blocks any http/https URI
 * that could arrive via a crafted intent.
 */
@Singleton
class UriValidator @Inject constructor() {
    private val allowed = setOf("content", "file")

    fun isLocal(uri: Uri?): Boolean = uri?.scheme?.lowercase() in allowed

    fun requireLocal(uri: Uri): Uri {
        check(isLocal(uri)) {
            "Security: rejected non-local URI scheme '${uri.scheme}'"
        }
        return uri
    }

    fun localOrNull(uri: Uri?): Uri? = if (isLocal(uri)) uri else null
}
