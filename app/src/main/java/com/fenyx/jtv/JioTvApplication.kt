package com.fenyx.jtv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class JioTvApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // 15% of available RAM (default is 25%)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .maxSizeBytes(50L * 1024 * 1024) // 50 MB disk cache
                    .directory(cacheDir.resolve("image_cache"))
                    .build()
            }
            .crossfade(false) // Disable crossfade globally to reduce GPU compositing on weak GPUs
            .build()
    }
}
