package com.fenyx.jtv.ui.player

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.fenyx.jtv.ui.main.MainViewModel

/** What is drawn over the video. Exactly one at a time keeps the key model (and Back) simple. */
enum class PlayerOverlay { None, Banner, Browse, Options, Menu }

/** Pages of the right-side options panel. Sub pages are inline lists, never a separate dialog window. */
enum class OptionsPage { Main, Language, Quality, Aspect, Voice, Sleep }

/** The ways a failed channel is explained. Each maps to one sentence and one or two buttons. */
enum class ErrorAction { Retry, NextChannel, Settings }

@Immutable
data class PlayerError(val message: String, val primary: ErrorAction, val secondary: ErrorAction? = null)

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

    private var lastBump = 0L

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
internal val StrapBg = Color(0xFF141416)

internal fun groupLabel(group: String?): String = when (group) {
    null, MainViewModel.GROUP_ALL -> "All channels"
    MainViewModel.GROUP_FAVORITES -> "Favourites"
    else -> group
}

internal val QUALITY_OPTIONS = listOf(
    "auto" to "Auto (recommended)", "high" to "Best picture", "medium" to "Good picture", "low" to "Data saver",
)
internal val ASPECT_OPTIONS = listOf(0 to "Fit", 3 to "Stretch", 4 to "Zoom")
internal val VOICE_OPTIONS = listOf(0 to "Off", 1 to "Low", 2 to "Medium", 3 to "High", 4 to "Most")
internal val SLEEP_OPTIONS = listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 90 to "1 hour 30 min", 120 to "2 hours")

internal fun qualityLabel(q: String) = QUALITY_OPTIONS.firstOrNull { it.first == q }?.second ?: "Auto (recommended)"
internal fun aspectLabel(m: Int) = ASPECT_OPTIONS.firstOrNull { it.first == m }?.second ?: "Fit"
internal fun voiceLabel(v: Int) = VOICE_OPTIONS.firstOrNull { it.first == v }?.second ?: "Off"
internal fun sleepLabel(m: Int) = if (m == 0) "Off" else SLEEP_OPTIONS.firstOrNull { it.first == m }?.second ?: "$m min"
