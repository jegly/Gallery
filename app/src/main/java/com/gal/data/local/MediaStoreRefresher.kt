package com.gal.data.local

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared tick that any [LocalDataSource] flow can listen to. Bumping it re-queries MediaStore
 * (cheap, no scan). It also asks the media scanner to walk the public media folders, at most
 * every [SCAN_INTERVAL_MS], to pick up files written by tools that bypassed MediaStore.
 *
 * The scan used to run on every resume: on a large library that is a walk over tens of
 * thousands of files each time the app comes back, and every row it touches fires a
 * MediaStore change notification. Since Android 11 anything written through the normal
 * storage APIs (camera apps, file managers, USB/MTP copies) is indexed by MediaStore on close,
 * so the scan is only a safety net.
 */
@Singleton
class MediaStoreRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private var lastScan = 0L

    fun bump() {
        val now = SystemClock.elapsedRealtime()
        _tick.value = now
        if (lastScan == 0L || now - lastScan >= SCAN_INTERVAL_MS) {
            lastScan = now
            triggerScan()
        }
    }

    private fun triggerScan() {
        val dirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        )
        val paths = dirs.filter { it.exists() }.map { it.absolutePath }.toTypedArray()
        if (paths.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(context, paths, null, null)
            }
        }
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
