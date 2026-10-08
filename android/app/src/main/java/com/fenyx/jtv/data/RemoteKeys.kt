package com.fenyx.jtv.data

import android.view.KeyEvent
import androidx.annotation.StringRes
import com.fenyx.jtv.R
import com.fenyx.jtv.i18n.UiText

/**
 * Custom remote buttons (docs/v2/INTERACTION.md §6). Pure Kotlin (only KeyEvent's compile-time
 * constants), so the resolver is unit-tested on the JVM.
 *
 * A [RemoteKeyMap] binds each [RemoteAction] to any number of [KeySpec]s. A key spec is a button
 * ([KeySpec.code], with [KeySpec.scan] as the fallback for remotes that send KEYCODE_UNKNOWN) plus
 * whether it is a tap or a hold (long press): "Hold Right" is its own slot, separate from "Right".
 *
 * Locked: Back never takes an action (so nobody gets stuck). OK and the arrows keep their tap meaning;
 * only their hold slot can be given an action. System keys (Home, power, volume) are never recorded.
 */
enum class RemoteAction(val id: String, @StringRes val labelRes: Int) {
    QuickMenu("quick_menu", R.string.remote_action_quick_menu),
    Options("options", R.string.remote_action_options),
    Favourite("favourite", R.string.remote_action_favourite),
    Guide("guide", R.string.remote_action_guide),
    ChannelList("channel_list", R.string.remote_action_channel_list),
    ChannelUp("channel_up", R.string.remote_action_channel_up),
    ChannelDown("channel_down", R.string.remote_action_channel_down),
    PreviousChannel("previous_channel", R.string.remote_action_previous_channel),
    PlayPause("play_pause", R.string.remote_action_play_pause),
    Language("language", R.string.remote_action_language),
    Quality("quality", R.string.remote_action_quality),
    Aspect("aspect", R.string.remote_action_aspect),
    VoiceBoost("voice_boost", R.string.remote_action_voice_boost),
    Sleep("sleep", R.string.remote_action_sleep),
    Mute("mute", R.string.remote_action_mute),
    Search("search", R.string.remote_action_search),
    GoLive("go_live", R.string.remote_action_go_live),
    NumberEntry("number_entry", R.string.remote_action_number_entry);

    companion object {
        fun byId(id: String): RemoteAction? = entries.firstOrNull { it.id == id }
    }
}

/** One button slot. [code] is the (normalised) Android key code; 0 = unknown, then [scan] identifies it. */
data class KeySpec(val code: Int, val scan: Int = 0, val hold: Boolean = false) {
    /** The same physical button and slot. The scan code only counts when the key code is unknown. */
    fun sameSlot(o: KeySpec): Boolean = hold == o.hold && sameButton(o)

    fun sameButton(o: KeySpec): Boolean =
        if (code != KeyEvent.KEYCODE_UNKNOWN || o.code != KeyEvent.KEYCODE_UNKNOWN) code == o.code
        else scan != 0 && scan == o.scan

    val label: UiText get() = RemoteKeys.buttonLabel(code, scan).let { if (hold) UiText.of(R.string.remote_hold, it) else it }

    companion object {
        /**
         * From a raw key event's codes: OK's aliases (Enter, numpad Enter) become OK, and the scan code
         * is kept only when the key code is unknown (so equal specs are the same slot).
         */
        fun of(keyCode: Int, scanCode: Int, hold: Boolean = false) =
            KeySpec(RemoteKeys.normalise(keyCode), if (keyCode == KeyEvent.KEYCODE_UNKNOWN) scanCode else 0, hold)
    }
}

enum class RemoteProfile(val id: String, @StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    Standard("standard", R.string.remote_profile_standard, R.string.remote_profile_standard_desc),
    FireTv("fire_tv", R.string.remote_profile_fire_tv, R.string.remote_profile_fire_tv_desc),
    Basic("basic", R.string.remote_profile_basic, R.string.remote_profile_basic_desc),
    AirMouse("air_mouse", R.string.remote_profile_air_mouse, R.string.remote_profile_air_mouse_desc);

    companion object {
        fun byId(id: String?): RemoteProfile? = entries.firstOrNull { it.id == id }
    }
}

