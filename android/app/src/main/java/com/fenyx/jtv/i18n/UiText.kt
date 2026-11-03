package com.fenyx.jtv.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A user-visible text that is resolved to the current app language only where it is shown.
 * Lets code without a Context (enums, view-model state, data classes, unit tests) carry a
 * translatable string. [args] may themselves be [UiText]s; they are resolved first.
 */
sealed interface UiText {
    /** Text that is not translated (a channel name, a server message, a number). */
    data class Raw(val text: String) : UiText
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    fun resolve(context: Context): String = when (this) {
        is Raw -> text
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *resolveArgs(args, context))
        is Plural -> context.resources.getQuantityString(id, count, *resolveArgs(args, context))
    }

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
        fun raw(text: String): UiText = Raw(text)

        private fun resolveArgs(args: List<Any>, context: Context): Array<Any> =
            args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
    }
}

/** Resolves this text in a Composable (re-reads when the app language changes). */
@Composable
@ReadOnlyComposable
fun UiText.text(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> if (args.isEmpty()) stringResource(id)
                     else stringResource(id, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, count, *args.map { if (it is UiText) it.text() else it }.toTypedArray())
}

/** For non-UI code that only has a Context at hand. */
fun Context.text(t: UiText): String = t.resolve(this)
