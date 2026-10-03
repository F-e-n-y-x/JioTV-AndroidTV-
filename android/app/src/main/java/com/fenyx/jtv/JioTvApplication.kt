package com.fenyx.jtv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class JioTvApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Self-hosted crash reports first, so a crash anywhere below is still recorded.
        com.fenyx.jtv.crash.CrashReports.install(this)
        collectAndUploadReports()
        // Android 7–12: the in-app language also applies to strings read via the application context.
        com.fenyx.jtv.i18n.AppLocale.applyToApp(this)
        // Keep the Jio access token fresh in the background (see TokenRefreshScheduler).
        com.fenyx.jtv.data.TokenRefreshScheduler.schedule(this)
        // Device-to-device sync over the home Wi-Fi; runs only while the app is in the foreground.
        com.fenyx.jtv.sync.LanSync.init(this)
        // Pre-open connections to Jio's video servers so the first channel starts sooner.
        com.fenyx.jtv.data.Net.warmUp()
    }

    /**
     * Off the startup path: store ANRs / native crashes since the last run (API 30+), then, when this
     * device signs in through a JTV server, upload any reports the server hasn't got yet.
     */
    private fun collectAndUploadReports() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(5_000)
            val reports = com.fenyx.jtv.crash.CrashReports
            reports.collectExitReasons(this@JioTvApplication)
            runCatching {
                val settings = com.fenyx.jtv.data.SettingsManager(this@JioTvApplication)
                val mode = settings.setupModeFlow.first()
                if (mode == "jtv" || mode == "server") {
                    val urls = com.fenyx.jtv.data.ServerClient.candidateUrls(mode, settings.serverUrlFlow.first())
                    reports.uploadPending(this@JioTvApplication, urls, settings.serverTokenFlow.first())
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.fenyx.jtv.i18n.AppLocale.applyToApp(this) // a system config change resets the app resources' locale
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
