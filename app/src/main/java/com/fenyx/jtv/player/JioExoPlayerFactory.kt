package com.fenyx.jtv.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@UnstableApi
object JioExoPlayerFactory {

    fun create(context: Context, preferredAudioLang: String): ExoPlayer {
        // Amlogic Audio Sync Fix
        val audioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return audioSink
            }
        }

        // HW+ Fix: Prefer MediaCodec Hardware decoders
        renderersFactory.setMediaCodecSelector(MediaCodecSelector.DEFAULT)
        // CRITICAL for low-end TVs: Disable software extension renderers completely so it doesn't fall back to ffmpeg and kill the CPU
        renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        renderersFactory.setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context)
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage(preferredAudioLang)
            .setTunnelingEnabled(true) // CRITICAL: Amlogic fix for extreme hardware acceleration
            // Removed .setMaxVideoSizeSd() so we can handle quality dynamically in TvPlayerScreen
            .build()

        // Optimize buffering to prevent lag on low-end TVs (drastically lower than default to save RAM)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2000,  // Min buffer: 2 seconds
                15000, // Max buffer: 15 seconds (reduced from default 50s)
                1000,  // Buffer for playback: 1 second
                2000   // Buffer for playback after rebuffer: 2 seconds
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
    }
}
