package com.univerflow.kaznu

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univerflow.kaznu.data.AppPreferences
import com.univerflow.kaznu.data.GradeStore
import com.univerflow.kaznu.domain.GradeChange
import com.univerflow.kaznu.domain.GradeReport
import com.univerflow.kaznu.domain.GradeTotals
import com.univerflow.kaznu.grades.GradeAlerts
import com.univerflow.kaznu.grades.GradeCheck
import com.univerflow.kaznu.grades.GradeCheck.Outcome
import com.univerflow.kaznu.grades.JournalPage
import com.univerflow.kaznu.ui.context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The background grade check with a made-up journal instead of the network, its own store file (never the saved
 * grades.json) and the real reader in its own WebView on about:blank. Nothing is posted and nothing goes to Univer.
 */
@RunWith(AndroidJUnit4::class)
class GradeCheckTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = GradeStore(context, "grades-test.json")
    private val notified = mutableListOf<List<GradeChange>>()

    @Before fun clean() = store.erase()
    @After fun tidy() = store.erase()

    // The markup of tests/dom/grades-read.test.cjs: the observed journal with made-up courses.
    private fun journal(second: String) = "<!doctype html><html lang=\"ru\"><body><table class=\"wrappertable\"><tr id=\"l4\"><td class=\"center\"><table class=\"mt\">" +
        "<tr><td><a href=\"/student/attendance/full/2025/2\">Предыдущий</a><a href=\"/student/attendance/full/2026/2\">Следующий</a></td></tr>" +
        course("Социология", "РК1 (100): 63 РК2 (100): $second", 1) + course("Психология", "РК1 (25): 12 РК2 (25):", 3) +
        "</table></td></tr></table></body></html>"

    private fun course(name: String, totals: String, id: Int) =
        "<tr class=\"top\"><td class=\"ct\">$name ([РК1 + РК2 + Экз] (100)) 01.09.2026 - 12.12.2026</td></tr>" +
            "<tr class=\"mid\"><td class=\"ct\"><b>$totals</b></td></tr>" +
            "<tr class=\"mid\"><td class=\"ts\"><a class=\"lt\" href=\"/student/attendance/show/$id/2026/1\">лекция - Teacher $id</a></td></tr>" +
            "<tr><td><table class=\"inner\"><tr><th rowspan=\"2\">РК1</th><th>01.09</th><th>Сумма</th></tr><tr><td>5</td><td>5</td></tr></table></td></tr>"

    private fun check(fetch: suspend (() -> Boolean) -> JournalPage, allowed: Boolean = true, signedOut: () -> Unit = {}) =
        GradeCheck(context, store, allowed = { allowed }, fetch = fetch, language = { "ru" }, notify = { notified += it },
            withdraw = { withdrawn++ }, signedOut = signedOut)

    private var withdrawn = 0

    private fun found(second: String): suspend (() -> Boolean) -> JournalPage = { JournalPage.Found(journal(second)) }

    @Test
    fun theFirstLookKeepsTheBaseAndLaterOnesAnnounceOnlyWhatIsNew() = runBlocking {
        assertEquals(Outcome.BASED, check(found("")).run())
        assertEquals(Outcome.UNCHANGED, check(found("")).run())
        assertEquals(Outcome.NOTIFIED, check(found("71")).run())
        assertEquals(listOf(listOf(GradeChange("Социология", "РК2", "71", "100"))), notified)
        assertEquals(Outcome.UNCHANGED, check(found("71")).run())
        assertEquals(1, notified.size)
    }

    @Test
    fun anEndedSessionWaitsAndAPageThatIsNotTheJournalKeepsNothing() = runBlocking {
        var waiting = false
        assertEquals(Outcome.SIGNED_OUT, check({ JournalPage.SignedOut }, signedOut = { waiting = true }).run())
        assertTrue(waiting)
        assertEquals(Outcome.FAILED, check({ JournalPage.Found("<!doctype html><p>Вход</p>") }).run())
        assertEquals(Outcome.FAILED, check({ JournalPage.Failed }).run())
        var fetched = false
        assertEquals(Outcome.SKIPPED, check({ fetched = true; JournalPage.Failed }, allowed = false).run())
        assertFalse("nothing is asked for while tracking may not run", fetched)
        assertEquals(Outcome.BASED, check(found("")).run())
        assertTrue(notified.isEmpty())
    }

    @Test
    fun theJournalTheStudentOpenedMeanwhileAndASignOutWinOverTheLook() = runBlocking {
        assertEquals(Outcome.BASED, check(found("")).run())
        val seen = GradeTotals(GradeReport.Term(2026, 1), "ru", listOf(GradeReport.Course("Социология",
            listOf(GradeReport.Score("РК1", "100", "63"), GradeReport.Score("РК2", "100", "71")))))
        // The page kept 71 while the request was out: already seen, so not announced.
        assertEquals(Outcome.SKIPPED, check({ assertTrue(store.keep(seen, store.generation)); JournalPage.Found(journal("71")) }).run())
        assertTrue(notified.isEmpty())
        assertEquals("71", store.base(seen)?.get(GradeTotals.key("Социология", "РК2")))
        // A sign-out while the request was out: nothing is kept after it, and its renewed cookie would not be kept either.
        var current = true
        assertEquals(Outcome.SKIPPED, check({ store.erase(); current = it(); JournalPage.Found(journal("90")) }).run())
        assertFalse(current)
        assertNull(store.base(seen))
        assertTrue(notified.isEmpty())
        // A sign-out between the write and the notification takes the notification back.
        assertEquals(Outcome.BASED, check(found("")).run())
        val late = GradeCheck(context, store, allowed = { true }, fetch = found("71"), language = { "ru" },
            notify = { notified += it; store.erase() }, withdraw = { withdrawn++ })
        assertEquals(Outcome.SKIPPED, late.run())
        assertEquals(1, withdrawn)
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun theNotificationNamesTheGradeButNotOnTheLockScreenOrWithAPin() {
        val words = AppPreferences(context).language.context(context)
        val one = listOf(GradeChange("Социология", "РК1", "85", "100"))
        val shown = GradeAlerts.notification(context, one, hidden = false)
        assertEquals(words.getString(R.string.grade_new), shown.title())
        assertEquals("Социология, РК1 — 85", shown.text())
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertNotNull(shown.publicVersion)
        assertEquals(words.getString(R.string.grades_appeared), shown.publicVersion.title())
        assertNull("the lock screen names no course", shown.publicVersion.text())
        assertNotNull("a tap opens the journal", shown.contentIntent)

        val two = GradeAlerts.notification(context, one + GradeChange("Право", "РК2", "14", "25"), hidden = false)
        assertEquals(words.getString(R.string.grades_new), two.title())
        assertTrue(two.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Право, РК2 — 14/25"))

        val pin = GradeAlerts.notification(context, one, hidden = true)
        assertEquals(words.getString(R.string.grades_appeared), pin.title())
        assertNull("with a PIN nothing names the course", pin.text())
        assertEquals(Notification.VISIBILITY_PUBLIC, pin.visibility)
    }
}
