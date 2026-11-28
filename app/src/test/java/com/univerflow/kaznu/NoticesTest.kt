package com.univerflow.kaznu

import com.univerflow.kaznu.domain.NoticeDetail
import com.univerflow.kaznu.domain.NoticeKind
import com.univerflow.kaznu.domain.NoticeLink
import com.univerflow.kaznu.domain.NoticeMessage
import com.univerflow.kaznu.domain.NoticeScan
import com.univerflow.kaznu.domain.Notices
import com.univerflow.kaznu.domain.SurveyCheck
import com.univerflow.kaznu.web.NoticeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Notifications list follows the site's marks: what a read shows, what it no longer shows, and what was read. */
class NoticesTest {
    private val rating = Notices.SURVEYS
    private val otherCounter = "https://univer.kaznu.kz/student/tests/"
    private val journal = "https://univer.kaznu.kz/student/attendance/full/"
    private fun menu(count: Int, details: List<NoticeDetail> = emptyList(), url: String = otherCounter, title: String = "Тестирование") =
        NoticeScan("/student/bachelor/", listOf(NoticeLink(url, title, count, details), NoticeLink(journal, "Журнал посещений и успеваемости", 0)), emptyList())

    private fun checked(count: Int, open: Int, details: List<NoticeDetail> = emptyList()) = NoticeScan("/student/bachelor/",
        listOf(NoticeLink(rating, "Анкетирование студентов", count, details, open = open, menu = true)), emptyList(), menu = true)

    private fun pending(count: Int) = NoticeScan("/student/bachelor/",
        listOf(NoticeLink(rating, "Анкетирование студентов", count, menu = true, checking = true)), emptyList(), menu = true)

    private fun fallback(count: Int) = NoticeScan("/student/bachelor/",
        listOf(NoticeLink(rating, "Анкетирование студентов", count, menu = true)), emptyList(), menu = true)

    @Test fun aCountedMenuItemBecomesAnUnreadNotificationAndAnUncountedOneDoesNot() {
        val list = Notices.merge(emptyList(), menu(2), now = 100)
        assertEquals(1, list.size)
        val notice = list.single()
        assertEquals(NoticeKind.COUNTER, notice.kind)
        assertEquals("Тестирование", notice.title)
        assertEquals(otherCounter, notice.url)
        assertEquals(2, notice.count)
        assertEquals(100L, notice.firstSeen)
        assertFalse(notice.read)
        assertEquals(1, Notices.unread(list))
    }

    @Test fun readStaysUntilTheCountGrowsAndTheItemGoesWhenTheSiteDropsTheCount() {
        val read = Notices.markRead(Notices.merge(emptyList(), menu(2), 100), "counter:$otherCounter")
        assertEquals(0, Notices.unread(read))
        val same = Notices.merge(read, menu(2), 200)
        assertTrue(same.single().read)
        assertEquals(100L, same.single().firstSeen)
        val lower = Notices.merge(same, menu(1), 300)
        assertTrue("one item done: nothing new to read", lower.single().read)
        val grown = Notices.merge(lower, menu(3), 400)
        assertFalse("a new item is unread again", grown.single().read)
        assertEquals(listOf("counter:$otherCounter"), Notices.newlyUnread(lower, grown).map { it.key })
        assertTrue("the site shows no count: resolved", Notices.merge(grown, menu(0), 500).isEmpty())
    }

    @Test fun aReadNotificationLeavesTheListUntilTheCountGrows() {
        val list = Notices.merge(emptyList(), menu(2), 100)
        assertEquals(1, Notices.listed(list).size)
        val read = Notices.markRead(list, "counter:$otherCounter")
        assertTrue(Notices.listed(read).isEmpty())
        assertTrue("the site still counts it: still not listed", Notices.listed(Notices.merge(read, menu(2), 200)).isEmpty())
        assertEquals(1, Notices.listed(Notices.merge(read, menu(3), 300)).size)
    }

