package com.fenyx.jtv.ui.main

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.fenyx.jtv.R

/**
 * Display names for things that arrive from Jio in English: channel categories ("Entertainment") and
 * channel languages ("Hindi"). Known ones are translated; anything else is shown as Jio sends it.
 */
object Labels {
    @StringRes
    fun groupRes(group: String): Int? = when (group.trim().lowercase()) {
        MainViewModel.GROUP_ALL.lowercase() -> R.string.home_cat_all_channels
        MainViewModel.GROUP_FAVORITES.lowercase() -> R.string.home_cat_favourites
        MainViewModel.GROUP_RECENT.lowercase() -> R.string.home_cat_recent
        "entertainment" -> R.string.home_group_entertainment
        "movies" -> R.string.home_group_movies
        "kids" -> R.string.home_group_kids
        "sports" -> R.string.home_group_sports
        "lifestyle" -> R.string.home_group_lifestyle
        "infotainment" -> R.string.home_group_infotainment
        "news" -> R.string.home_group_news
        "music" -> R.string.home_group_music
        "devotional", "religious" -> R.string.home_group_devotional
        "business", "business news" -> R.string.home_group_business
        "educational", "education" -> R.string.home_group_educational
        "shopping" -> R.string.home_group_shopping
        else -> null
    }

    /** Language by English name ("Hindi") or code ("hi"). */
    @StringRes
    fun languageRes(nameOrCode: String?): Int? = when (nameOrCode?.trim()?.lowercase()) {
        null -> R.string.lang_default
        "hi", "hindi" -> R.string.lang_hindi
        "en", "english", "eng" -> R.string.lang_english
        "ta", "tamil" -> R.string.lang_tamil
        "te", "telugu" -> R.string.lang_telugu
        "kn", "kannada" -> R.string.lang_kannada
        "ml", "malayalam" -> R.string.lang_malayalam
        "bn", "bengali", "bangla" -> R.string.lang_bengali
        "mr", "marathi" -> R.string.lang_marathi
        "gu", "gujarati" -> R.string.lang_gujarati
        "pa", "punjabi" -> R.string.lang_punjabi
        "or", "odia", "oriya" -> R.string.lang_odia
        "as", "assamese" -> R.string.lang_assamese
        "bho", "bhojpuri" -> R.string.lang_bhojpuri
        "ur", "urdu" -> R.string.lang_urdu
        "ne", "nepali" -> R.string.lang_nepali
        "kok", "konkani" -> R.string.lang_konkani
        "sd", "sindhi" -> R.string.lang_sindhi
        "fr", "french" -> R.string.lang_french
        "other" -> R.string.lang_other
        else -> null
    }
}

/** A channel category name in the app language (Jio's own name when unknown). */
@Composable
@ReadOnlyComposable
fun groupLabel(group: String): String = Labels.groupRes(group)?.let { stringResource(it) } ?: group

/** A channel language name in the app language (as given when unknown). */
@Composable
@ReadOnlyComposable
fun languageLabel(nameOrCode: String?): String =
    Labels.languageRes(nameOrCode)?.let { stringResource(it) } ?: nameOrCode.orEmpty()
