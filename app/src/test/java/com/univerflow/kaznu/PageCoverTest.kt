package com.univerflow.kaznu

import com.univerflow.kaznu.web.PageCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCoverTest {
    private val shown = mutableListOf<Boolean>()
    private val instant = mutableListOf<Boolean>()
    private val cover = PageCover { visible, atOnce -> shown += visible; instant += atOnce }

    @Test fun aTapCoversThePageAtOnceAndTheReadyPageLiftsIt() {
        cover.leave(covered = true)
        cover.started()
        cover.concealed(true)
        cover.concealed(false)
        assertEquals(listOf(true, true, false), shown)
    }

    @Test fun theOldDocumentFinishingCannotLiftTheCoverOfTheNextOne() {
        cover.leave(covered = true)
        cover.concealed(false)
        assertEquals(listOf(true), shown)
        assertTrue(cover.leaving)
    }

    @Test fun theOriginalLayoutIsNeverCovered() {
        cover.leave(covered = false)
        assertTrue(shown.isEmpty())
        assertTrue(cover.leaving)
    }

    @Test fun aNavigationThatNeverCameUncoversThePageItLeft() {
        cover.leave(covered = true)
        assertFalse(cover.abandoned())
        assertEquals(listOf(true, false), shown)
        assertFalse(cover.leaving)
    }

    @Test fun aFailedDocumentIsNeverUncoveredByARetryThatNeverStarted() {
        cover.failed()
        cover.leave(covered = true)
        shown.clear()
        assertTrue("The error screen must come back instead", cover.abandoned())
        assertTrue(shown.isEmpty())
    }

    @Test fun nothingFadesInOverTheBrowsersErrorPage() {
        cover.leave(covered = true)
        assertEquals("An ordinary page may be seen going", listOf(false), instant)
        cover.failed()
        instant.clear()
        cover.leave(covered = true)
        assertEquals(listOf(true), instant)
    }

    @Test fun aLoadedPageClearsTheFailure() {
        cover.failed()
        cover.loaded()
        cover.leave(covered = true)
        assertFalse(cover.abandoned())
    }
}
