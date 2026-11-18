package com.univerflow.kaznu.web

import org.junit.Assert.assertEquals
import org.junit.Test

class PageShapesTest {
    private fun shape(path: String) = PageShapes.of("https://univer.kaznu.kz$path")

    @Test fun everyListedStudentPageHasItsOwnPlaceholder() {
        mapOf(
            "/" to PageShape.NEWS, "/news/student/1/" to PageShape.NEWS, "/lang/change/kz/" to PageShape.NEWS,
            "/student/bachelor/" to PageShape.BACHELOR, "/ssc/" to PageShape.SERVICES,
            "/student/monrknews/" to PageShape.NEWS_LIST, "/my/faq/" to PageShape.FAQ, "/my/docs/" to PageShape.CATALOG,
            "/my/messages/index/" to PageShape.MESSAGES, "/my/calculatorgpa/" to PageShape.CALCULATOR,
            "/my/appeals/index/" to PageShape.APPEALS, "/student/rating/" to PageShape.SURVEYS,
            "/student/mydata/edit/" to PageShape.FORM, "/student/practik/indexlist/" to PageShape.PRACTICE,
            "/student/advicer/" to PageShape.ADVISOR, "/student/contract/list/" to PageShape.CONTRACTS,
            "/student/academcalendar/" to PageShape.CALENDAR, "/student/myexam/schedule/" to PageShape.EXAMS,
            "/student/mymidterm/schedule/" to PageShape.MIDTERM, "/student/educplan/" to PageShape.PLAN,
            "/student/attestation/" to PageShape.ATTESTATION, "/student/profile/" to PageShape.PROFILE,
            "/student/stud/rating/anket/" to PageShape.IGPA, "/student/TestList" to PageShape.TESTS,
            "/student/fail/" to PageShape.DEBTS, "/student/failfx/" to PageShape.RETAKES,
            "/student/gpaincrease/" to PageShape.RAISE, "/student/myschedule/" to PageShape.SCHEDULE,
            "/student/attendance/full/" to PageShape.JOURNAL, "/student/umkd/" to PageShape.MATERIALS,
            "/student/discussion/index/" to PageShape.DISTANCE,
        ).forEach { (path, expected) -> assertEquals(path, expected, shape(path)) }
    }

    @Test fun pagesUnderASectionTakeItsShapeAndTheRestTheList() {
        assertEquals(PageShape.FAQ, shape("/my/faq/2/"))
        assertEquals(PageShape.SURVEY, shape("/student/rating/208"))
        assertEquals(PageShape.QUESTIONS, shape("/student/rating/208/15/"))
        assertEquals(PageShape.MATERIALS, shape("/student/umkd/4512/"))
        // A neighbour that only starts with the same letters is another page.
        assertEquals(PageShape.LIST, shape("/student/failures/"))
        assertEquals(PageShape.LIST, shape("/ssc/UserServiceList"))
        assertEquals(PageShape.LIST, PageShapes.of("https://example.org/my/faq/"))
        assertEquals(PageShape.LIST, PageShapes.of(null))
    }

    @Test fun theLitTabFollowsTheSiteSection() {
        assertEquals(0, PageShapes.tab("https://univer.kaznu.kz/"))
        assertEquals(0, PageShapes.tab("https://univer.kaznu.kz/my/faq/"))
        assertEquals(0, PageShapes.tab("https://univer.kaznu.kz/student/monrknews/"))
        assertEquals(1, PageShapes.tab("https://univer.kaznu.kz/student/umkd/"))
        assertEquals(2, PageShapes.tab("https://univer.kaznu.kz/ssc/UserServiceList"))
    }
}
