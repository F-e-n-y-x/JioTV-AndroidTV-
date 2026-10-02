package com.fenyx.jtv.ui.player

import androidx.compose.runtime.Immutable
import com.fenyx.jtv.data.ChannelLanguage
import java.util.Locale

/**
 * One audio track as the stream reports it (raw, before de-duplication). [index] is its position in the
 * player's flat track list, used for an override when the track has no language.
 */
@Immutable
internal data class StreamAudio(val index: Int, val language: String?, val name: String?, val selected: Boolean)

/**
 * The single "Language" choice. Values are prefixed so one list can hold both kinds:
 *  - `a:<code>` an in-stream language (picked with a preferred-language setting, no override),
 *  - `t:<index>` an unnamed in-stream track (picked with a track override),
 *  - `c:<channelId>` a sibling channel of another language (picked by switching channel).
 */
@Immutable
internal data class LanguageChoices(
    val options: List<Pair<String, String>>,
    val current: String,
    /** What is playing now, for display (never empty). */
    val label: String,
)

internal const val LANG_AUDIO = "a:"
internal const val LANG_TRACK = "t:"
internal const val LANG_CHANNEL = "c:"

private val NO_LANGUAGE = setOf("und", "mul", "zxx", "mis", "qaa", "")

private val iso3To2: Map<String, String> by lazy {
    buildMap {
        for (two in Locale.getISOLanguages()) {
            runCatching { Locale.forLanguageTag(two).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { put(it, two) }
        }
        // Bibliographic codes some encoders write instead of the terminology ones.
        put("ben", "bn"); put("tam", "ta"); put("tel", "te"); put("mal", "ml"); put("kan", "kn")
    }
}

/** "hin", "hi-IN", "HI" → "hi". Null for "und" and other non-languages. */
internal fun normalizeLang(code: String?): String? {
    val c = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
    if (c in NO_LANGUAGE) return null
    if (c.length == 3) return iso3To2[c] ?: c
    return c
}

/** Plain English name for a language code: "hi" → "Hindi". */
internal fun languageName(code: String): String {
    val known = ChannelLanguage.displayName(code)
    if (known != code.replaceFirstChar { it.uppercase() }) return known
    val loc = runCatching { Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()
    return if (!loc.isNullOrBlank() && !loc.equals(code, ignoreCase = true)) loc else known
}

/**
 * Builds the one Language list from the stream's audio tracks and the channel's sibling-language feeds.
 *
 * - HD masters list the same language once per quality group (two "Hindi" renditions): tracks are
 *   de-duplicated by normalised language code, so "Hindi" appears once.
 * - SD masters have one muxed, unnamed track: it is named after the channel's language when known,
 *   else "Original sound". Several unnamed tracks become "Sound 1", "Sound 2".
 * - Sibling channels are added only for languages the stream does not already carry.
 */
internal fun buildLanguageChoices(
    tracks: List<StreamAudio>,
    variants: List<ChannelLanguage.Variant>,
    playingId: String?,
    playingLang: String?,
    fallbackLabel: String,
): LanguageChoices {
    val opts = ArrayList<Pair<String, String>>()
    val codes = HashSet<String>()
    var current: String? = null

    // In-stream, named / with a language.
    val seen = HashSet<String>()
    val unnamed = ArrayList<StreamAudio>()
    val selectedKeys = HashSet<String>()
    val firstByKey = LinkedHashMap<String, StreamAudio>()
    for (t in tracks) {
        val code = normalizeLang(t.language)
        val name = t.name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(t.language, ignoreCase = true) }
        val key = when {
            code != null -> LANG_AUDIO + code
            name != null -> "n:" + name.lowercase()
            else -> { unnamed.add(t); continue }
        }
        if (seen.add(key)) firstByKey[key] = t
        if (t.selected) selectedKeys.add(key)
    }
    for ((key, t) in firstByKey) {
        val code = normalizeLang(t.language)
        val value = if (code != null) key else LANG_TRACK + t.index
        val label = t.name?.trim()?.takeIf { it.isNotEmpty() && !it.equals(t.language, ignoreCase = true) }
            ?: languageName(code!!)
        if (code != null) codes.add(code)
        opts.add(value to label)
        if (key in selectedKeys && current == null) current = value
    }
    if (unnamed.size == 1) {
        val t = unnamed[0]
        val lang = normalizeLang(playingLang)?.takeIf { it !in codes && firstByKey.isEmpty() }
        if (lang != null) codes.add(lang)
        opts.add(LANG_TRACK + t.index to (lang?.let(::languageName) ?: "Original sound"))
        if (t.selected && current == null) current = LANG_TRACK + t.index
    } else {
        unnamed.forEachIndexed { n, t ->
            opts.add(LANG_TRACK + t.index to "Sound ${n + 1}")
            if (t.selected && current == null) current = LANG_TRACK + t.index
        }
    }
    val haveStream = opts.isNotEmpty()

    // Sibling-language channels.
    for (v in variants) {
        val code = normalizeLang(v.langCode)
        val isPlaying = v.channel.id == playingId
        if (isPlaying && haveStream) continue // the stream's own options already cover it
        if (code != null && !codes.add(code)) continue
        val value = LANG_CHANNEL + v.channel.id
        opts.add(value to ChannelLanguage.displayName(v.langCode))
        if (isPlaying && current == null) current = value
    }

    val cur = current ?: opts.firstOrNull()?.first ?: ""
    val label = opts.firstOrNull { it.first == cur }?.second ?: fallbackLabel
    return LanguageChoices(opts, cur, label)
}