/** Result of trying to put a button on an action. */
sealed interface AssignResult {
    data class Done(val map: RemoteKeyMap) : AssignResult
    /** Back (always), or a tap on OK / an arrow. */
    data object Locked : AssignResult
    /** Home, power, volume: never taken from the system. */
    data object SystemKey : AssignResult
    /** Already on this action: nothing to do. */
    data object AlreadySet : AssignResult
    /** Used by [owner]; ask "Already used for … — replace?" and call again with replace = true. */
    data class Conflict(val owner: RemoteAction) : AssignResult
}

class RemoteKeyMap(bindings: Map<RemoteAction, List<KeySpec>>) {
    /** Every action, in [RemoteAction] order, with its keys (empty = not set). */
    val bindings: Map<RemoteAction, List<KeySpec>> =
        RemoteAction.entries.associateWith { a -> bindings[a].orEmpty().distinct() }

    fun keysFor(action: RemoteAction): List<KeySpec> = bindings[action].orEmpty()

    /** The action on this exact slot, or null. Locked slots never resolve. */
    fun actionFor(spec: KeySpec): RemoteAction? {
        if (RemoteKeys.isLockedSlot(spec)) return null
        return bindings.entries.firstOrNull { (_, ks) -> ks.any { it.sameSlot(spec) } }?.key
    }

    fun actionFor(keyCode: Int, scanCode: Int, hold: Boolean): RemoteAction? =
        actionFor(KeySpec.of(keyCode, scanCode, hold))

    /** True when this button has a hold action, so its tap must wait for the key-up. */
    fun hasHold(keyCode: Int, scanCode: Int): Boolean = actionFor(keyCode, scanCode, hold = true) != null

    fun ownerOf(spec: KeySpec): RemoteAction? =
        bindings.entries.firstOrNull { (_, ks) -> ks.any { it.sameSlot(spec) } }?.key

    fun assign(action: RemoteAction, spec: KeySpec, replace: Boolean = false): AssignResult {
        if (RemoteKeys.isSystem(spec.code)) return AssignResult.SystemKey
        if (RemoteKeys.isLockedSlot(spec)) return AssignResult.Locked
        val owner = ownerOf(spec)
        if (owner == action) return AssignResult.AlreadySet
        if (owner != null && !replace) return AssignResult.Conflict(owner)
        val next = bindings.mapValues { (a, ks) ->
            when (a) {
                action -> ks + spec
                owner -> ks.filterNot { it.sameSlot(spec) }
                else -> ks
            }
        }
        return AssignResult.Done(RemoteKeyMap(next))
    }

    fun clear(action: RemoteAction): RemoteKeyMap = RemoteKeyMap(bindings + (action to emptyList()))

    /** Same bindings, ignoring order. */
    fun sameAs(o: RemoteKeyMap): Boolean = RemoteAction.entries.all { a -> keysFor(a).toSet() == o.keysFor(a).toSet() }

    /** The profile these bindings are exactly, or null ("Custom"). */
    fun matchingProfile(): RemoteProfile? = RemoteProfile.entries.firstOrNull { sameAs(RemoteKeys.profile(it)) }

    /** `{"guide":[{"code":172,"scan":0,"hold":false}], ...}` (only actions with keys). */
    fun toJson(): String = bindings.filterValues { it.isNotEmpty() }.entries.joinToString(",", "{", "}") { (a, ks) ->
        "\"${a.id}\":" + ks.joinToString(",", "[", "]") { "{\"code\":${it.code},\"scan\":${it.scan},\"hold\":${it.hold}}" }
    }

    override fun equals(other: Any?): Boolean = other is RemoteKeyMap && bindings == other.bindings
    override fun hashCode(): Int = bindings.hashCode()
    override fun toString(): String = toJson()

