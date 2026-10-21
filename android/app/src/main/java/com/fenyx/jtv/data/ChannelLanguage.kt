package com.fenyx.jtv.data

/**
 * Detects the trailing "language" token in a JioTV channel name so near-duplicate per-language feeds
 * (e.g. "Star Sports 1 Hindi", "Star Sports 1 Tamil", "Star Sports 1 Telugu") can be collapsed into
 * one logical channel with an in-player language switch.
 *
 * Jio ships each language as a **separate channel_id / HLS stream** (not multiple audio tracks in one
 * manifest), so collapsing here is purely a UI grouping — switching language reloads the sibling feed.
 */
object ChannelLanguage {

    /** Trailing quality markers that sit *after* the language token on some feeds ("… Hindi HD"). */
    private val QUALITY_MARKERS = setOf("HD", "SD", "FHD", "UHD", "4K")

    /** token (lowercase) -> ISO-ish language code used by the player's preferred-audio selection. */
    private val LANG_TOKENS: Map<String, String> = mapOf(
        "hindi" to "hi",
        "english" to "en", "eng" to "en",
        "tamil" to "ta",
        "telugu" to "te",
        "kannada" to "kn",
        "malayalam" to "ml",
        "bengali" to "bn", "bangla" to "bn",
        "marathi" to "mr",
        "gujarati" to "gu",
        "punjabi" to "pa",
        "odia" to "or", "oriya" to "or",
        "assamese" to "as",
        "bhojpuri" to "bho",
        "urdu" to "ur",
        "nepali" to "ne",
        "konkani" to "kok",
        "sindhi" to "sd"
    )

    /** Human-readable label for a language code (falls back to the code itself). */
    fun displayName(code: String?): String = when (code) {
        "hi" -> "Hindi"; "en" -> "English"; "ta" -> "Tamil"; "te" -> "Telugu"; "kn" -> "Kannada"
        "ml" -> "Malayalam"; "bn" -> "Bengali"; "mr" -> "Marathi"; "gu" -> "Gujarati"; "pa" -> "Punjabi"
        "or" -> "Odia"; "as" -> "Assamese"; "bho" -> "Bhojpuri"; "ur" -> "Urdu"; "ne" -> "Nepali"
        "kok" -> "Konkani"; "sd" -> "Sindhi"
        null -> "Default"
        else -> code.replaceFirstChar { it.uppercase() }
    }

    /** Result of splitting a channel name into its language-independent base and language code. */
    data class LangInfo(val base: String, val langCode: String?)

    /**
     * Splits a channel name into (base, langCode). A quality marker that trails the language
     * ("Star Sports 1 Hindi HD") is preserved on the base so HD and SD feeds never merge.
     * Returns langCode = null (and base = full name) when no trailing language token is present.
     */
    fun detect(name: String): LangInfo {
        val parts = name.trim().split(Regex("\\s+")).toMutableList()
        if (parts.size < 2) return LangInfo(name.trim(), null)

        // Peel a trailing quality marker so we can inspect the token before it.
        var quality: String? = null
        if (parts.last().uppercase() in QUALITY_MARKERS) {
            quality = parts.removeAt(parts.lastIndex)
        }
        if (parts.size < 2) return LangInfo(name.trim(), null)

        val lang = LANG_TOKENS[parts.last().lowercase()]
        if (lang != null) {
            parts.removeAt(parts.lastIndex)
            val base = (parts + listOfNotNull(quality)).joinToString(" ")
            return LangInfo(base, lang)
        }
        return LangInfo(name.trim(), null)
    }

    /**
     * Categories where per-language channels are genuine DUBS of one feed (Discovery Tamil/Telugu/…,
     * Cartoon Network, Sony Yay, Star Sports 2 Hindi/Tamil, …) and so are safe to collapse.
     *
     * This is the key discriminator, verified against the real ~1300-channel Jio list: regional
     * *distinct* channels (Zee Tamil, Colors Kannada, DD Bangla, TV9 Telugu, News18 Marathi, Sangeet
     * Bhojpuri, Aastha Gujarati…) all live in Entertainment / Movies / News / Music / Devotional and
     * must NEVER merge, whereas language families in the categories below are always dubs. Neither the
     * API's channelLanguageId nor broadcasterId distinguishes the two (Discovery Kids Tamil and Zee
     * Tamil share languageId 8), so category is what we key on.
     */
    private val COLLAPSE_CATEGORIES = setOf("sports", "infotainment", "kids", "lifestyle")

