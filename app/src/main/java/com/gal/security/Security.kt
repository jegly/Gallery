package com.gal.security

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignatureVerifier @Inject constructor() {

    // Expected signing-cert SHA-256 digests, stored XOR-masked (never as greppable hex — string
    // literals survive R8) and compared as raw bytes. M = jegly key0 (98d324d4…),
    // P = ML-DSA-87 PQC key (5f99de5b…).
    // API 37+ verifies the APK Signature Scheme v3.2 hybrid block, so the current signer is the PQC
    // key there. key0 is deliberately NOT accepted on API 37+: an APK re-signed with only key0 would
    // otherwise pass. Older releases ignore v3.2 and still see key0.
    private val k = intArrayOf(0x11, 0xE9, 0xB0, 0x75, 0x72, 0xDA, 0xA4, 0x67, 0x97)
    private val m = intArrayOf(
        0x89, 0x3A, 0x94, 0xA1, 0x62, 0xB0, 0x92, 0xEB, 0xF5, 0x63, 0x73, 0xBA, 0x51, 0xAB, 0x76, 0x3E,
        0x0C, 0xD0, 0xE9, 0x45, 0xFC, 0x10, 0xF7, 0xEE, 0x21, 0x56, 0x67, 0xFF, 0xA7, 0x06, 0xD5, 0x3E,
    )
    private val p = intArrayOf(
        0x4E, 0x70, 0x6E, 0x2E, 0x43, 0x4F, 0xD1, 0x5A, 0xD8, 0xF9, 0x34, 0x1B, 0x9E, 0xA0, 0x21, 0x83,
        0x52, 0xFC, 0x30, 0xE0, 0x66, 0x8E, 0xC0, 0x9D, 0xBB, 0xAC, 0x2A, 0xE3, 0xF3, 0x2E, 0x03, 0xD4,
    )

    /** Reconstruct the expected 32-byte cert digest from the masked form (never materialized as hex). */
    private fun expected(): ByteArray {
        val masked = if (Build.VERSION.SDK_INT >= 37) p else m
        return ByteArray(masked.size) { (masked[it] xor k[it % k.size]).toByte() }
    }

    fun isSignedByTrustedCert(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        // Current signer only — never signingCertificateHistory, which still contains key0.
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        val digest = MessageDigest.getInstance("SHA-256")
        val want = expected()
        signers.any { sig ->
            digest.reset()
            digest.digest(sig.toByteArray()).contentEquals(want)
        }
    }.getOrDefault(false)
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
