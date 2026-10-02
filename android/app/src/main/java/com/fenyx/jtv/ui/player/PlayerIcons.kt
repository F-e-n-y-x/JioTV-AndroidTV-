package com.fenyx.jtv.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material icons the player needs that are not in material-icons-core (the extended set is a
 * multi-MB dependency). Same 24dp grid and path data as the Material originals, built lazily once.
 */
internal object PlayerIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val StarBorder by lazy {
        icon(
            "StarBorder",
            "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24z" +
                "M12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
        )
    }
    val Translate by lazy {
        icon(
            "Translate",
            "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7L10,2L8,2v2L1,4v1.99h11.17" +
                "C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19" +
                "l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33L19.12,17h-3.24z",
        )
    }
    val Hd by lazy {
        icon(
            "Hd",
            "M19,3L5,3c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2z" +
                "M11,15L9.5,15v-2h-2v2L6,15L6,9h1.5v2.5h2L9.5,9L11,9v6zM13,9h4c0.55,0 1,0.45 1,1v4c0,0.55 -0.45,1 -1,1h-4L13,9z" +
                "M14.5,13.5h2v-3h-2v3z",
        )
    }
    val Timer by lazy {
        icon(
            "Timer",
            "M15,1L9,1v2h6L15,1zM11,14h2L13,8h-2v6zM19.03,7.39l1.42,-1.42c-0.43,-0.51 -0.9,-0.99 -1.41,-1.41l-1.42,1.42" +
                "C16.07,4.74 14.12,4 12,4c-4.97,0 -9,4.03 -9,9s4.02,9 9,9 9,-4.03 9,-9c0,-2.12 -0.74,-4.07 -1.97,-5.61z" +
                "M12,20c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        )
    }
    val Pause by lazy { icon("Pause", "M6,19h4L10,5L6,5v14zM14,5v14h4L18,5h-4z") }
    val SkipPrevious by lazy { icon("SkipPrevious", "M6,6h2v12L6,18zM9.5,12l8.5,6L18,6z") }
    val SkipNext by lazy { icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2L18,6h-2z") }
    val Fullscreen by lazy {
        icon(
            "Fullscreen",
            "M7,14L5,14v5h5v-2L7,17v-3zM5,10h2L7,7h3L10,5L5,5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2L19,5h-5z",
        )
    }
    val FullscreenExit by lazy {
        icon(
            "FullscreenExit",
            "M5,16h3v3h2v-5L5,14v2zM8,8L5,8v2h5L10,5L8,5v3zM14,19h2v-3h3v-2h-5v5zM16,8L16,5h-2v5h5L19,8h-3z",
        )
    }
}
