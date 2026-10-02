package com.fenyx.jtv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

@UnstableApi
object JioExoPlayerFactory {

    /**
     * Optimistic first-chunk bandwidth estimate (12 Mbps). Jio's top live rendition is ~3 Mbps
     * (`maxrate=3024000`), so this comfortably clears the highest rung and ABR selects it immediately
     * instead of ramping from the ~80 kbps floor.
     */
    private const val INITIAL_VIDEO_BITRATE_ESTIMATE = 12_000_000L

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
            .setEnableAudioOutputPlaybackParameters(false)
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

        // ── Start at HIGH quality, not the bottom rung ──────────────────────────────────────────────
        // Jio's manifest advertises renditions all the way down to ~80 kbps (see `minrate=80000` in the
        // stream URL). ExoPlayer's ABR picks its first rendition from the bandwidth *estimate*, and the
        // built-in default estimate is deliberately pessimistic — so playback began at ~480p and only
        // crawled up over ~30s. These TVs are on wired/strong Wi-Fi, so seed an optimistic estimate and
        // let ABR correct downward if it's wrong (fast) rather than upward from the floor (slow).
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(C.TRACK_TYPE_VIDEO, INITIAL_VIDEO_BITRATE_ESTIMATE)
            .build()

        // Climb quality quickly and drop reluctantly: default requires 10s of buffer before stepping
        // up, which is why the picture stayed soft for so long after a zap.
        val adaptiveFactory = AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 3_000,
            /* maxDurationForQualityDecreaseMs = */ 20_000,
            /* minDurationToRetainAfterDiscardMs = */ 15_000,
            /* bandwidthFraction = */ 0.9f
        )

        val trackSelector = DefaultTrackSelector(context, adaptiveFactory)
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage(preferredAudioLang)
            .setTunnelingEnabled(tunneling)
            // ─── The "picture glitches for a second mid-programme" fix ───
            // On these boxes every ABR resolution change tears down and re-initialises the SECURE AVC
            // decoder and renegotiates the Widevine session. Logcat during a 720p->1080p switch showed
            // repeated CryptoHal re-init, "WIDEVINE_FLOW_CONTROL Command 16505: 43993 us" (a 44ms
            // block), OMX "MS.AVC.Decoder.secure ... ParamPortDefinition ERROR", and the AudioTrack
            // being recreated. That is the visible hitch.
            //
            // Secure decoders on MediaTek/Amlogic TV SoCs don't support seamless adaptation, so we
            // refuse switches that would not be seamless: ABR holds its rendition instead of glitching.
            // For a LIVE TV app a rock-steady picture beats chasing the last rung of bitrate.
            .setAllowVideoNonSeamlessAdaptiveness(false)
            // Quality bounds (min AND max) are applied per-selection in TvPlayerScreen so an explicit
            // user choice is a FLOOR as well as a ceiling.
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
                1200,   // Buffer required to START — low so channels zap in ~1s instead of ~3s
                5000    // Buffer required to resume after a rebuffer — short so a stall isn't a long freeze
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            // Smaller back-buffer: 20s of already-played video is wasted RAM on weak TVs (they only
            // rewind within the ~1-min live window anyway) and adds GC pressure.
            .setBackBuffer(8000, true)
            .build()

        // ─── The "picture blinks/judders every few seconds" fix ───
        // Hold a stable distance from the live edge — but do it RARELY and GENTLY.
        //
        // The defaults are far too twitchy for a TV: DefaultLivePlaybackSpeedControl re-evaluates every
        // 1s and corrects as soon as the live offset drifts by just 20ms, over a wide 0.97x–1.03x range.
        // On 25fps broadcast content that meant playback speed was being modulated continuously — the
        // MediaTek decoder log showed the decode rate oscillating 25 -> 24 -> 25 fps forever, with
        // E:0,D:0,S:0 (no dropped or skipped frames, so the decoder was fine). A constantly shifting
        // frame cadence against a fixed-Hz panel is seen as a periodic judder/blink.
        //
        // So: tolerate a full second of drift before touching speed, re-evaluate at most every 5s, keep
        // the correction sub-perceptual (±0.5%), and apply it gently. Live-edge tracking still works
        // (over minutes, which is all it needs to do) without ever visibly changing cadence.
        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.995f)
            .setFallbackMaxPlaybackSpeed(1.005f)
            .setMaxLiveOffsetErrorMsForUnitSpeed(1_000)
            .setMinUpdateIntervalMs(5_000)
            .setProportionalControlFactor(0.1f)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            // Dynamic scheduling is explicitly turned OFF. Since Media3 1.11 ExoPlayer.Builder enables
            // it by default, so leaving the call out silently switched it on. It alters the playback
            // loop's timing and was live on the build where mid-playback stutter was reported; the
            // theoretical CPU saving isn't worth risking a steady picture on a live TV app. Revisit
            // only with an on-device A/B on the weakest TV box.
            .experimentalSetDynamicSchedulingEnabled(false)
            .build()
    }
}
