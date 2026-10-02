package com.fenyx.jtv.data

import androidx.compose.runtime.Immutable

@Immutable
data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String,
    val group: String,
    val streamUrl: String,
    val isDrm: Boolean = false,
    val channelNumber: Int = 0,
    val licenseUrl: String? = null,
    val language: String = "English",
    val isCatchup: Boolean = false,
    /** Jio's set-top-box channel number (v3.1 `stbChannelNumber`), 0 when the list has none. */
    val stbNumber: Int = 0,
    /** v3.1 `is_premium`. */
    val isPremium: Boolean = false,
    /** v3.1 `plan_type` ("free", "premium", ...), "" when absent. */
    val planType: String = ""
)