    companion object {
        private val ENTRY = Regex("\"([a-z_]+)\"\\s*:\\s*\\[([^\\]]*)]")
        private val OBJ = Regex("\\{([^\\}]*)\\}")
        private val CODE = Regex("\"code\"\\s*:\\s*(-?\\d+)")
        private val SCAN = Regex("\"scan\"\\s*:\\s*(-?\\d+)")
        private val HOLD = Regex("\"hold\"\\s*:\\s*(true|false)")

        /** Null when [json] is missing or unreadable (the caller then uses the default profile). */
        fun fromJson(json: String?): RemoteKeyMap? {
            if (json.isNullOrBlank() || !json.trim().startsWith("{")) return null
            return runCatching {
                val map = mutableMapOf<RemoteAction, List<KeySpec>>()
                ENTRY.findAll(json).forEach { m ->
                    val a = RemoteAction.byId(m.groupValues[1]) ?: return@forEach
                    map[a] = OBJ.findAll(m.groupValues[2]).mapNotNull { o ->
                        val body = o.groupValues[1]
                        val code = CODE.find(body)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
                        KeySpec.of(
                            code,
                            SCAN.find(body)?.groupValues?.get(1)?.toInt() ?: 0,
                            HOLD.find(body)?.groupValues?.get(1) == "true",
                        )
                    }.filterNot { RemoteKeys.isLockedSlot(it) || RemoteKeys.isSystem(it.code) }.toList()
                }
                RemoteKeyMap(map)
            }.getOrNull()
        }
    }
}

object RemoteKeys {
    /** The long-press threshold, the same as hold-OK in the player. */
    const val HOLD_MS = 450L

