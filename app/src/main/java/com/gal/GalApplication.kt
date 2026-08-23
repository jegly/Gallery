package com.gal

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.video.VideoFrameDecoder
import com.gal.di.galDataStore
import com.gal.security.ExifScrubber
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@HiltAndroidApp
class GalApplication : Application(), SingletonImageLoader.Factory {

    companion object {
        private val KEY_SECURE_THUMBNAIL_CACHE = booleanPreferencesKey("secure_thumbnail_cache")
    }

    override fun onCreate() {
        super.onCreate()
        ExifScrubber().clearCache(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val secureThumbnails = runBlocking {
            applicationContext.galDataStore.data.first()[KEY_SECURE_THUMBNAIL_CACHE] ?: false
        }
        return ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .apply { if (secureThumbnails) diskCachePolicy(CachePolicy.DISABLED) }
            .build()
    }
}
