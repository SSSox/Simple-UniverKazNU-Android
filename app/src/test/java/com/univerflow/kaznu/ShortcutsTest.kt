package com.univerflow.kaznu

import com.univerflow.kaznu.domain.HomeShortcuts
import com.univerflow.kaznu.domain.PinnedLink
import com.univerflow.kaznu.domain.ShortcutRequest
import com.univerflow.kaznu.domain.Shortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private val messages = "https://univer.kaznu.kz/student/messages/"

    @Test fun fixedHomeTilesAreNeverPinnedTwice() {
        assertTrue(Shortcuts.fixedHomeLink("https://univer.kaznu.kz/student/myschedule/"))
        assertTrue(Shortcuts.fixedHomeLink("https://univer.kaznu.kz/user/loginMoodle"))
        assertTrue(Shortcuts.fixedHomeLink("https://univer.kaznu.kz/ssc/StatementList"))
        // A query makes it a different page.
        assertFalse(Shortcuts.fixedHomeLink("https://univer.kaznu.kz/student/myschedule/?week=2"))
        val next = Shortcuts.apply(HomeShortcuts(), ShortcutRequest.Pin(true, "Расписание", "https://univer.kaznu.kz/student/myschedule/"))
        assertEquals(emptyList<PinnedLink>(), next!!.pins)
    }

    @Test fun builtInTileIsRemovedByHidingAndComesBackWhenPinned() {
        val schedule = "https://univer.kaznu.kz/student/myschedule/"
        val hidden = Shortcuts.apply(HomeShortcuts(), ShortcutRequest.Pin(false, "Расписание", schedule))!!
        assertEquals(listOf("https://univer.kaznu.kz/student/myschedule"), hidden.hidden)
        assertEquals("Hiding twice changes nothing", hidden, Shortcuts.apply(hidden, ShortcutRequest.Pin(false, null, "https://univer.kaznu.kz/student/MySchedule")))
        assertEquals(emptyList<PinnedLink>(), hidden.pins)
        val back = Shortcuts.apply(hidden, ShortcutRequest.Pin(true, "Расписание", schedule))!!
        assertEquals(emptyList<String>(), back.hidden)
        assertEquals("Moodle and the services desk are tiles too", listOf("https://univer.kaznu.kz/user/loginmoodle", "https://univer.kaznu.kz/ssc"),
            Shortcuts.normalizeHidden(listOf("https://univer.kaznu.kz/user/loginMoodle", "https://univer.kaznu.kz/ssc/", "https://example.org/", messages)))
    }

    @Test fun homeTileCountIsTheBuiltInsLeftPlusThePins() {
        assertEquals(6, Shortcuts.tiles(HomeShortcuts()))
        val state = HomeShortcuts(listOf(PinnedLink("Сообщения", messages), PinnedLink("FAQ", "https://univer.kaznu.kz/my/faq/")),
            emptyList(), listOf("https://univer.kaznu.kz/student/umkd", "https://example.org/"))
        assertEquals("A foreign address in the hidden list is no tile", 7, Shortcuts.tiles(state))
    }

    @Test fun removingOnePinnedLinkOrTileKeepsTheOthers() {
        val state = HomeShortcuts(listOf(PinnedLink("Сообщения", messages)), emptyList(), listOf("https://univer.kaznu.kz/student/umkd"))
        val unpinned = Shortcuts.apply(state, ShortcutRequest.Pin(false, null, messages))!!
        assertEquals(emptyList<PinnedLink>(), unpinned.pins)
        assertEquals(listOf("https://univer.kaznu.kz/student/umkd"), unpinned.hidden)
        val pinned = Shortcuts.apply(state, ShortcutRequest.Pin(true, "Ещё", "https://example.org/a"))!!
        assertEquals(listOf("https://univer.kaznu.kz/student/umkd"), pinned.hidden)
    }

    @Test fun rejectsUnsafeAddresses() {
        assertFalse(Shortcuts.allowed("javascript:alert(1)"))
        assertFalse(Shortcuts.allowed("https://user:pass@example.org/"))
        assertFalse(Shortcuts.allowed("http://univer.kaznu.kz/student/messages/"))
        assertFalse(Shortcuts.allowed("https://univer.kaznu.kz/user/logout/"))
        assertFalse(Shortcuts.allowed("https://univer.kaznu.kz/a\nb"))
        assertTrue(Shortcuts.allowed("https://www.youtube.com/@kaznu"))
        assertNull(Shortcuts.apply(HomeShortcuts(), ShortcutRequest.Pin(true, "x", "file:///sdcard/a")))
    }

    @Test fun pinsUnpinsAndCleansTitles() {
        val pinned = Shortcuts.apply(HomeShortcuts(), ShortcutRequest.Pin(true, "  Сообщения\n\t", messages))!!
        assertEquals(listOf(PinnedLink("Сообщения", messages)), pinned.pins)
        assertEquals(pinned, Shortcuts.apply(pinned, ShortcutRequest.Pin(true, "Again", messages)))
        assertEquals(emptyList<PinnedLink>(), Shortcuts.apply(pinned, ShortcutRequest.Pin(false, null, messages))!!.pins)
        assertEquals(120, Shortcuts.cleanTitle("я".repeat(300)).length)
        assertEquals("Сообщения ещё", Shortcuts.cleanTitle("Сообщения\u2028ещё\u2029"))
        assertEquals("a\\u2028b\\u2029", Shortcuts.scriptLiteral("a\u2028b\u2029"))
    }

    @Test fun limitStopsAt24Links() {
        val full = HomeShortcuts((1..Shortcuts.LIMIT).map { PinnedLink("Link $it", "https://example.org/$it") })
        assertNull(Shortcuts.apply(full, ShortcutRequest.Pin(true, "One more", "https://example.org/25")))
        assertEquals(Shortcuts.LIMIT - 1, Shortcuts.apply(full, ShortcutRequest.Pin(false, null, "https://example.org/1"))!!.pins.size)
    }

    @Test fun orderAcceptsOnlyValidDistinctAddresses() {
        val order = listOf(messages, "https://univer.kaznu.kz/student/myschedule/")
        assertEquals(order, Shortcuts.apply(HomeShortcuts(), ShortcutRequest.Order(order))!!.order)
        assertNull(Shortcuts.apply(HomeShortcuts(), ShortcutRequest.Order(listOf(messages, messages))))
        assertNull(Shortcuts.apply(HomeShortcuts(), ShortcutRequest.Order(listOf(messages, "javascript:void(0)"))))
        assertNull(Shortcuts.apply(HomeShortcuts(), ShortcutRequest.Order((1..31).map { "https://example.org/$it" })))
    }
}
