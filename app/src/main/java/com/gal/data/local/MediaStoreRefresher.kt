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
 * (cheap, no scan) and also pokes MediaScannerConnection to pick up files dropped by file
 * managers that didn't notify MediaStore themselves.
 */
@Singleton
class MediaStoreRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    fun bump() {
        _tick.value = SystemClock.elapsedRealtime()
        triggerScan()
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
}
