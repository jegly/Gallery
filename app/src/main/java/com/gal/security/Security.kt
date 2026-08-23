package com.gal.security

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignatureVerifier @Inject constructor() {

    private val trustedSha256 = "98d324d4106a368c62729a0a24d9ac9a6b47f8ac4c6585348531f0ee4eb6a04c"

    fun isSignedByTrustedCert(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        signers.any { sig ->
            digest.reset()
            digest.digest(sig.toByteArray()).toHex() == trustedSha256
        }
    }.getOrDefault(false)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

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
        // Keep share temp files in a dedicated subdirectory so the FileProvider path
        // declaration can be scoped tightly (see res/xml/filepaths.xml).
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val tmp = File(shareDir, "share_${System.currentTimeMillis()}.$ext")

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
        File(context.cacheDir, "share").listFiles()?.forEach { it.delete() }
        // Legacy cleanup: previous versions wrote share_* directly in cacheDir.
        context.cacheDir.listFiles { f ->
            f.name.startsWith("share_") && f.isFile
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
