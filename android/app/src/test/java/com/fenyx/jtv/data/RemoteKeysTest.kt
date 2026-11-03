package com.fenyx.jtv.data

import android.view.KeyEvent
import com.fenyx.jtv.R
import com.fenyx.jtv.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeysTest {
    private val std = RemoteKeys.Default

    private fun done(r: AssignResult): RemoteKeyMap {
        assertTrue("expected Done, got $r", r is AssignResult.Done)
        return (r as AssignResult.Done).map
    }

    // ── Standard profile = today's keys ──

    @Test fun standardKeepsTodaysKeys() {
        assertEquals(RemoteAction.QuickMenu, std.actionFor(KeyEvent.KEYCODE_MENU, 0, false))
        assertEquals(RemoteAction.QuickMenu, std.actionFor(KeyEvent.KEYCODE_DPAD_CENTER, 0, true))
        assertEquals(RemoteAction.PlayPause, std.actionFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0, false))
        // Keys with a built-in job and no action stay unmapped, so the player's own handling runs.
        assertNull(std.actionFor(KeyEvent.KEYCODE_CHANNEL_UP, 0, false))
        assertNull(std.actionFor(KeyEvent.KEYCODE_CHANNEL_DOWN, 0, false))
        assertNull(std.actionFor(KeyEvent.KEYCODE_INFO, 0, false))
        assertNull(std.actionFor(KeyEvent.KEYCODE_5, 0, false))
    }

    @Test fun standardColourKeys() {
        assertEquals(RemoteAction.Guide, std.actionFor(KeyEvent.KEYCODE_PROG_RED, 0, false))
        assertEquals(RemoteAction.Language, std.actionFor(KeyEvent.KEYCODE_PROG_GREEN, 0, false))
        assertEquals(RemoteAction.Quality, std.actionFor(KeyEvent.KEYCODE_PROG_YELLOW, 0, false))
        assertEquals(RemoteAction.Sleep, std.actionFor(KeyEvent.KEYCODE_PROG_BLUE, 0, false))
        assertEquals(RemoteAction.Guide, std.actionFor(KeyEvent.KEYCODE_GUIDE, 0, false))
        assertEquals(RemoteAction.PreviousChannel, std.actionFor(KeyEvent.KEYCODE_LAST_CHANNEL, 0, false))
    }

    @Test fun enterAndNumpadEnterAreOk() {
        assertEquals(RemoteAction.QuickMenu, std.actionFor(KeyEvent.KEYCODE_ENTER, 0, true))
        assertEquals(RemoteAction.QuickMenu, std.actionFor(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, true))
    }

    // ── Hold slot ──

    @Test fun holdIsItsOwnSlot() {
        val m = done(std.assign(RemoteAction.Favourite, KeySpec(KeyEvent.KEYCODE_PROG_RED, hold = true)))
        assertEquals(RemoteAction.Favourite, m.actionFor(KeyEvent.KEYCODE_PROG_RED, 0, true))
        assertEquals(RemoteAction.Guide, m.actionFor(KeyEvent.KEYCODE_PROG_RED, 0, false))
        assertTrue(m.hasHold(KeyEvent.KEYCODE_PROG_RED, 0))
        assertFalse(std.hasHold(KeyEvent.KEYCODE_PROG_RED, 0))
    }

    @Test fun holdArrowCanTakeAnAction() {
        val m = done(std.assign(RemoteAction.Options, KeySpec(KeyEvent.KEYCODE_DPAD_RIGHT, hold = true)))
        assertEquals(RemoteAction.Options, m.actionFor(KeyEvent.KEYCODE_DPAD_RIGHT, 0, true))
        assertNull(m.actionFor(KeyEvent.KEYCODE_DPAD_RIGHT, 0, false))
    }

    // ── Locked keys ──

    @Test fun backIsAlwaysLocked() {
        assertEquals(AssignResult.Locked, std.assign(RemoteAction.Guide, KeySpec(KeyEvent.KEYCODE_BACK)))
        assertEquals(AssignResult.Locked, std.assign(RemoteAction.Guide, KeySpec(KeyEvent.KEYCODE_BACK, hold = true)))
        assertEquals(AssignResult.Locked, std.assign(RemoteAction.Guide, KeySpec(KeyEvent.KEYCODE_ESCAPE)))
    }

    @Test fun okAndArrowTapsAreLocked() {
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )) {
            assertEquals(AssignResult.Locked, std.assign(RemoteAction.Mute, KeySpec.of(code, 0)))
        }
    }

    @Test fun systemKeysAreNeverTaken() {
        // Volume keys can take an action (game controllers); Home and Power never can.
        assertTrue(std.assign(RemoteAction.ChannelList, KeySpec(KeyEvent.KEYCODE_VOLUME_UP)) is AssignResult.Done)
        assertEquals(AssignResult.SystemKey, std.assign(RemoteAction.Mute, KeySpec(KeyEvent.KEYCODE_HOME)))
    }

    @Test fun lockedSlotsInStoredJsonAreDropped() {
        val m = RemoteKeyMap.fromJson("""{"mute":[{"code":4,"scan":0,"hold":false},{"code":19,"scan":0,"hold":false},{"code":19,"scan":0,"hold":true}]}""")!!
        assertEquals(listOf(KeySpec(KeyEvent.KEYCODE_DPAD_UP, hold = true)), m.keysFor(RemoteAction.Mute))
        assertNull(m.actionFor(KeyEvent.KEYCODE_BACK, 0, false))
    }

    // ── Conflicts ──

    @Test fun conflictNamesTheOwner() {
        assertEquals(
            AssignResult.Conflict(RemoteAction.Guide),
            std.assign(RemoteAction.Favourite, KeySpec(KeyEvent.KEYCODE_PROG_RED)),
        )
    }

    @Test fun replaceMovesTheKey() {
        val m = done(std.assign(RemoteAction.Favourite, KeySpec(KeyEvent.KEYCODE_PROG_RED), replace = true))
        assertEquals(RemoteAction.Favourite, m.actionFor(KeyEvent.KEYCODE_PROG_RED, 0, false))
        assertEquals(listOf(KeySpec(KeyEvent.KEYCODE_GUIDE)), m.keysFor(RemoteAction.Guide))
    }

    @Test fun sameKeyOnSameActionIsAlreadySet() {
        assertEquals(AssignResult.AlreadySet, std.assign(RemoteAction.Guide, KeySpec(KeyEvent.KEYCODE_PROG_RED)))
    }

    @Test fun anActionCanHaveSeveralKeys() {
        val m = done(std.assign(RemoteAction.Mute, KeySpec(KeyEvent.KEYCODE_F1)))
        val m2 = done(m.assign(RemoteAction.Mute, KeySpec(KeyEvent.KEYCODE_F2)))
        assertEquals(2, m2.keysFor(RemoteAction.Mute).size)
        assertEquals(RemoteAction.Mute, m2.actionFor(KeyEvent.KEYCODE_F1, 0, false))
        assertEquals(RemoteAction.Mute, m2.actionFor(KeyEvent.KEYCODE_F2, 0, false))
        assertTrue(m2.clear(RemoteAction.Mute).keysFor(RemoteAction.Mute).isEmpty())
    }

    // ── Scan code fallback ──

    @Test fun unknownKeyCodeMatchesByScanCode() {
        val m = done(std.assign(RemoteAction.GoLive, KeySpec.of(KeyEvent.KEYCODE_UNKNOWN, 0x1F4)))
        assertEquals(RemoteAction.GoLive, m.actionFor(KeyEvent.KEYCODE_UNKNOWN, 0x1F4, false))
        assertNull(m.actionFor(KeyEvent.KEYCODE_UNKNOWN, 0x1F5, false))
        // A known key code wins: a different button with the same scan code isn't the same slot.
        assertNull(m.actionFor(KeyEvent.KEYCODE_F5, 0x1F4, false))
    }

    @Test fun knownKeyCodeIgnoresScanCode() {
        assertEquals(RemoteAction.Guide, std.actionFor(KeyEvent.KEYCODE_PROG_RED, 398, false))
        assertEquals(KeySpec(KeyEvent.KEYCODE_PROG_RED), KeySpec.of(KeyEvent.KEYCODE_PROG_RED, 398))
    }

    // ── Profiles ──

    @Test fun basicRemoteUsesHoldSlots() {
        val b = RemoteKeys.profile(RemoteProfile.Basic)
        assertEquals(RemoteAction.QuickMenu, b.actionFor(KeyEvent.KEYCODE_DPAD_CENTER, 0, true))
        assertEquals(RemoteAction.Options, b.actionFor(KeyEvent.KEYCODE_DPAD_RIGHT, 0, true))
        assertNull(b.actionFor(KeyEvent.KEYCODE_MENU, 0, false))
        assertNull(b.actionFor(KeyEvent.KEYCODE_DPAD_RIGHT, 0, false))
    }

    @Test fun everyProfileIsRecognised() {
        for (p in RemoteProfile.entries) assertEquals(p, RemoteKeys.profile(p).matchingProfile())
        val custom = done(std.assign(RemoteAction.Mute, KeySpec(KeyEvent.KEYCODE_F3)))
        assertNull(custom.matchingProfile())
    }

    @Test fun noProfileBindsALockedSlotOrOneKeyTwice() {
        for (p in RemoteProfile.entries) {
            val all = RemoteKeys.profile(p).bindings.values.flatten()
            assertTrue(p.name, all.none { RemoteKeys.isLockedSlot(it) || RemoteKeys.isSystem(it.code) })
            assertEquals(p.name, all.size, all.toSet().size)
        }
    }

    // ── Storage ──

    @Test fun jsonRoundTrip() {
        val m = done(done(std.assign(RemoteAction.Mute, KeySpec.of(KeyEvent.KEYCODE_UNKNOWN, 77)))
            .assign(RemoteAction.Options, KeySpec(KeyEvent.KEYCODE_DPAD_RIGHT, hold = true)))
        assertEquals(m, RemoteKeyMap.fromJson(m.toJson()))
        for (p in RemoteProfile.entries) assertEquals(RemoteKeys.profile(p), RemoteKeyMap.fromJson(RemoteKeys.profile(p).toJson()))
    }

    @Test fun badJsonIsNull() {
        assertNull(RemoteKeyMap.fromJson(null))
        assertNull(RemoteKeyMap.fromJson(""))
        assertNull(RemoteKeyMap.fromJson("not json"))
        // Unknown action names are skipped, not fatal.
        assertEquals(listOf(KeySpec(KeyEvent.KEYCODE_F1)), RemoteKeyMap.fromJson("""{"nope":[{"code":1}],"mute":[{"code":131}]}""")!!.keysFor(RemoteAction.Mute))
    }

    @Test fun labels() {
        assertEquals(UiText.of(R.string.remote_key_red), KeySpec(KeyEvent.KEYCODE_PROG_RED).label)
        assertEquals(UiText.of(R.string.remote_hold, UiText.of(R.string.remote_key_ok)),
            KeySpec(KeyEvent.KEYCODE_DPAD_CENTER, hold = true).label)
        assertEquals(UiText.of(R.string.remote_hold, UiText.of(R.string.remote_key_right)),
            KeySpec(KeyEvent.KEYCODE_DPAD_RIGHT, hold = true).label)
        assertEquals(UiText.of(R.string.remote_key_button, 500), KeySpec.of(KeyEvent.KEYCODE_UNKNOWN, 500).label)
        assertEquals(UiText.of(R.string.remote_key_number, 7), RemoteKeys.buttonLabel(KeyEvent.KEYCODE_7))
        assertEquals(UiText.raw("F3"), RemoteKeys.buttonLabel(KeyEvent.KEYCODE_F3))
    }
}
