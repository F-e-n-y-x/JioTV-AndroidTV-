package com.fenyx.jtv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class JioTvApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Keep the Jio access token fresh in the background (see TokenRefreshScheduler).
        com.fenyx.jtv.data.TokenRefreshScheduler.schedule(this)
        // Device-to-device sync over the home Wi-Fi; runs only while the app is in the foreground.
        com.fenyx.jtv.sync.LanSync.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20) // 20% of RAM — a bit more headroom keeps logos hot while scrolling
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .maxSizeBytes(64L * 1024 * 1024) // 64 MB disk cache (channel logos rarely change)
                    .directory(cacheDir.resolve("image_cache"))
                    .build()
            }
            .crossfade(false)          // no fade → less GPU compositing on weak TV GPUs
            .allowRgb565(true)         // 16-bit bitmaps for opaque logos → ~half the memory + faster decode
            .allowHardware(true)       // GPU-backed bitmaps (skips a CPU copy)
            .respectCacheHeaders(false) // trust the cache; never re-validate logos over the network
            // Same pooled HTTP/2 client as the player, so logo and stream connections are shared.
            .okHttpClient { com.fenyx.jtv.data.Net.client }
            .build()
    }
}