    @Test fun aCountWithEveryQuestionnaireAnsweredIsNotListed() {
        val initial = Notices.merge(emptyList(), pending(2), 50)
        assertTrue("not listed before verification", Notices.listed(initial).isEmpty())
        assertTrue(Notices.newlyUnread(emptyList(), initial).isEmpty())
        val done = Notices.merge(initial, checked(2, open = 0), 100)
        assertTrue("the site counts 2, but both are answered", Notices.listed(done).isEmpty())
        assertEquals(0, Notices.unread(done))
        assertTrue(Notices.newlyUnread(initial, done).isEmpty())
        val laterPending = Notices.merge(done, pending(2), 200)
        assertTrue("a later menu read waiting for a check keeps the verified zero", Notices.listed(laterPending).isEmpty())
        assertTrue(Notices.newlyUnread(done, laterPending).isEmpty())
        val reopened = Notices.merge(laterPending, checked(2, open = 1), 300)
        assertEquals("a questionnaire has a line to answer again", 1, Notices.listed(reopened).size)
        assertEquals(1, Notices.shown(Notices.listed(reopened).single()))
        assertEquals(listOf("counter:$rating"), Notices.newlyUnread(laterPending, reopened).map { it.key })
    }

    @Test fun onlyTheQuestionnairesLeftToAnswerAreDetailedAndUpToTenAreKept() {
        val left = (1..10).map { NoticeDetail("Анкета $it", "30.11.2026") }
        val notice = Notices.merge(emptyList(), checked(2, open = 10, details = left), 100).single()
        assertFalse(notice.read)
        assertEquals(10, notice.open)
        assertEquals(10, Notices.shown(notice))
        assertEquals(left, notice.details)
        val read = Notices.markRead(listOf(notice), notice.key)
        assertTrue("marked read stays read on the same open count", Notices.merge(read, checked(2, open = 10, details = left), 200).single().read)
    }

    @Test fun readMarkShrinksWithAnsweredQuestionnairesSoTheNextOneIsAnnounced() {
        for ((readAt, left) in listOf(1 to 0, 2 to 1)) {
            var list = Notices.merge(emptyList(), checked(2, open = readAt), 100)
            list = Notices.markAllRead(list)
            val answered = Notices.merge(list, checked(2, open = left), 200)
            assertEquals(0, Notices.unread(answered))
            val next = Notices.merge(answered, checked(2, open = left + 1), 300)
            assertEquals(listOf("counter:$rating"), Notices.newlyUnread(answered, next).map { it.key })
            assertEquals(left + 1, Notices.shown(Notices.listed(next).single()))
        }
    }

    @Test fun incompleteSurveyCheckReplacesEarlierResultWithFallbackAndIsNeverAnnounced() {
        val details = listOf(NoticeDetail("Анкета", "30.11.2026"))
        val first = Notices.merge(emptyList(), fallback(2), 50)
        assertEquals(1, Notices.listed(first).size)
        assertEquals(2, Notices.shown(Notices.listed(first).single()))
        assertTrue("an unverified fallback is never announced", Notices.newlyUnread(emptyList(), first).isEmpty())

        val verified = Notices.merge(emptyList(), checked(2, open = 1, details = details), 100)
        val afterFail = Notices.merge(verified, fallback(2), 200)
        val notice = Notices.listed(afterFail).single()
        assertEquals(SurveyCheck.FALLBACK, notice.surveyStatus)
        assertEquals(2, Notices.shown(notice))
        assertTrue("stale details are cleared on fallback", notice.details.isEmpty())
        assertTrue("fallback is never announced", Notices.newlyUnread(verified, afterFail).isEmpty())
        val verifiedAgain = Notices.merge(afterFail, checked(2, open = 1), 300)
        assertTrue("same open count after fallback is not re-announced", Notices.newlyUnread(afterFail, verifiedAgain).isEmpty())
    }

    @Test fun aFailedCheckKeepsAnAllAnsweredResultForTheSameSiteCount() {
        val answered = Notices.merge(emptyList(), checked(5, open = 0), 100)
        val afterFail = Notices.merge(answered, fallback(5), 200)
        assertTrue("nothing is left to answer", Notices.listed(afterFail).isEmpty())
        val another = Notices.merge(answered, fallback(6), 200)
        assertEquals("a new site count is shown until checked", 1, Notices.listed(another).size)
    }

    @Test fun changedSiteCountWaitsForVerificationAndAnnouncesOnlyAGrowingOpenCount() {
        val list = Notices.merge(emptyList(), checked(2, open = 1), 100)
        assertEquals(1, Notices.listed(Notices.merge(list, pending(2), 150)).size)
        val rechecking = Notices.merge(list, pending(3), 200)
        assertEquals(SurveyCheck.RECHECKING, rechecking.single().surveyStatus)
        assertTrue("rechecking hides the old count until verified", Notices.listed(rechecking).isEmpty())
        assertTrue(Notices.newlyUnread(list, rechecking).isEmpty())
        val same = Notices.merge(rechecking, checked(3, open = 1), 300)
        assertEquals(1, Notices.listed(same).size)
        assertTrue("same open count is not re-announced", Notices.newlyUnread(rechecking, same).isEmpty())
        val grown = Notices.merge(rechecking, checked(3, open = 2), 300)
        assertEquals(listOf("counter:$rating"), Notices.newlyUnread(rechecking, grown).map { it.key })
    }

