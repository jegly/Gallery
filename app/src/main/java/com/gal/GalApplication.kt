package com.gal

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.gal.security.ExifScrubber
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GalApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        ExifScrubber().clearCache(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