    private val OK_CODES = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    private val ARROWS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )
    private val BACK = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)
    private val SYSTEM = setOf(
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SLEEP,
        // Volume keys are NOT here: they can take an action in the player (game controllers have few
        // buttons); with none set they fall through and change the volume as usual.
        KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_ASSIST,
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ALL_APPS, KeyEvent.KEYCODE_NOTIFICATION,
    )

    fun normalise(code: Int): Int = if (code in OK_CODES) KeyEvent.KEYCODE_DPAD_CENTER else code

    fun isOk(code: Int) = normalise(code) == KeyEvent.KEYCODE_DPAD_CENTER
    fun isArrow(code: Int) = code in ARROWS
    fun isBack(code: Int) = code in BACK
    fun isSystem(code: Int) = code in SYSTEM

    /** OK and the arrows: the tap keeps its job, the hold slot is free. */
    fun isLockedTap(code: Int) = isOk(code) || isArrow(code)

    /** A slot that can never take an action: Back (tap or hold), or a tap on OK / an arrow. */
    fun isLockedSlot(spec: KeySpec) = isBack(spec.code) || (!spec.hold && isLockedTap(spec.code))

    fun buttonLabel(code: Int, scan: Int = 0): UiText =
        when (code) {
            KeyEvent.KEYCODE_UNKNOWN -> if (scan != 0) UiText.of(R.string.remote_key_button, scan) else UiText.of(R.string.remote_key_unknown)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> UiText.of(R.string.remote_key_ok)
            KeyEvent.KEYCODE_DPAD_UP -> UiText.of(R.string.remote_key_up)
            KeyEvent.KEYCODE_DPAD_DOWN -> UiText.of(R.string.remote_key_down)
            KeyEvent.KEYCODE_DPAD_LEFT -> UiText.of(R.string.remote_key_left)
            KeyEvent.KEYCODE_DPAD_RIGHT -> UiText.of(R.string.remote_key_right)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> UiText.of(R.string.remote_key_back)
            KeyEvent.KEYCODE_MENU -> UiText.of(R.string.remote_key_menu)
            KeyEvent.KEYCODE_VOLUME_UP -> UiText.of(R.string.remote_key_volume_up)
            KeyEvent.KEYCODE_VOLUME_DOWN -> UiText.of(R.string.remote_key_volume_down)
            KeyEvent.KEYCODE_VOLUME_MUTE -> UiText.of(R.string.remote_key_mute)
            // Game controller buttons: their printed names, the same in every language.
            KeyEvent.KEYCODE_BUTTON_A -> UiText.raw("A")
            KeyEvent.KEYCODE_BUTTON_B -> UiText.raw("B")
            KeyEvent.KEYCODE_BUTTON_X -> UiText.raw("X")
            KeyEvent.KEYCODE_BUTTON_Y -> UiText.raw("Y")
            KeyEvent.KEYCODE_BUTTON_L1 -> UiText.raw("L1")
            KeyEvent.KEYCODE_BUTTON_R1 -> UiText.raw("R1")
            KeyEvent.KEYCODE_BUTTON_L2 -> UiText.raw("L2")
            KeyEvent.KEYCODE_BUTTON_R2 -> UiText.raw("R2")
            KeyEvent.KEYCODE_BUTTON_THUMBL -> UiText.raw("L3")
            KeyEvent.KEYCODE_BUTTON_THUMBR -> UiText.raw("R3")
            KeyEvent.KEYCODE_BUTTON_START -> UiText.raw("Start")
            KeyEvent.KEYCODE_BUTTON_SELECT -> UiText.raw("Select")
            KeyEvent.KEYCODE_BUTTON_MODE -> UiText.raw("Mode")
            KeyEvent.KEYCODE_GUIDE -> UiText.of(R.string.remote_key_guide)
            KeyEvent.KEYCODE_INFO -> UiText.of(R.string.remote_key_info)
            KeyEvent.KEYCODE_PROG_RED -> UiText.of(R.string.remote_key_red)
            KeyEvent.KEYCODE_PROG_GREEN -> UiText.of(R.string.remote_key_green)
            KeyEvent.KEYCODE_PROG_YELLOW -> UiText.of(R.string.remote_key_yellow)
            KeyEvent.KEYCODE_PROG_BLUE -> UiText.of(R.string.remote_key_blue)
            KeyEvent.KEYCODE_CHANNEL_UP -> UiText.of(R.string.remote_key_channel_up)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> UiText.of(R.string.remote_key_channel_down)
            KeyEvent.KEYCODE_LAST_CHANNEL -> UiText.of(R.string.remote_key_last_channel)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> UiText.of(R.string.remote_key_play_pause)
            KeyEvent.KEYCODE_MEDIA_PLAY -> UiText.of(R.string.remote_key_play)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> UiText.of(R.string.remote_key_pause)
            KeyEvent.KEYCODE_MEDIA_STOP -> UiText.of(R.string.remote_key_stop)
            KeyEvent.KEYCODE_MEDIA_REWIND -> UiText.of(R.string.remote_key_rewind)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> UiText.of(R.string.remote_key_fast_forward)
            KeyEvent.KEYCODE_MEDIA_NEXT -> UiText.of(R.string.remote_key_next)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> UiText.of(R.string.remote_key_previous)
            KeyEvent.KEYCODE_MEDIA_RECORD -> UiText.of(R.string.remote_key_record)
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> UiText.of(R.string.remote_key_audio)
            KeyEvent.KEYCODE_CAPTIONS -> UiText.of(R.string.remote_key_subtitles)
            KeyEvent.KEYCODE_SEARCH -> UiText.of(R.string.remote_key_search)
            KeyEvent.KEYCODE_BOOKMARK -> UiText.of(R.string.remote_key_bookmark)
            KeyEvent.KEYCODE_TV -> UiText.of(R.string.remote_key_tv)
            KeyEvent.KEYCODE_DVR -> UiText.of(R.string.remote_key_recordings)
            KeyEvent.KEYCODE_SETTINGS -> UiText.of(R.string.remote_key_settings)
            KeyEvent.KEYCODE_TV_INPUT -> UiText.of(R.string.remote_key_input)
            KeyEvent.KEYCODE_WINDOW -> UiText.of(R.string.remote_key_window)
            KeyEvent.KEYCODE_SPACE -> UiText.of(R.string.remote_key_space)
            KeyEvent.KEYCODE_PAGE_UP -> UiText.of(R.string.remote_key_page_up)
            KeyEvent.KEYCODE_PAGE_DOWN -> UiText.of(R.string.remote_key_page_down)
            KeyEvent.KEYCODE_DEL -> UiText.of(R.string.remote_key_backspace)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> UiText.of(R.string.remote_key_number, code - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> UiText.of(R.string.remote_key_number, code - KeyEvent.KEYCODE_NUMPAD_0)
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> UiText.of(R.string.remote_key_letter, ('A' + (code - KeyEvent.KEYCODE_A)).toString())
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> UiText.raw("F${code - KeyEvent.KEYCODE_F1 + 1}")
            in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 -> UiText.of(R.string.remote_key_button, code - KeyEvent.KEYCODE_BUTTON_1 + 1)
            else -> UiText.of(R.string.remote_key_button, code)
        }

    private fun tap(code: Int) = KeySpec(code)
    private fun hold(code: Int) = KeySpec(code, hold = true)

    /** The one-step profiles. [RemoteProfile.Standard] is what the app does with no custom mapping. */
    fun profile(p: RemoteProfile): RemoteKeyMap = RemoteKeyMap(
        when (p) {
            RemoteProfile.Standard -> mapOf(
                RemoteAction.QuickMenu to listOf(tap(KeyEvent.KEYCODE_MENU), hold(KeyEvent.KEYCODE_DPAD_CENTER)),
                RemoteAction.Guide to listOf(tap(KeyEvent.KEYCODE_GUIDE), tap(KeyEvent.KEYCODE_PROG_RED)),
                RemoteAction.Language to listOf(tap(KeyEvent.KEYCODE_PROG_GREEN), tap(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK)),
                RemoteAction.Quality to listOf(tap(KeyEvent.KEYCODE_PROG_YELLOW)),
                RemoteAction.Sleep to listOf(tap(KeyEvent.KEYCODE_PROG_BLUE)),
                RemoteAction.PreviousChannel to listOf(tap(KeyEvent.KEYCODE_LAST_CHANNEL)),
                RemoteAction.PlayPause to listOf(tap(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
                RemoteAction.Search to listOf(tap(KeyEvent.KEYCODE_SEARCH)),
                RemoteAction.Favourite to listOf(tap(KeyEvent.KEYCODE_BOOKMARK)),
            )
            RemoteProfile.FireTv -> mapOf(
                RemoteAction.QuickMenu to listOf(tap(KeyEvent.KEYCODE_MENU), hold(KeyEvent.KEYCODE_DPAD_CENTER)),
                RemoteAction.Options to listOf(hold(KeyEvent.KEYCODE_DPAD_RIGHT)),
                RemoteAction.Guide to listOf(tap(KeyEvent.KEYCODE_GUIDE), tap(KeyEvent.KEYCODE_TV)),
                RemoteAction.PlayPause to listOf(tap(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
                RemoteAction.PreviousChannel to listOf(hold(KeyEvent.KEYCODE_MEDIA_REWIND)),
                RemoteAction.ChannelList to listOf(hold(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)),
                RemoteAction.Favourite to listOf(hold(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)),
                RemoteAction.Search to listOf(tap(KeyEvent.KEYCODE_SEARCH)),
            )
            RemoteProfile.Basic -> mapOf(
                RemoteAction.QuickMenu to listOf(hold(KeyEvent.KEYCODE_DPAD_CENTER)),
                RemoteAction.Options to listOf(hold(KeyEvent.KEYCODE_DPAD_RIGHT)),
                RemoteAction.PreviousChannel to listOf(hold(KeyEvent.KEYCODE_DPAD_LEFT)),
            )
            RemoteProfile.AirMouse -> mapOf(
                RemoteAction.QuickMenu to listOf(tap(KeyEvent.KEYCODE_MENU), hold(KeyEvent.KEYCODE_DPAD_CENTER)),
                RemoteAction.Options to listOf(hold(KeyEvent.KEYCODE_DPAD_RIGHT)),
                RemoteAction.PlayPause to listOf(tap(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), tap(KeyEvent.KEYCODE_SPACE)),
                RemoteAction.Guide to listOf(tap(KeyEvent.KEYCODE_GUIDE)),
                RemoteAction.PreviousChannel to listOf(tap(KeyEvent.KEYCODE_LAST_CHANNEL)),
                RemoteAction.Search to listOf(tap(KeyEvent.KEYCODE_SEARCH)),
            )
        },
    )

    val Default: RemoteKeyMap get() = profile(RemoteProfile.Standard)
}
