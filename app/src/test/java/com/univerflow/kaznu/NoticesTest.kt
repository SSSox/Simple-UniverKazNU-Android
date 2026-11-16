package com.univerflow.kaznu

import com.univerflow.kaznu.domain.NoticeDetail
import com.univerflow.kaznu.domain.NoticeKind
import com.univerflow.kaznu.domain.NoticeLink
import com.univerflow.kaznu.domain.NoticeMessage
import com.univerflow.kaznu.domain.NoticeScan
import com.univerflow.kaznu.domain.Notices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Notifications list follows the site's marks: what a read shows, what it no longer shows, and what was read. */
class NoticesTest {
    private val rating = "https://univer.kaznu.kz/student/rating/"
    private val journal = "https://univer.kaznu.kz/student/attendance/full/"
    private fun menu(count: Int, details: List<NoticeDetail> = emptyList()) = NoticeScan("/student/bachelor/",
        listOf(NoticeLink(rating, "Анкетирование студентов", count, details), NoticeLink(journal, "Журнал посещений и успеваемости", 0)), emptyList())

    @Test fun aCountedMenuItemBecomesAnUnreadNotificationAndAnUncountedOneDoesNot() {
        val list = Notices.merge(emptyList(), menu(2), now = 100)
        assertEquals(1, list.size)
        val notice = list.single()
        assertEquals(NoticeKind.COUNTER, notice.kind)
        assertEquals("Анкетирование студентов", notice.title)
        assertEquals(rating, notice.url)
        assertEquals(2, notice.count)
        assertEquals(100L, notice.firstSeen)
        assertFalse(notice.read)
        assertEquals(1, Notices.unread(list))
    }

    @Test fun readStaysUntilTheCountGrowsAndTheItemGoesWhenTheSiteDropsTheCount() {
        val read = Notices.markRead(Notices.merge(emptyList(), menu(2), 100), "counter:$rating")
        assertEquals(0, Notices.unread(read))
        val same = Notices.merge(read, menu(2), 200)
        assertTrue(same.single().read)
        assertEquals(100L, same.single().firstSeen)
        val lower = Notices.merge(same, menu(1), 300)
        assertTrue("one questionnaire done: nothing new to read", lower.single().read)
        val grown = Notices.merge(lower, menu(3), 400)
        assertFalse("a new questionnaire is unread again", grown.single().read)
        assertEquals(listOf("counter:$rating"), Notices.newlyUnread(lower, grown).map { it.key })
        assertTrue("the site shows no count: resolved", Notices.merge(grown, menu(0), 500).isEmpty())
    }

    @Test fun aReadNotificationLeavesTheListUntilTheCountGrows() {
        val list = Notices.merge(emptyList(), menu(2), 100)
        assertEquals(1, Notices.listed(list).size)
        val read = Notices.markRead(list, "counter:$rating")
        assertTrue(Notices.listed(read).isEmpty())
        assertTrue("the site still counts it: still not listed", Notices.listed(Notices.merge(read, menu(2), 200)).isEmpty())
        assertEquals(1, Notices.listed(Notices.merge(read, menu(3), 300)).size)
    }

    private fun checked(count: Int, open: Int, details: List<NoticeDetail> = emptyList()) = NoticeScan("/student/bachelor/",
        listOf(NoticeLink(rating, "Анкетирование студентов", count, details, open = open)), emptyList())

    @Test fun aCountWithEveryQuestionnaireAnsweredIsNotListed() {
        val done = Notices.merge(emptyList(), checked(2, open = 0), 100)
        assertTrue("the site counts 2, but both are answered", Notices.listed(done).isEmpty())
        assertTrue(Notices.newlyUnread(emptyList(), done).isEmpty())
        assertTrue("a later menu read with the same count keeps the check", Notices.listed(Notices.merge(done, menu(2), 200)).isEmpty())
        val reopened = Notices.merge(done, checked(2, open = 1), 300)
        assertEquals("a questionnaire has a line to answer again", 1, Notices.listed(reopened).size)
        assertEquals(listOf("counter:$rating"), Notices.newlyUnread(done, reopened).map { it.key })
        assertEquals("a new questionnaire is to do", 1, Notices.listed(Notices.merge(done, menu(3), 400)).size)
    }

