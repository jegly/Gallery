package com.gal.data.raw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * Coil fetcher that renders camera RAW files (ARW, DNG, NEF, ...) from the JPEG preview embedded
 * in the file. Anything that isn't RAW is passed on to Coil's normal fetchers by returning null.
 */
class RawPreviewFetcher(
    private val uri: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val resolver = options.context.contentResolver
        val mime = resolver.getType(uri)
        if (!RawFormats.isRaw(null, mime)) {
            // Common formats and video are never RAW: skip the display-name lookup.
            if (mime != null && (mime.startsWith("video/") || mime in COMMON_MIME)) return@withContext null
            if (!RawFormats.isRaw(displayName(), mime)) return@withContext null
        }

        val target = targetLongSide()
        val bitmap = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { stream ->
                val result = runCatching { RawPreviewReader(stream.channel).read() }.getOrNull()
                    ?: return@use null
                val pick = result.previews.firstOrNull { it.longSide >= target }
                    ?: result.previews.last()
                val jpeg = ByteArray(pick.length.toInt())
                val read = stream.channel.read(java.nio.ByteBuffer.wrap(jpeg), pick.offset)
                if (read < jpeg.size) return@use null
                decode(jpeg, pick, target)?.let { rotate(it, result.orientation.takeIf { o -> o > 1 } ?: exifOrientation(jpeg)) }
            }
        } ?: thumbnailFallback()
        ?: error("No embedded preview found in $uri")

        ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun decode(jpeg: ByteArray, pick: RawPreviewReader.Preview, target: Int): Bitmap? {
        var sample = 1
        while (pick.longSide / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    /** Last resort for RAW variants the walker can't map: the small EXIF thumbnail. */
    private fun thumbnailFallback(): Bitmap? = runCatching {
        options.context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val bytes = exif.thumbnailBytes ?: return@use null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { rotate(it, exifOrientationValue(exif)) }
        }
    }.getOrNull()

    private fun exifOrientation(jpeg: ByteArray): Int = runCatching {
        exifOrientationValue(ExifInterface(ByteArrayInputStream(jpeg)))
    }.getOrDefault(1)

    private fun exifOrientationValue(exif: ExifInterface) =
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    private fun rotate(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun targetLongSide(): Int {
        val w = (options.size.width as? Dimension.Pixels)?.px
        val h = (options.size.height as? Dimension.Pixels)?.px
        return maxOf(w ?: 0, h ?: 0).takeIf { it > 0 } ?: Int.MAX_VALUE
    }

    private fun displayName(): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            options.context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    private companion object {
        val COMMON_MIME = setOf(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "image/heic", "image/heif", "image/avif", "image/bmp",
        )
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "content" && data.scheme != "file") return null
            return RawPreviewFetcher(data.toAndroidUri(), options)
        }
    }
}
