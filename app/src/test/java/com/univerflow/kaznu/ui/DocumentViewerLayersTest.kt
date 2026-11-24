package com.univerflow.kaznu.ui

import com.univerflow.kaznu.web.SecureHold
import com.univerflow.kaznu.web.UnderlayTraversal
import com.univerflow.kaznu.web.UnderlayTraversal.Traversal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The document window's layer over the main screen: screenshot protection while it fades, traversal, the error panel. */
class DocumentViewerLayersTest {
    private var released = 0
    private var settled = 0
    private val hold = SecureHold({ released++ }, { settled++ })

    @Test fun protectionStaysWhileTheClosedWindowFadesAndUntilAFrameWithoutItIsDrawn() {
        val turn = hold.closing()
        assertTrue(hold.holding)
        // A frame drawn during the fade still shows the page.
        hold.drawn(turn)
        assertEquals(0, released)
        hold.removed(turn)
        assertEquals(0, released)
        assertTrue(hold.holding)
        hold.drawn(turn)
        assertEquals(1, released)
        assertFalse(hold.holding)
        hold.drawn(turn)
        assertEquals(1, released)
    }

    @Test fun aWindowOpenedDuringTheFadeKeepsItsProtection() {
        val turn = hold.closing()
        hold.cancel() // A new window replaces the closing one.
        hold.removed(turn)
        hold.drawn(turn)
        assertEquals(0, released)
        assertFalse(hold.holding)
    }

    @Test fun theOldMoodleLayerKeepsTheFlagUnderAWindowOpenedDuringItsFadeUntilItIsGone() {
        val turn = hold.closing(secure = true)
        // A document opens while the Moodle layer still fades: its release is void, its hold is not.
        hold.cancel()
        assertTrue("the old layer still shows through the new window", hold.holding)
        hold.drawn(turn)
        assertTrue(hold.holding)
        hold.removed(turn)
        assertTrue("not before a frame without it is drawn", hold.holding)
        hold.drawn(turn)
        assertFalse(hold.holding)
        assertEquals("the new window keeps its own flag: nothing is lifted", 0, released)
        assertEquals("the owner works the flag out again for the open window", 1, settled)
    }

    @Test fun aClosedDocumentLayerIsWaitedForButDoesNotCountAsAServicePage() {
        val document = hold.closing(secure = false)
        assertFalse(hold.holding)
        hold.removed(document); hold.drawn(document)
        assertEquals(1, released)
    }

    @Test fun theFlagWaitsForEveryClosedLayerWhicheverFadeEndsFirst() {
        val moodle = hold.closing(secure = true)
        val document = hold.closing(secure = false)
        hold.removed(document); hold.drawn(document)
        assertTrue("the Moodle layer is still on screen", hold.holding)
        assertEquals(0, released)
        hold.removed(moodle); hold.drawn(moodle)
        assertFalse(hold.holding)
        assertEquals(1, released)
    }

    @Test fun anOlderFadeEndingLateDoesNotLiftTheNextClosesProtection() {
        val first = hold.closing()
        val second = hold.closing()
        hold.removed(first); hold.drawn(first)
        assertEquals(0, released)
        hold.removed(second); hold.drawn(second)
        assertEquals(1, released)
    }

    private class Node(val name: String, var traversal: Traversal)
    private val toolbar = Node("toolbar", Traversal(0, 0x40000))
    private val page = Node("page", Traversal(1, 0x20000))
    private val status = Node("status", Traversal(2, null))
    private val underlay = UnderlayTraversal<Node>(
        read = { it.traversal }, write = { node, value -> node.traversal = value },
        hidden = { it.copy(importance = 4, focusability = it.focusability?.let { 0x60000 }) })

    @Test fun theScreenUnderTheWindowLeavesTabAndTalkBackAndComesBackWithItsFocus() {
        underlay.exclude(listOf(toolbar, page, status), page)
        assertTrue(underlay.excluding)
        assertEquals(Traversal(4, 0x60000), toolbar.traversal)
        assertEquals(Traversal(4, 0x60000), page.traversal)
        assertEquals(Traversal(4, null), status.traversal)
        assertEquals(page, underlay.restore())
        assertEquals(Traversal(0, 0x40000), toolbar.traversal)
        assertEquals(Traversal(1, 0x20000), page.traversal)
        assertEquals(Traversal(2, null), status.traversal)
        assertFalse(underlay.excluding)
        assertNull(underlay.restore())
    }

    @Test fun excludingTwiceKeepsTheViewsOwnSettingsAndTheFirstFocus() {
        underlay.exclude(listOf(toolbar), toolbar)
        underlay.exclude(listOf(toolbar, page), page)
        assertEquals(toolbar, underlay.restore())
        assertEquals(Traversal(0, 0x40000), toolbar.traversal)
        assertEquals(Traversal(1, 0x20000), page.traversal)
    }

    @Test fun theErrorPanelScrollsSoRetryStaysReachableInAShortWindow() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/view_document_error.xml")).documentElement
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("ScrollView", xml.tagName)
        assertEquals("@+id/document_error", xml.getAttributeNS(android, "id").ifEmpty { xml.getAttribute("android:id") })
        assertEquals("gone", xml.getAttribute("android:visibility"))
        val content = (0 until xml.childNodes.length).map { xml.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>()
        assertEquals("A ScrollView holds exactly one child", 1, content.size)
        val ids = content.single().getElementsByTagName("*").let { list -> (0 until list.length).map { (list.item(it) as org.w3c.dom.Element).getAttribute("android:id") } }
        assertTrue("Retry scrolls with the message", "@+id/document_retry" in ids)
        assertTrue("@+id/document_error_message" in ids)
    }
}
