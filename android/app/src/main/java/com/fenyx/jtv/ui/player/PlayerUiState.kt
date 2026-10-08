package com.fenyx.jtv.ui.player

import com.fenyx.jtv.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.fenyx.jtv.ui.main.MainViewModel

/** What is drawn over the video. Exactly one at a time keeps the key model (and Back) simple. */
/**
 * [Banner]: the info strap on TV, and the YouTube-style controls layer on touch. [Controls]: the TV
 * controls layer (OK opens it): top bar, Previous / Play-Pause / Next, seek bar + Live.
 */
enum class PlayerOverlay { None, Banner, Browse, Options, Menu, Controls }

/** Pages of the right-side options panel. Sub pages are inline lists, never a separate dialog window. */
enum class OptionsPage { Main, Language, Quality, Aspect, Voice, Sleep }

/** The ways a failed channel is explained. Each maps to one sentence and one or two buttons. */
enum class ErrorAction { Retry, NextChannel, Settings, GoLive }

@Immutable
data class PlayerError(@androidx.annotation.StringRes val message: Int, val primary: ErrorAction, val secondary: ErrorAction? = null)

/**
 * Overlay state, kept apart from playback state so only the small overlay composables that read a field
 * recompose when it changes (the video surface never does).
 */
@Stable
class PlayerUi(initial: PlayerOverlay) {
    var overlay by mutableStateOf(initial)
    /** Bumped to restart the banner's auto-hide timer. */
    var bannerToken by mutableIntStateOf(0)
    var optionsPage by mutableStateOf(OptionsPage.Main)
    /** The page the panel was opened on (a sub page opened directly closes after a pick). */
    var optionsEntry by mutableStateOf(OptionsPage.Main)

    /** Channel-number entry buffer ("" = not entering). */
    var number by mutableStateOf("")
    /** Short message after a number that matched nothing. */
    var numberMiss by mutableStateOf<String?>(null)

    /** Mouse moved: show the top bar with Back (hidden again by the next arrow key). */
    var pointerChrome by mutableStateOf(false)

    // Channel browse (TV tile rail / touch rail)
    var browseGroup by mutableStateOf<String?>(null)
    var browseIndex by mutableIntStateOf(0)
    /** Bumped to move focus (and scroll) to [browseIndex]. */
    var browseFocusToken by mutableIntStateOf(0)

    /** TV controls layer: bumped to move focus to [controlsFocusTarget] when the layer opens. */
    var controlsFocusToken by mutableIntStateOf(0)
    var controlsFocusTarget = ControlFocus.Play
    /** Which control holds focus (set by the controls themselves; read by the key handler only). */
    var focusedControl = ControlFocus.None

    private var lastBump = 0L

    /** TV: open the controls layer (or keep it open) and put focus on [focus]. */
    fun openControls(focus: ControlFocus = ControlFocus.Play) {
        if (overlay == PlayerOverlay.None || overlay == PlayerOverlay.Banner || overlay == PlayerOverlay.Controls) {
            val opening = overlay != PlayerOverlay.Controls
            overlay = PlayerOverlay.Controls
            bannerToken++
            if (opening || focus != ControlFocus.Play) {
                controlsFocusTarget = focus
                controlsFocusToken++
            }
        }
    }

    fun showBanner() {
        if (overlay == PlayerOverlay.None || overlay == PlayerOverlay.Banner) {
            overlay = PlayerOverlay.Banner
            bannerToken++
        }
    }

    /** Restart the auto-hide timer at most twice a second (pointer moves arrive at 60+ Hz). */
    fun bumpThrottled() {
        val t = System.currentTimeMillis()
        if (t - lastBump > 500) { lastBump = t; bannerToken++ }
    }

    fun openOptions(page: OptionsPage = OptionsPage.Main) {
        optionsPage = page
        optionsEntry = page
        overlay = PlayerOverlay.Options
    }
}

/** The one solid strap colour (approved mockups). Everything else uses Jtv.colors tokens. */
internal val StrapBg = Color(0xC7141416) // ~78%: readable over bright video, still shows the picture

@Composable
internal fun groupLabel(group: String?): String = when (group) {
    null, MainViewModel.GROUP_ALL -> stringResource(R.string.player_group_all)
    MainViewModel.GROUP_FAVORITES -> stringResource(R.string.common_favourites)
    MainViewModel.GROUP_RECENT -> stringResource(R.string.player_group_recent)
    else -> com.fenyx.jtv.ui.main.groupLabel(group)
}

internal val QUALITY_OPTIONS = listOf(
    "auto" to R.string.player_quality_auto, "high" to R.string.player_quality_high,
    "medium" to R.string.player_quality_medium, "low" to R.string.player_quality_low,
)
internal val ASPECT_OPTIONS = listOf(0 to R.string.player_aspect_fit, 3 to R.string.player_aspect_stretch, 4 to R.string.player_aspect_zoom)
internal val VOICE_OPTIONS = listOf(
    0 to R.string.common_off, 1 to R.string.player_voice_low, 2 to R.string.player_voice_medium,
    3 to R.string.player_voice_high, 4 to R.string.player_voice_most,
)
/** Sleep timer choices in minutes (0 = off). */
internal val SLEEP_OPTIONS = listOf(0, 15, 30, 60, 90, 120)

internal fun voiceLabelRes(v: Int) = VOICE_OPTIONS.firstOrNull { it.first == v }?.second ?: R.string.common_off

@Composable
internal fun qualityLabel(q: String) = stringResource(QUALITY_OPTIONS.firstOrNull { it.first == q }?.second ?: R.string.player_quality_auto)
@Composable
internal fun aspectLabel(m: Int) = stringResource(ASPECT_OPTIONS.firstOrNull { it.first == m }?.second ?: R.string.player_aspect_fit)
@Composable
internal fun voiceLabel(v: Int) = stringResource(voiceLabelRes(v))

internal fun sleepLabel(res: android.content.res.Resources, m: Int): String = when (m) {
    0 -> res.getString(R.string.common_off)
    60 -> res.getString(R.string.player_sleep_1h)
    90 -> res.getString(R.string.player_sleep_1h30)
    120 -> res.getString(R.string.player_sleep_2h)
    else -> res.getQuantityString(R.plurals.player_minutes, m, m)
}

@Composable
internal fun sleepLabel(m: Int): String {
    androidx.compose.ui.platform.LocalConfiguration.current // re-read when the language changes
    return sleepLabel(androidx.compose.ui.platform.LocalContext.current.resources, m)
}
