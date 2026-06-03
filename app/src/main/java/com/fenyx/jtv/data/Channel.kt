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
    val licenseUrl: String? = null
)