    @Test fun readingTheSiteFallbackKeepsOnlyTheLastVerifiedCountRead() {
        data class Case(val all: Boolean, val previousOpen: Int, val nextOpen: Int, val expectedUnread: Int)
        for (case in listOf(
            Case(false, -1, 1, 1),
            Case(true, -1, 1, 1),
            Case(false, 1, 2, 1),
            Case(true, 1, 1, 0),
        )) {
            val verified = if (case.previousOpen < 0) emptyList() else Notices.merge(emptyList(), checked(5, case.previousOpen), 100)
            val fb = Notices.merge(verified, fallback(5), 200)
            assertEquals(5, Notices.shown(Notices.listed(fb).single()))
            val read = if (case.all) Notices.markAllRead(fb) else Notices.markRead(fb, fb.single().key)
            assertEquals(0, Notices.unread(read))
            val next = Notices.merge(read, checked(5, case.nextOpen), 300)
            assertEquals("case=$case", case.expectedUnread, Notices.unread(next))
            assertEquals("case=$case", case.expectedUnread, Notices.newlyUnread(read, next).size)
        }
    }

    @Test fun aCountUnderCheckWaitsForTheResult() {
        val checking = NoticeScan("/student/bachelor/", listOf(NoticeLink(rating, "Анкетирование студентов", 2, checking = true)), emptyList())
        assertTrue("not listed before its check", Notices.merge(emptyList(), checking, 100).isEmpty())
        val done = Notices.merge(emptyList(), checked(2, open = 0), 100)
        val rechecking = Notices.merge(done, checking.copy(links = listOf(checking.links.single().copy(count = 3))), 200)
        assertTrue("still not listed while rechecking the changed site count", Notices.listed(rechecking).isEmpty())
        assertEquals(SurveyCheck.RECHECKING, rechecking.single().surveyStatus)
    }

    @Test fun aReadWithoutTheLinkKeepsTheNotification() {
        val list = Notices.merge(emptyList(), menu(2), 100)
        val otherSection = NoticeScan("/ssc/", listOf(NoticeLink("https://univer.kaznu.kz/ssc/StatementList", "Заявки е-услуг", 0)), emptyList())
        assertEquals(list, Notices.merge(list, otherSection, 200))
    }

    @Test fun detailsFromTheCountedPageStayWhileTheCountIsTheSame() {
        val details = listOf(NoticeDetail("Тест по истории", "30.11.2026"))
        val withDetails = Notices.merge(emptyList(), menu(2, details), 100)
        assertEquals(details, withDetails.single().details)
        assertEquals("a menu read alone keeps them", details, Notices.merge(withDetails, menu(2), 200).single().details)
        assertTrue("another count makes them stale", Notices.merge(withDetails, menu(1), 300).single().details.isEmpty())
    }

    @Test fun aPageMessageIsKeptPerPageAndGoesWhenThatPageNoLongerShowsIt() {
        val text = "Для просмотра успеваемости необходимо пройти анкетирование"
        val blocked = NoticeScan("/student/attendance/full/", emptyList(), listOf(NoticeMessage("Журнал посещений и успеваемости", text, rating)))
        val list = Notices.merge(emptyList(), blocked, 100)
        val notice = list.single()
        assertEquals(NoticeKind.BLOCKED, notice.kind)
        assertEquals(text, notice.text)
        assertEquals(rating, notice.url)
        assertEquals("/student/attendance/full/", notice.page)
        assertEquals("another page says nothing about it", list, Notices.merge(list, NoticeScan("/student/myschedule/", emptyList(), emptyList()), 200))
        assertTrue(Notices.merge(list, NoticeScan("/student/attendance/full/", emptyList(), emptyList()), 300).isEmpty())
    }

    @Test fun unreadComeFirstAndTheListIsBounded() {
        var list = Notices.merge(emptyList(), NoticeScan("/student/bachelor/",
            (1..40).map { NoticeLink("https://univer.kaznu.kz/student/item$it/", "Пункт $it", it) }, emptyList()), 100)
        assertEquals(Notices.MAX, list.size)
        list = Notices.markAllRead(list)
        list = Notices.merge(list, checked(1, open = 1), 200)
        assertEquals("counter:$rating", list.first().key)
        assertFalse(list.first().read)
        assertTrue(list.drop(1).all { it.read })
    }

