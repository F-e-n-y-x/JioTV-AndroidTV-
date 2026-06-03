package com.fenyx.jtv

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data object Settings : NavKey
@Serializable data object Login : NavKey
@Serializable data class Player(val channelIndex: Int, val group: String? = null) : NavKey
