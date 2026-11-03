package com.fenyx.jtv

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data object Settings : NavKey
@Serializable data object Login : NavKey
@Serializable data object Search : NavKey
/** [catchup]: replay that programme instead of live (null = live; older saved stacks have none). */
@Serializable data class Player(
    val channelIndex: Int,
    val group: String? = null,
    val catchup: com.fenyx.jtv.data.CatchupRequest? = null,
) : NavKey
@Serializable data object Guide : NavKey