    /**
     * Groups a channel list into logical channels. A group sharing the same (base, category) collapses
     * only when it has ≥2 language-tagged members AND the category is a dub category
     * ([COLLAPSE_CATEGORIES]). We deliberately do NOT collapse on a numbered base name: brands like
     * "TV9" and "News18" end in a digit but are distinct regional channels, and every genuine numbered
     * feed (Star Sports 2, Sony Ten 4) already lives in Sports. The representative is
     * language-independent (un-suffixed feed, else lowest channel number) so the grid list stays stable
     * regardless of the user's preferred language, and its display name is cleaned to the family base
     * (e.g. "Discovery" instead of "Discovery HD Tamil"). The player picks the preferred-language feed
     * at playback time.
     *
     * @return (representative channels to show, map of representativeId -> its variants).
     */
    fun collapse(channels: List<Channel>): Pair<List<Channel>, Map<String, List<Variant>>> {
        // Preserve original order for a stable, sensible channel list.
        val order = channels.withIndex().associate { (i, c) -> c.id to i }

        data class Tagged(val channel: Channel, val base: String, val lang: String?)
        val tagged = channels.map { ch ->
            val info = detect(ch.name)
            Tagged(ch, info.base, info.langCode)
        }
        // Case-insensitive bucket so "Sony LIV Sports 4" and "Sony Liv Sports 4" group together. Keying
        // on category too keeps a channel that appears under two categories from cross-merging.
        val buckets = tagged.groupBy { it.base.lowercase() to it.channel.group }

        val representatives = mutableListOf<Channel>()
        val variantMap = mutableMapOf<String, List<Variant>>()

        for ((_, members) in buckets) {
            val languagedCount = members.count { it.lang != null }
            val category = members.first().channel.group.lowercase()
            val collapsible = category in COLLAPSE_CATEGORIES
            if (members.size < 2 || languagedCount < 2 || !collapsible) {
                // Not a confident dub family — pass every member through unchanged.
                members.forEach { representatives.add(it.channel) }
                continue
            }
            val variants = members
                .map { Variant(it.lang, it.channel) }
                .sortedWith(
                    compareByDescending<Variant> { it.langCode == null }
                        .thenBy { displayName(it.langCode) }
                )
            // Representative = the un-suffixed feed if present, else the lowest channel number.
            val repChannel = variants.firstOrNull { it.langCode == null }?.channel
                ?: members.minByOrNull { it.channel.channelNumber }!!.channel
            // Show the clean family base as the tile name (strip the language/quality suffix).
            val rep = repChannel.copy(name = detect(repChannel.name).base)
            representatives.add(rep)
            variantMap[rep.id] = variants
        }

        // Restore the original ordering by the representative's position in the source list.
        val sorted = representatives.sortedBy { order[it.id] ?: Int.MAX_VALUE }
        return sorted to variantMap
    }

    /** One language feed within a collapsed logical channel. */
    data class Variant(val langCode: String?, val channel: Channel)

    /**
     * Channels known to carry in-stream multi-audio tracks, keyed by lowercase base name (no HD/language
     * suffix), matched on whole words. Curated: confirm a channel really ships extra audio before adding.
     */
    private val MULTI_AUDIO_MAP: Map<String, Set<String>> = mapOf(
        "disney channel" to setOf("Hindi", "English", "Tamil", "Telugu"),
        "disney junior" to setOf("Hindi", "English", "Tamil", "Telugu"),
        "hungama" to setOf("Hindi", "Tamil", "Telugu"),
        "super hungama" to setOf("Hindi", "Tamil", "Telugu", "English"),
        "pogo" to setOf("Hindi", "Tamil", "Telugu"),
        "sonic" to setOf("Hindi", "Tamil", "Telugu"),
        "nickelodeon" to setOf("Hindi", "English", "Tamil", "Telugu"),
        "nick" to setOf("Hindi", "English", "Tamil", "Telugu"),
        "cartoon network" to setOf("English", "Hindi", "Tamil", "Telugu", "Kannada", "Malayalam", "Marathi"),
        "sony yay" to setOf("Hindi", "Tamil", "Telugu", "Bengali", "Malayalam")
    )

    /**
     * Returns all languages offered by [channel], combining its primary catalog language,
     * any in-stream multi-audio languages, and languages from sibling variants if provided.
     */
    fun channelSupportedLanguages(channel: Channel, variantLangs: List<String> = emptyList()): Set<String> {
        val set = mutableSetOf(channel.language)
        variantLangs.forEach { set.add(it) }
        val padded = " ${detect(channel.name).base.lowercase().trim()} "
        MULTI_AUDIO_MAP.forEach { (key, langs) ->
            if (padded.contains(" $key ")) set.addAll(langs) // whole-word match: "nick" != "nickname"
        }
        return set
    }
}
