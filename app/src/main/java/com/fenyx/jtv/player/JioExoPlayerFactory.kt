package com.fenyx.jtv.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@UnstableApi
object JioExoPlayerFactory {

    /**
     * @param tunneling  Tunneled playback. Off by default: on many Amlogic/MediaTek TVs (incl. MiTV)
     *                   tunneling is the main cause of random black screens / video freezing while
     *                   audio keeps playing. Only enable if a specific device needs it for A/V sync.
     * @param hardwareOnly  When true (default), software extension renderers are disabled so the app
     *                      never falls back to ffmpeg/software decode that would max out a weak CPU.
     *                      When false, software decoding is allowed as a fallback.
     */
    fun create(
        context: Context,
        preferredAudioLang: String,
        tunneling: Boolean = false,
        hardwareOnly: Boolean = true,
        maxBufferSec: Int = 60,
        dialogueProcessor: androidx.media3.common.audio.AudioProcessor? = null
    ): ExoPlayer {
        // Amlogic Audio Sync Fix + optional dialogue (center-channel) processor. We insert the
        // processor via DefaultAudioProcessorChain so the built-in Sonic processor (used by the live
        // speed control) is preserved.
        val sinkBuilder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
        if (dialogueProcessor != null) {
            sinkBuilder.setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(dialogueProcessor)
            )
        }
        val audioSink = sinkBuilder.build()

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
        // On low-end TVs, disabling software extension renderers prevents an ffmpeg fallback that
        // would max out the CPU. Controlled by the "Hardware Decoder" setting.
        renderersFactory.setExtensionRendererMode(
            if (hardwareOnly) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )
        renderersFactory.setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context)
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage(preferredAudioLang)
            .setTunnelingEnabled(tunneling)
            // Removed .setMaxVideoSizeSd() so we can handle quality dynamically in TvPlayerScreen
            .build()

        // Buffering tuned for smooth LIVE playback on a wired connection. Large buffers ride out CDN
        // stalls at the live edge (the main cause of mid-view "loading" + black flashes). largeHeap is
        // set in the manifest so this is comfortably within RAM. After a rebuffer we wait for a solid
        // cushion (12s) before resuming so playback doesn't stutter-loop.
        val maxBufferMs = (maxBufferSec.coerceIn(15, 180)) * 1000
        val minBufferMs = (maxBufferMs / 2).coerceAtLeast(15000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                3000,   // Buffer required to start/resume playback
                12000   // Buffer required to resume after a rebuffer (build a cushion first)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            // Keep a back-buffer so brief upstream gaps can be smoothed without a hard rebuffer.
            .setBackBuffer(20000, true)
            .build()

        // Gently nudge playback speed (0.97x–1.03x) to hold a stable distance from the live edge
        // instead of repeatedly draining the buffer and rebuffering.
        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.03f)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .build()
    }
}
