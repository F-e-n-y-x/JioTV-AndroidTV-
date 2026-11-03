package com.fenyx.jtv.ui.player

private fun normRatio(s: String?): String? = when (s?.trim()?.lowercase()?.replace(':', 'x')) {
    "4x3" -> "4x3"
    "16x9" -> "16x9"
    else -> null
}

/**
 * Effective display aspect of a stream. SD PAL streams (e.g. 720x576) often omit SAR, giving raw
 * 1.25 instead of 4:3; [declared] is the optional Jio "aspectRatio" ("4x3"/"4:3"/"16x9"/"16:9").
 * Unknown size -> 16:9.
 */
fun correctedStreamAspect(rawW: Int, rawH: Int, pixelAspect: Float, declared: String?): Float {
    val par = if (pixelAspect > 0f) pixelAspect else 1f
    val aspect = if (rawW > 0 && rawH > 0) (rawW * par) / rawH.toFloat() else 16f / 9f
    val d = normRatio(declared)
    return when {
        // Jio's "aspectRatio" says 4x3 for ~80% of channels, HD ones included (Sony Vizha), so it
        // never decides the shape on its own: only a 5:4-ish SD frame without SAR is fixed to 4:3.
        d != "16x9" && rawH in 1..576 && aspect in 1.15f..1.35f -> 4f / 3f
        d == "16x9" && aspect < 1.5f && (rawH in 1..576 || rawW in 1..1024) -> 16f / 9f
        else -> aspect
    }
}