    @Test fun onlyTheQuestionnairesLeftToAnswerAreDetailed() {
        val left = listOf(NoticeDetail("Удовлетворенность обучающихся качеством преподавания", "30.11.2026"))
        val notice = Notices.merge(emptyList(), checked(2, open = 1, details = left), 100).single()
        assertFalse(notice.read)
        assertEquals(1, notice.open)
        assertEquals(left, notice.details)
        val read = Notices.markRead(listOf(notice), notice.key)
        assertTrue("one left that was read stays read", Notices.merge(read, checked(2, open = 1, details = left), 200).single().read)
    }

    @Test fun aCountUnderCheckWaitsForTheResult() {
        val checking = NoticeScan("/student/bachelor/", listOf(NoticeLink(rating, "Анкетирование студентов", 2, checking = true)), emptyList())
        assertTrue("not listed before its check", Notices.merge(emptyList(), checking, 100).isEmpty())
        val done = Notices.merge(emptyList(), checked(2, open = 0), 100)
        assertEquals(done, Notices.merge(done, checking.copy(links = listOf(checking.links.single().copy(count = 3))), 200))
    }

    @Test fun aReadWithoutTheLinkKeepsTheNotification() {
        val list = Notices.merge(emptyList(), menu(2), 100)
        val otherSection = NoticeScan("/ssc/", listOf(NoticeLink("https://univer.kaznu.kz/ssc/StatementList", "Заявки е-услуг", 0)), emptyList())
        assertEquals(list, Notices.merge(list, otherSection, 200))
    }

    @Test fun detailsFromTheCountedPageStayWhileTheCountIsTheSame() {
        val details = listOf(NoticeDetail("Удовлетворенность обучающихся качеством преподавания", "30.11.2026"))
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
        list = Notices.merge(list, menu(1), 200)
        assertEquals("counter:$rating", list.first().key)
        assertFalse(list.first().read)
        assertTrue(list.drop(1).all { it.read })
    }

    @Test fun aCompleteMenuReadWithoutTheItemRemovesItsCounter() {
        val menuLink = NoticeLink(rating, "Анкетирование студентов", 2, menu = true)
        val list = Notices.merge(emptyList(), NoticeScan("/student/bachelor/", listOf(menuLink), emptyList(), menu = true), 100)
        assertEquals("/student/bachelor/", list.single().page)
        assertTrue(list.single().menu)
        val partial = NoticeScan("/student/bachelor/", listOf(NoticeLink(journal, "Журнал", 0, menu = true)), emptyList())
        assertEquals("a read that may be cut keeps it", list, Notices.merge(list, partial, 200))
        assertTrue("the whole menu no longer lists it", Notices.merge(list, partial.copy(menu = true), 300).isEmpty())
    }

    // Live: the menus of Главная (/news/student) and E-услуги (/ssc/) do not list Анкетирование студентов at all.
    @Test fun anotherSectionsMenuKeepsTheCounterAndItsReadMark() {
        val menuLink = NoticeLink(rating, "Анкетирование студентов", 2, menu = true)
        val read = Notices.markAllRead(Notices.merge(emptyList(),
            NoticeScan("/student/attendance/full/", listOf(menuLink), emptyList(), menu = true, section = "/student/bachelor/"), 100))
        val other = NoticeLink("https://univer.kaznu.kz/ssc/StatementList", "Заявки е-услуг", 0, menu = true)
        val services = NoticeScan("/ssc/", listOf(other), emptyList(), menu = true, section = "/ssc/")
        val news = NoticeScan("/news/student", listOf(other.copy(url = "https://univer.kaznu.kz/news/student")), emptyList(), menu = true, section = "/")
        val after = Notices.merge(Notices.merge(read, services, 200), news, 300)
        assertEquals("the read mark survives", read, after)
        assertTrue("a later read of the same count keeps it read", Notices.listed(Notices.merge(after, menu(2), 400)).isEmpty())
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
}