    @Test fun aCompleteMenuReadWithoutTheItemRemovesItsCounter() {
        val menuLink = NoticeLink(rating, "Анкетирование студентов", 2, open = 2, menu = true)
        val list = Notices.merge(emptyList(), NoticeScan("/student/bachelor/", listOf(menuLink), emptyList(), menu = true), 100)
        assertEquals("/student/bachelor/", list.single().page)
        assertTrue(list.single().menu)
        val partial = NoticeScan("/student/bachelor/", listOf(NoticeLink(journal, "Журнал", 0, menu = true)), emptyList())
        assertEquals("a read that may be cut keeps it", list, Notices.merge(list, partial, 200))
        assertTrue("the whole menu no longer lists it", Notices.merge(list, partial.copy(menu = true), 300).isEmpty())
    }

    // Live: the menus of Главная (/news/student) and E-услуги (/ssc/) do not list Анкетирование студентов at all.
    @Test fun anotherSectionsMenuKeepsTheCounterAndItsReadMark() {
        val menuLink = NoticeLink(rating, "Анкетирование студентов", 2, open = 2, menu = true)
        val read = Notices.markAllRead(Notices.merge(emptyList(),
            NoticeScan("/student/attendance/full/", listOf(menuLink), emptyList(), menu = true, section = "/student/bachelor/"), 100))
        val other = NoticeLink("https://univer.kaznu.kz/ssc/StatementList", "Заявки е-услуг", 0, menu = true)
        val services = NoticeScan("/ssc/", listOf(other), emptyList(), menu = true, section = "/ssc/")
        val news = NoticeScan("/news/student", listOf(other.copy(url = "https://univer.kaznu.kz/news/student")), emptyList(), menu = true, section = "/")
        val after = Notices.merge(Notices.merge(read, services, 200), news, 300)
        assertEquals("the read mark survives", read, after)
        assertTrue("a later read of the same count keeps it read", Notices.listed(Notices.merge(after, checked(2, open = 2), 400)).isEmpty())
        val bachelor = NoticeScan("/student/rating/", listOf(other), emptyList(), menu = true, section = "/student/bachelor/")
        assertTrue("its own section's whole menu no longer lists it", Notices.merge(after, bachelor, 500).isEmpty())
    }

    @Test fun aSectionCounterGoesOnlyWithAReadOfItsOwnPage() {
        val strip = NoticeLink("https://univer.kaznu.kz/ssc/StatementList", "Заявки е-услуг", 1)
        val list = Notices.merge(emptyList(), NoticeScan("/ssc/", listOf(strip), emptyList(), menu = true), 100)
        assertEquals("/ssc/", list.single().page)
        val elsewhere = NoticeScan("/student/bachelor/", listOf(NoticeLink(rating, "Анкетирование студентов", 0, menu = true)), emptyList(), menu = true)
        assertEquals("another page's menu says nothing about the strip", list, Notices.merge(list, elsewhere, 200))
        assertTrue(Notices.merge(list, elsewhere.copy(page = "/ssc/"), 300).isEmpty())
    }

    @Test fun noticeBridgeParsesUpToTenSurveyDetailsAndRestrictsCheckingFlagToSurveys() {
        val details = (1..12).joinToString(",") { """{"t":"Анкета $it","e":"30.11.2026"}""" }
        val json = """{"v":1,"g":1,"kind":"section","page":"/student/rating/","s":"/student/bachelor/","menu":true,"links":[""" +
            """{"u":"/student/rating/","t":"Анкетирование студентов","n":12,"m":1,"o":10,"d":[$details]},""" +
            """{"u":"/student/tests/","t":"Тесты","n":4,"m":1,"c":1,"d":[$details]}]}"""
        val scan = NoticeBridge.parse(json, section = true)
        assertNotNull(scan)
        val survey = scan!!.links.single { it.url == rating }
        assertEquals(10, survey.open)
        assertEquals(10, survey.details.size)
        assertFalse(survey.checking)
        val nonSurvey = scan.links.single { it.url == otherCounter }
        assertEquals(Notices.MAX_DETAILS, nonSurvey.details.size)
        assertFalse("checking flag applies only to surveys", nonSurvey.checking)
        assertNull(NoticeBridge.parse(json, section = false))
    }
}
